package com.example.ambientremote

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.ambientremote.ui.AmbientRemoteApp
import com.example.ambientremote.ui.OledBlack

class AmbientRemoteActivity : ComponentActivity(), SensorEventListener {

    private lateinit var settings: RemoteSettings
    private lateinit var client: CommandRouter

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            BluetoothHidRemote.start(this)
            if (settings.notificationControls) RemoteControlService.start(this)
        }

    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null

    /** True while the phone is face-down / covered: screen goes black and touches are swallowed. */
    private var blackout by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    /** True while the keyguard is showing (remote running on the lock screen). */
    private var onLockScreen by mutableStateOf(false)

    private val lockAdmin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isLockAdmin()) lockAndShowRemote()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = RemoteSettings(this)
        applyLockScreenMode(settings.runOnLockScreen)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.attributes = window.attributes.apply {
            screenBrightness = settings.brightness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        client = CommandRouter(settings, UdpRemoteClient(lifecycleScope) { settings.ip to settings.port })
        val missing = buildList {
            if (settings.transport == Transport.BLUETOOTH && !BluetoothHidRemote.hasPermission(this@AmbientRemoteActivity)) {
                addAll(BluetoothHidRemote.requiredPermissions)
            }
            if (settings.notificationControls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            bluetoothPermission.launch(missing.toTypedArray())
        } else {
            if (settings.transport == Transport.BLUETOOTH) BluetoothHidRemote.start(this)
            if (settings.notificationControls) RemoteControlService.start(this)
        }

        sensorManager = getSystemService(SensorManager::class.java)
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        showSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

        setContent {
            LaunchedEffect(settings.runOnLockScreen) { applyLockScreenMode(settings.runOnLockScreen) }
            LaunchedEffect(Unit) {
                while (true) {
                    onLockScreen = keyguard()?.isKeyguardLocked == true
                    delay(1_000)
                }
            }
            val level = if (onLockScreen) settings.lockBrightness else settings.brightness
            LaunchedEffect(level) { window.applyBrightness(level) }
            Box(Modifier.fillMaxSize().background(OledBlack)) {
                AmbientRemoteApp(
                    client = client,
                    settings = settings,
                    showExit = true,
                    onExit = { finish() },
                    showSettings = showSettings,
                    onShowSettingsChange = { showSettings = it },
                    onEnterLockScreen = { enterLockScreen() },
                    onLockScreen = onLockScreen,
                    onExitLockScreen = { exitLockScreen() },
                )
                if (blackout) {
                    Box(Modifier.fillMaxSize().background(OledBlack))
                }
            }
        }
    }

    private fun keyguard(): KeyguardManager? = getSystemService(KeyguardManager::class.java)

    private fun applyLockScreenMode(on: Boolean) {
        setShowWhenLocked(on)
        setTurnScreenOn(on)
    }

    private fun adminComponent() = ComponentName(this, LockAdminReceiver::class.java)

    private fun isLockAdmin(): Boolean =
        getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent()) == true

    /** "Enter Lock Screen": lock the phone, then wake it with the remote showing over the lock screen. */
    private fun enterLockScreen() {
        showSettings = false
        if (!settings.runOnLockScreen) settings.updateRunOnLockScreen(true)
        applyLockScreenMode(true)
        if (isLockAdmin()) {
            lockAndShowRemote()
        } else {
            lockAdmin.launch(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_description),
                    )
            )
        }
    }

    /** "Exit lock screen": ask for the normal unlock (fingerprint/PIN), keeping the remote open. */
    private fun exitLockScreen() {
        val km = keyguard() ?: return
        if (!km.isKeyguardLocked) {
            onLockScreen = false
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                onLockScreen = false
            }
        })
    }

    private fun lockAndShowRemote() {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        try {
            dpm.lockNow()
        } catch (_: SecurityException) {
            return
        }
        // Wake the screen straight back up; the remote shows over the lock screen.
        Handler(Looper.getMainLooper()).postDelayed({
            val pm = getSystemService(PowerManager::class.java) ?: return@postDelayed
            @Suppress("DEPRECATION")
            val wake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ambientremote:lockscreen",
            )
            wake.acquire(3_000)
            onLockScreen = true
        }, 700)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings = true
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (settings.transport == Transport.BLUETOOTH) BluetoothHidRemote.start(this)
        proximity?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        blackout = false
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Phone volume buttons control the TV (setting). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    client.send("VOL_UP")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    client.send("VOL_DOWN")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Swallow every touch while blacked out (face-down / in a pocket). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (blackout) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val max = event.sensor.maximumRange
        val distance = event.values.firstOrNull() ?: return
        blackout = distance < minOf(max, NEAR_THRESHOLD_CM)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        private const val NEAR_THRESHOLD_CM = 5f
    }
}
