package com.example.ambientremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ambientremote.RemoteSettings
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val SEND_INTERVAL_MS = 16L
private const val TAP_MAX_MS = 280L

/**
 * Laptop-style trackpad: smooth surface with a scroll edge on the right,
 * and physical-style left / middle / right buttons attached underneath.
 */
@Composable
fun TrackpadTab(send: (String) -> Unit, settings: RemoteSettings) {
    val theme = LocalRemoteTheme.current
    val scrollStep = scrollStepFor(settings.scrollSpeed)
    val scrollUp = if (settings.naturalScroll) "MOUSE_SCROLL_DOWN" else "MOUSE_SCROLL_UP"
    val scrollDown = if (settings.naturalScroll) "MOUSE_SCROLL_UP" else "MOUSE_SCROLL_DOWN"
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .border(WireBorder, theme.border, shape)
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                TrackpadSurface(
                    send = send,
                    speed = settings.pointerSpeed,
                    acceleration = settings.pointerAcceleration,
                    scrollStep = scrollStep,
                    scrollUp = scrollUp,
                    scrollDown = scrollDown,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 18.dp)
                        .background(theme.faint)
                )
                DragStrip(
                    label = "⇕",
                    upCommand = scrollUp,
                    downCommand = scrollDown,
                    send = send,
                    stepDp = scrollStep,
                    framed = false,
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .background(theme.accent.copy(alpha = 0.05f)),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WireBorder)
                    .background(theme.dim)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                ClickButton("LEFT", "MOUSE_CLICK", send, Modifier.weight(2f).fillMaxHeight())
                ButtonDivider()
                ClickButton("•", "MOUSE_CENTER", send, Modifier.weight(0.8f).fillMaxHeight())
                ButtonDivider()
                ClickButton("RIGHT", "MOUSE_RIGHT_CLICK", send, Modifier.weight(2f).fillMaxHeight())
            }
        }
        Spacer(Modifier.height(14.dp))
        WireButton(
            label = "BACK",
            onClick = { send("BACK") },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(25.dp),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ButtonDivider() {
    val theme = LocalRemoteTheme.current
    Box(
        Modifier
            .width(WireBorder)
            .fillMaxHeight()
            .background(theme.dim)
    )
}

@Composable
private fun ClickButton(label: String, command: String, send: (String) -> Unit, modifier: Modifier) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .background(if (pressed) theme.fillPressed else theme.accent.copy(alpha = 0.04f))
            .clickable(interactionSource = interaction, indication = null) {
                haptics.heavy()
                send(command)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) theme.label else theme.dim,
            fontSize = if (label.length == 1) 20.sp else 12.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One finger: move the pointer (throttled MOUSE_MOVE deltas). Quick tap: left click.
 * Two fingers: drag to scroll, quick tap to right click.
 */
@Composable
private fun TrackpadSurface(
    send: (String) -> Unit,
    speed: Float,
    acceleration: Boolean,
    scrollStep: Dp,
    scrollUp: String,
    scrollDown: String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val sendNow by rememberUpdatedState(send)
    val speedNow by rememberUpdatedState(speed)
    val accelNow by rememberUpdatedState(acceleration)
    val stepNow by rememberUpdatedState(scrollStep)
    val upNow by rememberUpdatedState(scrollUp)
    val downNow by rememberUpdatedState(scrollDown)
    var touches by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Box(
        modifier
            .background(theme.accent.copy(alpha = 0.09f))
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val scrollStepPx = stepNow.toPx()
                    val down = awaitFirstDown()
                    down.consume()
                    touches = listOf(down.position)
                    val downTime = down.uptimeMillis
                    var pendingX = 0f
                    var pendingY = 0f
                    var travelled = 0f
                    var moving = false
                    var scrolled = false
                    var scrollAcc = 0f
                    var maxFingers = 1
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
                        val pressed = event.changes.filter { it.pressed }
                        maxFingers = max(maxFingers, pressed.size)

                        if (pressed.isEmpty()) {
                            val upTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: downTime
                            if (!moving && !scrolled && upTime - downTime <= TAP_MAX_MS) {
                                haptics.tap()
                                sendNow(if (maxFingers >= 2) "MOUSE_RIGHT_CLICK" else "MOUSE_CLICK")
                            } else {
                                flush()
                            }
                            event.changes.forEach { it.consume() }
                            break
                        }

                        touches = pressed.map { it.position }

                        if (pressed.size >= 2) {
                            // Two-finger scroll (finger up = scroll up, like the scroll edge).
                            val dy = pressed.map { it.positionChange().y }.average().toFloat()
                            pressed.forEach { it.consume() }
                            travelled += abs(dy)
                            if (travelled > slop) scrolled = true
                            if (scrolled) {
                                scrollAcc += dy
                                while (scrollAcc <= -scrollStepPx) {
                                    scrollAcc += scrollStepPx
                                    sendNow(upNow)
                                    haptics.tick()
                                }
                                while (scrollAcc >= scrollStepPx) {
                                    scrollAcc -= scrollStepPx
                                    sendNow(downNow)
                                    haptics.tick()
                                }
                            }
                            continue
                        }

                        val change = pressed.first()
                        val delta = change.positionChange()
                        change.consume()
                        if (maxFingers >= 2) continue // finishing a two-finger gesture
                        travelled += delta.getDistance()
                        if (!moving && travelled > slop) moving = true
                        if (moving) {
                            val dt = max(1L, change.uptimeMillis - change.previousUptimeMillis)
                            val velocity = delta.getDistance() / dt // px per ms
                            val accel = if (accelNow) 1f + min(velocity * 0.45f, 2.2f) else 1f
                            pendingX += delta.x * speedNow * accel
                            pendingY += delta.y * speedNow * accel
                            if (change.uptimeMillis - lastSent >= SEND_INTERVAL_MS) {
                                flush()
                                lastSent = change.uptimeMillis
                            }
                        }
                    }
                    touches = emptyList()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 26.dp.toPx()
            touches.forEach { p ->
                drawCircle(color = theme.accent.copy(alpha = 0.16f), radius = r, center = p)
                drawCircle(
                    color = theme.border,
                    radius = r,
                    center = p,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Text(
            "TAP · CLICK    2 FINGERS · SCROLL",
            color = theme.faint,
            fontSize = 10.sp,
            fontFamily = Mono,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        )
    }
}

/** Scroll speed 1..5 → distance of finger travel per scroll step. */
fun scrollStepFor(speed: Int): Dp = when (speed) {
    1 -> 30.dp
    2 -> 24.dp
    3 -> 18.dp
    4 -> 13.dp
    else -> 9.dp
}
