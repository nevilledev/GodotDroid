package com.godotdroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.godotdroid.boot.StandaloneBootManager
import com.godotdroid.ui.screens.GameLibraryScreen
import com.godotdroid.ui.screens.StandaloneBootScreen
import com.godotdroid.ui.theme.GodotDroidTheme
import com.godotdroid.ui.viewmodel.GameLibraryViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: GameLibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Detect if running inside an exported Standalone Game APK
        if (StandaloneBootManager.isStandaloneApk(this)) {
            setContent {
                GodotDroidTheme {
                    StandaloneBootScreen(
                        onBootComplete = { intent ->
                            startActivity(intent)
                            finish()
                        }
                    )
                }
            }
            return
        }

        setContent {
            GodotDroidTheme {
                GameLibraryScreen(viewModel = viewModel)
            }
        }
    }
}
