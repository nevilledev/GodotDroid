package com.godotdroid.overlay.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.godotdroid.overlay.input.ITouchInputDispatcher
import com.godotdroid.overlay.model.VirtualControlElement
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
fun VirtualDpad(
    element: VirtualControlElement,
    dispatcher: ITouchInputDispatcher?,
    isEditMode: Boolean,
    modifier: Modifier = Modifier
) {
    val sizeDp = (110 * element.scale).dp

    var upPressed by remember { mutableStateOf(false) }
    var downPressed by remember { mutableStateOf(false) }
    var leftPressed by remember { mutableStateOf(false) }
    var rightPressed by remember { mutableStateOf(false) }

    fun updateDirection(normX: Float, normY: Float, isTouching: Boolean) {
        val newUp = isTouching && normY < -0.3f
        val newDown = isTouching && normY > 0.3f
        val newLeft = isTouching && normX < -0.3f
        val newRight = isTouching && normX > 0.3f

        if (newUp != upPressed) {
            upPressed = newUp
            dispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_UP, 4194320, newUp)
        }
        if (newDown != downPressed) {
            downPressed = newDown
            dispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_DOWN, 4194322, newDown)
        }
        if (newLeft != leftPressed) {
            leftPressed = newLeft
            dispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_LEFT, 4194319, newLeft)
        }
        if (newRight != rightPressed) {
            rightPressed = newRight
            dispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, 4194321, newRight)
        }
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(isEditMode) {
                if (isEditMode) return@pointerInput

                detectTapGestures(
                    onPress = { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = offset - center
                        val radius = size.width / 2f
                        updateDirection(delta.x / radius, delta.y / radius, true)
                        tryAwaitRelease()
                        updateDirection(0f, 0f, false)
                    }
                )
            }
            .pointerInput(isEditMode) {
                if (isEditMode) return@pointerInput

                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = change.position - center
                        val radius = size.width / 2f
                        updateDirection(delta.x / radius, delta.y / radius, true)
                    },
                    onDragEnd = { updateDirection(0f, 0f, false) },
                    onDragCancel = { updateDirection(0f, 0f, false) }
                )
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val armW = w / 3f
            val armH = h / 3f

            val stroke = Stroke(width = 2.dp.toPx())
            val baseColor = Color.Black.copy(alpha = 0.35f * element.opacity)
            val strokeColor = Color.White.copy(alpha = 0.40f * element.opacity)
            val activeColor = Color(0xFF478CBF).copy(alpha = 0.80f * element.opacity)

            // Horizontal Bar
            drawRect(
                color = if (leftPressed || rightPressed) activeColor else baseColor,
                topLeft = Offset(0f, armH),
                size = Size(w, armH)
            )
            drawRect(
                color = strokeColor,
                topLeft = Offset(0f, armH),
                size = Size(w, armH),
                style = stroke
            )

            // Vertical Bar
            drawRect(
                color = if (upPressed || downPressed) activeColor else baseColor,
                topLeft = Offset(armW, 0f),
                size = Size(armW, h)
            )
            drawRect(
                color = strokeColor,
                topLeft = Offset(armW, 0f),
                size = Size(armW, h),
                style = stroke
            )
        }
    }
}
