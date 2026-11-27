package com.example.ambientremote

import android.content.Intent
import android.view.KeyEvent
import android.service.dreams.DreamService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ambientremote.ui.AmbientRemoteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Interactive screensaver ("Daydream") that hosts the same Compose remote UI,
 * so the remote appears automatically when the phone is docked or charging.
 */
class AmbientDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: RemoteSettings
    private lateinit var client: CommandRouter

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        AppSession.enter()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        settings = RemoteSettings(this)
        client = CommandRouter(settings, UdpRemoteClient(scope) { settings.ip to settings.port })
        if (settings.transport == Transport.BLUETOOTH) BluetoothHidRemote.start(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        // Bright mode on so the user's brightness setting controls the level instead of the system dim.
        isScreenBright = true

        val win = window
        win.decorView.setViewTreeLifecycleOwner(this)
        win.decorView.setViewTreeSavedStateRegistryOwner(this)
        WindowCompat.setDecorFitsSystemWindows(win, false)
        WindowInsetsControllerCompat(win, win.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LaunchedEffect(settings.brightness) { win.applyBrightness(settings.brightness) }
                AmbientRemoteApp(
                    client = client,
                    settings = settings,
                    showExit = false,
                    onExit = {},
                    showSettings = false,
                    onShowSettingsChange = { wantsOpen -> if (wantsOpen) openSettingsInActivity() },
                )
            }
        }
        setContentView(composeView)
    }

    /** Phone volume buttons control the TV (setting). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (settings.volumeKeys && isVolume) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                client.send(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) "VOL_UP" else "VOL_DOWN")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        client.close()
        scope.cancel()
        AppSession.leave(this)
        super.onDestroy()
    }

    /** IP entry needs a keyboard, so hand over to the full activity with its settings dialog open. */
    private fun openSettingsInActivity() {
        startActivity(
            Intent(this, AmbientRemoteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AmbientRemoteActivity.EXTRA_OPEN_SETTINGS, true)
        )
        finish()
    }
}
