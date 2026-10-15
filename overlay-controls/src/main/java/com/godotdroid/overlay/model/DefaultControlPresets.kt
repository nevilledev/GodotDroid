package com.godotdroid.overlay.model

import android.view.KeyEvent

object DefaultControlPresets {

    const val PRESET_PC_KEYBOARD = "PC Keyboard (WASD + Mouse)"
    const val PRESET_GAMEPAD = "Standard Gamepad (Xbox/PlayStation)"
    const val PRESET_POINT_AND_CLICK = "Point & Click (Trackpad)"

    fun createPreset(name: String, gameId: String): TouchControlLayout {
        return when (name) {
            PRESET_GAMEPAD -> createGamepadPreset(gameId)
            PRESET_POINT_AND_CLICK -> createPointAndClickPreset(gameId)
            else -> createPcKeyboardPreset(gameId)
        }
    }

    /**
     * Preset 1: PC Keyboard & Mouse (Default for PC Godot games).
     * Many PC games don't support controller input and rely on WASD + Space/Shift + Mouse.
     */
    fun createPcKeyboardPreset(gameId: String): TouchControlLayout {
        val elements = listOf(
            VirtualControlElement(
                id = "stick_wasd",
                label = "WASD",
                type = ControlElementType.JOYSTICK_LEFT,
                normX = 0.15f,
                normY = 0.70f,
                scale = 1.1f,
                opacity = 0.70f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_W, 87, "WASD")
            ),
            VirtualControlElement(
                id = "btn_space",
                label = "SPACE",
                type = ControlElementType.BUTTON,
                normX = 0.85f,
                normY = 0.78f,
                scale = 1.15f,
                opacity = 0.75f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_SPACE, 32, "SPACE")
            ),
            VirtualControlElement(
                id = "btn_shift",
                label = "SHIFT",
                type = ControlElementType.BUTTON,
                normX = 0.74f,
                normY = 0.85f,
                scale = 0.95f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_SHIFT_LEFT, 4194325, "SHIFT")
            ),
            VirtualControlElement(
                id = "btn_e",
                label = "E",
                type = ControlElementType.BUTTON,
                normX = 0.76f,
                normY = 0.68f,
                scale = 0.95f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_E, 69, "E")
            ),
            VirtualControlElement(
                id = "btn_esc",
                label = "ESC",
                type = ControlElementType.BUTTON,
                normX = 0.08f,
                normY = 0.12f,
                scale = 0.85f,
                opacity = 0.55f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_ESCAPE, 4194305, "ESC")
            ),
            VirtualControlElement(
                id = "btn_mouse_left",
                label = "L-CLICK",
                type = ControlElementType.MOUSE_BUTTON,
                normX = 0.88f,
                normY = 0.58f,
                scale = 1.05f,
                opacity = 0.75f,
                visible = true,
                binding = InputBinding.MouseButtonBinding(1, "L-CLICK")
            ),
            VirtualControlElement(
                id = "btn_mouse_right",
                label = "R-CLICK",
                type = ControlElementType.MOUSE_BUTTON,
                normX = 0.90f,
                normY = 0.42f,
                scale = 0.95f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.MouseButtonBinding(2, "R-CLICK")
            )
        )

        return TouchControlLayout(
            gameId = gameId,
            presetName = PRESET_PC_KEYBOARD,
            mouseMode = MouseEmulationMode.TRACKPAD_RELATIVE,
            trackpadSensitivity = 1.0f,
            elements = elements
        )
    }

    /**
     * Preset 2: Standard Gamepad (Left Stick + D-Pad + A/B/X/Y + L1/R1/Start/Select).
     */
    fun createGamepadPreset(gameId: String): TouchControlLayout {
        val elements = listOf(
            VirtualControlElement(
                id = "joy_left",
                label = "L-STICK",
                type = ControlElementType.JOYSTICK_LEFT,
                normX = 0.15f,
                normY = 0.72f,
                scale = 1.15f,
                opacity = 0.70f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(0, KeyEvent.KEYCODE_BUTTON_A, "L-STICK")
            ),
            VirtualControlElement(
                id = "dpad",
                label = "D-PAD",
                type = ControlElementType.DPAD,
                normX = 0.15f,
                normY = 0.35f,
                scale = 1.0f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(0, KeyEvent.KEYCODE_DPAD_CENTER, "D-PAD")
            ),
            VirtualControlElement(
                id = "btn_a",
                label = "A",
                type = ControlElementType.BUTTON,
                normX = 0.86f,
                normY = 0.80f,
                scale = 1.05f,
                opacity = 0.80f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(0, KeyEvent.KEYCODE_BUTTON_A, "A")
            ),
            VirtualControlElement(
                id = "btn_b",
                label = "B",
                type = ControlElementType.BUTTON,
                normX = 0.93f,
                normY = 0.70f,
                scale = 1.05f,
                opacity = 0.80f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(1, KeyEvent.KEYCODE_BUTTON_B, "B")
            ),
            VirtualControlElement(
                id = "btn_x",
                label = "X",
                type = ControlElementType.BUTTON,
                normX = 0.79f,
                normY = 0.70f,
                scale = 1.05f,
                opacity = 0.80f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(2, KeyEvent.KEYCODE_BUTTON_X, "X")
            ),
            VirtualControlElement(
                id = "btn_y",
                label = "Y",
                type = ControlElementType.BUTTON,
                normX = 0.86f,
                normY = 0.60f,
                scale = 1.05f,
                opacity = 0.80f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(3, KeyEvent.KEYCODE_BUTTON_Y, "Y")
            ),
            VirtualControlElement(
                id = "btn_l1",
                label = "L1",
                type = ControlElementType.BUTTON,
                normX = 0.15f,
                normY = 0.12f,
                scale = 0.95f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(9, KeyEvent.KEYCODE_BUTTON_L1, "L1")
            ),
            VirtualControlElement(
                id = "btn_r1",
                label = "R1",
                type = ControlElementType.BUTTON,
                normX = 0.85f,
                normY = 0.12f,
                scale = 0.95f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(10, KeyEvent.KEYCODE_BUTTON_R1, "R1")
            ),
            VirtualControlElement(
                id = "btn_start",
                label = "START",
                type = ControlElementType.BUTTON,
                normX = 0.56f,
                normY = 0.90f,
                scale = 0.85f,
                opacity = 0.60f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(6, KeyEvent.KEYCODE_BUTTON_START, "START")
            ),
            VirtualControlElement(
                id = "btn_select",
                label = "SELECT",
                type = ControlElementType.BUTTON,
                normX = 0.44f,
                normY = 0.90f,
                scale = 0.85f,
                opacity = 0.60f,
                visible = true,
                binding = InputBinding.GamepadButtonBinding(4, KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT")
            )
        )

        return TouchControlLayout(
            gameId = gameId,
            presetName = PRESET_GAMEPAD,
            mouseMode = MouseEmulationMode.DIRECT_TOUCH,
            trackpadSensitivity = 1.0f,
            elements = elements
        )
    }

    /**
     * Preset 3: Point & Click / Strategy (Relative Trackpad + Left/Right Click + Esc/Space).
     */
    fun createPointAndClickPreset(gameId: String): TouchControlLayout {
        val elements = listOf(
            VirtualControlElement(
                id = "btn_click_left",
                label = "L-CLICK",
                type = ControlElementType.MOUSE_BUTTON,
                normX = 0.88f,
                normY = 0.75f,
                scale = 1.2f,
                opacity = 0.80f,
                visible = true,
                binding = InputBinding.MouseButtonBinding(1, "L-CLICK")
            ),
            VirtualControlElement(
                id = "btn_click_right",
                label = "R-CLICK",
                type = ControlElementType.MOUSE_BUTTON,
                normX = 0.88f,
                normY = 0.55f,
                scale = 1.0f,
                opacity = 0.70f,
                visible = true,
                binding = InputBinding.MouseButtonBinding(2, "R-CLICK")
            ),
            VirtualControlElement(
                id = "btn_esc",
                label = "ESC",
                type = ControlElementType.BUTTON,
                normX = 0.08f,
                normY = 0.12f,
                scale = 0.85f,
                opacity = 0.60f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_ESCAPE, 4194305, "ESC")
            ),
            VirtualControlElement(
                id = "btn_space",
                label = "SPACE",
                type = ControlElementType.BUTTON,
                normX = 0.12f,
                normY = 0.80f,
                scale = 1.0f,
                opacity = 0.65f,
                visible = true,
                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_SPACE, 32, "SPACE")
            )
        )

        return TouchControlLayout(
            gameId = gameId,
            presetName = PRESET_POINT_AND_CLICK,
            mouseMode = MouseEmulationMode.TRACKPAD_RELATIVE,
            trackpadSensitivity = 1.1f,
            elements = elements
        )
    }
}
