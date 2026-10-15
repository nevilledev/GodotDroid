package com.godotdroid.runtime.activity

import android.app.Activity
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.core.CoreManager
import com.godotdroid.runtime.loader.DynamicGodotLoader
import com.godotdroid.runtime.patcher.RuntimePatchInjector
import java.io.File

/**
 * Isolated process host activity running under android:process=":godot_runner".
 * Executes the loaded Godot engine inside an immersive landscape container and terminates
 * its PID on exit to prevent native symbol collisions across engine versions.
 */
class GodotRunnerActivity : Activity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var coreManager: CoreManager
    private lateinit var patchInjector: RuntimePatchInjector
    private lateinit var dynamicLoader: DynamicGodotLoader

    private var engineInstance: DynamicGodotLoader.IEngineInstance? = null
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
        val core = coreManager.getCore(versionKey)
            ?: coreManager.findMatchingCore(
                versionKey.substringBefore('.').toIntOrNull() ?: 4,
                versionKey.substringAfter('.').toIntOrNull() ?: 3
            )

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

        // 5. Prepare runtime environment (override.cfg, CLI args, plugins)
        val environment = patchInjector.prepareRuntimeEnvironment(
            gameId = gameId.ifBlank { "game_${System.currentTimeMillis()}" },
            pckOrExeFile = targetFile,
            selectedRenderer = renderer,
            mainSceneOverride = mainSceneOverride
        )

        // 6. Dynamically load and initialize Godot
        val result = dynamicLoader.loadAndInitialize(
            core = core,
            environment = environment,
            activity = this,
            container = rootContainer
        )

        result.onSuccess { instance ->
            engineInstance = instance
            hideLoadingIndicator()
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

        fun createIntent(
            context: Context,
            gameId: String,
            gameTitle: String,
            filePath: String,
            renderer: RendererType,
            versionKey: String,
            mainSceneOverride: String? = null
        ): Intent {
            return Intent(context, GodotRunnerActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, gameId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_RENDERER, renderer.name)
                putExtra(EXTRA_VERSION_KEY, versionKey)
                putExtra(EXTRA_MAIN_SCENE_OVERRIDE, mainSceneOverride)
            }
        }
    }
}
