package com.focussounds.ui

import android.Manifest
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.focussounds.FocusApp
import com.focussounds.playback.PlaybackService
import com.focussounds.ui.screens.ActivitiesViewModel
import com.focussounds.ui.screens.HistoryViewModel
import com.focussounds.ui.screens.ProgressViewModel
import com.focussounds.ui.screens.SettingsViewModel
import com.focussounds.ui.theme.FocusTheme
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as FocusApp).container }
    private val focusVm: FocusViewModel by viewModels { FocusViewModel.factory(container) }
    private val progressVm: ProgressViewModel by viewModels { ProgressViewModel.factory(container) }
    private val historyVm: HistoryViewModel by viewModels { HistoryViewModel.factory(container) }
    private val activitiesVm: ActivitiesViewModel by viewModels { ActivitiesViewModel.factory(container) }
    private val settingsVm: SettingsViewModel by viewModels { SettingsViewModel.factory(container) }

    /**
     * Connecting a controller binds (and so creates) the playback service while the UI is visible,
     * which makes the player ready to start instantly. Commands go straight to the engine.
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { focusVm.requestStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark: light status/navigation bar icons on a dark background, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val vms = AppViewModels(focusVm, progressVm, historyVm, activitiesVm, settingsVm)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            FocusTheme(accentArgb = settings.accentArgb, motionLevel = settings.motion, hapticsEnabled = settings.haptics) {
                AppRoot(vms, container.messages, onStartRequested = ::onStartRequested)
            }
        }
    }

    /** Asks once (Android 13+) for notification permission so the playback controls are visible. */
    private fun onStartRequested() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !container.prefs.askedNotificationPermission) {
            container.prefs.askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            focusVm.requestStart()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        // Playback may only be started from the foreground, so a pending countdown is cancelled.
        focusVm.cancelCountdown()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
