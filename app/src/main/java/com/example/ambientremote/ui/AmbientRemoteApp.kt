package com.example.ambientremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ambientremote.RemoteSettings
import com.example.ambientremote.UdpRemoteClient
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.random.Random

enum class RemoteMode(val title: String) {
    REMOTE("REMOTE"),
    TRACKPAD("TRACKPAD"),
    KEYBOARD("KEYBOARD"),
}

/** Shared UI for both AmbientRemoteActivity and AmbientDreamService. */
@Composable
fun AmbientRemoteApp(
    client: UdpRemoteClient,
    settings: RemoteSettings,
    showExit: Boolean,
    onExit: () -> Unit,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
) {
    val theme = RemoteThemes[settings.themeIndex]
    var mode by rememberSaveable { mutableStateOf(RemoteMode.REMOTE) }
    val burnIn = rememberBurnInOffset()
    val send: (String) -> Unit = remember(client) { { cmd: String -> client.send(cmd) } }
    val keyboard = remember(send) { KeyboardState(send) }

    val colors = darkColorScheme(
        primary = theme.accent,
        onPrimary = OledBlack,
        background = OledBlack,
        surface = Color(0xFF060606),
        onSurface = theme.accent,
        onSurfaceVariant = theme.dim,
        outline = theme.dim,
    )

    CompositionLocalProvider(LocalRemoteTheme provides theme) {
        MaterialTheme(colorScheme = colors) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OledBlack)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .offset { burnIn.value }
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(top = 12.dp, bottom = if (mode == RemoteMode.KEYBOARD) 10.dp else 12.dp)
                    ) {
                        TopBar(
                            showExit = showExit,
                            onExit = onExit,
                            onCycleTheme = { settings.cycleTheme() },
                            onOpenSettings = { onShowSettingsChange(true) },
                        )
                        Spacer(Modifier.height(10.dp))
                        ModeTabs(mode) { mode = it }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            when (mode) {
                                RemoteMode.REMOTE -> RemoteTab(send)
                                RemoteMode.TRACKPAD -> TrackpadTab(send)
                                RemoteMode.KEYBOARD -> KeyboardInputArea(keyboard, Modifier.fillMaxSize())
                            }
                        }
                    }
                    if (mode == RemoteMode.KEYBOARD) {
                        AndroidKeyboard(keyboard)
                    }
                }
            }
            if (showSettings) {
                SettingsDialog(settings = settings, onDismiss = { onShowSettingsChange(false) })
            }
        }
    }
}

/** OLED burn-in protection: nudge the whole UI by ±8–10 px every 60 s. */
@Composable
private fun rememberBurnInOffset(): State<IntOffset> {
    val state = remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            state.value = IntOffset(jitter(), jitter())
        }
    }
    return state
}

private fun jitter(): Int = Random.nextInt(8, 11) * (if (Random.nextBoolean()) 1 else -1)

@Composable
private fun TopBar(
    showExit: Boolean,
    onExit: () -> Unit,
    onCycleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveClock()
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable {
                    haptics.tap()
                    onCycleTheme()
                }
                .border(WireBorder, theme.border, CircleShape)
                .padding(7.dp)
                .background(theme.accent, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        WireButton(
            label = "⚙\uFE0E",
            onClick = onOpenSettings,
            modifier = Modifier.height(34.dp),
            horizontalPadding = 12.dp,
            fontSize = 18.sp,
        )
        if (showExit) {
            Spacer(Modifier.width(10.dp))
            WireButton(
                label = "EXIT",
                onClick = onExit,
                modifier = Modifier.height(34.dp),
                horizontalPadding = 12.dp,
            )
        }
    }
}

@Composable
private fun LiveClock() {
    val theme = LocalRemoteTheme.current
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }
    Text(
        text = now.format(formatter),
        color = theme.label,
        fontSize = 26.sp,
        fontFamily = Mono,
        fontWeight = FontWeight.Light,
    )
}

@Composable
private fun ModeTabs(mode: RemoteMode, onSelect: (RemoteMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteMode.entries.forEach { m ->
            WireButton(
                label = m.title,
                onClick = { onSelect(m) },
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                selected = m == mode,
                dimLabel = m != mode,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingsDialog(settings: RemoteSettings, onDismiss: () -> Unit) {
    val theme = LocalRemoteTheme.current
    var ip by remember { mutableStateOf(settings.ip) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    val portValue = port.toIntOrNull()
    val valid = ip.isNotBlank() && portValue != null && portValue in 1..65535
    val shape = RoundedCornerShape(18.dp)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.border,
        unfocusedBorderColor = theme.dim,
        focusedLabelColor = theme.label,
        unfocusedLabelColor = theme.dim,
        cursorColor = theme.accent,
        focusedTextColor = theme.label,
        unfocusedTextColor = theme.label,
        focusedContainerColor = OledBlack,
        unfocusedContainerColor = OledBlack,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(WireBorder, theme.border, shape),
        shape = shape,
        containerColor = OledBlack,
        titleContentColor = theme.label,
        textContentColor = theme.label,
        title = { Text("SETTINGS", fontFamily = Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("BRIGHTNESS", color = theme.dim, fontFamily = Mono, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(settings.brightness * 100).roundToInt()}%",
                        color = theme.label,
                        fontFamily = Mono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = settings.brightness,
                    onValueChange = { settings.updateBrightness(it) },
                    valueRange = RemoteSettings.MIN_BRIGHTNESS..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.accent,
                        activeTrackColor = theme.accent,
                        inactiveTrackColor = theme.faint,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Text("TARGET DEVICE", color = theme.dim, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP address", fontFamily = Mono) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text("UDP port", fontFamily = Mono) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "THEME · ${theme.name.uppercase()}",
                    color = theme.dim,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    RemoteThemes.forEachIndexed { index, t ->
                        val selected = index == settings.themeIndex
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable { settings.setTheme(index) }
                                .border(
                                    if (selected) 2.dp else WireBorder,
                                    if (selected) t.accent else t.accent.copy(alpha = 0.35f),
                                    CircleShape,
                                )
                                .padding(if (selected) 6.dp else 8.dp)
                                .background(t.accent, CircleShape)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (portValue != null) settings.setTarget(ip, portValue)
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = theme.label,
                    disabledContentColor = theme.faint,
                ),
            ) { Text("SAVE", fontFamily = Mono, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = theme.dim),
            ) { Text("CANCEL", fontFamily = Mono) }
        },
    )
}
