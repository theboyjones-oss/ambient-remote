package com.example.ambientremote

import android.content.Context
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ambientremote.ui.RemoteThemes

/** IP, port and colour theme, persisted in SharedPreferences and observable from Compose. */
class RemoteSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var ip by mutableStateOf(prefs.getString(KEY_IP, null) ?: UdpRemoteClient.DEFAULT_IP)
        private set
    var port by mutableIntStateOf(prefs.getInt(KEY_PORT, UdpRemoteClient.DEFAULT_PORT))
        private set
    var themeIndex by mutableIntStateOf(
        prefs.getInt(KEY_THEME, 0).coerceIn(0, RemoteThemes.lastIndex)
    )
        private set

    /** Screen brightness for the remote, 0.01 (1%) to 1.0 (100%). */
    var brightness by mutableFloatStateOf(
        prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS).coerceIn(MIN_BRIGHTNESS, 1f)
    )
        private set

    fun updateBrightness(level: Float) {
        brightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
        prefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()
    }

    /** Show the remote over the lock screen. */
    var runOnLockScreen by mutableStateOf(prefs.getBoolean(KEY_RUN_ON_LOCK, true))
        private set

    fun updateRunOnLockScreen(on: Boolean) {
        runOnLockScreen = on
        prefs.edit().putBoolean(KEY_RUN_ON_LOCK, on).apply()
    }

    /** Brightness used while the phone is locked, kept high enough to see the remote. */
    var lockBrightness by mutableFloatStateOf(
        prefs.getFloat(KEY_LOCK_BRIGHTNESS, DEFAULT_LOCK_BRIGHTNESS).coerceIn(MIN_BRIGHTNESS, 1f)
    )
        private set

    fun updateLockBrightness(level: Float) {
        lockBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
        prefs.edit().putFloat(KEY_LOCK_BRIGHTNESS, lockBrightness).apply()
    }

    var transport by mutableStateOf(
        runCatching { Transport.valueOf(prefs.getString(KEY_TRANSPORT, null) ?: "") }
            .getOrDefault(Transport.BLUETOOTH)
    )
        private set

    fun updateTransport(t: Transport) {
        transport = t
        prefs.edit().putString(KEY_TRANSPORT, t.name).apply()
    }

    // ---- Phone
    /** Phone volume buttons change the TV volume while the remote is open. */
    var volumeKeys by mutableStateOf(prefs.getBoolean(KEY_VOLUME_KEYS, true))
        private set

    fun updateVolumeKeys(on: Boolean) {
        volumeKeys = on
        prefs.edit().putBoolean(KEY_VOLUME_KEYS, on).apply()
    }

    /** Ongoing notification with play/pause and volume buttons. */
    var notificationControls by mutableStateOf(prefs.getBoolean(KEY_NOTIFICATION, true))
        private set

    fun updateNotificationControls(on: Boolean) {
        notificationControls = on
        prefs.edit().putBoolean(KEY_NOTIFICATION, on).apply()
    }

    // ---- Trackpad
    /** Pointer speed multiplier, 0.4 to 3.0. */
    var pointerSpeed by mutableFloatStateOf(prefs.getFloat(KEY_POINTER_SPEED, 1f).coerceIn(0.4f, 3f))
        private set

    fun updatePointerSpeed(v: Float) {
        pointerSpeed = v.coerceIn(0.4f, 3f)
        prefs.edit().putFloat(KEY_POINTER_SPEED, pointerSpeed).apply()
    }

    /** Faster finger movement moves the pointer further. */
    var pointerAcceleration by mutableStateOf(prefs.getBoolean(KEY_POINTER_ACCEL, true))
        private set

    fun updatePointerAcceleration(on: Boolean) {
        pointerAcceleration = on
        prefs.edit().putBoolean(KEY_POINTER_ACCEL, on).apply()
    }

    /** Scroll speed 1 (slow) to 5 (fast). */
    var scrollSpeed by mutableIntStateOf(prefs.getInt(KEY_SCROLL_SPEED, 3).coerceIn(1, 5))
        private set

    fun updateScrollSpeed(v: Int) {
        scrollSpeed = v.coerceIn(1, 5)
        prefs.edit().putInt(KEY_SCROLL_SPEED, scrollSpeed).apply()
    }

    /** Finger up scrolls content up (like a phone / Mac natural scrolling). */
    var naturalScroll by mutableStateOf(prefs.getBoolean(KEY_NATURAL_SCROLL, false))
        private set

    fun updateNaturalScroll(on: Boolean) {
        naturalScroll = on
        prefs.edit().putBoolean(KEY_NATURAL_SCROLL, on).apply()
    }

    // ---- Feel
    /** 0 = off, 1 = light, 2 = strong. */
    var hapticLevel by mutableIntStateOf(prefs.getInt(KEY_HAPTICS, 1).coerceIn(0, 2))
        private set

    fun updateHapticLevel(v: Int) {
        hapticLevel = v.coerceIn(0, 2)
        prefs.edit().putInt(KEY_HAPTICS, hapticLevel).apply()
    }

    /** Seconds without a touch before the screen fades to black. 0 = never. */
    var idleTimeoutSec by mutableIntStateOf(prefs.getInt(KEY_IDLE, 30))
        private set

    fun updateIdleTimeout(sec: Int) {
        idleTimeoutSec = sec
        prefs.edit().putInt(KEY_IDLE, sec).apply()
    }

    fun setTarget(newIp: String, newPort: Int) {
        ip = newIp.trim()
        port = newPort
        prefs.edit().putString(KEY_IP, ip).putInt(KEY_PORT, port).apply()
    }

    fun setTheme(index: Int) {
        themeIndex = index.coerceIn(0, RemoteThemes.lastIndex)
        prefs.edit().putInt(KEY_THEME, themeIndex).apply()
    }

    fun cycleTheme() = setTheme((themeIndex + 1) % RemoteThemes.size)

    companion object {
        private const val PREFS_NAME = "ambient_remote"
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_THEME = "theme"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_RUN_ON_LOCK = "run_on_lock"
        private const val KEY_LOCK_BRIGHTNESS = "lock_brightness"
        private const val KEY_VOLUME_KEYS = "volume_keys"
        private const val KEY_NOTIFICATION = "notification_controls"
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_POINTER_ACCEL = "pointer_accel"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_IDLE = "idle_timeout"
        const val DEFAULT_LOCK_BRIGHTNESS = 0.45f
        const val DEFAULT_BRIGHTNESS = 0.15f
        const val MIN_BRIGHTNESS = 0.01f
    }
}

/** Overrides this window's screen brightness (0..1). */
fun Window.applyBrightness(level: Float) {
    attributes = attributes.apply { screenBrightness = level }
}
