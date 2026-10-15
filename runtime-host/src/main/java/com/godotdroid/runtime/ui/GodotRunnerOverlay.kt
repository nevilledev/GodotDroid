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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // Virtual Touch Controls Overlay
            AnimatedVisibility(
                visible = isOverlayVisible || isEditMode,
                enter = fadeIn(),
                exit = fadeOut()
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
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
    }
}
