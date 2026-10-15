package com.godotdroid.overlay.input

/**
 * Interface for dispatching interactive touch overlay events into the Godot engine.
 */
interface ITouchInputDispatcher {
    /**
     * Sends a Keyboard key event (pressed or released).
     */
    fun sendKey(androidKeyCode: Int, godotKeyCode: Int, isPressed: Boolean)

    /**
     * Sends a Gamepad button event (pressed or released).
     */
    fun sendJoyButton(buttonIndex: Int, isPressed: Boolean)

    /**
     * Sends a continuous Joystick Axis event (-1.0f..1.0f).
     */
    fun sendJoyAxis(stickIndex: Int, axisX: Float, axisY: Float)

    /**
     * Sends relative or absolute mouse cursor movement.
     */
    fun sendMouseMotion(deltaX: Float, deltaY: Float, currentNormX: Float, currentNormY: Float)

    /**
     * Sends a Mouse button event (1 = Left, 2 = Right, 3 = Middle).
     */
    fun sendMouseButton(mouseButton: Int, isPressed: Boolean)
}
