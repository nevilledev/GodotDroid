package com.godotdroid.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.runtime.core.CoreDescriptor
import com.godotdroid.runtime.core.DownloadProgress
import com.godotdroid.runtime.core.InstalledCore
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.ErrorRed
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SuccessGreen
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoreManagerSheet(
    installedCores: List<InstalledCore> = emptyList(),
    registryCores: List<CoreDescriptor> = emptyList(),
    downloadProgress: DownloadProgress? = null,
    isDownloading: Boolean = false,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onImportTemplate: (Uri, String) -> Unit,
    onDownloadCore: (CoreDescriptor) -> Unit,
    onDeleteCore: (String) -> Unit,
    currentRegistryUrl: String = "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest",
    isSyncing: Boolean = false,
    onSyncRegistry: (String) -> Unit = {},
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedImportVersionKey by remember { mutableStateOf("4.3") }
    var registryUrlInput by remember(currentRegistryUrl) { mutableStateOf(currentRegistryUrl) }

    val distinctInstalled = remember(installedCores) { (installedCores ?: emptyList()).distinctBy { it.versionKey } }
    val distinctRegistry = remember(registryCores) { (registryCores ?: emptyList()).distinctBy { it.versionKey } }

    // SAF Picker for local export template (.apk, .tpz, .aar)
    val openTemplateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onImportTemplate(it, selectedImportVersionKey) }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Godot Runtime Cores",
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Multi-version ARM64 engine binaries & W^X Safe Dynamic Loader",
                        color = AccentCyan,
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

            // Error Banner
            if (!errorMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ErrorRed.copy(alpha = 0.15f))
                        .border(1.dp, ErrorRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = ErrorRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = errorMessage,
                            color = TextPrimaryDark,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClearError,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Active Download Progress
            if (isDownloading && downloadProgress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCardDark)
                        .border(1.dp, GodotBlue.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = downloadProgress.currentStage,
                                color = TextPrimaryDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${downloadProgress.percentage}%",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress.percentage / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = GodotBlue,
                            trackColor = SurfaceVariantDark
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Section 1: Installed Cores
            Text(
                text = "INSTALLED CORES (${distinctInstalled.size})",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (distinctInstalled.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCardDark)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "No Godot cores installed yet.\nImport an official Godot Android export template (.apk / .tpz) or download a core from the registry below.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (core in distinctInstalled) {
                        key("installed_${core.versionKey}") {
                            InstalledCoreCard(
                                core = core,
                                onDelete = { onDeleteCore(core.versionKey) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 2: Local Import Action
            Text(
                text = "IMPORT OFFICIAL GODOT TEMPLATE",
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
                Column {
                    Text(
                        text = "Extract libgodot_android.so and classes.dex from official android_release.apk, android_debug.apk, or export_templates.tpz archive.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Quick Version Selector buttons for import
                        val versions = listOf("4.7", "4.6", "4.5", "4.4", "4.3", "4.2", "4.1", "4.0", "3.6", "3.5", "3.4", "3.3", "3.2")
                        for (v in versions) {
                            val isSel = selectedImportVersionKey == v
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) GodotBlue else SurfaceVariantDark)
                                    .clickable { selectedImportVersionKey = v }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "v$v",
                                    color = if (isSel) Color.White else TextSecondaryDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { openTemplateLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(AccentCyan.copy(alpha = 0.5f))
                        )
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Select Export Template File (.apk, .tpz)", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: Available Registry Cores
            Text(
                text = "OFFICIAL REGISTRY CORES",
                color = TextTertiaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Registry Source URL Config Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCardDark)
                    .border(1.dp, SurfaceVariantDark, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "Registry Source URL (GitHub Releases / CDN)",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = registryUrlInput,
                            onValueChange = { registryUrlInput = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextPrimaryDark,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SurfaceVariantDark,
                                unfocusedContainerColor = SurfaceVariantDark,
                                focusedIndicatorColor = GodotBlue,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onSyncRegistry(registryUrlInput) },
                            enabled = !isSyncing && registryUrlInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = GodotBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Godot 4.x Cores
            val godot4Cores = distinctRegistry.filter { it.versionKey.startsWith("4.") }
            if (godot4Cores.isNotEmpty()) {
                Text(
                    text = "Godot 4.x Cores (Vulkan / Compatibility)",
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (descriptor in godot4Cores) {
                        key("g4_${descriptor.versionKey}") {
                            val isAlreadyInstalled = distinctInstalled.any { it.versionKey == descriptor.versionKey }
                            RegistryCoreCard(
                                descriptor = descriptor,
                                isInstalled = isAlreadyInstalled,
                                isDownloading = isDownloading,
                                onDownload = { onDownloadCore(descriptor) },
                                onDelete = { onDeleteCore(descriptor.versionKey) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Godot 3.x Cores
            val godot3Cores = distinctRegistry.filter { it.versionKey.startsWith("3.") }
            if (godot3Cores.isNotEmpty()) {
                Text(
                    text = "Godot 3.x Cores (GLES3 / GLES2 Classic)",
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (descriptor in godot3Cores) {
                        key("g3_${descriptor.versionKey}") {
                            val isAlreadyInstalled = distinctInstalled.any { it.versionKey == descriptor.versionKey }
                            RegistryCoreCard(
                                descriptor = descriptor,
                                isInstalled = isAlreadyInstalled,
                                isDownloading = isDownloading,
                                onDownload = { onDownloadCore(descriptor) },
                                onDelete = { onDeleteCore(descriptor.versionKey) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InstalledCoreCard(
    core: InstalledCore,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCardDark)
            .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Godot ${core.versionKey} (${core.engineVersion})",
                        color = TextPrimaryDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ARM64-v8a • ${core.sourceType} • W^X Read-Only Safe",
                        color = TextTertiaryDark,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ErrorRed
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ErrorRed.copy(alpha = 0.7f))
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove Core",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Remove", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RegistryCoreCard(
    descriptor: CoreDescriptor,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCardDark)
            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = descriptor.displayName,
                        color = TextPrimaryDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (descriptor.isRecommended) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(GodotBlue.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "RECOMMENDED", color = GodotBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Engine v${descriptor.engineVersion} • ${(descriptor.sizeBytes / 1_000_000)} MB",
                    color = TextTertiaryDark,
                    fontSize = 11.sp
                )
            }

            if (isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Installed ✓",
                        color = SuccessGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (onDelete != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Core",
                                tint = ErrorRed.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else if (descriptor.downloadUrl.isBlank()) {
                Text(
                    text = "Not in registry",
                    color = TextTertiaryDark,
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = GodotBlue),
                    modifier = Modifier.height(34.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Get", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
