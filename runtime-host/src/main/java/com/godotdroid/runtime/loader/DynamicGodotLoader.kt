package com.godotdroid.runtime.loader

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.godotdroid.runtime.core.InstalledCore
import com.godotdroid.runtime.patcher.RuntimeEnvironment
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Array as JavaArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException

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
            Log.i(TAG, "Godot CLI arguments: ${environment.cliArgs.joinToString(" ")}")

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
                val cause = unwrapException(e)
                Log.w(TAG, "Note: GodotLib pre-load triggered: ${cause.message}")
            }

            try {
                installCompatHooks()
                Log.i(TAG, "Installed anti-crash compat hooks for recursive mutexes")
            } catch (e: Throwable) {
                Log.d(TAG, "installCompatHooks: ${e.message}")
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
            val rootCause = unwrapException(t)
            Log.e(TAG, "Failed to load and initialize Godot engine: ${rootCause.message}", rootCause)
            return Result.failure(rootCause)
        }
    }

    /**
     * Creates a dynamic proxy implementing org.godotengine.godot.GodotHost with 100% type safety.
     * Prevents any primitive unboxing NullPointerExceptions.
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
            val returnType = method.returnType
            when (method.name) {
                "getActivity" -> activity
                "getCommandLine" -> java.util.ArrayList(cliArgs.toList())
                "getHostPlugins" -> java.util.HashSet<Any>()
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
                    activity.runOnUiThread {
                        activity.finish()
                    }
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        false
                    } else null
                }
                "onGodotRestart", "onGodotRestartRequested" -> {
                    Log.i(TAG, "GodotHost: onGodotRestartRequested")
                    null
                }
                "onNewGodotInstanceRequested" -> 0
                "supportsFeature" -> false
                "onEditorWorkspaceSelected" -> null
                "signApk", "verifyApk" -> null
                "equals" -> {
                    val other = args?.getOrNull(0)
                    proxy === other
                }
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GodotHostProxy[Activity=${activity.componentName}]"
                else -> {
                    // Safe non-null primitive default return values to prevent unboxing NullPointerException
                    when (returnType) {
                        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
                        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
                        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
                        Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
                        Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
                        Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
                        Char::class.javaPrimitiveType, java.lang.Character::class.java -> '\u0000'
                        Void.TYPE, java.lang.Void::class.java -> null
                        java.util.List::class.java -> java.util.ArrayList<Any>()
                        java.util.Set::class.java -> java.util.HashSet<Any>()
                        java.util.Map::class.java -> java.util.HashMap<Any, Any>()
                        else -> {
                            if (returnType.isArray && returnType.componentType != null) {
                                JavaArray.newInstance(returnType.componentType!!, 0)
                            } else {
                                null
                            }
                        }
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
            val constructor = godotClass.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            godotInstance = constructor.newInstance(activity)
            Log.i(TAG, "Instantiated Godot with Godot(Context) constructor")
        } catch (e: Exception) {
            val u = unwrapException(e)
            Log.d(TAG, "Godot(Context) constructor not available: ${u.message}")
            try {
                val constructor = godotClass.getDeclaredConstructor(Activity::class.java)
                constructor.isAccessible = true
                godotInstance = constructor.newInstance(activity)
                Log.i(TAG, "Instantiated Godot with Godot(Activity) constructor")
            } catch (e2: Exception) {
                if (godotHostClass != null && godotHostProxy != null) {
                    try {
                        val constructor = godotClass.getDeclaredConstructor(godotHostClass)
                        constructor.isAccessible = true
                        godotInstance = constructor.newInstance(godotHostProxy)
                        Log.i(TAG, "Instantiated Godot with Godot(GodotHost) constructor")
                    } catch (_: Exception) {
                        try {
                            val constructor = godotClass.getDeclaredConstructor(Activity::class.java, godotHostClass)
                            constructor.isAccessible = true
                            godotInstance = constructor.newInstance(activity, godotHostProxy)
                            Log.i(TAG, "Instantiated Godot with Godot(Activity, GodotHost) constructor")
                        } catch (_: Exception) {}
                    }
                }
                if (godotInstance == null) {
                    try {
                        val defaultConstructor = godotClass.getDeclaredConstructor()
                        defaultConstructor.isAccessible = true
                        godotInstance = defaultConstructor.newInstance()
                        Log.i(TAG, "Instantiated Godot with default constructor")
                    } catch (_: Exception) {}
                }
            }
        }

        if (godotInstance == null) {
            throw IllegalStateException("Failed to instantiate Godot engine from class ${godotClass.name}")
        }

        var renderView: View? = null
        var godotHostLayout: FrameLayout? = null
        var fragmentAttached = false

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
            Log.i(TAG, "Godot CLI arguments (${cliArgs.size}): ${cliArgs.joinToString(" ")}")
            Log.i(TAG, "Invoking Godot.onCreate(GodotHost)")
            onCreateMethod.isAccessible = true
            try {
                onCreateMethod.invoke(godotInstance, godotHostProxy)
            } catch (e: Throwable) {
                val cause = unwrapException(e)
                Log.e(TAG, "Godot.onCreate(GodotHost) failed: ${cause.message}", cause)
                throw cause
            }

            if (onInitNativeLayerMethod != null) {
                Log.i(TAG, "Invoking Godot.onInitNativeLayer(GodotHost)")
                onInitNativeLayerMethod.isAccessible = true
                val nativeSuccess = try {
                    onInitNativeLayerMethod.invoke(godotInstance, godotHostProxy) as? Boolean ?: true
                } catch (e: Throwable) {
                    val cause = unwrapException(e)
                    Log.e(TAG, "Godot.onInitNativeLayer(GodotHost) failed: ${cause.message}", cause)
                    throw cause
                }
                Log.i(TAG, "Godot native layer initialization result: $nativeSuccess")
                if (!nativeSuccess) {
                    throw IllegalStateException("Godot native layer initialization failed (onInitNativeLayer returned false)")
                }
            }

            // Step C: onInitRenderView(GodotHost) or onInitRenderView(GodotHost, FrameLayout)
            val onInitRenderViewHostOnly = godotClass.methods.find {
                it.name == "onInitRenderView" && !it.name.contains("$") &&
                        it.parameterTypes.size == 1 &&
                        godotHostClass != null &&
                        it.parameterTypes[0].isAssignableFrom(godotHostClass)
            }
            val onInitRenderViewWithContainer = godotClass.methods.find {
                it.name == "onInitRenderView" && !it.name.contains("$") &&
                        it.parameterTypes.size == 2 &&
                        godotHostClass != null &&
                        it.parameterTypes[0].isAssignableFrom(godotHostClass) &&
                        FrameLayout::class.java.isAssignableFrom(it.parameterTypes[1])
            }

            if (onInitRenderViewHostOnly != null) {
                try {
                    Log.i(TAG, "Invoking Godot.onInitRenderView(GodotHost)")
                    onInitRenderViewHostOnly.isAccessible = true
                    godotHostLayout = onInitRenderViewHostOnly.invoke(godotInstance, godotHostProxy) as? FrameLayout
                } catch (e: Throwable) {
                    val cause = unwrapException(e)
                    Log.w(TAG, "onInitRenderView(host) note: ${cause.javaClass.simpleName}: ${cause.message}", cause)
                }
            } else if (onInitRenderViewWithContainer != null) {
                try {
                    Log.i(TAG, "Invoking Godot.onInitRenderView(GodotHost, FrameLayout)")
                    onInitRenderViewWithContainer.isAccessible = true
                    val freshLayout = FrameLayout(activity)
                    godotHostLayout = onInitRenderViewWithContainer.invoke(godotInstance, godotHostProxy, freshLayout) as? FrameLayout ?: freshLayout
                } catch (e: Throwable) {
                    val cause = unwrapException(e)
                    Log.w(TAG, "onInitRenderView(host, layout) note: ${cause.javaClass.simpleName}: ${cause.message}", cause)
                }
            }
        } else {
            // Godot 3.x Fragment-based architecture
            Log.i(TAG, "Initializing Godot 3 runtime for ${godotClass.name}")

            // 1. Inject godotHost field on Fragment if available
            if (godotHostProxy != null) {
                try {
                    val hostField = godotClass.getDeclaredField("godotHost")
                    hostField.isAccessible = true
                    hostField.set(godotInstance, godotHostProxy)
                    Log.i(TAG, "Injected godotHostProxy into Godot 3 Fragment")
                } catch (_: Exception) {}
            }

            // 2. Inject command_line field on Fragment
            try {
                val cmdField = godotClass.getDeclaredField("command_line")
                cmdField.isAccessible = true
                cmdField.set(godotInstance, cliArgs)
                Log.i(TAG, "Injected cliArgs into Godot 3 Fragment: ${cliArgs.joinToString(" ")}")
            } catch (_: Exception) {}

            if (activity is FragmentActivity && godotInstance is Fragment) {
                try {
                    if (container.id == View.NO_ID) {
                        container.id = View.generateViewId()
                    }
                    Log.i(TAG, "Attaching Godot 3 Fragment to SupportFragmentManager in container #${container.id}")
                    activity.supportFragmentManager.beginTransaction()
                        .replace(container.id, godotInstance)
                        .commitNowAllowingStateLoss()
                    fragmentAttached = true
                    Log.i(TAG, "Godot 3 Fragment attached and committed synchronously via SupportFragmentManager")

                    val fragView = godotInstance.view
                    if (fragView != null) {
                        renderView = fragView
                        if (fragView is FrameLayout) {
                            godotHostLayout = fragView
                        }
                    }
                } catch (t: Throwable) {
                    val cause = unwrapException(t)
                    Log.w(TAG, "SupportFragmentManager attachment note: ${cause.message}", cause)
                }
            }

            // Fallback if not attached via SupportFragmentManager
            if (!fragmentAttached) {
                val godotLibClass = try {
                    classLoader.loadClass("org.godotengine.godot.GodotLib")
                } catch (_: ClassNotFoundException) {
                    null
                }

                if (godotLibClass != null) {
                    try {
                        val libInit = godotLibClass.methods.find { it.name == "initialize" }
                        if (libInit != null) {
                            libInit.isAccessible = true
                            when (libInit.parameterTypes.size) {
                                8 -> {
                                    val ioClass = classLoader.loadClass("org.godotengine.godot.GodotIO")
                                    val ioCtor = ioClass.getDeclaredConstructor(Activity::class.java).apply { isAccessible = true }
                                    val io = ioCtor.newInstance(activity)
                                    val netClass = classLoader.loadClass("org.godotengine.godot.utils.GodotNetUtils")
                                    val netCtor = netClass.getDeclaredConstructor(Activity::class.java).apply { isAccessible = true }
                                    val net = netCtor.newInstance(activity)
                                    val dirClass = classLoader.loadClass("org.godotengine.godot.io.directory.DirectoryAccessHandler")
                                    val dirCtor = dirClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                                    val dir = dirCtor.newInstance(activity)
                                    val fileClass = classLoader.loadClass("org.godotengine.godot.io.file.FileAccessHandler")
                                    val fileCtor = fileClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                                    val file = fileCtor.newInstance(activity)
                                    libInit.invoke(null, activity, godotInstance, activity.assets, io, net, dir, file, false)
                                    Log.i(TAG, "GodotLib.initialize(8 params) invoked successfully for Godot 3 runtime")
                                }
                                3 -> libInit.invoke(null, activity, activity.assets, false)
                                2 -> libInit.invoke(null, activity, activity.assets)
                                1 -> libInit.invoke(null, activity)
                            }
                        }
                    } catch (e: Throwable) {
                        val cause = unwrapException(e)
                        Log.w(TAG, "GodotLib.initialize() fallback note: ${cause.message}", cause)
                    }
                }

                try {
                    val onAttachMethod = godotClass.methods.find {
                        it.name == "onAttach" && it.parameterTypes.size == 1 &&
                                (it.parameterTypes[0].isAssignableFrom(Activity::class.java) ||
                                 it.parameterTypes[0].isAssignableFrom(Context::class.java))
                    }
                    onAttachMethod?.let {
                        it.isAccessible = true
                        it.invoke(godotInstance, activity)
                        Log.i(TAG, "Godot 3 fallback: onAttach(Activity) invoked")
                    }

                    val onCreateBundleMethod = godotClass.methods.find {
                        it.name == "onCreate" && it.parameterTypes.size == 1 &&
                                android.os.Bundle::class.java.isAssignableFrom(it.parameterTypes[0])
                    }
                    onCreateBundleMethod?.let {
                        it.isAccessible = true
                        it.invoke(godotInstance, android.os.Bundle())
                        Log.i(TAG, "Godot 3 fallback: onCreate(Bundle) invoked")
                    }

                    val onCreateViewMethod = godotClass.methods.find {
                        it.name == "onCreateView" && it.parameterTypes.size == 3
                    }
                    if (onCreateViewMethod != null) {
                        onCreateViewMethod.isAccessible = true
                        val viewResult = onCreateViewMethod.invoke(godotInstance, activity.layoutInflater, container, null)
                        Log.i(TAG, "Godot 3 fallback: onCreateView returned: $viewResult")
                        if (viewResult is View) {
                            renderView = viewResult
                            if (viewResult is FrameLayout) {
                                godotHostLayout = viewResult
                            }
                        }
                    }

                    val onStartMethod = godotClass.methods.find { it.name == "onStart" && it.parameterTypes.isEmpty() }
                    onStartMethod?.let {
                        it.isAccessible = true
                        it.invoke(godotInstance)
                    }

                    val onResumeMethod = godotClass.methods.find { it.name == "onResume" && it.parameterTypes.isEmpty() }
                    onResumeMethod?.let {
                        it.isAccessible = true
                        it.invoke(godotInstance)
                    }
                } catch (e: Throwable) {
                    val cause = unwrapException(e)
                    Log.w(TAG, "Godot 3 Fragment lifecycle note: ${cause.message}", cause)
                }
            }

            // Extract containerLayout field if not yet resolved
            if (godotHostLayout == null) {
                try {
                    val clField = godotClass.getDeclaredField("containerLayout").apply { isAccessible = true }
                    val cl = clField.get(godotInstance) as? FrameLayout
                    if (cl != null) {
                        Log.i(TAG, "Found Godot 3 containerLayout field: $cl")
                        godotHostLayout = cl
                    }
                } catch (_: Exception) {}
            }

            // Extract mView (GodotView / GLSurfaceView) if available for accurate touch dispatch
            try {
                val mViewField = godotClass.getDeclaredField("mView").apply { isAccessible = true }
                val godotView = mViewField.get(godotInstance) as? View
                if (godotView != null) {
                    Log.i(TAG, "Found Godot 3 mView (GodotView): $godotView")
                    renderView = godotView
                }
            } catch (_: Exception) {}
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

        renderView?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
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
                } catch (e: Throwable) {
                    val cause = unwrapException(e)
                    Log.w(TAG, "Error invoking $methodName: ${cause.message}", cause)
                }
            }
        }

        return object : IEngineInstance {
            override val renderView: View? get() = renderView
            override val classLoader: ClassLoader get() = classLoader

            override fun onStart() {
                if (!fragmentAttached) {
                    invokeEngineLifecycle("onStart")
                }
            }

            override fun onResume() {
                if (!fragmentAttached) {
                    invokeEngineLifecycle("onResume")
                }
            }

            override fun onPause() {
                if (!fragmentAttached) {
                    invokeEngineLifecycle("onPause")
                }
            }

            override fun onStop() {
                if (!fragmentAttached) {
                    invokeEngineLifecycle("onStop")
                }
            }

            override fun onDestroy() {
                if (!fragmentAttached) {
                    invokeEngineLifecycle("onDestroy")
                }
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
        val rSubClasses = listOf("dimen", "string", "id", "layout", "style", "xml", "mipmap")
        val fallbackStringId = try {
            val okId = context.resources.getIdentifier("dialog_ok", "string", context.packageName)
            if (okId != 0) okId else android.R.string.ok
        } catch (_: Exception) {
            android.R.string.ok
        }

        val packagePrefixes = listOf("org.godotengine.godot.R", "com.godot.game.R")
        for (pkg in packagePrefixes) {
            for (subName in rSubClasses) {
                try {
                    val rClass = classLoader.loadClass("$pkg\$$subName")
                    for (field in rClass.fields) {
                        val fieldName = field.name
                        val resId = context.resources.getIdentifier(fieldName, subName, context.packageName)
                        field.isAccessible = true
                        if (resId != 0) {
                            try {
                                field.setInt(null, resId)
                                Log.d(TAG, "Patched $pkg.$subName.$fieldName -> 0x${Integer.toHexString(resId)}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to set $pkg.$subName.$fieldName: ${e.message}")
                            }
                        } else if (subName == "string") {
                            // Fallback to safe valid string resource ID so Resources$NotFoundException can never be thrown
                            try {
                                field.setInt(null, fallbackStringId)
                                Log.d(TAG, "Fallback patched $pkg.string.$fieldName -> 0x${Integer.toHexString(fallbackStringId)}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to fallback set $pkg.string.$fieldName: ${e.message}")
                            }
                        }
                    }
                } catch (_: ClassNotFoundException) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting $pkg.$subName: ${e.message}")
                }
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

        init {
            try {
                System.loadLibrary("godot_compat")
                Log.i(TAG, "Loaded libgodot_compat.so via companion init")
            } catch (e: Throwable) {
                Log.d(TAG, "libgodot_compat.so not yet loaded: ${e.message}")
            }
        }

        @JvmStatic
        external fun installCompatHooks()

        /**
         * Recursively unwraps reflection and wrapper exceptions (InvocationTargetException,
         * UndeclaredThrowableException, etc.) to expose the true root cause.
         */
        fun unwrapException(t: Throwable): Throwable {
            var current: Throwable = t
            while (true) {
                val next = when (current) {
                    is InvocationTargetException -> current.targetException ?: current.cause
                    is UndeclaredThrowableException -> current.undeclaredThrowable ?: current.cause
                    else -> current.cause
                }
                if (next != null && next !== current) {
                    current = next
                } else {
                    break
                }
            }
            return current
        }

        fun formatExceptionMessage(t: Throwable): String {
            val unwrapped = unwrapException(t)
            val msg = unwrapped.message?.takeIf { it.isNotBlank() }
                ?: unwrapped.localizedMessage?.takeIf { it.isNotBlank() }
                ?: unwrapped.javaClass.simpleName
            return "${unwrapped.javaClass.simpleName}: $msg"
        }
    }
}
