package com.godotdroid.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.pck.model.RendererType
import com.godotdroid.ui.components.GameCard
import com.godotdroid.ui.components.GameInspectorSheet
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.BackgroundDark
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark
import com.godotdroid.ui.viewmodel.GameLibraryViewModel
import com.godotdroid.ui.viewmodel.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    modifier: Modifier = Modifier
) {
    val games by viewModel.games.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val selectedGame by viewModel.selectedGame.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var launchDialogData by remember { mutableStateOf<Pair<String, RendererType>?>(null) }
    var exportDialogGameTitle by remember { mutableStateOf<String?>(null) }

    // SAF File Picker
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importGame(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is UiEvent.ShowLaunchDialog -> launchDialogData = event.gameTitle to event.renderer
                is UiEvent.ShowExportDialog -> exportDialogGameTitle = event.gameTitle
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GodotBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = BackgroundDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "GodotDroid",
                                color = TextPrimaryDark,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "PC Godot Android Runtime & Exporter",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Filter for all files / octet-stream / executables
                    openDocumentLauncher.launch(arrayOf("*/*"))
                },
                containerColor = GodotBlue,
                contentColor = BackgroundDark,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Import Game")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = GodotBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Inspecting Godot binary structure...",
                            color = TextPrimaryDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (games.isEmpty()) {
                // Empty Library State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(SurfaceCardDark)
                            .border(1.dp, SurfaceVariantDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint = GodotBlue,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "No Godot Games Imported",
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap '+' to import a PC Godot game (.exe or .pck).\nGodotDroid inspects the binary, detects the engine version, extracts project configs, and checks Android compatibility.",
                        color = TextSecondaryDark,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            } else {
                // Games List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(games, key = { it.id }) { game ->
                        GameCard(
                            game = game,
                            onClick = { viewModel.selectGame(game) }
                        )
                    }
                }
            }

            // BottomSheet Inspector
            selectedGame?.let { game ->
                GameInspectorSheet(
                    game = game,
                    sheetState = sheetState,
                    onDismiss = { viewModel.selectGame(null) },
                    onRendererSelected = { renderer ->
                        viewModel.updateRendererOverride(game, renderer)
                    },
                    onExtractPck = { viewModel.extractStandalonePck(game) },
                    onLaunchGame = { viewModel.onLaunchGameClicked(game) },
                    onExportApk = { viewModel.onExportApkClicked(game) },
                    onDeleteGame = { viewModel.deleteGame(game) }
                )
            }

            // Launch Game Preview Dialog (Phase 2 Entry Point)
            launchDialogData?.let { (title, renderer) ->
                AlertDialog(
                    onDismissRequest = { launchDialogData = null },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = GodotBlue) },
                    title = { Text("Launch Game (Phase 2)") },
                    text = {
                        Text(
                            "Game: $title\nSelected Renderer: ${renderer.displayName}\n\n" +
                                    "Phase 1 inspection and scaffolding complete! In Phase 2, the isolated :godot_runner process host will load libgodot_android.so and boot the game with automatic touch controls overlay.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { launchDialogData = null }) {
                            Text("OK", color = GodotBlue)
                        }
                    },
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimaryDark,
                    textContentColor = TextSecondaryDark
                )
            }

            // Export Standalone APK Preview Dialog (Phase 4 Entry Point)
            exportDialogGameTitle?.let { title ->
                AlertDialog(
                    onDismissRequest = { exportDialogGameTitle = null },
                    icon = { Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = AccentCyan) },
                    title = { Text("Export Standalone APK (Phase 4)") },
                    text = {
                        Text(
                            "Game: $title\n\n" +
                                    "Phase 4 Standalone APK repackager interface scaffolded in :apk-exporter. This will package the inspected PCK, inject patched arm64-v8a native extensions, configure AndroidManifest.xml, and produce an installable Android APK.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { exportDialogGameTitle = null }) {
                            Text("OK", color = AccentCyan)
                        }
                    },
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimaryDark,
                    textContentColor = TextSecondaryDark
                )
            }
        }
    }
}
