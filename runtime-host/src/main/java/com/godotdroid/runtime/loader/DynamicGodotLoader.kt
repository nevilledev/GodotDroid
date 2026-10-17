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

            // 4. Trigger GodotLib initialization within DexClassLoader namespace
            // (Pass coreDir in DexClassLoader librarySearchPath so libgodot_android.so is loaded by DexClassLoader, NOT PathClassLoader)
            try {
                Class.forName("org.godotengine.godot.GodotLib", true, classLoader)
                Log.i(TAG, "GodotLib successfully loaded inside DexClassLoader namespace")
            } catch (e: Throwable) {
                Log.w(TAG, "Note: GodotLib pre-load triggered: ${e.message}")
            }

            // 5. Reflectively load org.godotengine.godot.Godot
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

            // Patch Godot R class fields in memory to point to host application resource IDs
            patchGodotRClasses(classLoader, activity)

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
        ) { proxy, method, args ->
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
                    if (method.returnType == Boolean::class.java || method.returnType == java.lang.Boolean::class.java) true else null
                }
                "onGodotRestart", "onGodotRestartRequested" -> {
                    Log.i(TAG, "GodotHost: onGodotRestartRequested")
                    null
                }
                "onNewGodotInstanceRequested" -> 0
                "equals" -> {
                    val other = args?.getOrNull(0)
                    proxy === other
                }
                "hashCode" -> System.identityHashCode(proxy)
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

        // In Godot 4.3+: constructor is Godot(Context) or Godot(Activity)
        try {
            val constructor = godotClass.getConstructor(Context::class.java)
            godotInstance = constructor.newInstance(activity)
        } catch (_: Exception) {
            try {
                val constructor = godotClass.getConstructor(Activity::class.java)
                godotInstance = constructor.newInstance(activity)
            } catch (_: Exception) {
                if (godotHostClass != null && godotHostProxy != null) {
                    try {
                        val constructor = godotClass.getConstructor(godotHostClass)
                        godotInstance = constructor.newInstance(godotHostProxy)
                    } catch (_: Exception) {
                        try {
                            val constructor = godotClass.getConstructor(Activity::class.java, godotHostClass)
                            godotInstance = constructor.newInstance(activity, godotHostProxy)
                        } catch (_: Exception) {}
                    }
                }
                if (godotInstance == null) {
                    try {
                        val defaultConstructor = godotClass.getConstructor()
                        godotInstance = defaultConstructor.newInstance()
                    } catch (_: Exception) {}
                }
            }
        }

        if (godotInstance == null) {
            throw IllegalStateException("Failed to instantiate Godot engine from class ${godotClass.name}")
        }

        var renderView: View? = null
        var godotHostLayout: FrameLayout? = null

        // Check for Godot 4.x GodotHost lifecycle methods
        val onCreateMethod = godotClass.methods.find {
            it.name == "onCreate" && it.parameterTypes.size == 1 &&
                    godotHostClass != null && it.parameterTypes[0].isAssignableFrom(godotHostClass)
        }
        val onInitNativeLayerMethod = godotClass.methods.find {
            it.name == "onInitNativeLayer" && it.parameterTypes.size == 1 &&
                    godotHostClass != null && it.parameterTypes[0].isAssignableFrom(godotHostClass)
        }

        if (onCreateMethod != null && godotHostProxy != null) {
            Log.i(TAG, "Invoking Godot.onCreate(GodotHost)")
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(godotInstance, godotHostProxy)

            if (onInitNativeLayerMethod != null) {
                Log.i(TAG, "Invoking Godot.onInitNativeLayer(GodotHost)")
                onInitNativeLayerMethod.isAccessible = true
                val nativeSuccess = onInitNativeLayerMethod.invoke(godotInstance, godotHostProxy) as? Boolean ?: true
                Log.i(TAG, "Godot native layer initialization result: $nativeSuccess")
            }

            // Step C: onInitRenderView(GodotHost) or onInitRenderView(GodotHost, FrameLayout)
            val onInitRenderViewHostOnly = godotClass.methods.find {
                it.name == "onInitRenderView" && !it.name.contains("$") &&
                        it.parameterTypes.size == 1 &&
                        godotHostClass != null &&
                        it.parameterTypes[0].isAssignableFrom(godotHostClass!!)
            }
            val onInitRenderViewWithContainer = godotClass.methods.find {
                it.name == "onInitRenderView" && !it.name.contains("$") &&
                        it.parameterTypes.size == 2 &&
                        godotHostClass != null &&
                        it.parameterTypes[0].isAssignableFrom(godotHostClass!!) &&
                        FrameLayout::class.java.isAssignableFrom(it.parameterTypes[1])
            }

            if (onInitRenderViewHostOnly != null) {
                try {
                    Log.i(TAG, "Invoking Godot.onInitRenderView(GodotHost)")
                    onInitRenderViewHostOnly.isAccessible = true
                    godotHostLayout = onInitRenderViewHostOnly.invoke(godotInstance, godotHostProxy) as? FrameLayout
                } catch (e: Exception) {
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    Log.w(TAG, "onInitRenderView(host) note: ${cause.javaClass.simpleName}: ${cause.message}", cause)
                }
            } else if (onInitRenderViewWithContainer != null) {
                try {
                    Log.i(TAG, "Invoking Godot.onInitRenderView(GodotHost, FrameLayout)")
                    onInitRenderViewWithContainer.isAccessible = true
                    val freshLayout = FrameLayout(activity)
                    godotHostLayout = onInitRenderViewWithContainer.invoke(godotInstance, godotHostProxy, freshLayout) as? FrameLayout ?: freshLayout
                } catch (e: Exception) {
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    Log.w(TAG, "onInitRenderView(host, layout) note: ${cause.javaClass.simpleName}: ${cause.message}", cause)
                }
            }

            if (godotHostLayout != null && godotHostLayout !== container) {
                val parent = godotHostLayout.parent as? ViewGroup
                if (parent != container) {
                    parent?.removeView(godotHostLayout)
                    container.addView(
                        godotHostLayout,
                        0,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        } else {
            // Godot 3.x / legacy 4.0 init
            val initMethod = findInitMethod(godotClass)
            if (initMethod != null) {
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
                    Log.w(TAG, "Could not invoke legacy init method on Godot: ${e.message}")
                }
            }

            val godotLibClass = try {
                classLoader.loadClass("org.godotengine.godot.GodotLib")
            } catch (_: ClassNotFoundException) {
                null
            }

            // In Godot 3.x, initialize GodotLib with Activity & Assets, and pass command line
            if (godotLibClass != null) {
                try {
                    val libInit = godotLibClass.methods.find { it.name == "initialize" }
                    if (libInit != null) {
                        libInit.isAccessible = true
                        when (libInit.parameterTypes.size) {
                            3 -> libInit.invoke(null, activity, activity.assets, false)
                            2 -> libInit.invoke(null, activity, activity.assets)
                            1 -> libInit.invoke(null, activity)
                        }
                        Log.i(TAG, "GodotLib.initialize() invoked for Godot 3 runtime")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GodotLib.initialize() note: ${e.message}")
                }

                try {
                    val setupMethod = godotLibClass.methods.find { it.name == "setup" && it.parameterTypes.size == 1 }
                    if (setupMethod != null) {
                        setupMethod.isAccessible = true
                        setupMethod.invoke(null, cliArgs)
                        Log.i(TAG, "GodotLib.setup(cliArgs) invoked for Godot 3 runtime")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GodotLib.setup() note: ${e.message}")
                }
            }
        }

        // Resolve underlying renderView (GodotRenderView / SurfaceView) for touch and input dispatch
        if (renderView == null) {
            try {
                val getRenderViewMethod = godotClass.methods.find {
                    it.name == "getRenderView" && it.parameterTypes.isEmpty()
                } ?: godotClass.methods.find {
                    it.name == "getView" && it.parameterTypes.isEmpty()
                }
                if (getRenderViewMethod != null) {
                    getRenderViewMethod.isAccessible = true
                    val grv = getRenderViewMethod.invoke(godotInstance)
                    if (grv is View) {
                        renderView = grv
                    } else if (grv != null) {
                        val getViewM = grv.javaClass.methods.find { it.name == "getView" && it.parameterTypes.isEmpty() }
                        if (getViewM != null) {
                            getViewM.isAccessible = true
                            renderView = getViewM.invoke(grv) as? View
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (renderView == null && godotHostLayout != null) {
            renderView = if (godotHostLayout.childCount > 0) godotHostLayout.getChildAt(0) else godotHostLayout
        }

        // Godot 3.x view fallbacks
        if (renderView == null && godotInstance is View) {
            renderView = godotInstance
        }
        if (renderView == null) {
            try {
                val godotViewClass = classLoader.loadClass("org.godotengine.godot.GodotView")
                for (ctor in godotViewClass.constructors) {
                    try {
                        val pTypes = ctor.parameterTypes
                        if (pTypes.size == 1 && pTypes[0].isAssignableFrom(Context::class.java)) {
                            renderView = ctor.newInstance(activity) as? View
                            break
                        } else if (pTypes.size == 1 && pTypes[0].isAssignableFrom(Activity::class.java)) {
                            renderView = ctor.newInstance(activity) as? View
                            break
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        if (renderView != null && renderView !== container) {
            val parent = renderView.parent as? ViewGroup
            if (parent != container && (godotHostLayout == null || parent != godotHostLayout)) {
                parent?.removeView(renderView)
                container.addView(
                    renderView,
                    0,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        fun invokeEngineLifecycle(methodName: String) {
            val method = godotClass.methods.find { m ->
                m.name == methodName && (
                    m.parameterTypes.isEmpty() ||
                    (godotHostClass != null && m.parameterTypes.size == 1 && m.parameterTypes[0].isAssignableFrom(godotHostClass))
                )
            }
            if (method != null) {
                try {
                    method.isAccessible = true
                    if (method.parameterTypes.isEmpty()) {
                        method.invoke(godotInstance)
                    } else if (godotHostProxy != null) {
                        method.invoke(godotInstance, godotHostProxy)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking $methodName: ${e.message}")
                }
            }
        }

        return object : IEngineInstance {
            override val renderView: View? get() = renderView
            override val classLoader: ClassLoader get() = classLoader

            override fun onStart() {
                invokeEngineLifecycle("onStart")
            }

            override fun onResume() {
                invokeEngineLifecycle("onResume")
                try {
                    val godotLibClass = classLoader.loadClass("org.godotengine.godot.GodotLib")
                    val focusIn = godotLibClass.methods.find { it.name == "focusin" && it.parameterTypes.isEmpty() }
                    focusIn?.invoke(null)
                } catch (_: Exception) {}
            }

            override fun onPause() {
                try {
                    val godotLibClass = classLoader.loadClass("org.godotengine.godot.GodotLib")
                    val focusOut = godotLibClass.methods.find { it.name == "focusout" && it.parameterTypes.isEmpty() }
                    focusOut?.invoke(null)
                } catch (_: Exception) {}
                invokeEngineLifecycle("onPause")
            }

            override fun onStop() {
                invokeEngineLifecycle("onStop")
            }

            override fun onDestroy() {
                invokeEngineLifecycle("onDestroy")
            }

            override fun onBackPressed(): Boolean {
                return try {
                    val onBackPressedMethod = godotClass.methods.find { it.name == "onBackPressed" && it.parameterTypes.isEmpty() }
                    if (onBackPressedMethod != null) {
                        onBackPressedMethod.isAccessible = true
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

    private fun patchGodotRClasses(classLoader: ClassLoader, context: Context) {
        val rSubClasses = listOf("dimen", "string", "id", "layout", "style", "xml")
        for (subName in rSubClasses) {
            try {
                val rClass = classLoader.loadClass("org.godotengine.godot.R\$$subName")
                for (field in rClass.fields) {
                    val fieldName = field.name
                    val resId = context.resources.getIdentifier(fieldName, subName, context.packageName)
                    if (resId != 0) {
                        try {
                            field.isAccessible = true
                            field.setInt(null, resId)
                            Log.d(TAG, "Patched Godot R.$subName.$fieldName -> 0x${Integer.toHexString(resId)}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set R.$subName.$fieldName: ${e.message}")
                        }
                    }
                }
            } catch (_: ClassNotFoundException) {
            } catch (e: Exception) {
                Log.w(TAG, "Error inspecting R.$subName: ${e.message}")
            }
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
