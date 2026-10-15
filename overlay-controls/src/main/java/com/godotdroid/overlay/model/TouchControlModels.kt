package com.godotdroid.overlay.model

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

enum class ControlElementType {
    BUTTON,
    JOYSTICK_LEFT,
    JOYSTICK_RIGHT,
    DPAD,
    MOUSE_BUTTON
}

enum class MouseEmulationMode {
    DIRECT_TOUCH,
    TRACKPAD_RELATIVE,
    DISABLED
}

/**
 * Polymorphic input binding for mapping virtual controls to Keyboard, Gamepad, or Mouse.
 */
sealed class InputBinding {
    abstract val label: String
    abstract fun toJson(): JSONObject

    data class KeyBinding(
        val androidKeyCode: Int,
        val godotKeyCode: Int,
        override val label: String
    ) : InputBinding() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "KEY")
            put("androidKeyCode", androidKeyCode)
            put("godotKeyCode", godotKeyCode)
            put("label", label)
        }
    }

    data class GamepadButtonBinding(
        val buttonIndex: Int,
        val androidKeyCode: Int,
        override val label: String
    ) : InputBinding() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "GAMEPAD_BUTTON")
            put("buttonIndex", buttonIndex)
            put("androidKeyCode", androidKeyCode)
            put("label", label)
        }
    }

    data class MouseButtonBinding(
        val mouseButton: Int, // 1 = Left, 2 = Right, 3 = Middle
        override val label: String
    ) : InputBinding() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "MOUSE_BUTTON")
            put("mouseButton", mouseButton)
            put("label", label)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): InputBinding {
            return when (json.optString("type")) {
                "KEY" -> KeyBinding(
                    androidKeyCode = json.optInt("androidKeyCode", KeyEvent.KEYCODE_SPACE),
                    godotKeyCode = json.optInt("godotKeyCode", 32),
                    label = json.optString("label", "SPACE")
                )
                "GAMEPAD_BUTTON" -> GamepadButtonBinding(
                    buttonIndex = json.optInt("buttonIndex", 0),
                    androidKeyCode = json.optInt("androidKeyCode", KeyEvent.KEYCODE_BUTTON_A),
                    label = json.optString("label", "A")
                )
                "MOUSE_BUTTON" -> MouseButtonBinding(
                    mouseButton = json.optInt("mouseButton", 1),
                    label = json.optString("label", "L-CLICK")
                )
                else -> KeyBinding(KeyEvent.KEYCODE_SPACE, 32, "SPACE")
            }
        }
    }
}

/**
 * Interactive virtual control element positioned on the screen with normalized coordinates.
 */
data class VirtualControlElement(
    val id: String,
    val label: String,
    val type: ControlElementType,
    val normX: Float, // 0.0f..1.0f relative to screen width
    val normY: Float, // 0.0f..1.0f relative to screen height
    val scale: Float = 1.0f, // 0.5f..2.0f
    val opacity: Float = 0.65f, // 0.15f..1.0f
    val visible: Boolean = true,
    val binding: InputBinding
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("type", type.name)
        put("normX", normX.toDouble())
        put("normY", normY.toDouble())
        put("scale", scale.toDouble())
        put("opacity", opacity.toDouble())
        put("visible", visible)
        put("binding", binding.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): VirtualControlElement {
            val type = try {
                ControlElementType.valueOf(json.optString("type", ControlElementType.BUTTON.name))
            } catch (_: Exception) {
                ControlElementType.BUTTON
            }

            return VirtualControlElement(
                id = json.getString("id"),
                label = json.optString("label", "BTN"),
                type = type,
                normX = json.optDouble("normX", 0.5).toFloat(),
                normY = json.optDouble("normY", 0.5).toFloat(),
                scale = json.optDouble("scale", 1.0).toFloat(),
                opacity = json.optDouble("opacity", 0.65).toFloat(),
                visible = json.optBoolean("visible", true),
                binding = InputBinding.fromJson(json.getJSONObject("binding"))
            )
        }
    }
}

/**
 * Complete control layout for a specific game.
 */
data class TouchControlLayout(
    val gameId: String,
    val presetName: String,
    val mouseMode: MouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE,
    val trackpadSensitivity: Float = 1.0f,
    val elements: List<VirtualControlElement>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gameId", gameId)
        put("presetName", presetName)
        put("mouseMode", mouseMode.name)
        put("trackpadSensitivity", trackpadSensitivity.toDouble())
        val arr = JSONArray()
        for (el in elements) {
            arr.put(el.toJson())
        }
        put("elements", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): TouchControlLayout {
            val mouseMode = try {
                MouseEmulationMode.valueOf(json.optString("mouseMode", MouseEmulationMode.TRACKPAD_RELATIVE.name))
            } catch (_: Exception) {
                MouseEmulationMode.TRACKPAD_RELATIVE
            }

            val elementsList = mutableListOf<VirtualControlElement>()
            val arr = json.optJSONArray("elements") ?: JSONArray()
            for (i in 0 until arr.length()) {
                elementsList.add(VirtualControlElement.fromJson(arr.getJSONObject(i)))
            }

            return TouchControlLayout(
                gameId = json.getString("gameId"),
                presetName = json.optString("presetName", "Custom"),
                mouseMode = mouseMode,
                trackpadSensitivity = json.optDouble("trackpadSensitivity", 1.0).toFloat(),
                elements = elementsList
            )
        }
    }
}
