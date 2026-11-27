package com.example.ambientremote.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SENSITIVITY = 1.0f
private const val SEND_INTERVAL_MS = 16L
private const val TAP_MAX_MS = 280L

@Composable
fun TrackpadTab(send: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            DotGridTrackpad(
                send = send,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Spacer(Modifier.width(10.dp))
            DragStrip(
                label = "SCR",
                upCommand = "MOUSE_SCROLL_UP",
                downCommand = "MOUSE_SCROLL_DOWN",
                send = send,
                stepDp = 18.dp,
                modifier = Modifier
                    .width(62.dp)
                    .fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(16.dp))
        BottomButtonRow(
            listOf(
                "L-CLICK" to "MOUSE_CLICK",
                "R-CLICK" to "MOUSE_RIGHT_CLICK",
                "CENTER" to "MOUSE_CENTER",
                "BACK" to "BACK",
            ),
            send,
        )
    }
}

/** Drag = throttled relative MOUSE_MOVE deltas, quick tap = MOUSE_CLICK. */
@Composable
private fun DotGridTrackpad(send: (String) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val sendNow by rememberUpdatedState(send)
    var touch by remember { mutableStateOf<Offset?>(null) }
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier
            .clip(shape)
            .background(theme.fill)
            .border(WireBorder, theme.border, shape)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touch = down.position
                    val downTime = down.uptimeMillis
                    var pendingX = 0f
                    var pendingY = 0f
                    var travelled = 0f
                    var moving = false
                    var lastSent = downTime

                    fun flush() {
                        val dx = pendingX.toInt()
                        val dy = pendingY.toInt()
                        if (dx != 0 || dy != 0) {
                            sendNow("MOUSE_MOVE:$dx,$dy")
                            pendingX -= dx
                            pendingY -= dy
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!moving && change.uptimeMillis - downTime <= TAP_MAX_MS) {
                                haptics.tap()
                                sendNow("MOUSE_CLICK")
                            } else {
                                flush()
                            }
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        change.consume()
                        touch = change.position
                        travelled += delta.getDistance()
                        if (!moving && travelled > slop) moving = true
                        if (moving) {
                            pendingX += delta.x * SENSITIVITY
                            pendingY += delta.y * SENSITIVITY
                            if (change.uptimeMillis - lastSent >= SEND_INTERVAL_MS) {
                                flush()
                                lastSent = change.uptimeMillis
                            }
                        }
                    }
                    touch = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 22.dp.toPx()
            val radius = 1.4.dp.toPx()
            val glow = 72.dp.toPx()
            val cols = (size.width / step).toInt().coerceAtLeast(1)
            val rows = (size.height / step).toInt().coerceAtLeast(1)
            val offX = (size.width - (cols - 1) * step) / 2f
            val offY = (size.height - (rows - 1) * step) / 2f
            val t = touch
            for (i in 0 until cols) {
                for (j in 0 until rows) {
                    val c = Offset(offX + i * step, offY + j * step)
                    val near = t != null && (t - c).getDistance() < glow
                    drawCircle(
                        color = if (near) theme.dim else theme.faint,
                        radius = if (near) radius * 1.5f else radius,
                        center = c,
                    )
                }
            }
            if (t != null) {
                drawCircle(
                    color = theme.border,
                    radius = 24.dp.toPx(),
                    center = t,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Text(
            "TAP · CLICK",
            color = theme.faint,
            fontSize = 10.sp,
            fontFamily = Mono,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
    }
}
