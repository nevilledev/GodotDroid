package com.godotdroid.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.overlay.input.ITouchInputDispatcher
import com.godotdroid.overlay.model.InputBinding
import com.godotdroid.overlay.model.VirtualControlElement

@Composable
fun VirtualButtonView(
    element: VirtualControlElement,
    dispatcher: ITouchInputDispatcher?,
    isEditMode: Boolean,
    modifier: Modifier = Modifier
) {
    val sizeDp = (54 * element.scale).dp
    var isPressed by remember { mutableStateOf(false) }

    fun sendPress(pressed: Boolean) {
        isPressed = pressed
        when (val binding = element.binding) {
            is InputBinding.KeyBinding -> {
                dispatcher?.sendKey(binding.androidKeyCode, binding.godotKeyCode, pressed)
            }
            is InputBinding.GamepadButtonBinding -> {
                dispatcher?.sendJoyButton(binding.buttonIndex, pressed)
            }
            is InputBinding.MouseButtonBinding -> {
                dispatcher?.sendMouseButton(binding.mouseButton, pressed)
            }
        }
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(isEditMode) {
                if (isEditMode) return@pointerInput
                detectTapGestures(
                    onPress = {
                        sendPress(true)
                        tryAwaitRelease()
                        sendPress(false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = size.width / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            val fillColor = if (isPressed) {
                Color(0xFF478CBF).copy(alpha = 0.85f * element.opacity)
            } else {
                Color.Black.copy(alpha = 0.40f * element.opacity)
            }

            val strokeColor = if (isPressed) {
                Color.White.copy(alpha = 0.95f * element.opacity)
            } else {
                Color.White.copy(alpha = 0.45f * element.opacity)
            }

            drawCircle(
                color = fillColor,
                radius = radius,
                center = center
            )
            drawCircle(
                color = strokeColor,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Text(
            text = element.label,
            color = Color.White.copy(alpha = element.opacity.coerceAtLeast(0.7f)),
            fontSize = (13 * element.scale).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
