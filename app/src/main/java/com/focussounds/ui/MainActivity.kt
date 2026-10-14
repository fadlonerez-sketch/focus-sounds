package com.focussounds.ui

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.focussounds.FocusApp
import com.focussounds.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as FocusApp).container }
    private val vm: FocusViewModel by viewModels { FocusViewModel.factory(container) }

    /**
     * Connecting a controller binds (and so creates) the playback service while the UI is visible,
     * which makes the player ready to start instantly. Commands go straight to the engine.
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                FocusRoot(vm, onStartRequested = ::onStartRequested)
            }
        }
    }

    /** Asks once (Android 13+) for notification permission so the playback controls are visible. */
    private fun onStartRequested() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !container.prefs.askedNotificationPermission) {
            container.prefs.askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
