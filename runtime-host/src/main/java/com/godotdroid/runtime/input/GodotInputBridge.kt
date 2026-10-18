package com.godotdroid.runtime.input

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.godotdroid.overlay.input.ITouchInputDispatcher
import java.lang.reflect.Method

/**
 * Hybrid JNI + View Input Bridge for dispatching touch/gamepad/mouse events
 * into Godot 4.x and 3.x runtimes with dual-path delivery.
 */
class GodotInputBridge(
    private val activity: Activity,
    private val targetViewProvider: () -> View?,
    private val classLoaderProvider: () -> ClassLoader?
) : ITouchInputDispatcher {

    // Virtual cursor coordinates for PC Mouse trackpad emulation
    private var virtualCursorX = 0f
    private var virtualCursorY = 0f

    // Cached JNI reflection methods on GodotLib
    private var godotLibClass: Class<*>? = null
    private var keyMethod6Params: Method? = null
    private var keyMethod5Params: Method? = null
    private var keyMethod4Params: Method? = null
    private var keyMethod3Params: Method? = null
    private var joyButtonMethod: Method? = null
    private var joyAxisMethod: Method? = null

    private var hasResolvedJni = false

    private fun resolveJniMethods() {
        if (hasResolvedJni) return
        val cl = classLoaderProvider() ?: return
        try {
            val clazz = cl.loadClass("org.godotengine.godot.GodotLib")
            godotLibClass = clazz

            for (m in clazz.methods) {
                if (m.name == "key") {
                    when (m.parameterTypes.size) {
                        6 -> keyMethod6Params = m.apply { isAccessible = true }
                        5 -> keyMethod5Params = m.apply { isAccessible = true }
                        4 -> keyMethod4Params = m.apply { isAccessible = true }
                        3 -> keyMethod3Params = m.apply { isAccessible = true }
                    }
                } else if (m.name == "joybutton") {
                    joyButtonMethod = m.apply { isAccessible = true }
                } else if (m.name == "joyaxis") {
                    joyAxisMethod = m.apply { isAccessible = true }
                }
            }
            hasResolvedJni = true
            Log.i(TAG, "Resolved GodotLib JNI: key6=$keyMethod6Params, key5=$keyMethod5Params, key4=$keyMethod4Params, key3=$keyMethod3Params, joyButton=$joyButtonMethod, joyAxis=$joyAxisMethod")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve GodotLib JNI methods: ${e.message}")
            hasResolvedJni = true
        }
    }

    override fun sendKey(androidKeyCode: Int, godotKeyCode: Int, isPressed: Boolean) {
        // Path 1: Native View KeyEvent Dispatch sequentially on UI thread
        activity.runOnUiThread {
            val view = targetViewProvider() ?: activity.window.decorView
            val now = SystemClock.uptimeMillis()
            val action = if (isPressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val event = KeyEvent(
                now, now, action, androidKeyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
            )
            val handled = event.dispatch(view)
            if (!handled) {
                // Path 2: Direct GodotLib JNI Fallback ONLY when view does not consume it
                dispatchDirectKeyJni(androidKeyCode, godotKeyCode, isPressed)
            }
        }
    }

    override fun sendJoyButton(buttonIndex: Int, isPressed: Boolean) {
        // Path 1: Native View KeyEvent with SOURCE_GAMEPAD sequentially on UI thread
        activity.runOnUiThread {
            val view = targetViewProvider() ?: activity.window.decorView
            val now = SystemClock.uptimeMillis()
            val action = if (isPressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val androidKeyCode = mapJoyButtonToAndroidKeyCode(buttonIndex)
            val event = KeyEvent(
                now, now, action, androidKeyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_GAMEPAD
            )
            val handled = event.dispatch(view)
            if (!handled) {
                // Path 2: Direct GodotLib joybutton JNI Fallback ONLY when view does not consume it
                dispatchDirectJoyButtonJni(buttonIndex, isPressed)
            }
        }
    }

    override fun sendJoyAxis(stickIndex: Int, axisX: Float, axisY: Float) {
        activity.runOnUiThread {
            resolveJniMethods()
            try {
                val baseAxis = if (stickIndex == 1) 2 else 0
                joyAxisMethod?.invoke(null, 0, baseAxis, axisX)
                joyAxisMethod?.invoke(null, 0, baseAxis + 1, axisY)
            } catch (e: Exception) {
                Log.w(TAG, "GodotLib.joyaxis JNI dispatch error: ${e.message}")
            }
        }
    }

    private fun dispatchDirectKeyJni(androidKeyCode: Int, godotKeyCode: Int, isPressed: Boolean): Boolean {
        resolveJniMethods()
        return try {
            val unicode = when (androidKeyCode) {
                KeyEvent.KEYCODE_SPACE -> 32
                KeyEvent.KEYCODE_ENTER -> 10
                KeyEvent.KEYCODE_TAB -> 9
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (androidKeyCode - KeyEvent.KEYCODE_0)
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'A'.code + (androidKeyCode - KeyEvent.KEYCODE_A)
                else -> if (godotKeyCode in 32..126) godotKeyCode else 0
            }

            if (keyMethod6Params != null) {
                // Godot 4.3+ multi-window: key(screen, keycode, physicalKeycode, unicode, pressed, echo)
                keyMethod6Params?.invoke(null, 0, godotKeyCode, godotKeyCode, unicode, isPressed, false)
                true
            } else if (keyMethod5Params != null) {
                // Godot 4.x: key(keycode, physicalKeycode, unicode, pressed, echo)
                keyMethod5Params?.invoke(null, godotKeyCode, godotKeyCode, unicode, isPressed, false)
                true
            } else if (keyMethod4Params != null) {
                // Godot 3.x: key(chr, keycode, scancode, pressed)
                // Note: Godot 3 JNI expects (unicode, androidKeyCode, unicode, isPressed)
                keyMethod4Params?.invoke(null, unicode, androidKeyCode, unicode, isPressed)
                true
            } else if (keyMethod3Params != null) {
                keyMethod3Params?.invoke(null, godotKeyCode, unicode, isPressed)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "GodotLib.key JNI dispatch fallback error: ${e.message}")
            false
        }
    }

    private fun dispatchDirectJoyButtonJni(buttonIndex: Int, isPressed: Boolean): Boolean {
        resolveJniMethods()
        return try {
            if (joyButtonMethod != null) {
                joyButtonMethod?.invoke(null, 0, buttonIndex, isPressed)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "GodotLib.joybutton JNI dispatch fallback error: ${e.message}")
            false
        }
    }

    override fun sendMouseMotion(deltaX: Float, deltaY: Float, currentNormX: Float, currentNormY: Float) {
        val view = targetViewProvider() ?: return
        activity.runOnUiThread {
            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()
            if (viewW <= 0f || viewH <= 0f) return@runOnUiThread

            virtualCursorX = currentNormX * viewW
            virtualCursorY = currentNormY * viewH

            val now = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = virtualCursorX
                    y = virtualCursorY
                    pressure = 0f
                    size = 1f
                }
            )

            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_HOVER_MOVE,
                1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
            )
            view.dispatchGenericMotionEvent(event)
            event.recycle()
        }
    }

    override fun sendMouseButton(mouseButton: Int, isPressed: Boolean) {
        val view = targetViewProvider() ?: return
        activity.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            val action = if (isPressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            val buttonState = when (mouseButton) {
                2 -> MotionEvent.BUTTON_SECONDARY
                3 -> MotionEvent.BUTTON_TERTIARY
                else -> MotionEvent.BUTTON_PRIMARY
            }

            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = virtualCursorX
                    y = virtualCursorY
                    pressure = if (isPressed) 1f else 0f
                    size = 1f
                }
            )

            val event = MotionEvent.obtain(
                now, now, action,
                1, props, coords, 0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
            )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /**
     * Forwards physical hardware controller key events directly to the engine view and JNI.
     */
    fun dispatchHardwareKeyEvent(event: KeyEvent): Boolean {
        val view = targetViewProvider()
        if (view != null && event.dispatch(view)) {
            return true
        }

        // GodotLib JNI Fallback for Gamepad Buttons
        val godotBtn = mapAndroidKeyCodeToGodotJoyButton(event.keyCode)
        if (godotBtn != -1) {
            val isPressed = event.action == KeyEvent.ACTION_DOWN
            return dispatchDirectJoyButtonJni(godotBtn, isPressed)
        }

        // Hardware Keyboard & ADB Key Event Fallback
        val isPressed = event.action == KeyEvent.ACTION_DOWN
        val godotKeyCode = mapAndroidKeyCodeToGodotKeyCode(event.keyCode)
        if (godotKeyCode != 0) {
            return dispatchDirectKeyJni(event.keyCode, godotKeyCode, isPressed)
        }

        return false
    }

    private fun mapAndroidKeyCodeToGodotKeyCode(androidKeyCode: Int): Int {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
            KeyEvent.KEYCODE_TAB -> 9
            KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_DEL -> 8
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'A'.code + (androidKeyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (androidKeyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_DPAD_UP -> 19
            KeyEvent.KEYCODE_DPAD_DOWN -> 20
            KeyEvent.KEYCODE_DPAD_LEFT -> 21
            KeyEvent.KEYCODE_DPAD_RIGHT -> 22
            else -> 0
        }
    }

    /**
     * Forwards physical hardware controller joystick/axis motion events to the engine view and JNI.
     */
    fun dispatchHardwareGenericMotionEvent(event: MotionEvent): Boolean {
        val view = targetViewProvider()
        if (view != null && view.dispatchGenericMotionEvent(event)) {
            return true
        }

        // Direct JNI joyaxis fallback
        resolveJniMethods()
        if (joyAxisMethod != null) {
            try {
                val lx = event.getAxisValue(MotionEvent.AXIS_X)
                val ly = event.getAxisValue(MotionEvent.AXIS_Y)
                val rx = event.getAxisValue(MotionEvent.AXIS_Z)
                val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
                val lt = event.getAxisValue(MotionEvent.AXIS_BRAKE).let { if (it > 0f) it else event.getAxisValue(MotionEvent.AXIS_LTRIGGER) }
                val rt = event.getAxisValue(MotionEvent.AXIS_GAS).let { if (it > 0f) it else event.getAxisValue(MotionEvent.AXIS_RTRIGGER) }

                joyAxisMethod?.invoke(null, 0, 0, lx)
                joyAxisMethod?.invoke(null, 0, 1, ly)
                joyAxisMethod?.invoke(null, 0, 2, rx)
                joyAxisMethod?.invoke(null, 0, 3, ry)
                if (lt > 0f) joyAxisMethod?.invoke(null, 0, 4, lt)
                if (rt > 0f) joyAxisMethod?.invoke(null, 0, 5, rt)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "GodotLib.joyaxis JNI fallback error: ${e.message}")
            }
        }
        return false
    }

    private fun mapAndroidKeyCodeToGodotJoyButton(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_B -> 1
            KeyEvent.KEYCODE_BUTTON_X -> 2
            KeyEvent.KEYCODE_BUTTON_Y -> 3
            KeyEvent.KEYCODE_BUTTON_L1 -> 9
            KeyEvent.KEYCODE_BUTTON_R1 -> 10
            KeyEvent.KEYCODE_BUTTON_L2 -> 15
            KeyEvent.KEYCODE_BUTTON_R2 -> 16
            KeyEvent.KEYCODE_BUTTON_SELECT -> 4
            KeyEvent.KEYCODE_BUTTON_START -> 6
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 7
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 8
            KeyEvent.KEYCODE_DPAD_UP -> 11
            KeyEvent.KEYCODE_DPAD_DOWN -> 12
            KeyEvent.KEYCODE_DPAD_LEFT -> 13
            KeyEvent.KEYCODE_DPAD_RIGHT -> 14
            else -> -1
        }
    }

    private fun mapJoyButtonToAndroidKeyCode(buttonIndex: Int): Int {
        return when (buttonIndex) {
            0 -> KeyEvent.KEYCODE_BUTTON_A
            1 -> KeyEvent.KEYCODE_BUTTON_B
            2 -> KeyEvent.KEYCODE_BUTTON_X
            3 -> KeyEvent.KEYCODE_BUTTON_Y
            4 -> KeyEvent.KEYCODE_BUTTON_SELECT
            6 -> KeyEvent.KEYCODE_BUTTON_START
            9 -> KeyEvent.KEYCODE_BUTTON_L1
            10 -> KeyEvent.KEYCODE_BUTTON_R1
            else -> KeyEvent.KEYCODE_BUTTON_A
        }
    }

    companion object {
        private const val TAG = "GodotInputBridge"
    }
}
