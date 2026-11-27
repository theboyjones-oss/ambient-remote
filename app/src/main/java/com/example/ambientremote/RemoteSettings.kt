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

    fun setBrightness(level: Float) {
        brightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
        prefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()
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
        const val DEFAULT_BRIGHTNESS = 0.15f
        const val MIN_BRIGHTNESS = 0.01f
    }
}

/** Overrides this window's screen brightness (0..1). */
fun Window.applyBrightness(level: Float) {
    attributes = attributes.apply { screenBrightness = level }
}
