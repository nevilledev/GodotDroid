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

/**
 * Android 14+ W^X Safe Dynamic Engine Loader.
 * Uses DexClassLoader and System.load to load isolated Godot versions into the :godot_runner process.
 */
class DynamicGodotLoader(private val context: Context) {

    interface IEngineInstance {
        val renderView: View?
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

            // 4. Load native library libgodot_android.so
            Log.i(TAG, "Loading native library: ${core.soFile.absolutePath}")
            System.load(core.soFile.absolutePath)

            // 5. Reflectively instantiate org.godotengine.godot.Godot
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

            val instance = instantiateGodotEngine(godotClass, activity, container, environment.cliArgs)
            return Result.success(instance)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load and initialize Godot engine", t)
            return Result.failure(t)
        }
    }

    private fun instantiateGodotEngine(
        godotClass: Class<*>,
        activity: Activity,
        container: FrameLayout,
        cliArgs: Array<String>
    ): IEngineInstance {
        Log.i(TAG, "Binding Godot class: ${godotClass.name}")

        // In Godot 4: constructor is Godot(Context) or Godot(Activity)
        var godotInstance: Any? = null
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
                val paramCount = initMethod.parameterTypes.size
                if (paramCount == 2 && initMethod.parameterTypes[1] == Array<String>::class.java) {
                    initMethod.invoke(godotInstance, activity, cliArgs)
                } else if (paramCount == 1 && initMethod.parameterTypes[0] == Array<String>::class.java) {
                    initMethod.invoke(godotInstance, cliArgs)
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
