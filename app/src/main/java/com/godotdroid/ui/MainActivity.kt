package com.godotdroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.godotdroid.ui.screens.GameLibraryScreen
import com.godotdroid.ui.theme.GodotDroidTheme
import com.godotdroid.ui.viewmodel.GameLibraryViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: GameLibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GodotDroidTheme {
                GameLibraryScreen(viewModel = viewModel)
            }
        }
    }
}
