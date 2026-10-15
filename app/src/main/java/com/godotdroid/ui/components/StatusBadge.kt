package com.godotdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.pck.model.RendererType
import com.godotdroid.ui.theme.AccentCyan
import com.godotdroid.ui.theme.ErrorRed
import com.godotdroid.ui.theme.ErrorRedBg
import com.godotdroid.ui.theme.GodotBlue
import com.godotdroid.ui.theme.SuccessGreen
import com.godotdroid.ui.theme.SuccessGreenBg
import com.godotdroid.ui.theme.SurfaceVariantDark
import com.godotdroid.ui.theme.TextPrimaryDark
import com.godotdroid.ui.theme.WarningAmber
import com.godotdroid.ui.theme.WarningAmberBg

@Composable
fun StatusBadge(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color = textColor.copy(alpha = 0.4f),
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun GodotVersionBadge(summary: String, modifier: Modifier = Modifier) {
    StatusBadge(
        text = summary,
        textColor = AccentCyan,
        backgroundColor = SurfaceVariantDark,
        borderColor = GodotBlue.copy(alpha = 0.5f),
        icon = Icons.Default.Info,
        modifier = modifier
    )
}

@Composable
fun RendererBadge(rendererType: RendererType, modifier: Modifier = Modifier) {
    when (rendererType) {
        RendererType.FORWARD_PLUS -> {
            StatusBadge(
                text = "Forward+ (Desktop)",
                textColor = WarningAmber,
                backgroundColor = WarningAmberBg,
                icon = Icons.Default.Warning,
                modifier = modifier
            )
        }
        RendererType.MOBILE -> {
            StatusBadge(
                text = "Vulkan Mobile",
                textColor = SuccessGreen,
                backgroundColor = SuccessGreenBg,
                icon = Icons.Default.CheckCircle,
                modifier = modifier
            )
        }
        RendererType.GL_COMPATIBILITY -> {
            StatusBadge(
                text = "GL Compatibility",
                textColor = SuccessGreen,
                backgroundColor = SuccessGreenBg,
                icon = Icons.Default.CheckCircle,
                modifier = modifier
            )
        }
        RendererType.UNKNOWN -> {
            StatusBadge(
                text = "Auto / Default",
                textColor = TextPrimaryDark,
                backgroundColor = SurfaceVariantDark,
                modifier = modifier
            )
        }
    }
}

@Composable
fun PluginStatusBadge(
    missingCount: Int,
    hasSteam: Boolean,
    modifier: Modifier = Modifier
) {
    when {
        missingCount > 0 -> {
            StatusBadge(
                text = "Missing $missingCount Android .so",
                textColor = ErrorRed,
                backgroundColor = ErrorRedBg,
                icon = Icons.Default.Warning,
                modifier = modifier
            )
        }
        hasSteam -> {
            StatusBadge(
                text = "Steam (Needs Stub)",
                textColor = WarningAmber,
                backgroundColor = WarningAmberBg,
                icon = Icons.Default.Warning,
                modifier = modifier
            )
        }
        else -> {
            StatusBadge(
                text = "Android Compatible",
                textColor = SuccessGreen,
                backgroundColor = SuccessGreenBg,
                icon = Icons.Default.CheckCircle,
                modifier = modifier
            )
        }
    }
}

@Composable
fun DotNetBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "C# / .NET",
        textColor = WarningAmber,
        backgroundColor = WarningAmberBg,
        icon = Icons.Default.Warning,
        modifier = modifier
    )
}
