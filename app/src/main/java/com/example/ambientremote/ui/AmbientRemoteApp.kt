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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.example.ambientremote.RemoteControlService
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.ambientremote.BluetoothHidRemote
import com.example.ambientremote.CommandRouter
import com.example.ambientremote.Transport
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
    client: CommandRouter,
    settings: RemoteSettings,
    showExit: Boolean,
    onExit: () -> Unit,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    onEnterLockScreen: (() -> Unit)? = null,
    onLockScreen: Boolean = false,
    onExitLockScreen: () -> Unit = {},
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

    CompositionLocalProvider(
        LocalRemoteTheme provides theme,
        LocalHapticLevel provides settings.hapticLevel,
    ) {
        MaterialTheme(colorScheme = colors) {
            var lastPress by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
            var dimmed by remember { mutableStateOf(false) }
            val idleSec = settings.idleTimeoutSec
            LaunchedEffect(lastPress, idleSec, dimmed, showSettings) {
                if (idleSec > 0 && !dimmed && !showSettings) {
                    delay(idleSec * 1_000L)
                    dimmed = true
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OledBlack)
                    .pointerInput(Unit) {
                        // Watch every touch (without consuming) to reset the idle timer.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.type == PointerEventType.Press) lastPress = SystemClock.uptimeMillis()
                            }
                        }
                    }
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
                            chip = {
                                ConnectionChip(
                                    settings = settings,
                                    allowMenu = showExit,
                                    onOpenSettings = { onShowSettingsChange(true) },
                                )
                            },
                            showExit = showExit,
                            exitLabel = if (onLockScreen) "EXIT LOCK" else "EXIT",
                            onExit = if (onLockScreen) onExitLockScreen else onExit,
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
                                RemoteMode.TRACKPAD -> TrackpadTab(send, settings)
                                RemoteMode.KEYBOARD -> KeyboardInputArea(keyboard, Modifier.fillMaxSize())
                            }
                        }
                    }
                    if (mode == RemoteMode.KEYBOARD) {
                        AndroidKeyboard(keyboard)
                    }
                }
                if (dimmed) {
                    // Idle: pure black (OLED pixels off). First tap only wakes the screen.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(OledBlack)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    dimmed = false
                                    lastPress = SystemClock.uptimeMillis()
                                    waitForUpOrCancellation()?.consume()
                                }
                            }
                    )
                }
            }
            if (showSettings) {
                SettingsDialog(
                    settings = settings,
                    onDismiss = { onShowSettingsChange(false) },
                    onEnterLockScreen = onEnterLockScreen,
                )
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
    chip: @Composable () -> Unit,
    showExit: Boolean,
    exitLabel: String,
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
        chip()
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
                label = exitLabel,
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
private fun SettingsDialog(
    settings: RemoteSettings,
    onDismiss: () -> Unit,
    onEnterLockScreen: (() -> Unit)?,
) {
    val theme = LocalRemoteTheme.current
    var ip by remember { mutableStateOf(settings.ip) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    val portValue = port.toIntOrNull()
    val wifiValid = ip.isNotBlank() && portValue != null && portValue in 1..65535
    val valid = settings.transport == Transport.BLUETOOTH || wifiValid
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
                Text("LOCK SCREEN", color = theme.dim, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireButton(
                        label = if (settings.runOnLockScreen) "RUN ON LOCK: ON" else "RUN ON LOCK: OFF",
                        onClick = { settings.updateRunOnLockScreen(!settings.runOnLockScreen) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        selected = settings.runOnLockScreen,
                        dimLabel = !settings.runOnLockScreen,
                        fontSize = 11.sp,
                    )
                    if (onEnterLockScreen != null) {
                        WireButton(
                            label = "ENTER LOCK SCREEN",
                            onClick = onEnterLockScreen,
                            modifier = Modifier.weight(1f).height(40.dp),
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("LOCK SCREEN BRIGHTNESS", color = theme.dim, fontFamily = Mono, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(settings.lockBrightness * 100).roundToInt()}%",
                        color = theme.label,
                        fontFamily = Mono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = settings.lockBrightness,
                    onValueChange = { settings.updateLockBrightness(it) },
                    valueRange = RemoteSettings.MIN_BRIGHTNESS..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.accent,
                        activeTrackColor = theme.accent,
                        inactiveTrackColor = theme.faint,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Text("CONNECTION", color = theme.dim, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Transport.entries.forEach { t ->
                        WireButton(
                            label = if (t == Transport.BLUETOOTH) "BLUETOOTH" else "WI-FI",
                            onClick = { settings.updateTransport(t) },
                            modifier = Modifier.weight(1f).height(36.dp),
                            selected = settings.transport == t,
                            dimLabel = settings.transport != t,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (settings.transport == Transport.BLUETOOTH) {
                    BluetoothSection()
                } else {
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
                        isError = !wifiValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(18.dp))
                PhoneAndTrackpadSettings(settings)
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
                    if (wifiValid && portValue != null) settings.setTarget(ip, portValue)
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

@Composable
private fun BluetoothSection() {
    val theme = LocalRemoteTheme.current
    val context = LocalContext.current
    val status = BluetoothHidRemote.status
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { BluetoothHidRemote.start(context) }
    val systemLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { BluetoothHidRemote.start(context) }
    LaunchedEffect(Unit) { BluetoothHidRemote.start(context) }

    val statusText = when (status) {
        BluetoothHidRemote.Status.CONNECTED -> "Connected to ${BluetoothHidRemote.connectedName ?: "device"}"
        BluetoothHidRemote.Status.CONNECTING -> "Connecting…"
        BluetoothHidRemote.Status.READY -> "Not connected. Tap a device below."
        BluetoothHidRemote.Status.STARTING -> "Starting Bluetooth…"
        BluetoothHidRemote.Status.BT_OFF -> "Bluetooth is off"
        BluetoothHidRemote.Status.NO_PERMISSION -> "Bluetooth permission needed"
        BluetoothHidRemote.Status.UNAVAILABLE ->
            "Bluetooth keyboard mode isn't available. Close other remote apps (like TV Trackpad) and reopen."
    }
    Text(
        statusText,
        color = if (status == BluetoothHidRemote.Status.CONNECTED) theme.label else theme.dim,
        fontFamily = Mono,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(8.dp))

    when (status) {
        BluetoothHidRemote.Status.NO_PERMISSION -> WireButton(
            label = "ALLOW BLUETOOTH",
            onClick = { permissionLauncher.launch(BluetoothHidRemote.requiredPermissions) },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            fontSize = 12.sp,
        )
        BluetoothHidRemote.Status.BT_OFF -> WireButton(
            label = "TURN ON BLUETOOTH",
            onClick = { systemLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            fontSize = 12.sp,
        )
        else -> Unit
    }

    val ready = status == BluetoothHidRemote.Status.READY ||
        status == BluetoothHidRemote.Status.CONNECTING ||
        status == BluetoothHidRemote.Status.CONNECTED
    if (ready) {
        val devices = remember(status, BluetoothHidRemote.connectedAddress) {
            BluetoothHidRemote.pairedDevices(context)
        }
        Text("PAIRED DEVICES", color = theme.dim, fontFamily = Mono, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        if (devices.isEmpty()) {
            Text("None yet. Pair one below.", color = theme.faint, fontFamily = Mono, fontSize = 12.sp)
        }
        devices.forEach { d ->
            WireButton(
                label = d.name,
                onClick = { BluetoothHidRemote.connect(context, d.address) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .height(40.dp),
                selected = d.address == BluetoothHidRemote.connectedAddress,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton(
                label = "MAKE VISIBLE",
                onClick = {
                    systemLauncher.launch(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    )
                },
                modifier = Modifier.weight(1f).height(38.dp),
                fontSize = 11.sp,
            )
            if (status == BluetoothHidRemote.Status.CONNECTED) {
                WireButton(
                    label = "DISCONNECT",
                    onClick = { BluetoothHidRemote.disconnect() },
                    modifier = Modifier.weight(1f).height(38.dp),
                    dimLabel = true,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "New device: tap MAKE VISIBLE, then pair this phone from the TV " +
                "(Settings › Remotes & Accessories › Pair accessory) or Mac (System Settings › Bluetooth).",
            color = theme.faint,
            fontFamily = Mono,
            fontSize = 11.sp,
        )
    }
}

/** BT/WIFI chip in the top bar. In the app it opens a quick device switcher. */
@Composable
private fun ConnectionChip(settings: RemoteSettings, allowMenu: Boolean, onOpenSettings: () -> Unit) {
    val theme = LocalRemoteTheme.current
    val context = LocalContext.current
    val bt = settings.transport == Transport.BLUETOOTH
    val connected = !bt || BluetoothHidRemote.status == BluetoothHidRemote.Status.CONNECTED
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        WireButton(
            label = if (bt) "BT" else "WIFI",
            onClick = {
                if (allowMenu) {
                    menuOpen = true
                } else {
                    onOpenSettings()
                }
            },
            modifier = Modifier.height(34.dp),
            horizontalPadding = 10.dp,
            fontSize = 12.sp,
            dimLabel = !connected,
        )
        if (allowMenu) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = OledBlack,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(WireBorder, theme.border),
            ) {
                val devices = remember(menuOpen) { BluetoothHidRemote.pairedDevices(context) }
                devices.forEach { d ->
                    val isCurrent = bt && d.address == BluetoothHidRemote.connectedAddress
                    DropdownMenuItem(
                        text = { MenuText((if (isCurrent) "● " else "   ") + d.name, true) },
                        onClick = {
                            menuOpen = false
                            if (!bt) settings.updateTransport(Transport.BLUETOOTH)
                            BluetoothHidRemote.start(context)
                            BluetoothHidRemote.connect(context, d.address)
                        },
                    )
                }
                if (devices.isEmpty()) {
                    DropdownMenuItem(
                        text = { MenuText("No paired devices yet", false) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { MenuText(if (bt) "Use Wi-Fi instead" else "Use Bluetooth", false) },
                    onClick = {
                        menuOpen = false
                        val next = if (bt) Transport.WIFI else Transport.BLUETOOTH
                        settings.updateTransport(next)
                        if (next == Transport.BLUETOOTH) BluetoothHidRemote.start(context)
                    },
                )
                DropdownMenuItem(
                    text = { MenuText("Connection settings…", false) },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuText(text: String, bright: Boolean) {
    val theme = LocalRemoteTheme.current
    Text(text, color = if (bright) theme.label else theme.dim, fontFamily = Mono, fontSize = 14.sp)
}

@Composable
private fun SectionTitle(text: String) {
    val theme = LocalRemoteTheme.current
    Text(text, color = theme.dim, fontFamily = Mono, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalRemoteTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = theme.label, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OledBlack,
                checkedTrackColor = theme.accent,
                checkedBorderColor = theme.accent,
                uncheckedThumbColor = theme.dim,
                uncheckedTrackColor = OledBlack,
                uncheckedBorderColor = theme.dim,
            ),
        )
    }
}

@Composable
private fun SegmentRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            WireButton(
                label = label,
                onClick = { onSelect(i) },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                selected = i == selected,
                dimLabel = i != selected,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val theme = LocalRemoteTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = theme.label, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(valueText, color = theme.label, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = theme.accent,
            activeTrackColor = theme.accent,
            inactiveTrackColor = theme.faint,
            activeTickColor = OledBlack,
            inactiveTickColor = theme.dim,
        ),
    )
}

private val IDLE_OPTIONS = listOf(0, 15, 30, 60)

@Composable
private fun PhoneAndTrackpadSettings(settings: RemoteSettings) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { RemoteControlService.start(context) }

    SectionTitle("PHONE")
    SettingToggle("Volume buttons control TV", settings.volumeKeys) { settings.updateVolumeKeys(it) }
    SettingToggle("Notification controls", settings.notificationControls) { on ->
        settings.updateNotificationControls(on)
        if (!on) {
            RemoteControlService.stop(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RemoteControlService.start(context)
        }
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle("TRACKPAD")
    LabelledSlider(
        label = "Pointer speed",
        valueText = "${"%.1f".format(settings.pointerSpeed)}×",
        value = settings.pointerSpeed,
        range = 0.4f..3f,
    ) { settings.updatePointerSpeed(it) }
    SettingToggle("Pointer acceleration", settings.pointerAcceleration) { settings.updatePointerAcceleration(it) }
    LabelledSlider(
        label = "Scroll speed",
        valueText = "${settings.scrollSpeed}",
        value = settings.scrollSpeed.toFloat(),
        range = 1f..5f,
        steps = 3,
    ) { settings.updateScrollSpeed(it.roundToInt()) }
    SettingToggle("Natural scrolling", settings.naturalScroll) { settings.updateNaturalScroll(it) }

    Spacer(Modifier.height(14.dp))
    SectionTitle("BUTTON FEEL")
    SegmentRow(listOf("OFF", "LIGHT", "STRONG"), settings.hapticLevel) { settings.updateHapticLevel(it) }

    Spacer(Modifier.height(14.dp))
    SectionTitle("DIM WHEN IDLE")
    SegmentRow(
        listOf("NEVER", "15 S", "30 S", "60 S"),
        IDLE_OPTIONS.indexOf(settings.idleTimeoutSec).coerceAtLeast(0),
    ) { settings.updateIdleTimeout(IDLE_OPTIONS[it]) }
}
