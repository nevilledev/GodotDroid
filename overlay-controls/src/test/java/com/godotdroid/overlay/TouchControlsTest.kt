package com.godotdroid.overlay

import com.godotdroid.overlay.model.ControlElementType
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.overlay.model.InputBinding
import com.godotdroid.overlay.model.MouseEmulationMode
import com.godotdroid.overlay.model.TouchControlLayout
import com.godotdroid.overlay.model.VirtualControlElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchControlsTest {

    @Test
    fun testKeyBindingJsonRoundTrip() {
        val original = InputBinding.KeyBinding(androidKeyCode = 62, godotKeyCode = 32, label = "SPACE")
        val json = original.toJson()
        val restored = InputBinding.fromJson(json) as InputBinding.KeyBinding

        assertEquals(62, restored.androidKeyCode)
        assertEquals(32, restored.godotKeyCode)
        assertEquals("SPACE", restored.label)
    }

    @Test
    fun testGamepadButtonBindingJsonRoundTrip() {
        val original = InputBinding.GamepadButtonBinding(buttonIndex = 0, androidKeyCode = 96, label = "A")
        val json = original.toJson()
        val restored = InputBinding.fromJson(json) as InputBinding.GamepadButtonBinding

        assertEquals(0, restored.buttonIndex)
        assertEquals(96, restored.androidKeyCode)
        assertEquals("A", restored.label)
    }

    @Test
    fun testMouseButtonBindingJsonRoundTrip() {
        val original = InputBinding.MouseButtonBinding(mouseButton = 2, label = "R-CLICK")
        val json = original.toJson()
        val restored = InputBinding.fromJson(json) as InputBinding.MouseButtonBinding

        assertEquals(2, restored.mouseButton)
        assertEquals("R-CLICK", restored.label)
    }

    @Test
    fun testVirtualControlElementJsonRoundTrip() {
        val element = VirtualControlElement(
            id = "btn_jump",
            label = "JUMP",
            type = ControlElementType.BUTTON,
            normX = 0.85f,
            normY = 0.75f,
            scale = 1.2f,
            opacity = 0.8f,
            visible = true,
            binding = InputBinding.KeyBinding(62, 32, "SPACE")
        )

        val json = element.toJson()
        val restored = VirtualControlElement.fromJson(json)

        assertEquals("btn_jump", restored.id)
        assertEquals("JUMP", restored.label)
        assertEquals(ControlElementType.BUTTON, restored.type)
        assertEquals(0.85f, restored.normX, 0.001f)
        assertEquals(0.75f, restored.normY, 0.001f)
        assertEquals(1.2f, restored.scale, 0.001f)
        assertEquals(0.8f, restored.opacity, 0.001f)
        assertTrue(restored.visible)
        assertTrue(restored.binding is InputBinding.KeyBinding)
    }

    @Test
    fun testTouchControlLayoutJsonRoundTrip() {
        val layout = DefaultControlPresets.createPcKeyboardPreset("test_game_123")
        val json = layout.toJson()
        val restored = TouchControlLayout.fromJson(json)

        assertEquals("test_game_123", restored.gameId)
        assertEquals(DefaultControlPresets.PRESET_PC_KEYBOARD, restored.presetName)
        assertEquals(MouseEmulationMode.TRACKPAD_RELATIVE, restored.mouseMode)
        assertEquals(layout.elements.size, restored.elements.size)

        val wasdStick = restored.elements.find { it.id == "stick_wasd" }
        assertNotNull(wasdStick)
        assertEquals(ControlElementType.JOYSTICK_LEFT, wasdStick?.type)

        val lClick = restored.elements.find { it.id == "btn_mouse_left" }
        assertNotNull(lClick)
        assertEquals(ControlElementType.MOUSE_BUTTON, lClick?.type)
    }

    @Test
    fun testDefaultControlPresetsCompleteness() {
        // Preset 1: PC Keyboard
        val pcLayout = DefaultControlPresets.createPreset(DefaultControlPresets.PRESET_PC_KEYBOARD, "game_pc")
        assertEquals(MouseEmulationMode.TRACKPAD_RELATIVE, pcLayout.mouseMode)
        assertTrue(pcLayout.elements.any { it.label == "WASD" })
        assertTrue(pcLayout.elements.any { it.label == "L-CLICK" })
        assertTrue(pcLayout.elements.any { it.label == "R-CLICK" })

        // Preset 2: Gamepad
        val padLayout = DefaultControlPresets.createPreset(DefaultControlPresets.PRESET_GAMEPAD, "game_pad")
        assertEquals(MouseEmulationMode.DIRECT_TOUCH, padLayout.mouseMode)
        assertTrue(padLayout.elements.any { it.label == "A" })
        assertTrue(padLayout.elements.any { it.label == "B" })
        assertTrue(padLayout.elements.any { it.label == "X" })
        assertTrue(padLayout.elements.any { it.label == "Y" })
        assertTrue(padLayout.elements.any { it.label == "D-PAD" })
        assertTrue(padLayout.elements.any { it.label == "L-STICK" })

        // Preset 3: Point and Click
        val pacLayout = DefaultControlPresets.createPreset(DefaultControlPresets.PRESET_POINT_AND_CLICK, "game_pac")
        assertEquals(MouseEmulationMode.TRACKPAD_RELATIVE, pacLayout.mouseMode)
        assertTrue(pacLayout.elements.any { it.label == "L-CLICK" })
        assertTrue(pacLayout.elements.any { it.label == "R-CLICK" })
        assertTrue(pacLayout.elements.any { it.label == "ESC" })
    }
}
