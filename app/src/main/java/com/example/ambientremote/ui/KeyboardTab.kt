package com.example.ambientremote.ui

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ShiftMode { OFF, ONCE, LOCKED }

/** Keyboard state shared by the input area (top) and the docked keyboard panel (bottom). */
@Stable
class KeyboardState(private val send: (String) -> Unit) {
    var batch by mutableStateOf(false)
    var buffer by mutableStateOf("")
        private set
    var echo by mutableStateOf("")
        private set
    var shift by mutableStateOf(ShiftMode.OFF)
        private set
    var symbols by mutableStateOf(false)
        private set
    private var lastShiftTap = 0L

    val upper: Boolean get() = shift != ShiftMode.OFF && !symbols

    fun typeChar(raw: String) {
        val c = if (upper) raw.uppercase() else raw
        if (batch) {
            buffer += c
        } else {
            send("KEY_CHAR:$c")
            echo = (echo + c).takeLast(400)
        }
        if (shift == ShiftMode.ONCE) shift = ShiftMode.OFF
    }

    /** Whole phrase at once (voice typing, paste). */
    fun typeText(text: String) {
        if (text.isEmpty()) return
        if (batch) {
            buffer += text
        } else {
            send("KEY_TEXT:$text")
            echo = (echo + text).takeLast(400)
        }
    }

    fun backspace() {
        if (batch) {
            buffer = buffer.dropLast(1)
        } else {
            send("KEY_BACKSPACE")
            echo = echo.dropLast(1)
        }
    }

    fun enter() {
        if (batch) {
            sendBatch()
        } else {
            send("KEY_ENTER")
            echo = ""
        }
    }

    fun sendBatch() {
        if (buffer.isNotEmpty()) {
            send("KEY_TEXT:$buffer")
            buffer = ""
        }
    }

    /** Tap = next letter capital, double-tap = caps lock, tap again = off. */
    fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = when (shift) {
            ShiftMode.OFF -> ShiftMode.ONCE
            ShiftMode.ONCE -> if (now - lastShiftTap < 350) ShiftMode.LOCKED else ShiftMode.OFF
            ShiftMode.LOCKED -> ShiftMode.OFF
        }
        lastShiftTap = now
    }

    fun toggleSymbols() {
        symbols = !symbols
        shift = ShiftMode.OFF
    }

    fun command(cmd: String) = send(cmd)
}

// ---------------------------------------------------------------- layouts

private sealed interface KeySpec {
    val weight: Float
}

private data class CharKey(val char: String, override val weight: Float = 1f) : KeySpec
private data class FnKey(val fn: Fn, override val weight: Float) : KeySpec
private data class GapKey(override val weight: Float) : KeySpec

private enum class Fn { SHIFT, BACKSPACE, LAYER, SPACE, ENTER }

private fun chars(s: String): List<KeySpec> = s.map { CharKey(it.toString()) }

private val NUMBER_ROW = chars("1234567890")

private val BOTTOM_ROW: List<KeySpec> = listOf(
    FnKey(Fn.LAYER, 1.5f), CharKey(","), FnKey(Fn.SPACE, 5f), CharKey("."), FnKey(Fn.ENTER, 1.5f),
)

private val LETTER_ROWS: List<List<KeySpec>> = listOf(
    chars("qwertyuiop"),
    listOf(GapKey(0.5f)) + chars("asdfghjkl") + GapKey(0.5f),
    listOf(FnKey(Fn.SHIFT, 1.5f)) + chars("zxcvbnm") + FnKey(Fn.BACKSPACE, 1.5f),
    BOTTOM_ROW,
)

private val SYMBOL_ROWS: List<List<KeySpec>> = listOf(
    chars("@#\$_&-+()/"),
    listOf(GapKey(0.5f)) + chars("*\"':;!?=%") + GapKey(0.5f),
    listOf(CharKey("~", 1.5f)) + chars("[]{}<>\\") + FnKey(Fn.BACKSPACE, 1.5f),
    BOTTOM_ROW,
)

// ---------------------------------------------------------------- top area

/** LIVE/BATCH toggle and a large read-out of what has been typed. */
@Composable
fun KeyboardInputArea(state: KeyboardState, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WireButton(
                label = "LIVE",
                onClick = { state.batch = false },
                modifier = Modifier.weight(1f).height(34.dp),
                selected = !state.batch,
                dimLabel = state.batch,
                fontSize = 11.sp,
            )
            WireButton(
                label = "BATCH",
                onClick = { state.batch = true },
                modifier = Modifier.weight(1f).height(34.dp),
                selected = state.batch,
                dimLabel = !state.batch,
                fontSize = 11.sp,
            )
            // Voice needs an activity to show the speech prompt (not available in the screensaver).
            if (LocalActivityResultRegistryOwner.current != null) {
                VoiceButton(state, Modifier.weight(1f).height(34.dp))
            }
            PasteButton(state, Modifier.weight(1f).height(34.dp))
            if (state.batch) {
                WireButton(
                    label = "SEND",
                    onClick = { state.sendBatch() },
                    modifier = Modifier.weight(1f).height(34.dp),
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(OledBlack)
                .border(WireBorder, theme.dim, shape)
                .padding(14.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            val text = if (state.batch) state.buffer else state.echo
            if (text.isEmpty()) {
                Text(
                    if (state.batch) "Type, then SEND" else "Live typing goes straight to the TV",
                    color = theme.faint,
                    fontSize = 15.sp,
                    fontFamily = Mono,
                )
            } else {
                Text(
                    text.takeLast(160) + "▌",
                    color = theme.label,
                    fontSize = 22.sp,
                    fontFamily = Mono,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun VoiceButton(state: KeyboardState, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) state.typeText(text)
    }
    WireButton(
        label = "VOICE",
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV")
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No voice typing on this phone", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        fontSize = 11.sp,
    )
}

@Composable
private fun PasteButton(state: KeyboardState, modifier: Modifier) {
    val context = LocalContext.current
    WireButton(
        label = "PASTE",
        onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            if (text.isNullOrEmpty()) {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                state.typeText(text)
            }
        },
        modifier = modifier,
        fontSize = 11.sp,
    )
}

// ---------------------------------------------------------------- docked keyboard

/** Full-width Android-style keyboard docked to the bottom of the screen. */
@Composable
fun AndroidKeyboard(state: KeyboardState, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(WireBorder)
                .background(theme.dim)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(theme.accent.copy(alpha = 0.06f))
                .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ToolbarStrip(state)
            KeyRow(NUMBER_ROW, state, numeric = true)
            (if (state.symbols) SYMBOL_ROWS else LETTER_ROWS).forEach { KeyRow(it, state) }
        }
    }
}

/** Where Gboard shows suggestions: cursor arrows, BACK and SEARCH. */
@Composable
private fun ToolbarStrip(state: KeyboardState) {
    val theme = LocalRemoteTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("←" to "LEFT", "→" to "RIGHT", "BACK" to "BACK", "SEARCH" to "KEY_SEARCH")
            .forEachIndexed { i, (label, cmd) ->
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(theme.faint)
                    )
                }
                ToolbarItem(label, Modifier.weight(1f).fillMaxHeight()) { state.command(cmd) }
            }
    }
}

@Composable
private fun ToolbarItem(label: String, modifier: Modifier, onClick: () -> Unit) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (pressed) theme.fillPressed else OledBlack.copy(alpha = 0f))
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = theme.label,
            fontSize = if (label.length == 1) 22.sp else 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KeyRow(row: List<KeySpec>, state: KeyboardState, numeric: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        row.forEach { key ->
            val m = Modifier
                .weight(key.weight)
                .fillMaxHeight()
            when (key) {
                is GapKey -> Spacer(m)
                is CharKey -> KbKey(
                    label = if (state.upper) key.char.uppercase() else key.char,
                    modifier = m,
                    fontSize = if (numeric) 21.sp else 23.sp,
                ) { state.typeChar(key.char) }
                is FnKey -> when (key.fn) {
                    Fn.SHIFT -> KbKey(
                        label = if (state.shift == ShiftMode.LOCKED) "⇪" else "⇧",
                        modifier = m,
                        tone = KeyTone.SPECIAL,
                        highlighted = state.shift != ShiftMode.OFF,
                        fontSize = 22.sp,
                    ) { state.tapShift() }
                    Fn.BACKSPACE -> KbKey(
                        label = "⌫",
                        modifier = m,
                        tone = KeyTone.SPECIAL,
                        fontSize = 20.sp,
                        repeat = true,
                    ) { state.backspace() }
                    Fn.LAYER -> KbKey(
                        label = if (state.symbols) "ABC" else "?123",
                        modifier = m,
                        tone = KeyTone.SPECIAL,
                        shape = RoundedCornerShape(50),
                        fontSize = 15.sp,
                    ) { state.toggleSymbols() }
                    Fn.SPACE -> KbKey(label = "", modifier = m) { state.typeChar(" ") }
                    Fn.ENTER -> KbKey(
                        label = "↵",
                        modifier = m,
                        tone = KeyTone.ACCENT,
                        shape = RoundedCornerShape(50),
                        fontSize = 24.sp,
                    ) { state.enter() }
                }
            }
        }
    }
}

private enum class KeyTone { NORMAL, SPECIAL, ACCENT }

/** Filled key tile. Haptic on touch-down, fires on release; [repeat] keys fire on down and repeat while held. */
@Composable
private fun KbKey(
    label: String,
    modifier: Modifier,
    tone: KeyTone = KeyTone.NORMAL,
    shape: Shape = RoundedCornerShape(8.dp),
    fontSize: TextUnit = 23.sp,
    highlighted: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }

    val background = when {
        tone == KeyTone.ACCENT -> if (pressed) theme.accent.copy(alpha = 0.7f) else theme.accent
        pressed || highlighted -> theme.accent.copy(alpha = 0.42f)
        tone == KeyTone.SPECIAL -> theme.accent.copy(alpha = 0.24f)
        else -> theme.accent.copy(alpha = 0.14f)
    }
    val foreground = if (tone == KeyTone.ACCENT) OledBlack else theme.label

    Box(
        modifier
            .clip(shape)
            .background(background)
            .pointerInput(repeat) {
                if (repeat) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        pressed = true
                        val job = scope.launch {
                            haptics.tap()
                            current()
                            delay(420)
                            while (isActive) {
                                haptics.tick()
                                current()
                                delay(55)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                        pressed = false
                    }
                } else {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        pressed = true
                        haptics.tap()
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) {
                            up.consume()
                            current()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, fontSize = fontSize, maxLines = 1)
    }
}
