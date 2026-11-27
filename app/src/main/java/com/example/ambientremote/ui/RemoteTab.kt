package com.example.ambientremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val LONG_PRESS_MS = 480L

@Composable
fun RemoteTab(send: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            GesturePad(
                send = send,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Spacer(Modifier.width(10.dp))
            DragStrip(
                label = "VOL",
                upCommand = "VOL_UP",
                downCommand = "VOL_DOWN",
                send = send,
                showTapButtons = true,
                modifier = Modifier
                    .width(62.dp)
                    .fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(16.dp))
        BottomButtonRow(
            listOf("BACK" to "BACK", "HOME" to "HOME", "PLAY" to "PLAY_PAUSE", "MUTE" to "MUTE"),
            send,
        )
    }
}

private fun swipeDirection(delta: Offset): String =
    if (abs(delta.x) > abs(delta.y)) {
        if (delta.x > 0) "RIGHT" else "LEFT"
    } else {
        if (delta.y > 0) "DOWN" else "UP"
    }

/** Blind-touch pad: swipe = direction, tap = SELECT, long-press = BACK. */
@Composable
private fun GesturePad(send: (String) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val sendNow by rememberUpdatedState(send)
    var flash by remember { mutableStateOf<String?>(null) }
    var flashId by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashId) {
        if (flashId > 0) {
            delay(450)
            flash = null
        }
    }
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier
            .clip(shape)
            .background(theme.fill)
            .border(WireBorder, theme.border, shape)
            .pointerInput(Unit) {
                val swipeThreshold = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var command: String? = null
                    var stillDown = true
                    val completed = withTimeoutOrNull(LONG_PRESS_MS) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                stillDown = false
                                break
                            }
                            val delta = change.position - down.position
                            if (!change.pressed) {
                                stillDown = false
                                command = if (delta.getDistance() < swipeThreshold) "SELECT"
                                else swipeDirection(delta)
                                break
                            }
                            change.consume()
                            if (delta.getDistance() >= swipeThreshold) {
                                command = swipeDirection(delta)
                                break
                            }
                        }
                        true
                    }
                    val isLongPress = completed == null
                    val cmd = if (isLongPress) "BACK" else command
                    if (cmd != null) {
                        if (isLongPress) haptics.heavy() else haptics.tap()
                        sendNow(cmd)
                        flash = cmd
                        flashId++
                    }
                    if (stillDown) {
                        do {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val active = flash
        Chevron("▲", active == "UP", Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        Chevron("▼", active == "DOWN", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        Chevron("◀︎", active == "LEFT", Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        Chevron("▶︎", active == "RIGHT", Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val lit = active == "SELECT" || active == "BACK"
            Box(
                Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .background(if (lit) theme.fillPressed else OledBlack)
                    .border(WireBorder, if (lit) theme.border else theme.dim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (active) {
                        null, "SELECT" -> "OK"
                        else -> active
                    },
                    color = if (active != null) theme.label else theme.dim,
                    fontSize = if (active == null || active == "SELECT") 18.sp else 12.sp,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "HOLD · BACK",
                color = theme.faint,
                fontSize = 10.sp,
                fontFamily = Mono,
            )
        }
    }
}

@Composable
private fun Chevron(glyph: String, active: Boolean, modifier: Modifier) {
    val theme = LocalRemoteTheme.current
    Text(
        text = glyph,
        color = if (active) theme.label else theme.dim,
        fontSize = if (active) 32.sp else 26.sp,
        modifier = modifier,
    )
}
