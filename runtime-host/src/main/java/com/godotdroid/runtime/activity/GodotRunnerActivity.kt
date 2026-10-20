package com.godotdroid.runtime.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.FragmentActivity
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.core.CoreManager
import com.godotdroid.runtime.input.GodotInputBridge
import com.godotdroid.runtime.loader.DynamicGodotLoader
import com.godotdroid.runtime.patcher.RuntimePatchInjector
import com.godotdroid.runtime.profiler.PerformanceMonitor
import com.godotdroid.runtime.ui.GodotRunnerOverlayScreen
import java.io.File

/**
 * Isolated process host activity running under android:process=":godot_runner".
 * Executes the loaded Godot engine inside an immersive landscape container and terminates
 * its PID on exit to prevent native symbol collisions across engine versions.
 */
class GodotRunnerActivity : FragmentActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var coreManager: CoreManager
    private lateinit var patchInjector: RuntimePatchInjector
    private lateinit var dynamicLoader: DynamicGodotLoader

    private var engineInstance: DynamicGodotLoader.IEngineInstance? = null
    private var inputBridge: GodotInputBridge? = null
    private var composeOverlay: ComposeView? = null
    private var currentGameId: String = ""

    private var performanceMonitor: PerformanceMonitor? = null
    private var hasPhysicalGamepad by mutableStateOf(false)
    private var isControllerActive by mutableStateOf(false)
    private var inputManager: InputManager? = null
    private var inputDeviceListener: InputManager.InputDeviceListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Setup Immersive Full-Screen Window
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // 2. Setup Container
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        coreManager = CoreManager(this)
        patchInjector = RuntimePatchInjector(this)
        dynamicLoader = DynamicGodotLoader(this)

        performanceMonitor = PerformanceMonitor().apply { start() }
        setupInputDeviceListener()



        // 3. Extract Intent Arguments
        val gameId = intent.getStringExtra(EXTRA_GAME_ID) ?: ""
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "Godot Game"
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        val rendererName = intent.getStringExtra(EXTRA_RENDERER) ?: RendererType.MOBILE.name
        val versionKey = intent.getStringExtra(EXTRA_VERSION_KEY) ?: "4.3"
        val mainSceneOverride = intent.getStringExtra(EXTRA_MAIN_SCENE_OVERRIDE)
        val injectSteamStub = intent.getBooleanExtra(EXTRA_INJECT_STEAM_STUB, false)
        val controlPreset = intent.getStringExtra(EXTRA_CONTROL_PRESET) ?: DefaultControlPresets.PRESET_PC_KEYBOARD

        currentGameId = gameId
        val renderer = try {
            RendererType.valueOf(rendererName)
        } catch (_: Exception) {
            RendererType.MOBILE
        }

        var targetFile = File(filePath)
        val parentDir = targetFile.parentFile
        if (parentDir != null && parentDir.exists()) {
            val mainPck = File(parentDir, "main.pck")
            if (mainPck.exists() && mainPck.length() > 0) {
                targetFile = mainPck
            }
        }

        if (!targetFile.exists()) {
            val parent = targetFile.parentFile
            if (parent != null && parent.exists()) {
                val match = parent.listFiles()?.firstOrNull {
                    it.name.equals(targetFile.name, ignoreCase = true) ||
                    java.text.Normalizer.normalize(it.name, java.text.Normalizer.Form.NFD)
                        .equals(java.text.Normalizer.normalize(targetFile.name, java.text.Normalizer.Form.NFD), ignoreCase = true) ||
                    (targetFile.name == "main.pck" && it.extension.lowercase() in listOf("exe", "pck"))
                }
                if (match != null) {
                    targetFile = match
                }
            }
        }

        // On-demand embedded EXE carving: If targetFile is an embedded Windows .exe, extract main.pck directly
        if (targetFile.exists() && targetFile.extension.lowercase() == "exe") {
            try {
                val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
                val header = inspector.inspectHeader(targetFile)
                if (header.isEmbeddedExe) {
                    val mainPck = File(targetFile.parentFile, "main.pck")
                    if (!mainPck.exists() || mainPck.length() == 0L) {
                        inspector.extractStandalonePck(targetFile, mainPck, header)
                        mainPck.setReadable(true, false)
                    }
                    if (mainPck.exists() && mainPck.length() > 0) {
                        Log.i(TAG, "Using carved standalone PCK: ${mainPck.absolutePath} (${mainPck.length()} bytes)")
                        targetFile = mainPck
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-demand extraction from embedded EXE failed: ${e.message}")
            }
        }

        if (!targetFile.exists()) {
            Toast.makeText(this, "Game file not found: $filePath", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Preflight generic unsupported feature checks
        val analyzer = com.godotdroid.pck.analyzer.GameCompatibilityAnalyzer()
        val report = try {
            analyzer.analyze(targetFile)
        } catch (e: Exception) {
            Log.w(TAG, "Preflight compatibility analysis skipped: ${e.message}")
            null
        }

        if (report != null) {
            if (report.isEncrypted) {
                val errMsg = "Encrypted PCK is not supported"
                Log.e(TAG, errMsg)
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            if (report.usesDotNet) {
                val errMsg = "This game was built with Godot Mono / .NET (C#). Desktop C# assemblies are not supported by the Android runtime."
                Log.e(TAG, errMsg)
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            if (report.missingAndroidPlugins.isNotEmpty()) {
                val missingNames = report.missingAndroidPlugins.joinToString { it.name }
                val errMsg = "Unsupported desktop GDExtension DLLs without Android ARM64 binaries ($missingNames)"
                Log.e(TAG, errMsg)
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        // Show loading splash
        showLoadingIndicator(gameTitle, versionKey)

        // 4. Find matching installed core by exact major.minor from PCK header
        val pckHeader = try {
            val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
            inspector.inspectHeader(targetFile)
        } catch (_: Exception) { null }

        val detectedEngineMajor = pckHeader?.engineMajor ?: versionKey.substringBefore('.').toIntOrNull() ?: 4
        val detectedEngineMinor = pckHeader?.engineMinor ?: versionKey.substringAfter('.').toIntOrNull() ?: 3
        val detectedEnginePatch = pckHeader?.enginePatch ?: 0
        val detectedEngineVersion = "$detectedEngineMajor.$detectedEngineMinor.$detectedEnginePatch"
        val exactVersionKey = "$detectedEngineMajor.$detectedEngineMinor"

        // 5. Prepare runtime environment and load Godot asynchronously
        lifecycleScope.launch {
            var core = coreManager.getCore(exactVersionKey)
                ?: coreManager.findMatchingCore(detectedEngineMajor, detectedEngineMinor)

            if (core == null) {
                // Check if core is available in registry (driven by cores_manifest.json)
                var descriptor = coreManager.getRegistryCatalog().find { it.versionKey == exactVersionKey }
                if (descriptor == null) {
                    coreManager.syncRemoteManifest()
                    descriptor = coreManager.getRegistryCatalog().find { it.versionKey == exactVersionKey }
                }

                if (descriptor == null) {
                    val errorMsg = "Needs Godot $exactVersionKey core, not available"
                    Log.e(TAG, errorMsg)
                    Toast.makeText(this@GodotRunnerActivity, errorMsg, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                Log.i(TAG, "Godot $exactVersionKey core not installed. Auto-downloading ${descriptor.displayName}...")
                loadingTextView?.text = "Downloading Godot $exactVersionKey core..."

                val downloadResult = coreManager.downloadCore(descriptor) { progress ->
                    runOnUiThread {
                        loadingTextView?.text = "Downloading Godot $exactVersionKey core: ${progress.percentage}%"
                    }
                }

                core = downloadResult.getOrNull()
                if (core == null) {
                    val errorMsg = "Needs Godot $exactVersionKey core, not available: ${downloadResult.exceptionOrNull()?.message}"
                    Log.e(TAG, errorMsg, downloadResult.exceptionOrNull())
                    Toast.makeText(this@GodotRunnerActivity, errorMsg, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                Log.i(TAG, "Successfully auto-downloaded and installed Godot $exactVersionKey core: ${core.engineVersion}")
            }

            val coreMajor = core.engineVersion.substringBefore('.').toIntOrNull() ?: detectedEngineMajor
            val coreMinor = core.engineVersion.substringAfter('.').substringBefore('.').toIntOrNull() ?: detectedEngineMinor

            Log.i(TAG, "Launching $gameTitle with core ${core.engineVersion} (${core.versionKey})")

            val environment = withContext(Dispatchers.IO) {
                patchInjector.prepareRuntimeEnvironment(
                    gameId = gameId.ifBlank { "game_${System.currentTimeMillis()}" },
                    pckOrExeFile = targetFile,
                    selectedRenderer = renderer,
                    mainSceneOverride = mainSceneOverride,
                    injectSteamStub = injectSteamStub,
                    engineMajor = coreMajor,
                    engineMinor = coreMinor
                )
            }

            // Pre-launch validation and logging
            val stagedPckFile = File(environment.runtimeDir, "runtime_staged.pck")
            val targetForInspection = if (stagedPckFile.exists()) stagedPckFile else targetFile
            val projectBinaryMagic = try {
                java.io.RandomAccessFile(targetForInspection, "r").use { raf ->
                    val ch = raf.channel
                    val insp = com.godotdroid.pck.inspector.PckBinaryInspector()
                    val res = insp.inspect(ch, ch.size())
                    val pbEntry = res.files.find { it.path == "res://project.binary" || it.rawPath == "project.binary" }
                    if (pbEntry != null) {
                        val buf = java.nio.ByteBuffer.allocate(4)
                        ch.position(pbEntry.fileOffset)
                        ch.read(buf)
                        buf.flip()
                        val b = ByteArray(4)
                        buf.get(b)
                        String(b, Charsets.ISO_8859_1)
                    } else {
                        "NOT_FOUND"
                    }
                }
            } catch (e: Exception) {
                "ERR: ${e.message}"
            }

            Log.i(TAG, "[LAUNCH_PREFLIGHT] Detected engine version: $detectedEngineVersion, Chosen core version: ${core.engineVersion} (${core.versionKey}), Renderer: ${renderer.name}, Staged source path: ${targetFile.absolutePath}, Staged runtime path: ${targetForInspection.absolutePath}, project.binary magic: $projectBinaryMagic")

            // 6. Instantiate Hybrid Input Bridge
            inputBridge = GodotInputBridge(
                activity = this@GodotRunnerActivity,
                targetViewProvider = { engineInstance?.renderView },
                classLoaderProvider = { engineInstance?.classLoader }
            )

            // 7. Change working directory to runtime staging folder and initialize Godot
            try {
                System.setProperty("user.dir", environment.runtimeDir.absolutePath)
                val libcoreClass = Class.forName("libcore.io.Libcore")
                val osField = libcoreClass.getField("os")
                val osInstance = osField.get(null)
                val chdirMethod = osInstance.javaClass.getMethod("chdir", String::class.java)
                chdirMethod.invoke(osInstance, environment.runtimeDir.absolutePath)
                Log.i(TAG, "Changed process working directory to: ${environment.runtimeDir.absolutePath}")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not chdir to ${environment.runtimeDir.absolutePath}: ${e.message}")
            }

            val result = dynamicLoader.loadAndInitialize(
                core = core,
                environment = environment,
                activity = this@GodotRunnerActivity,
                container = rootContainer
            )

            result.onSuccess { instance ->
                engineInstance = instance
                hideLoadingIndicator()

                // 8. Embed Touch Controls Overlay & Floating HUD above Godot SurfaceView
                val overlay = ComposeView(this@GodotRunnerActivity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        val metrics by (performanceMonitor?.metrics?.collectAsState()
                            ?: remember { mutableStateOf(PerformanceMonitor.PerformanceMetrics()) })

                        GodotRunnerOverlayScreen(
                            gameId = currentGameId,
                            initialPreset = controlPreset,
                            inputDispatcher = inputBridge,
                            onExitGame = { finish() },
                            performanceMetrics = metrics,
                            hasPhysicalGamepad = hasPhysicalGamepad,
                            isControllerActive = isControllerActive
                        )
                    }
                }
                composeOverlay = overlay
                rootContainer.addView(
                    overlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                instance.onStart()
                instance.onResume()
            }.onFailure { error ->
                val rootCause = DynamicGodotLoader.unwrapException(error)
                Log.e(TAG, "Failed to initialize Godot engine: ${rootCause.message}", rootCause)
                hideLoadingIndicator()
                showInitializationErrorDialog(rootCause)
            }
        }
    }

    private fun showInitializationErrorDialog(error: Throwable) {
        val rootCause = DynamicGodotLoader.unwrapException(error)
        val stackTraceString = Log.getStackTraceString(rootCause)
        val errorMsg = rootCause.message?.takeIf { it.isNotBlank() }
            ?: rootCause.localizedMessage?.takeIf { it.isNotBlank() }
            ?: rootCause.javaClass.simpleName

        val nativeLogs = captureNativeLogcat()
        val fullErrorLog = "Error: ${rootCause.javaClass.name}: $errorMsg\n\nStack Trace:\n$stackTraceString\n\n--- Native C++ Logs (Last 80 lines) ---\n$nativeLogs"

        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(40, 20, 40, 20)
        }
        val textView = TextView(this).apply {
            text = fullErrorLog
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        scrollView.addView(textView)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Engine Initialization Failed")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Close") { _, _ ->
                finish()
            }
            .setNeutralButton("Copy Error Log") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (clipboard != null) {
                    val clip = android.content.ClipData.newPlainText("GodotDroid Error Log", fullErrorLog)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Error log copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .create()

        dialog.show()
    }

    private fun captureNativeLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "80", "-s", "godot:*", "Godot:*", "GodotLib:*", "DynamicGodotLoader:*"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.isNotBlank()) output.trim() else "No native logcat output captured."
        } catch (e: Exception) {
            "Failed to capture logcat: ${e.message}"
        }
    }

    private var loadingView: View? = null
    private var loadingTextView: TextView? = null

    private fun showLoadingIndicator(title: String, version: String) {
        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F111A"))
        }

        val progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val textView = TextView(this).apply {
            text = "Loading $title\nGodot $version (W^X Safe Runtime)"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = 160
            }
        }

        layout.addView(progressBar)
        layout.addView(textView)
        loadingView = layout
        loadingTextView = textView
        rootContainer.addView(layout)
    }

    private fun hideLoadingIndicator() {
        loadingView?.let {
            rootContainer.removeView(it)
            loadingView = null
            loadingTextView = null
        }
    }

    override fun onStart() {
        super.onStart()
        engineInstance?.onStart()
    }

    override fun onResume() {
        super.onResume()
        engineInstance?.onResume()
    }

    override fun onPause() {
        engineInstance?.onPause()
        super.onPause()
    }

    override fun onStop() {
        engineInstance?.onStop()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val handled = engineInstance?.onBackPressed() ?: false
        if (!handled) {
            super.onBackPressed()
        }
    }

    private fun setupInputDeviceListener() {
        val im = getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager = im
        hasPhysicalGamepad = checkPhysicalGamepadConnected()

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                hasPhysicalGamepad = checkPhysicalGamepadConnected()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                hasPhysicalGamepad = checkPhysicalGamepadConnected()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                hasPhysicalGamepad = checkPhysicalGamepadConnected()
            }
        }
        inputDeviceListener = listener
        im?.registerInputDeviceListener(listener, null)
    }

    private fun checkPhysicalGamepadConnected(): Boolean {
        return try {
            val deviceIds = InputDevice.getDeviceIds()
            for (id in deviceIds) {
                val dev = InputDevice.getDevice(id) ?: continue
                val sources = dev.sources
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepadEvent = (event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (event.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE) ||
                (event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER)

        if (isGamepadEvent) {
            isControllerActive = true
        }

        // Forward to input bridge first (handles physical controller, physical keyboard, and adb keyevents)
        if (inputBridge?.dispatchHardwareKeyEvent(event) == true) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (event.source and InputDevice.SOURCE_GAMEPAD) != 0

        if (isJoystick) {
            isControllerActive = true
            if (inputBridge?.dispatchHardwareGenericMotionEvent(event) == true) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Direct screen touch restores on-screen control visibility
        if (event.action == MotionEvent.ACTION_DOWN) {
            isControllerActive = false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        performanceMonitor?.stop()
        performanceMonitor = null
        inputDeviceListener?.let { inputManager?.unregisterInputDeviceListener(it) }
        inputDeviceListener = null
        inputManager = null

        engineInstance?.onDestroy()
        engineInstance = null
        inputBridge = null
        composeOverlay = null

        if (currentGameId.isNotBlank()) {
            patchInjector.cleanupRuntimeEnvironment(currentGameId)
        }

        super.onDestroy()

        // Cleanly terminate the isolated :godot_runner process to prevent native JNI symbol conflicts
        Log.i(TAG, "Terminating isolated process :godot_runner (PID ${android.os.Process.myPid()})")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "GodotRunnerActivity"

        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_GAME_TITLE = "extra_game_title"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_RENDERER = "extra_renderer"
        const val EXTRA_VERSION_KEY = "extra_version_key"
        const val EXTRA_MAIN_SCENE_OVERRIDE = "extra_main_scene_override"
        const val EXTRA_INJECT_STEAM_STUB = "extra_inject_steam_stub"
        const val EXTRA_CONTROL_PRESET = "extra_control_preset"

        fun createIntent(
            context: Context,
            gameId: String,
            gameTitle: String,
            filePath: String,
            renderer: RendererType,
            versionKey: String,
            mainSceneOverride: String? = null,
            injectSteamStub: Boolean = false,
            controlPreset: String = DefaultControlPresets.PRESET_PC_KEYBOARD
        ): Intent {
            return Intent(context, GodotRunnerActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, gameId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_RENDERER, renderer.name)
                putExtra(EXTRA_VERSION_KEY, versionKey)
                putExtra(EXTRA_MAIN_SCENE_OVERRIDE, mainSceneOverride)
                putExtra(EXTRA_INJECT_STEAM_STUB, injectSteamStub)
                putExtra(EXTRA_CONTROL_PRESET, controlPreset)
            }
        }
    }
}
