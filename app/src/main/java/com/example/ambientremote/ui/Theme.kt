package com.example.ambientremote.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

data class RemoteTheme(val name: String, val accent: Color) {
    /** Subtle dark-tinted fill for touch targets. */
    val fill: Color get() = accent.copy(alpha = 0.07f)
    val fillPressed: Color get() = accent.copy(alpha = 0.26f)
    /** Crisp 1.5dp borders. */
    val border: Color get() = accent.copy(alpha = 0.9f)
    val dim: Color get() = accent.copy(alpha = 0.40f)
    val faint: Color get() = accent.copy(alpha = 0.18f)
    /** Bright readable labels. */
    val label: Color get() = accent
}

val RemoteThemes = listOf(
    RemoteTheme("Teal", Color(0xFF4DB6AC)),
    RemoteTheme("Green", Color(0xFF66BB6A)),
    RemoteTheme("Yellow", Color(0xFFFFD54F)),
    RemoteTheme("Blue", Color(0xFF42A5F5)),
    RemoteTheme("White", Color(0xFFF5F5F5)),
    RemoteTheme("Grey", Color(0xFF9E9E9E)),
    RemoteTheme("Purple", Color(0xFFAB47BC)),
)

val OledBlack = Color(0xFF000000)
val WireBorder = 1.5.dp
val Mono = FontFamily.Monospace

val LocalRemoteTheme = compositionLocalOf { RemoteThemes.first() }
