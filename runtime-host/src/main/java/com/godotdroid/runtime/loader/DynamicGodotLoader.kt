package com.godotdroid.runtime.loader

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.godotdroid.runtime.core.InstalledCore
import com.godotdroid.runtime.patcher.RuntimeEnvironment
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Android 14+ W^X Safe Dynamic Engine Loader.
 * Uses DexClassLoader, companion library loading, and dynamic GodotHost proxy binding.
 */
class DynamicGodotLoader(private val context: Context) {

    interface IEngineInstance {
        val renderView: View?
        val classLoader: ClassLoader
        fun onStart()
        fun onResume()
        fun onPause()
        fun onStop()
        fun onDestroy()
        fun onBackPressed(): Boolean
    }

    /**
     * Loads the specified Godot core and instantiates the engine inside the host activity.
     */
    fun loadAndInitialize(
        core: InstalledCore,
        environment: RuntimeEnvironment,
        activity: Activity,
        container: FrameLayout
    ): Result<IEngineInstance> {
        try {
            Log.i(TAG, "Initializing DynamicGodotLoader for Godot ${core.engineVersion}")

            // 1. Android 14 W^X compliance check
            if (!core.dexFile.exists() || !core.soFile.exists()) {
                return Result.failure(IllegalStateException("Missing core binaries in ${core.coreDir.absolutePath}"))
            }

            // Ensure read-only permissions are enforced on dex and so files
            if (core.dexFile.canWrite()) {
                core.dexFile.setReadOnly()
            }
            if (core.soFile.canWrite()) {
                core.soFile.setReadOnly()
            }

            // 2. Build native library search path (core directory + runtime jniLibs)
            val nativeLibPath = "${core.coreDir.absolutePath}${File.pathSeparator}${environment.jniLibsDir.absolutePath}"

            // 3. Create DexClassLoader
            val optDir = context.codeCacheDir
            val classLoader = DexClassLoader(
                core.dexFile.absolutePath,
                optDir.absolutePath,
                nativeLibPath,
                context.classLoader
            )

            // 4. Load companion native libraries if present (libc++_shared.so)
            val cPlusPlusSo = File(core.coreDir, "libc++_shared.so")
            if (cPlusPlusSo.exists()) {
                Log.i(TAG, "Loading companion native library: ${cPlusPlusSo.absolutePath}")
                if (cPlusPlusSo.canWrite()) cPlusPlusSo.setReadOnly()
                try {
                    System.load(cPlusPlusSo.absolutePath)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Could not pre-load libc++_shared.so: ${e.message}")
                }
            }

            // 5. Load main engine native library libgodot_android.so
            Log.i(TAG, "Loading native engine library: ${core.soFile.absolutePath}")
            System.load(core.soFile.absolutePath)

            // 6. Reflectively load org.godotengine.godot.Godot
            val godotClass = try {
                classLoader.loadClass("org.godotengine.godot.Godot")
            } catch (e: ClassNotFoundException) {
                // Godot 3.x fallback
                try {
                    classLoader.loadClass("org.godotengine.godot.GodotActivity")
                } catch (e2: ClassNotFoundException) {
                    throw ClassNotFoundException("Neither Godot nor GodotActivity found in dex file", e)
                }
            }

            val instance = instantiateGodotEngine(
                classLoader = classLoader,
                godotClass = godotClass,
                activity = activity,
                container = container,
                cliArgs = environment.cliArgs
            )
            return Result.success(instance)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load and initialize Godot engine", t)
            return Result.failure(t)
        }
    }

    /**
     * Creates a dynamic proxy implementing org.godotengine.godot.GodotHost.
     */
    fun createGodotHostProxy(
        classLoader: ClassLoader,
        activity: Activity,
        cliArgs: Array<String>,
        godotInstanceProvider: () -> Any?
    ): Any? {
        val godotHostClass = try {
            classLoader.loadClass("org.godotengine.godot.GodotHost")
        } catch (_: ClassNotFoundException) {
            null
        } ?: return null

        return Proxy.newProxyInstance(
            classLoader,
            arrayOf(godotHostClass)
        ) { _, method, args ->
            when (method.name) {
                "getActivity" -> activity
                "getCommandLine" -> java.util.ArrayList(cliArgs.toList())
                "getHostPlugins" -> emptySet<Any>()
                "getGodot" -> godotInstanceProvider()
                "onGodotSetupCompleted" -> {
                    Log.i(TAG, "GodotHost: onGodotSetupCompleted")
                    null
                }
                "onGodotMainLoopStarted" -> {
                    Log.i(TAG, "GodotHost: onGodotMainLoopStarted")
                    null
                }
                "onGodotForceQuit" -> {
                    Log.i(TAG, "GodotHost: onGodotForceQuit (code=${args?.getOrNull(0)})")
                    null
                }
                "onGodotRestart" -> {
                    Log.i(TAG, "GodotHost: onGodotRestart")
                    null
                }
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "GodotHostProxy[Activity=${activity.componentName}]"
                else -> {
                    when (method.returnType) {
                        Boolean::class.java, java.lang.Boolean::class.java -> false
                        Int::class.java, java.lang.Integer::class.java -> 0
                        Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
    }

    private fun instantiateGodotEngine(
        classLoader: ClassLoader,
        godotClass: Class<*>,
        activity: Activity,
        container: FrameLayout,
        cliArgs: Array<String>
    ): IEngineInstance {
        Log.i(TAG, "Binding Godot class: ${godotClass.name}")

        var godotInstance: Any? = null
        val godotHostClass = try {
            classLoader.loadClass("org.godotengine.godot.GodotHost")
        } catch (_: ClassNotFoundException) {
            null
        }

        val godotHostProxy = if (godotHostClass != null) {
            createGodotHostProxy(classLoader, activity, cliArgs) { godotInstance }
        } else null

        // In Godot 4.3+: constructor may take GodotHost
        if (godotHostClass != null && godotHostProxy != null) {
            try {
                val constructor = godotClass.getConstructor(godotHostClass)
                godotInstance = constructor.newInstance(godotHostProxy)
            } catch (_: Exception) {
                try {
                    val constructor = godotClass.getConstructor(Activity::class.java, godotHostClass)
                    godotInstance = constructor.newInstance(activity, godotHostProxy)
                } catch (_: Exception) {
                }
            }
        }

        // Fallbacks for earlier Godot versions: Godot(Context) or Godot(Activity)
        if (godotInstance == null) {
            try {
                val constructor = godotClass.getConstructor(Context::class.java)
                godotInstance = constructor.newInstance(activity)
            } catch (_: Exception) {
                try {
                    val constructor = godotClass.getConstructor(Activity::class.java)
                    godotInstance = constructor.newInstance(activity)
                } catch (_: Exception) {
                    try {
                        val defaultConstructor = godotClass.getConstructor()
                        godotInstance = defaultConstructor.newInstance()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Cache lifecycle methods
        val onStartMethod = findMethod(godotClass, "onStart")
        val onResumeMethod = findMethod(godotClass, "onResume")
        val onPauseMethod = findMethod(godotClass, "onPause")
        val onStopMethod = findMethod(godotClass, "onStop")
        val onDestroyMethod = findMethod(godotClass, "onDestroy")
        val onBackPressedMethod = findMethod(godotClass, "onBackPressed")
        val initMethod = findInitMethod(godotClass)

        var renderView: View? = null

        // Try initializing Godot view
        if (godotInstance != null && initMethod != null) {
            try {
                initMethod.isAccessible = true
                val paramTypes = initMethod.parameterTypes
                when (paramTypes.size) {
                    2 -> {
                        if (godotHostClass != null && paramTypes[0].isAssignableFrom(godotHostClass)) {
                            initMethod.invoke(godotInstance, godotHostProxy, cliArgs)
                        } else if (paramTypes[1] == Array<String>::class.java) {
                            initMethod.invoke(godotInstance, activity, cliArgs)
                        } else if (paramTypes[1] == java.util.List::class.java) {
                            initMethod.invoke(godotInstance, activity, java.util.ArrayList(cliArgs.toList()))
                        }
                    }
                    1 -> {
                        if (godotHostClass != null && paramTypes[0].isAssignableFrom(godotHostClass)) {
                            initMethod.invoke(godotInstance, godotHostProxy)
                        } else if (paramTypes[0] == Array<String>::class.java) {
                            initMethod.invoke(godotInstance, cliArgs)
                        } else if (paramTypes[0] == java.util.List::class.java) {
                            initMethod.invoke(godotInstance, java.util.ArrayList(cliArgs.toList()))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not invoke init method on Godot: ${e.message}")
            }
        }

        // Find renderView / getView
        if (godotInstance != null) {
            try {
                val getViewMethod = godotClass.getMethod("getRenderView")
                renderView = getViewMethod.invoke(godotInstance) as? View
            } catch (_: Exception) {
                try {
                    val getViewMethod = godotClass.getMethod("getView")
                    renderView = getViewMethod.invoke(godotInstance) as? View
                } catch (_: Exception) {
                }
            }
        }

        if (renderView != null) {
            val parent = renderView.parent as? ViewGroup
            parent?.removeView(renderView)
            container.addView(
                renderView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        return object : IEngineInstance {
            override val renderView: View? get() = renderView
            override val classLoader: ClassLoader get() = classLoader

            override fun onStart() {
                try {
                    onStartMethod?.invoke(godotInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onStart: ${e.message}")
                }
            }

            override fun onResume() {
                try {
                    onResumeMethod?.invoke(godotInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onResume: ${e.message}")
                }
            }

            override fun onPause() {
                try {
                    onPauseMethod?.invoke(godotInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onPause: ${e.message}")
                }
            }

            override fun onStop() {
                try {
                    onStopMethod?.invoke(godotInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onStop: ${e.message}")
                }
            }

            override fun onDestroy() {
                try {
                    onDestroyMethod?.invoke(godotInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onDestroy: ${e.message}")
                }
            }

            override fun onBackPressed(): Boolean {
                return try {
                    if (onBackPressedMethod != null) {
                        val result = onBackPressedMethod.invoke(godotInstance)
                        if (result is Boolean) result else false
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun findMethod(clazz: Class<*>, name: String): Method? {
        return try {
            clazz.getMethod(name).apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }
    }

    private fun findInitMethod(clazz: Class<*>): Method? {
        for (m in clazz.methods) {
            if (m.name.equals("init", ignoreCase = true) ||
                m.name.equals("initialize", ignoreCase = true) ||
                m.name.equals("onInitRenderView", ignoreCase = true)
            ) {
                return m
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DynamicGodotLoader"
    }
}
