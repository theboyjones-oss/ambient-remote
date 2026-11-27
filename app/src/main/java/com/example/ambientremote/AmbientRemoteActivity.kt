package com.example.ambientremote

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
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
    private lateinit var client: UdpRemoteClient

    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null

    /** True while the phone is face-down / covered: screen goes black and touches are swallowed. */
    private var blackout by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        settings = RemoteSettings(this)
        window.attributes = window.attributes.apply {
            screenBrightness = settings.brightness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        client = UdpRemoteClient(lifecycleScope) { settings.ip to settings.port }

        sensorManager = getSystemService(SensorManager::class.java)
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        showSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

        setContent {
            LaunchedEffect(settings.brightness) { window.applyBrightness(settings.brightness) }
            Box(Modifier.fillMaxSize().background(OledBlack)) {
                AmbientRemoteApp(
                    client = client,
                    settings = settings,
                    showExit = true,
                    onExit = { finish() },
                    showSettings = showSettings,
                    onShowSettingsChange = { showSettings = it },
                )
                if (blackout) {
                    Box(Modifier.fillMaxSize().background(OledBlack))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings = true
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
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
