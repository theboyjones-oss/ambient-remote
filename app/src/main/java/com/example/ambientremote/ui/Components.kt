package com.example.ambientremote.ui

import android.view.HapticFeedbackConstants
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Haptic level from settings: 0 = off, 1 = light (system feedback), 2 = strong (vibrator). */
val LocalHapticLevel = compositionLocalOf { 1 }

class Haptics(private val view: View, private val level: Int) {
    private val vibrator: Vibrator? by lazy {
        val ctx = view.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
    }

    private fun strong(effect: Int, fallbackMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            v.vibrate(VibrationEffect.createOneShot(fallbackMs, amplitude))
        }
    }

    fun tap() {
        when (level) {
            1 -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            2 -> strong(VibrationEffect.EFFECT_CLICK, 22, 220)
        }
    }

    fun tick() {
        when (level) {
            1 -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            2 -> strong(VibrationEffect.EFFECT_TICK, 12, 160)
        }
    }

    fun heavy() {
        when (level) {
            1 -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            2 -> strong(VibrationEffect.EFFECT_HEAVY_CLICK, 40, 255)
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val level = LocalHapticLevel.current
    return remember(view, level) { Haptics(view, level) }
}

/** Generic OLED wireframe button: dark tinted fill, crisp border, bright label. */
@Composable
fun WireButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    selected: Boolean = false,
    dimLabel: Boolean = false,
    fontSize: TextUnit = 13.sp,
    horizontalPadding: Dp = 0.dp,
) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit = pressed || selected
    Box(
        modifier
            .clip(shape)
            .background(if (lit) theme.fillPressed else theme.fill)
            .border(WireBorder, if (dimLabel && !lit) theme.dim else theme.border, shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (dimLabel && !lit) theme.dim else theme.label,
            fontSize = fontSize,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    }
}

@Composable
fun CircleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    WireButton(
        label = label,
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        fontSize = when {
            label.length >= 7 -> 9.sp
            label.length >= 5 -> 10.sp
            label.length >= 3 -> 12.sp
            else -> 20.sp
        },
    )
}

/** The row of 4 circular wireframe buttons at the bottom of each tab. Pairs are label to command. */
@Composable
fun BottomButtonRow(buttons: List<Pair<String, String>>, send: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEach { (label, command) ->
            CircleButton(label = label, onClick = { send(command) })
        }
    }
}

/** Key that fires once on press, then repeats while held (used for Backspace). */
@Composable
fun RepeatKey(label: String, onRepeat: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onRepeat)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) theme.fillPressed else theme.fill)
            .border(WireBorder, theme.border, shape)
            .pointerInput(Unit) {
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = theme.label, fontSize = 18.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

/**
 * Vertical drag strip with moving tick marks. Every [stepDp] of travel fires
 * [upCommand] (drag up) or [downCommand] (drag down). Optional +/- tap buttons.
 */
@Composable
fun DragStrip(
    label: String,
    upCommand: String,
    downCommand: String,
    send: (String) -> Unit,
    modifier: Modifier = Modifier,
    showTapButtons: Boolean = false,
    stepDp: Dp = 22.dp,
    framed: Boolean = true,
) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val sendNow by rememberUpdatedState(send)
    var phase by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Column(
        if (framed) {
            modifier
                .clip(shape)
                .background(theme.fill)
                .border(WireBorder, theme.border, shape)
        } else {
            modifier
        }
    ) {
        if (showTapButtons) {
            StripTapButton("+") { sendNow(upCommand) }
            Box(Modifier.fillMaxWidth().height(WireBorder).background(theme.dim))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(stepDp, upCommand, downCommand) {
                    val step = stepDp.toPx()
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f; dragging = true },
                        onDragEnd = { acc = 0f; dragging = false },
                        onDragCancel = { acc = 0f; dragging = false },
                    ) { change, dy ->
                        change.consume()
                        acc += dy
                        phase += dy
                        while (acc <= -step) {
                            acc += step
                            sendNow(upCommand)
                            haptics.tick()
                        }
                        while (acc >= step) {
                            acc -= step
                            sendNow(downCommand)
                            haptics.tick()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val spacing = 14.dp.toPx()
                val stroke = 1.5.dp.toPx()
                val shift = ((phase % spacing) + spacing) % spacing
                var y = shift - spacing
                var i = 0
                while (y < size.height + spacing) {
                    val major = i % 4 == 0
                    val half = size.width * (if (major) 0.28f else 0.16f)
                    drawLine(
                        color = if (dragging) theme.dim else theme.faint,
                        start = Offset(size.width / 2 - half, y),
                        end = Offset(size.width / 2 + half, y),
                        strokeWidth = stroke,
                    )
                    y += spacing
                    i++
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(OledBlack)
                    .border(WireBorder, theme.dim, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(label, color = theme.label, fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
        }
        if (showTapButtons) {
            Box(Modifier.fillMaxWidth().height(WireBorder).background(theme.dim))
            StripTapButton("−") { sendNow(downCommand) }
        }
    }
}

@Composable
private fun StripTapButton(label: String, onClick: () -> Unit) {
    val theme = LocalRemoteTheme.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(if (pressed) theme.fillPressed else OledBlack.copy(alpha = 0f))
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = theme.label, fontSize = 24.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}
