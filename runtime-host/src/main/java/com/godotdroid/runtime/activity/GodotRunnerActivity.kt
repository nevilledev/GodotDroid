package com.godotdroid.runtime.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.core.CoreManager
import com.godotdroid.runtime.input.GodotInputBridge
import com.godotdroid.runtime.loader.DynamicGodotLoader
import com.godotdroid.runtime.patcher.RuntimePatchInjector
import com.godotdroid.runtime.ui.GodotRunnerOverlayScreen
import java.io.File

/**
 * Isolated process host activity running under android:process=":godot_runner".
 * Executes the loaded Godot engine inside an immersive landscape container and terminates
 * its PID on exit to prevent native symbol collisions across engine versions.
 */
class GodotRunnerActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var coreManager: CoreManager
    private lateinit var patchInjector: RuntimePatchInjector
    private lateinit var dynamicLoader: DynamicGodotLoader

    private var engineInstance: DynamicGodotLoader.IEngineInstance? = null
    private var inputBridge: GodotInputBridge? = null
    private var composeOverlay: ComposeView? = null
    private var currentGameId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Setup Immersive Full-Screen Window
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // 2. Setup Container
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        coreManager = CoreManager(this)
        patchInjector = RuntimePatchInjector(this)
        dynamicLoader = DynamicGodotLoader(this)

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

        val targetFile = File(filePath)
        if (!targetFile.exists()) {
            Toast.makeText(this, "Game file not found: $filePath", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Show loading splash
        showLoadingIndicator(gameTitle, versionKey)

        // 4. Find matching installed core
        val major = versionKey.substringBefore('.').toIntOrNull() ?: 4
        val minor = versionKey.substringAfter('.').toIntOrNull() ?: 3
        val core = coreManager.getCore(versionKey)
            ?: coreManager.findMatchingCore(major, minor)

        if (core == null) {
            Toast.makeText(
                this,
                "No installed Godot core found for version $versionKey. Please install or import a core first.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        Log.i(TAG, "Launching $gameTitle with core ${core.engineVersion} (${core.versionKey})")

        // 5. Prepare runtime environment (override.cfg, CLI args, plugins, Steam stub)
        val environment = patchInjector.prepareRuntimeEnvironment(
            gameId = gameId.ifBlank { "game_${System.currentTimeMillis()}" },
            pckOrExeFile = targetFile,
            selectedRenderer = renderer,
            mainSceneOverride = mainSceneOverride,
            injectSteamStub = injectSteamStub,
            engineMajor = major
        )

        // 6. Instantiate Hybrid Input Bridge
        inputBridge = GodotInputBridge(
            activity = this,
            targetViewProvider = { engineInstance?.renderView },
            classLoaderProvider = { engineInstance?.classLoader }
        )

        // 7. Dynamically load and initialize Godot
        val result = dynamicLoader.loadAndInitialize(
            core = core,
            environment = environment,
            activity = this,
            container = rootContainer
        )

        result.onSuccess { instance ->
            engineInstance = instance
            hideLoadingIndicator()

            // 8. Embed Touch Controls Overlay & Floating HUD above Godot SurfaceView
            val overlay = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    GodotRunnerOverlayScreen(
                        gameId = currentGameId,
                        initialPreset = controlPreset,
                        inputDispatcher = inputBridge,
                        onExitGame = { finish() }
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
            Log.e(TAG, "Failed to initialize Godot engine", error)
            Toast.makeText(
                this,
                "Godot initialization failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private var loadingView: View? = null

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
        rootContainer.addView(layout)
    }

    private fun hideLoadingIndicator() {
        loadingView?.let {
            rootContainer.removeView(it)
            loadingView = null
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

    override fun onDestroy() {
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
