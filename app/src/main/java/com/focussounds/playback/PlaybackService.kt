package com.focussounds.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.focussounds.FocusApp
import com.focussounds.R
import com.focussounds.core.EndReason
import com.focussounds.core.SessionEngine
import com.focussounds.ui.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground media playback service. Owns the app's only ExoPlayer and the MediaSession that
 * powers the notification, lock screen, Bluetooth and system media controls.
 *
 * Media3 promotes this service to the foreground (type mediaPlayback) while sound is playing, which
 * keeps playback alive with the screen locked or the app in the background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var engine: SessionEngine
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var port: ExoPlaybackPort
    private var mediaSession: MediaSession? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        engine = (application as FocusApp).container.engine

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus = true: request AUDIOFOCUS_GAIN before playing (other media apps
                // pause), pause on permanent loss, pause/resume around calls, duck when asked.
                true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL) // keep CPU awake while playing with the screen off
            .build()
        port = ExoPlaybackPort(exoPlayer, engine)
        engine.attachPlayer(port)

        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(getString(R.string.stop))
            .setSessionCommand(STOP_COMMAND)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        mediaSession = MediaSession.Builder(this, SessionPlayer(exoPlayer, engine))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(listOf(stopButton))
            .setCallback(SessionCallback(engine))
            .build()

        // Optional chime/vibration when a timer completes (wherever the user is: locked, other app).
        val container = (application as FocusApp).container
        scope.launch {
            engine.state.map { it.lastResult }.distinctUntilChanged { a, b -> a?.id == b?.id }.drop(1).collect { r ->
                if (r?.reason == EndReason.COMPLETED) {
                    val s = container.settings.current
                    container.completionFeedback.onSessionCompleted(s.completionChime, s.completionVibration)
                }
            }
        }

        // When a session ends (stop, timer, error) leave the foreground and stop the service.
        // It stays alive only while the app's UI is still bound to it.
        scope.launch {
            engine.state.map { it.active != null }.distinctUntilChanged().drop(1).collect { active ->
                if (!active) pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null && engine.state.value.active == null) {
            // Restarted by the system after the process was killed. That session was already
            // recorded as interrupted and is never resumed silently, so remove the stale media
            // notification and stop instead of lingering as an idle foreground service.
            stopForeground(STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancelAll()
            stopSelf()
        }
        // Never ask the system to recreate the service after the process dies: there is nothing
        // to continue without the user, and recovery happens when the app next starts.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Ends a still-running session as interrupted (with accurate time) before releasing the player.
        engine.detachPlayer(port)
        port.release()
        mediaSession?.release()
        mediaSession = null
        exoPlayer.release()
        super.onDestroy()
    }

    private class SessionCallback(private val engine: SessionEngine) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(STOP_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP) {
                engine.stop()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // External controllers (assistants, car UIs) may not load arbitrary media into the player.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFailedFuture(UnsupportedOperationException("Start sessions from the app"))
    }

    companion object {
        const val ACTION_STOP = "com.focussounds.STOP_SESSION"
        val STOP_COMMAND = SessionCommand(ACTION_STOP, Bundle.EMPTY)
    }
}

/**
 * The player exposed to the notification and other controllers. Play/pause/stop are routed through
 * the engine so a session is recorded the same way whatever surface the command came from, and
 * seeking/skipping is hidden because a focus sound is an endless loop.
 */
@OptIn(UnstableApi::class)
private class SessionPlayer(player: Player, private val engine: SessionEngine) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .removeAll(
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
            )
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.resume() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }
}
