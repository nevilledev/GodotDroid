package com.godotdroid.runtime.loader

import android.app.Activity
import org.godotengine.godot.GodotHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException

class DynamicGodotLoaderTest {

    @Test
    fun testUnwrapException_recursiveInvocationTargetException() {
        val rootCause = IllegalStateException("Root cause error message")
        val ite1 = InvocationTargetException(rootCause, "wrapper message")
        val ite2 = InvocationTargetException(ite1)

        val unwrapped = DynamicGodotLoader.unwrapException(ite2)
        assertEquals(rootCause, unwrapped)
        assertEquals("Root cause error message", unwrapped.message)

        val formatted = DynamicGodotLoader.formatExceptionMessage(ite2)
        assertTrue(formatted.contains("IllegalStateException"))
        assertTrue(formatted.contains("Root cause error message"))
    }

    @Test
    fun testUnwrapException_undeclaredThrowableException() {
        val rootCause = RuntimeException("Database error")
        val ute = UndeclaredThrowableException(rootCause)
        val ite = InvocationTargetException(ute)

        val unwrapped = DynamicGodotLoader.unwrapException(ite)
        assertEquals(rootCause, unwrapped)
        assertEquals("Database error", unwrapped.message)
    }

    @Test
    fun testGodotHostProxy_primitiveSafety() {
        val activity = Activity()
        val loader = DynamicGodotLoader(activity)

        val cliArgs = arrayOf("--main-pack", "game.pck", "--fullscreen")
        val fakeGodot = Any()

        val proxy = loader.createGodotHostProxy(
            classLoader = javaClass.classLoader!!,
            activity = activity,
            cliArgs = cliArgs,
            godotInstanceProvider = { fakeGodot }
        )

        assertNotNull(proxy)
        assertTrue(proxy is GodotHost)
        val host = proxy as GodotHost

        // 1. Primitive boolean methods must NOT throw NullPointerException from unboxing
        val forceQuitResult = host.onGodotForceQuit(0)
        assertFalse(forceQuitResult)

        val supportsFeature = host.supportsFeature("vulkan")
        assertFalse(supportsFeature)

        // 2. Primitive int method must NOT throw NullPointerException
        val instanceId = host.onNewGodotInstanceRequested(arrayOf("test"))
        assertEquals(0, instanceId)

        // 3. Command line arguments returned as ArrayList
        val args = host.getCommandLine()
        assertEquals(3, args.size)
        assertEquals("--main-pack", args[0])
        assertEquals("game.pck", args[1])
        assertEquals("--fullscreen", args[2])

        // 4. Activity and instance providers
        assertEquals(activity, host.getActivity())
        assertEquals(fakeGodot, host.getGodot())
        assertNotNull(host.getHostPlugins())

        // 5. Void methods
        host.onGodotSetupCompleted()
        host.onGodotMainLoopStarted()

        // 6. Object methods
        assertNotNull(host.toString())
        assertTrue(host.hashCode() != 0)
        assertEquals(host, host)
    }
}
