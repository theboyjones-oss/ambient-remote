package com.example.ambientremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
        Spacer(Modifier.height(12.dp))
        MediaRow(send)
        Spacer(Modifier.height(12.dp))
        BottomButtonRow(
            listOf("BACK" to "BACK", "HOME" to "HOME", "MUTE" to "MUTE", "POWER" to "POWER"),
            send,
        )
    }
}

/** Rewind / play-pause / fast-forward. */
@Composable
private fun MediaRow(send: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "◀\uFE0E◀\uFE0E" to "REWIND",
            "▶\uFE0E ❚❚" to "PLAY_PAUSE",
            "▶\uFE0E▶\uFE0E" to "FAST_FORWARD",
        ).forEach { (label, cmd) ->
            WireButton(
                label = label,
                onClick = { send(cmd) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(23.dp),
                fontSize = 15.sp,
            )
        }
    }
}

private fun swipeDirection(delta: Offset): String =
    if (abs(delta.x) > abs(delta.y)) {
        if (delta.x > 0) "RIGHT" else "LEFT"
    } else {
        if (delta.y > 0) "DOWN" else "UP"
    }

/** D-pad zones around the centre: inside the circle = SELECT, on the ring = arrow, elsewhere = null. */
private val CENTER_RADIUS = 43.dp
private val ARROW_DISTANCE = 80.dp
private val ARROW_SIZE = 56.dp

/**
 * Blind-touch pad: swipe anywhere = direction, long-press = BACK,
 * tap an arrow = that direction, tap the circle (or anywhere else) = SELECT.
 */
@Composable
private fun GesturePad(send: (String) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val sendNow by rememberUpdatedState(send)
    var flash by remember { mutableStateOf<String?>(null) }
    var flashId by remember { mutableIntStateOf(0) }
    var pressedZone by remember { mutableStateOf<String?>(null) }
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
                val inner = CENTER_RADIUS.toPx() + 2.dp.toPx()
                val outer = ARROW_DISTANCE.toPx() + ARROW_SIZE.toPx() / 2 + 12.dp.toPx()
                fun zoneAt(p: Offset): String? {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val d = p - c
                    val dist = d.getDistance()
                    return when {
                        dist <= inner -> "SELECT"
                        dist <= outer -> swipeDirection(d)
                        else -> null
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val zone = zoneAt(down.position)
                    pressedZone = zone
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
                                command = if (delta.getDistance() < swipeThreshold) {
                                    // Tap: an arrow sends its direction, anywhere else is OK/SELECT.
                                    zone ?: "SELECT"
                                } else {
                                    swipeDirection(delta)
                                }
                                break
                            }
                            change.consume()
                            if (delta.getDistance() >= swipeThreshold) {
                                pressedZone = null
                                command = swipeDirection(delta)
                                break
                            }
                        }
                        true
                    }
                    pressedZone = null
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
        val lit = { dir: String -> active == dir || pressedZone == dir }

        ArrowKey("▲", lit("UP"), Modifier.offset(y = -ARROW_DISTANCE))
        ArrowKey("▼", lit("DOWN"), Modifier.offset(y = ARROW_DISTANCE))
        ArrowKey("◀\uFE0E", lit("LEFT"), Modifier.offset(x = -ARROW_DISTANCE))
        ArrowKey("▶\uFE0E", lit("RIGHT"), Modifier.offset(x = ARROW_DISTANCE))

        val centerLit = lit("SELECT") || active == "BACK"
        Box(
            Modifier
                .size(CENTER_RADIUS * 2)
                .clip(CircleShape)
                .background(if (centerLit) theme.fillPressed else OledBlack)
                .border(WireBorder, if (centerLit) theme.border else theme.dim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (active) {
                    null, "SELECT", "UP", "DOWN", "LEFT", "RIGHT" -> "OK"
                    else -> active
                },
                color = if (centerLit) theme.label else theme.dim,
                fontSize = if (active == "BACK") 13.sp else 18.sp,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            "SWIPE · TAP · HOLD = BACK",
            color = theme.faint,
            fontSize = 10.sp,
            fontFamily = Mono,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun ArrowKey(glyph: String, lit: Boolean, modifier: Modifier) {
    val theme = LocalRemoteTheme.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(ARROW_SIZE)
            .clip(shape)
            .background(if (lit) theme.fillPressed else theme.accent.copy(alpha = 0.10f))
            .border(WireBorder, if (lit) theme.border else theme.dim, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (lit) theme.label else theme.label.copy(alpha = 0.8f),
            fontSize = 22.sp,
        )
    }
}
