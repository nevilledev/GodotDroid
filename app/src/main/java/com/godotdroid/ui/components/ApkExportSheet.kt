package com.godotdroid.ui.components

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.content.FileProvider
import com.godotdroid.data.SavedGameItem
import com.godotdroid.exporter.ExportRequest
import com.godotdroid.exporter.StandaloneApkExporter
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.overlay.repository.TouchLayoutRepository
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.core.CoreManager
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApkExportSheet(
    game: SavedGameItem,
    sheetState: SheetState,
    coreManager: CoreManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sanitizedDefaultSlug = game.report.gameTitle
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .ifBlank { "game" }

    var appTitle by remember { mutableStateOf(game.report.gameTitle) }
    var packageName by remember { mutableStateOf("com.godotdroid.game.$sanitizedDefaultSlug") }
    var selectedRenderer by remember { mutableStateOf(game.selectedRenderer) }
    var selectedPreset by remember { mutableStateOf(DefaultControlPresets.PRESET_PC_KEYBOARD) }
    var injectSteamStub by remember { mutableStateOf(game.report.hasGodotSteam) }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var currentStep by remember { mutableStateOf("") }
    var exportedApkFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val isPackageValid = remember(packageName) {
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$").matches(packageName)
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
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.RocketLaunch,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Standalone APK",
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Package ${game.report.gameTitle} as an installable Android app",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = SurfaceVariantDark)
            Spacer(modifier = Modifier.height(16.dp))

            if (!isExporting && exportedApkFile == null) {
                // Configuration Form

                // App Title
                Text(text = "APPLICATION TITLE", color = TextTertiaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = appTitle,
                    onValueChange = { appTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedBorderColor = GodotBlue,
                        unfocusedBorderColor = SurfaceVariantDark
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Package Name
                Text(text = "ANDROID PACKAGE IDENTIFIER", color = TextTertiaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    isError = !isPackageValid,
                    supportingText = {
                        if (!isPackageValid) {
                            Text("Must be a valid format: com.example.game", color = Color(0xFFFF5370), fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedBorderColor = GodotBlue,
                        unfocusedBorderColor = SurfaceVariantDark
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Renderer Mode
                Text(text = "RENDERER MODE OVERRIDE", color = TextTertiaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = selectedRenderer == RendererType.MOBILE,
                        onClick = { selectedRenderer = RendererType.MOBILE },
                        label = { Text("Vulkan Mobile", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GodotBlue,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceCardDark,
                            labelColor = TextSecondaryDark
                        )
                    )
                    FilterChip(
                        selected = selectedRenderer == RendererType.GL_COMPATIBILITY,
                        onClick = { selectedRenderer = RendererType.GL_COMPATIBILITY },
                        label = { Text("OpenGL Compatibility", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GodotBlue,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceCardDark,
                            labelColor = TextSecondaryDark
                        )
                    )
                    FilterChip(
                        selected = selectedRenderer == RendererType.FORWARD_PLUS,
                        onClick = { selectedRenderer = RendererType.FORWARD_PLUS },
                        label = { Text("Forward+", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GodotBlue,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceCardDark,
                            labelColor = TextSecondaryDark
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Touch Controls Preset
                Text(text = "BUNDLED TOUCH CONTROLS PRESET", color = TextTertiaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // GodotSteam Bypass Stub
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceCardDark)
                        .border(1.dp, SurfaceVariantDark, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bundle GodotSteam Bypass Stub", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Embeds GDScript Steam singleton in exported APK", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                        Switch(
                            checked = injectSteamStub,
                            onCheckedChange = { injectSteamStub = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GodotBlue)
                        )
                    }
                }

                if (exportError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = exportError ?: "", color = Color(0xFFFF5370), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Build Button
                Button(
                    onClick = {
                        val versionKey = "${game.report.pckHeader.engineMajor}.${game.report.pckHeader.engineMinor}"
                        val core = coreManager.getCore(versionKey)
                            ?: coreManager.findMatchingCore(game.report.pckHeader.engineMajor, game.report.pckHeader.engineMinor)

                        if (core == null) {
                            exportError = "Godot $versionKey core is not installed. Please install it in Core Manager before exporting."
                            return@Button
                        }

                        isExporting = true
                        exportError = null
                        exportProgress = 0.05f
                        currentStep = "Starting standalone export..."

                        scope.launch {
                            val layoutRepo = TouchLayoutRepository(context)
                            val layout = layoutRepo.getLayout(game.id)

                            val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
                            val outputApk = File(exportsDir, "${sanitizedDefaultSlug}_standalone.apk")
                            val baseApkFile = File(context.applicationInfo.sourceDir)

                            val exporter = StandaloneApkExporter(context)
                            val request = ExportRequest(
                                gameId = game.id,
                                appTitle = appTitle,
                                packageName = packageName,
                                sourceGameFile = File(game.filePath),
                                baseApkFile = baseApkFile,
                                outputSignedApk = outputApk,
                                engineVersionKey = core.versionKey,
                                coreDir = core.coreDir,
                                selectedRenderer = selectedRenderer,
                                controlLayout = layout,
                                injectSteamStub = injectSteamStub
                            )

                            val result = exporter.exportApk(request) { step, progress ->
                                currentStep = step
                                exportProgress = progress
                            }

                            isExporting = false
                            result.onSuccess { file ->
                                exportedApkFile = file
                            }.onFailure { error ->
                                exportError = "Export failed: ${error.message}"
                            }
                        }
                    },
                    enabled = isPackageValid && appTitle.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Icon(imageVector = Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Build & Sign Standalone APK", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else if (isExporting) {
                // Live Export Progress View
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { exportProgress },
                        color = AccentCyan,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = currentStep,
                        color = TextPrimaryDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(exportProgress * 100).toInt()}%",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        color = AccentCyan,
                        trackColor = SurfaceVariantDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            } else if (exportedApkFile != null) {
                // Export Completed State
                val apkFile = exportedApkFile!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "APK Export Complete!",
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${apkFile.name} (${apkFile.length() / (1024 * 1024)} MB)",
                        color = TextSecondaryDark,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Install APK Button
                    Button(
                        onClick = {
                            installApk(context, apkFile)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GodotBlue)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Install Standalone APK", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Share APK Button
                    OutlinedButton(
                        onClick = {
                            shareApk(context, apkFile)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SurfaceVariantDark))
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Share APK", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun installApk(context: Context, apkFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun shareApk(context: Context, apkFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Standalone APK"))
    } catch (_: Exception) {
    }
}
