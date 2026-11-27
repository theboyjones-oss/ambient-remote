package com.example.ambientremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface KeySpec {
    val weight: Float
}

private data class CharKey(val char: String, override val weight: Float = 1f) : KeySpec
private data class FnKey(val fn: Fn, override val weight: Float) : KeySpec
private data class GapKey(override val weight: Float) : KeySpec

private enum class Fn { SHIFT, BACKSPACE, LAYER, SPACE, ENTER }

private fun chars(s: String): List<KeySpec> = s.map { CharKey(it.toString()) }

private val LETTER_ROWS: List<List<KeySpec>> = listOf(
    chars("qwertyuiop"),
    listOf(GapKey(0.5f)) + chars("asdfghjkl") + GapKey(0.5f),
    listOf(FnKey(Fn.SHIFT, 1.5f)) + chars("zxcvbnm") + FnKey(Fn.BACKSPACE, 1.5f),
    listOf(FnKey(Fn.LAYER, 1.5f), CharKey(","), FnKey(Fn.SPACE, 5f), CharKey("."), FnKey(Fn.ENTER, 1.5f)),
)

private val SYMBOL_ROWS: List<List<KeySpec>> = listOf(
    chars("1234567890"),
    chars("@#\$%&-+()/"),
    chars("*\"':;!?_") + FnKey(Fn.BACKSPACE, 2f),
    listOf(FnKey(Fn.LAYER, 1.5f), CharKey(","), FnKey(Fn.SPACE, 5f), CharKey("."), FnKey(Fn.ENTER, 1.5f)),
)

@Composable
fun KeyboardTab(send: (String) -> Unit) {
    val theme = LocalRemoteTheme.current
    var batch by rememberSaveable { mutableStateOf(false) }
    var buffer by rememberSaveable { mutableStateOf("") }
    var echo by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }

    fun sendBatch() {
        if (buffer.isNotEmpty()) {
            send("KEY_TEXT:$buffer")
            buffer = ""
        }
    }

    fun typeChar(raw: String) {
        val c = if (shift && !symbols) raw.uppercase() else raw
        if (batch) {
            buffer += c
        } else {
            send("KEY_CHAR:$c")
            echo = (echo + c).takeLast(40)
        }
        shift = false
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

    Column(Modifier.fillMaxSize()) {
        // LIVE / BATCH toggle
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton(
                label = "LIVE",
                onClick = { batch = false },
                modifier = Modifier.weight(1f).height(34.dp),
                selected = !batch,
                dimLabel = batch,
                fontSize = 12.sp,
            )
            WireButton(
                label = "BATCH",
                onClick = { batch = true },
                modifier = Modifier.weight(1f).height(34.dp),
                selected = batch,
                dimLabel = !batch,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Input / echo field
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(OledBlack)
                    .border(WireBorder, theme.dim, shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val text = if (batch) buffer else echo
                val placeholder = if (batch) "type, then SEND" else "live typing…"
                Text(
                    text = if (text.isEmpty()) placeholder else text.takeLast(30) + "▌",
                    color = if (text.isEmpty()) theme.faint else theme.label,
                    fontSize = 16.sp,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            if (batch) {
                Spacer(Modifier.width(8.dp))
                WireButton(
                    label = "SEND",
                    onClick = { sendBatch() },
                    modifier = Modifier.fillMaxHeight().width(76.dp),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 4-row wireframe keyboard
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
            rows.forEach { row ->
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    row.forEach { key ->
                        val keyMod = Modifier
                            .weight(key.weight)
                            .fillMaxHeight()
                        when (key) {
                            is GapKey -> Spacer(keyMod)
                            is CharKey -> WireButton(
                                label = if (shift && !symbols) key.char.uppercase() else key.char,
                                onClick = { typeChar(key.char) },
                                modifier = keyMod,
                                shape = RoundedCornerShape(7.dp),
                                fontSize = 17.sp,
                            )
                            is FnKey -> when (key.fn) {
                                Fn.BACKSPACE -> RepeatKey("⌫", { backspace() }, keyMod)
                                Fn.SHIFT -> WireButton(
                                    label = "⇧",
                                    onClick = { shift = !shift },
                                    modifier = keyMod,
                                    shape = RoundedCornerShape(7.dp),
                                    selected = shift,
                                    fontSize = 18.sp,
                                )
                                Fn.LAYER -> WireButton(
                                    label = if (symbols) "ABC" else "123",
                                    onClick = { symbols = !symbols; shift = false },
                                    modifier = keyMod,
                                    shape = RoundedCornerShape(7.dp),
                                    fontSize = 12.sp,
                                )
                                Fn.SPACE -> WireButton(
                                    label = "space",
                                    onClick = { typeChar(" ") },
                                    modifier = keyMod,
                                    shape = RoundedCornerShape(7.dp),
                                    dimLabel = true,
                                    fontSize = 12.sp,
                                )
                                Fn.ENTER -> WireButton(
                                    label = "↵",
                                    onClick = { enter() },
                                    modifier = keyMod,
                                    shape = RoundedCornerShape(7.dp),
                                    fontSize = 18.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        BottomButtonRow(
            listOf("←" to "LEFT", "→" to "RIGHT", "BACK" to "BACK", "SEARCH" to "KEY_SEARCH"),
            send,
        )
    }
}
