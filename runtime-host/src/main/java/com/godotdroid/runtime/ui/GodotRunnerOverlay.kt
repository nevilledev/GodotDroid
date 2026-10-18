package com.godotdroid.runtime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.runtime.profiler.PerformanceMonitor
import com.godotdroid.overlay.input.ITouchInputDispatcher
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.overlay.model.TouchControlLayout
import com.godotdroid.overlay.repository.TouchLayoutRepository
import com.godotdroid.overlay.ui.TouchControlsOverlay
import kotlinx.coroutines.launch

/**
 * Top-level Compose host for the interactive touch overlay and floating HUD pill.
 */
@Composable
fun GodotRunnerOverlayScreen(
    gameId: String,
    initialPreset: String,
    inputDispatcher: ITouchInputDispatcher?,
    onExitGame: () -> Unit,
    performanceMetrics: PerformanceMonitor.PerformanceMetrics = PerformanceMonitor.PerformanceMetrics(),
    hasPhysicalGamepad: Boolean = false,
    isControllerActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { TouchLayoutRepository(context) }
    val scope = rememberCoroutineScope()

    var layout by remember { mutableStateOf<TouchControlLayout?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var isOverlayVisible by remember { mutableStateOf(true) }
    var isHudExpanded by remember { mutableStateOf(false) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var showLogcatViewer by remember { mutableStateOf(false) }

    // Load saved or preset layout
    LaunchedEffect(gameId) {
        val loaded = repository.getLayout(gameId)
        if (loaded.presetName != initialPreset && initialPreset.isNotBlank()) {
            layout = repository.resetToPreset(gameId, initialPreset)
        } else {
            layout = loaded
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val current = layout
        if (current != null) {
            // Virtual Touch Controls Overlay (auto-dimmed when physical controller is in use)
            AnimatedVisibility(
                visible = isOverlayVisible || isEditMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isControllerActive && !isEditMode) 0.15f else 1.0f)
                ) {
                    TouchControlsOverlay(
                        layout = current,
                        dispatcher = inputDispatcher,
                        isEditMode = isEditMode,
                        onSaveLayout = { updated ->
                            layout = updated
                            scope.launch { repository.saveLayout(updated) }
                        },
                        onExitEditMode = { isEditMode = false }
                    )
                }
            }
        }

        // Floating HUD Pill (Top-Right)
        if (!isEditMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF141622).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF2E344A), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Real-time FPS + RAM Profiler readout
                    Text(
                        text = performanceMetrics.formattedSummary,
                        color = Color(0xFF4CE0D2),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // Hardware Gamepad connection badge
                    if (hasPhysicalGamepad) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF00E676).copy(alpha = 0.2f))
                                .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PAD",
                                color = Color(0xFF00E676),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    // 1-Tap Logcat Viewer HUD pill button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF282C40).copy(alpha = 0.85f))
                            .clickable { showLogcatViewer = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LOGS",
                            color = Color(0xFF4CE0D2),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    if (isHudExpanded) {
                        // Preset Switcher
                        Box {
                            IconButton(
                                onClick = { showPresetMenu = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Switch Preset",
                                    tint = Color(0xFF478CBF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showPresetMenu,
                                onDismissRequest = { showPresetMenu = false }
                            ) {
                                listOf(
                                    DefaultControlPresets.PRESET_PC_KEYBOARD,
                                    DefaultControlPresets.PRESET_GAMEPAD,
                                    DefaultControlPresets.PRESET_POINT_AND_CLICK
                                ).forEach { presetName ->
                                    DropdownMenuItem(
                                        text = { Text(presetName, fontSize = 12.sp) },
                                        onClick = {
                                            showPresetMenu = false
                                            scope.launch {
                                                val newLayout = repository.resetToPreset(gameId, presetName)
                                                layout = newLayout
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Edit Controls Toggle
                        IconButton(
                            onClick = {
                                isEditMode = true
                                isOverlayVisible = true
                                isHudExpanded = false
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Controls",
                                tint = Color(0xFF4CE0D2),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Visibility Toggle
                        IconButton(
                            onClick = { isOverlayVisible = !isOverlayVisible },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isOverlayVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Visibility",
                                tint = if (isOverlayVisible) Color.White else Color(0xFF9EABC2),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Exit Game Button
                        IconButton(
                            onClick = onExitGame,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Exit Game",
                                tint = Color(0xFFFF5370),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Main Pill Toggle Handle
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (isHudExpanded) Color(0xFF282C40) else Color.Transparent)
                            .clickable { isHudExpanded = !isHudExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHudExpanded) Icons.Default.Close else Icons.Default.SportsEsports,
                            contentDescription = "HUD Menu",
                            tint = if (isHudExpanded) Color.White else Color(0xFF478CBF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Real-Time In-Game Logcat Viewer Dialog
        if (showLogcatViewer) {
            InGameLogcatViewerDialog(onDismiss = { showLogcatViewer = false })
        }
    }
}

@Composable
private fun InGameLogcatViewerDialog(
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var logText by remember { mutableStateOf("Loading logcat...") }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var copyNotice by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        isLoading = true
        copyNotice = false
        logText = withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "logcat", "-d", "-t", "200", "-s",
                    "godot:*", "Godot:*", "GodotLib:*", "DynamicGodotLoader:*", "RuntimePatchInjector:*", "AndroidRuntime:E", "libc:*"
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                if (out.isBlank()) "(No Godot engine logs captured yet)" else out
            } catch (e: Exception) {
                "Failed to read logs: ${e.message}"
            }
        }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141622))
                .border(1.dp, Color(0xFF2E344A), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {} // Prevent backdrop click-through dismiss
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Live Engine Logs",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLoading) "Refreshing..." else if (copyNotice) "Copied to clipboard!" else "logcat -d",
                        color = if (copyNotice) Color(0xFF00E676) else Color(0xFF9EABC2),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(logText))
                            copyNotice = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = Color(0xFF4CE0D2),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { refreshKey++ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Logs",
                            tint = Color(0xFF478CBF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Log Content Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A0C14))
                    .border(1.dp, Color(0xFF1E2235), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF4CE0D2), modifier = Modifier.size(28.dp))
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        logText.lineSequence().forEach { line ->
                            val color = when {
                                line.contains(" E ") || line.contains(" E/") || line.contains("FATAL") || line.contains("Error") -> Color(0xFFFF5370)
                                line.contains(" W ") || line.contains(" W/") || line.contains("Warning") -> Color(0xFFFFCB6B)
                                line.contains("[GodotDroid]") -> Color(0xFF4CE0D2)
                                line.contains("godot", ignoreCase = true) -> Color(0xFF82AAFF)
                                else -> Color(0xFFC0CAD8)
                            }
                            Text(
                                text = line,
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
