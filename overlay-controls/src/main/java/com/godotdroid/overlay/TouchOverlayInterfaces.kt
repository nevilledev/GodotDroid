package com.godotdroid.overlay

/**
 * Virtual button identifiers for touch control overlay.
 */
enum class VirtualButton {
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    ACTION_A, ACTION_B, ACTION_X, ACTION_Y,
    TRIGGER_L1, TRIGGER_R1, TRIGGER_L2, TRIGGER_R2,
    THUMB_L3, THUMB_R3,
    START, SELECT
}

/**
 * Touch input events dispatched to the Godot engine input pipeline.
 */
sealed class TouchInputEvent {
    data class ButtonPress(val button: VirtualButton, val isPressed: Boolean) : TouchInputEvent()
    data class JoystickMove(val stickIndex: Int, val xAxis: Float, val yAxis: Float) : TouchInputEvent()
    data class MouseMove(val x: Float, val y: Float, val relativeX: Float, val relativeY: Float) : TouchInputEvent()
    data class MouseClick(val buttonIndex: Int, val isPressed: Boolean) : TouchInputEvent()
}

/**
 * Layout and appearance settings for the touch overlay.
 */
data class TouchControlLayout(
    val showDpad: Boolean = true,
    val showLeftStick: Boolean = true,
    val showRightStick: Boolean = true,
    val showActionButtons: Boolean = true,
    val showTriggers: Boolean = true,
    val opacity: Float = 0.65f,
    val buttonScale: Float = 1.0f,
    val vibrationFeedbackEnabled: Boolean = true
)

/**
 * Interface for touch control overlay management.
 */
interface ITouchOverlayManager {
    val currentLayout: TouchControlLayout

    fun updateLayout(newLayout: TouchControlLayout)
    fun setVisible(visible: Boolean)
    fun setInputListener(listener: (TouchInputEvent) -> Unit)
}
