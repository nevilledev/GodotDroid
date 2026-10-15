package com.godotdroid.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.data.SavedGameItem
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SurfaceCardDark
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.TextSecondaryDark
import com.godotdroid.ui.theme.TextTertiaryDark

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameCard(
    game: SavedGameItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCardDark)
            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Game Icon / Robot Placeholder
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceVariantDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = GodotBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.report.gameTitle,
                        color = TextPrimaryDark,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${game.originalFileName} • ${formatFileSize(game.fileSize)}",
                        color = TextTertiaryDark,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Inspect",
                    tint = TextSecondaryDark,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Badges FlowRow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Version badge
                GodotVersionBadge(summary = game.report.pckHeader.summaryBadge)

                // Renderer badge
                RendererBadge(rendererType = game.selectedRenderer)

                // Plugin status badge
                PluginStatusBadge(
                    missingCount = game.report.missingAndroidPlugins.size,
                    hasSteam = game.report.hasGodotSteam
                )

                // .NET badge
                if (game.report.usesDotNet) {
                    DotNetBadge()
                }

                // Embedded EXE badge
                if (game.report.isEmbeddedExe) {
                    StatusBadge(
                        text = "Embedded EXE",
                        textColor = Color(0xFFB0BEC5),
                        backgroundColor = SurfaceVariantDark
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return "%.1f %sB".format(bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
