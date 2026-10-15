package com.godotdroid.overlay.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.godotdroid.overlay.input.ITouchInputDispatcher
import com.godotdroid.overlay.model.InputBinding
import com.godotdroid.overlay.model.VirtualControlElement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun VirtualJoystick(
    element: VirtualControlElement,
    dispatcher: ITouchInputDispatcher?,
    isEditMode: Boolean,
    modifier: Modifier = Modifier
) {
    val baseRadiusDp = (60 * element.scale).dp
    val thumbRadiusDp = (24 * element.scale).dp

    var thumbOffset by remember { mutableStateOf(Offset.Zero) }

    // Track state for WASD key toggling
    var wPressed by remember { mutableStateOf(false) }
    var aPressed by remember { mutableStateOf(false) }
    var sPressed by remember { mutableStateOf(false) }
    var dPressed by remember { mutableStateOf(false) }

    val isWasdMode = element.binding is InputBinding.KeyBinding

    Box(
        modifier = modifier
            .size(baseRadiusDp * 2)
            .pointerInput(isEditMode) {
                if (isEditMode) return@pointerInput

                val maxDist = size.width / 2f
                detectDragGestures(
                    onDragStart = { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = offset - center
                        val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
                        val clampedDist = min(dist, maxDist)
                        val angle = atan2(delta.y, delta.x)
                        thumbOffset = Offset(clampedDist * cos(angle), clampedDist * sin(angle))

                        val normX = (thumbOffset.x / maxDist).coerceIn(-1f, 1f)
                        val normY = (thumbOffset.y / maxDist).coerceIn(-1f, 1f)

                        if (isWasdMode) {
                            val newW = normY < -0.35f
                            val newS = normY > 0.35f
                            val newA = normX < -0.35f
                            val newD = normX > 0.35f

                            if (newW != wPressed) { wPressed = newW; dispatcher?.sendKey(KeyEvent.KEYCODE_W, 87, newW) }
                            if (newS != sPressed) { sPressed = newS; dispatcher?.sendKey(KeyEvent.KEYCODE_S, 83, newS) }
                            if (newA != aPressed) { aPressed = newA; dispatcher?.sendKey(KeyEvent.KEYCODE_A, 65, newA) }
                            if (newD != dPressed) { dPressed = newD; dispatcher?.sendKey(KeyEvent.KEYCODE_D, 68, newD) }
                        } else {
                            dispatcher?.sendJoyAxis(0, normX, normY)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val target = thumbOffset + dragAmount
                        val dist = sqrt(target.x * target.x + target.y * target.y)
                        val clampedDist = min(dist, maxDist)
                        val angle = atan2(target.y, target.x)
                        thumbOffset = Offset(clampedDist * cos(angle), clampedDist * sin(angle))

                        val normX = (thumbOffset.x / maxDist).coerceIn(-1f, 1f)
                        val normY = (thumbOffset.y / maxDist).coerceIn(-1f, 1f)

                        if (isWasdMode) {
                            val newW = normY < -0.35f
                            val newS = normY > 0.35f
                            val newA = normX < -0.35f
                            val newD = normX > 0.35f

                            if (newW != wPressed) { wPressed = newW; dispatcher?.sendKey(KeyEvent.KEYCODE_W, 87, newW) }
                            if (newS != sPressed) { sPressed = newS; dispatcher?.sendKey(KeyEvent.KEYCODE_S, 83, newS) }
                            if (newA != aPressed) { aPressed = newA; dispatcher?.sendKey(KeyEvent.KEYCODE_A, 65, newA) }
                            if (newD != dPressed) { dPressed = newD; dispatcher?.sendKey(KeyEvent.KEYCODE_D, 68, newD) }
                        } else {
                            dispatcher?.sendJoyAxis(0, normX, normY)
                        }
                    },
                    onDragEnd = {
                        thumbOffset = Offset.Zero
                        if (isWasdMode) {
                            if (wPressed) { wPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_W, 87, false) }
                            if (sPressed) { sPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_S, 83, false) }
                            if (aPressed) { aPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_A, 65, false) }
                            if (dPressed) { dPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_D, 68, false) }
                        } else {
                            dispatcher?.sendJoyAxis(0, 0f, 0f)
                        }
                    },
                    onDragCancel = {
                        thumbOffset = Offset.Zero
                        if (isWasdMode) {
                            if (wPressed) { wPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_W, 87, false) }
                            if (sPressed) { sPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_S, 83, false) }
                            if (aPressed) { aPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_A, 65, false) }
                            if (dPressed) { dPressed = false; dispatcher?.sendKey(KeyEvent.KEYCODE_D, 68, false) }
                        } else {
                            dispatcher?.sendJoyAxis(0, 0f, 0f)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.width / 2f
            val thumbRadius = (24 * element.scale).dp.toPx()

            // Outer Base Ring
            drawCircle(
                color = Color.White.copy(alpha = 0.15f * element.opacity),
                radius = baseRadius,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.40f * element.opacity),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Inner Thumb Knob
            val thumbPos = center + thumbOffset
            drawCircle(
                color = Color(0xFF478CBF).copy(alpha = 0.70f * element.opacity),
                radius = thumbRadius,
                center = thumbPos
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f * element.opacity),
                radius = thumbRadius,
                center = thumbPos,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
