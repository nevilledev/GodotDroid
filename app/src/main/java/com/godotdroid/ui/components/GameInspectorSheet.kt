package com.godotdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.godotdroid.data.SavedGameItem
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.pck.model.RendererType
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.ErrorRed
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark
import com.godotdroid.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameInspectorSheet(
    game: SavedGameItem,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onRendererSelected: (RendererType) -> Unit,
    onExtractPck: () -> Unit,
    onLaunchGame: (preset: String, injectSteamStub: Boolean) -> Unit,
    onExportApk: () -> Unit,
    onDeleteGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPreset by remember(game.id) {
        mutableStateOf(DefaultControlPresets.PRESET_PC_KEYBOARD)
    }
    var injectSteamStub by remember(game.id) {
        mutableStateOf(game.report.hasGodotSteam)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = null,
        modifier = modifier
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(scrollState)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.report.gameTitle,
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = game.report.pckHeader.summaryBadge,
                        color = AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = SurfaceVariantDark)
            Spacer(modifier = Modifier.height(16.dp))

            // Binary & Package Details Card
            Text(
                text = "PACKAGE SPECIFICATION & BINARY OFFSETS",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCardDark)
                    .border(1.dp, SurfaceVariantDark, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Container Type", if (game.report.isEmbeddedExe) "Embedded Windows PE (.exe)" else "Standalone Package (.pck)")
                    DetailRow("PCK Start Offset", "0x${game.report.pckStartOffset.toString(16).uppercase()} (${game.report.pckStartOffset} bytes)")
                    DetailRow("Format Version", "v${game.report.pckHeader.formatVersion} (${game.report.pckHeader.format.description})")
                    DetailRow("Engine Target", game.report.pckHeader.engineVersionString)
                    DetailRow("Virtual Files Count", "${game.report.totalFilesCount} files")
                    DetailRow("Main Scene", game.report.mainScene ?: "Auto / Default")
                    val iconPath = game.report.iconPath
                    if (iconPath != null) {
                        DetailRow("App Icon Path", iconPath)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Renderer Mode Selector
            Text(
                text = "RENDERER MODE OVERRIDE",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Detected Default: ${game.report.renderingMethod ?: "Unknown"} (${game.report.detectedRenderer.displayName})",
                color = TextSecondaryDark,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                RendererOptionChip(
                    renderer = RendererType.MOBILE,
                    label = "Vulkan Mobile",
                    isSelected = game.selectedRenderer == RendererType.MOBILE,
                    onSelect = { onRendererSelected(RendererType.MOBILE) }
                )
                RendererOptionChip(
                    renderer = RendererType.GL_COMPATIBILITY,
                    label = "OpenGL Compatibility",
                    isSelected = game.selectedRenderer == RendererType.GL_COMPATIBILITY,
                    onSelect = { onRendererSelected(RendererType.GL_COMPATIBILITY) }
                )
                RendererOptionChip(
                    renderer = RendererType.FORWARD_PLUS,
                    label = "Forward+ (Desktop)",
                    isSelected = game.selectedRenderer == RendererType.FORWARD_PLUS,
                    onSelect = { onRendererSelected(RendererType.FORWARD_PLUS) }
                )
            }

            if (game.selectedRenderer == RendererType.FORWARD_PLUS) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceCardDark)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Forward+ uses clustered Vulkan PC features that may crash or perform poorly on mobile GPUs. Vulkan Mobile is recommended.",
                        color = WarningAmber,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Plugins & Compatibility Diagnostics
            Text(
                text = "DETECTED EXTENSIONS & DEPENDENCIES (${game.report.plugins.size})",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (game.report.plugins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCardDark)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Pure GDScript / Native Godot shaders. No external native C++ .gdextension plugins required.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (plugin in game.report.plugins) {
                        PluginItemCard(plugin)
                    }
                }
            }

            if (game.report.hasGodotSteam) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceCardDark)
                        .border(1.dp, GodotBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = GodotBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GodotSteam detected: GodotDroid Phase 3 will auto-inject the Android Steam stub library to avoid crash loops.",
                            color = TextPrimaryDark,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Touch Controls Preset Selector
            Text(
                text = "TOUCH CONTROLS PRESET",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Virtual controls layout applied on game launch",
                color = TextSecondaryDark,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = selectedPreset == DefaultControlPresets.PRESET_PC_KEYBOARD,
                    onClick = { selectedPreset = DefaultControlPresets.PRESET_PC_KEYBOARD },
                    label = { Text("PC WASD + Mouse", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GodotBlue,
                        selectedLabelColor = Color.White,
                        containerColor = SurfaceCardDark,
                        labelColor = TextSecondaryDark
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedPreset == DefaultControlPresets.PRESET_PC_KEYBOARD,
                        borderColor = if (selectedPreset == DefaultControlPresets.PRESET_PC_KEYBOARD) GodotBlue else SurfaceVariantDark
                    )
                )
                FilterChip(
                    selected = selectedPreset == DefaultControlPresets.PRESET_GAMEPAD,
                    onClick = { selectedPreset = DefaultControlPresets.PRESET_GAMEPAD },
                    label = { Text("Standard Gamepad", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GodotBlue,
                        selectedLabelColor = Color.White,
                        containerColor = SurfaceCardDark,
                        labelColor = TextSecondaryDark
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedPreset == DefaultControlPresets.PRESET_GAMEPAD,
                        borderColor = if (selectedPreset == DefaultControlPresets.PRESET_GAMEPAD) GodotBlue else SurfaceVariantDark
                    )
                )
                FilterChip(
                    selected = selectedPreset == DefaultControlPresets.PRESET_POINT_AND_CLICK,
                    onClick = { selectedPreset = DefaultControlPresets.PRESET_POINT_AND_CLICK },
                    label = { Text("Trackpad Point & Click", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GodotBlue,
                        selectedLabelColor = Color.White,
                        containerColor = SurfaceCardDark,
                        labelColor = TextSecondaryDark
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedPreset == DefaultControlPresets.PRESET_POINT_AND_CLICK,
                        borderColor = if (selectedPreset == DefaultControlPresets.PRESET_POINT_AND_CLICK) GodotBlue else SurfaceVariantDark
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Compatibility & GodotSteam Stubs
            Text(
                text = "COMPATIBILITY & STUBS",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCardDark)
                    .border(1.dp, SurfaceVariantDark, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inject GodotSteam Bypass Stub",
                            color = TextPrimaryDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (game.report.hasGodotSteam) {
                                "GodotSteam detected. Injects GDScript Steam autoload stub to prevent crashes."
                            } else {
                                "Inject dummy Steam singleton in case game attempts to call Steamworks API."
                            },
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = injectSteamStub,
                        onCheckedChange = { injectSteamStub = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GodotBlue,
                            uncheckedThumbColor = TextTertiaryDark,
                            uncheckedTrackColor = SurfaceVariantDark
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SurfaceVariantDark)
            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Text(
                text = "EXECUTION & EXPORT ACTIONS",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Extract Standalone PCK button
            OutlinedButton(
                onClick = onExtractPck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AccentCyan.copy(alpha = 0.6f)))
            ) {
                Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Extract Standalone .pck", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Launch Game
            Button(
                onClick = { onLaunchGame(selectedPreset, injectSteamStub) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GodotBlue)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Launch Game", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Export Standalone APK (Phase 4)
            OutlinedButton(
                onClick = onExportApk,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SurfaceVariantDark))
            ) {
                Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Export Standalone APK (Phase 4)", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Delete game from library
            OutlinedButton(
                onClick = onDeleteGame,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(ErrorRed.copy(alpha = 0.5f)))
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Remove From Library", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondaryDark, fontSize = 12.sp)
        Text(
            text = value,
            color = TextPrimaryDark,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RendererOptionChip(
    renderer: RendererType,
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onSelect,
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = GodotBlue,
            selectedLabelColor = Color.White,
            containerColor = SurfaceCardDark,
            labelColor = TextSecondaryDark
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderColor = if (isSelected) GodotBlue else SurfaceVariantDark
        )
    )
}

@Composable
private fun PluginItemCard(plugin: com.godotdroid.pck.model.PluginDependency) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceCardDark)
            .border(
                1.dp,
                if (!plugin.hasAndroidArm64 && plugin.desktopLibraries.isNotEmpty()) ErrorRed.copy(alpha = 0.4f) else SurfaceVariantDark,
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = plugin.name,
                    color = TextPrimaryDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                if (plugin.hasAndroidArm64) {
                    Text(
                        text = "Android arm64 OK",
                        color = com.godotdroid.ui.theme.SuccessGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (plugin.desktopLibraries.isNotEmpty()) {
                    Text(
                        text = "Missing Android arm64 .so",
                        color = ErrorRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = plugin.configPath,
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
