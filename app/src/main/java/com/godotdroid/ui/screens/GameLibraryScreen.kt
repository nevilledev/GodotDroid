package com.godotdroid.ui.screens

import android.content.Context
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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.data.SavedGameItem
import com.godotdroid.pck.model.RendererType
import com.godotdroid.ui.components.CoreManagerSheet
import com.godotdroid.ui.components.GameCard
import com.godotdroid.ui.components.GameInspectorSheet
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.BackgroundDark
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.components.ApkExportSheet
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark
import com.godotdroid.ui.theme.WarningAmber
import com.godotdroid.ui.viewmodel.GameLibraryViewModel
import com.godotdroid.ui.viewmodel.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val games by viewModel.games.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val selectedGame by viewModel.selectedGame.collectAsState()

    val installedCores by viewModel.installedCores.collectAsState()
    val isDownloadingCore by viewModel.isDownloadingCore.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val registryCores by viewModel.registryCores.collectAsState()
    val isSyncingManifest by viewModel.isSyncingManifest.collectAsState()
    val coreErrorMessage by viewModel.coreErrorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val exportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showCoreManagerSheet by remember { mutableStateOf(false) }
    var promptInstallData by remember { mutableStateOf<UiEvent.PromptCoreInstall?>(null) }
    var gameToExport by remember { mutableStateOf<SavedGameItem?>(null) }

    // SAF File Picker for Games (.exe, .pck)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importGame(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is UiEvent.ShowLaunchDialog -> {}
                is UiEvent.ShowExportDialog -> gameToExport = selectedGame
                is UiEvent.PromptCoreInstall -> promptInstallData = event
                is UiEvent.LaunchGame -> {
                    promptInstallData = null
                    context.startActivity(event.intent)
                }
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
                                text = "PC Godot Android Runtime",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showCoreManagerSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = "Core Manager",
                            tint = if (installedCores.isNotEmpty()) AccentCyan else TextSecondaryDark
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
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
                    onLaunchGame = { preset, injectSteamStub ->
                        viewModel.launchGame(context, game, preset, injectSteamStub)
                    },
                    onExportApk = { gameToExport = game },
                    onDeleteGame = { viewModel.deleteGame(game) }
                )
            }

            // Core Manager Sheet
            if (showCoreManagerSheet) {
                CoreManagerSheet(
                    installedCores = installedCores ?: emptyList(),
                    registryCores = registryCores ?: emptyList(),
                    downloadProgress = downloadProgress,
                    isDownloading = isDownloadingCore,
                    sheetState = coreSheetState,
                    onDismiss = { showCoreManagerSheet = false },
                    onImportTemplate = { uri, versionKey ->
                        viewModel.importCoreFromTemplate(uri, versionKey)
                    },
                    onDownloadCore = { descriptor ->
                        viewModel.downloadCore(descriptor)
                    },
                    onDeleteCore = { versionKey ->
                        viewModel.deleteCore(versionKey)
                    },
                    currentRegistryUrl = viewModel.getRegistryBaseUrl(),
                    isSyncing = isSyncingManifest,
                    onSyncRegistry = { url ->
                        viewModel.syncRemoteRegistry(url)
                    },
                    errorMessage = coreErrorMessage,
                    onClearError = { viewModel.clearCoreError() }
                )
            }

            // Prompt Core Install Dialog
            promptInstallData?.let { prompt ->
                if (isDownloadingCore && downloadProgress != null) {
                    AlertDialog(
                        onDismissRequest = { /* Modal during active auto-download */ },
                        icon = {
                            CircularProgressIndicator(
                                color = GodotBlue,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        title = {
                            Text(
                                "Downloading Godot ${prompt.versionKey} Core...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = downloadProgress?.currentStage ?: "Preparing download...",
                                    color = TextSecondaryDark,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { ((downloadProgress?.percentage ?: 0) / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = GodotBlue,
                                    trackColor = SurfaceVariantDark
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Game \"${prompt.gameTitle}\" will launch automatically once installed.",
                                    color = AccentCyan,
                                    fontSize = 11.sp
                                )
                            }
                        },
                        confirmButton = {},
                        containerColor = SurfaceDark,
                        titleContentColor = TextPrimaryDark,
                        textContentColor = TextSecondaryDark
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { promptInstallData = null },
                        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber) },
                        title = {
                            Text(
                                "Godot ${prompt.versionKey} Core Required",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Game \"${prompt.gameTitle}\" was built with Godot ${prompt.versionKey} bytecode.\n\n" +
                                            "To run on Android, GodotDroid requires the matching Godot ${prompt.versionKey} ARM64 runtime core.",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = TextSecondaryDark
                                )
                                if (prompt.fallbackCore != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "⚡ You have Godot ${prompt.fallbackCore.versionKey} core installed, which may run this game.",
                                        fontSize = 12.sp,
                                        color = AccentCyan,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.autoDownloadAndLaunch(
                                            context = context,
                                            game = prompt.game,
                                            versionKey = prompt.versionKey,
                                            controlPreset = prompt.controlPreset,
                                            injectSteamStub = prompt.injectSteamStub
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GodotBlue),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RocketLaunch,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Auto-Download Godot ${prompt.versionKey} & Launch",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                if (prompt.fallbackCore != null) {
                                    OutlinedButton(
                                        onClick = {
                                            val data = prompt
                                            promptInstallData = null
                                            viewModel.launchWithInstalledCore(
                                                context = context,
                                                game = data.game,
                                                installedCore = data.fallbackCore,
                                                controlPreset = data.controlPreset,
                                                injectSteamStub = data.injectSteamStub
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = AccentCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Try Launching with Godot ${prompt.fallbackCore.versionKey} Core",
                                            fontSize = 12.sp,
                                            color = AccentCyan
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(onClick = {
                                        promptInstallData = null
                                        viewModel.selectGame(null)
                                        showCoreManagerSheet = true
                                    }) {
                                        Text("Open Core Manager", color = GodotBlue, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TextButton(onClick = { promptInstallData = null }) {
                                        Text("Cancel", color = TextSecondaryDark, fontSize = 12.sp)
                                    }
                                }
                            }
                        },
                        containerColor = SurfaceDark,
                        titleContentColor = TextPrimaryDark,
                        textContentColor = TextSecondaryDark
                    )
                }
            }

            // Export Standalone APK Sheet
            val targetGame = gameToExport
            if (targetGame != null) {
                ApkExportSheet(
                    game = targetGame,
                    sheetState = exportSheetState,
                    coreManager = viewModel.coreManager,
                    onDismiss = { gameToExport = null }
                )
            }
        }
    }
}
