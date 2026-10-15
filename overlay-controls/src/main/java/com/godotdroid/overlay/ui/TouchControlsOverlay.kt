package com.godotdroid.overlay.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godotdroid.overlay.input.ITouchInputDispatcher
import com.godotdroid.overlay.model.ControlElementType
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.overlay.model.InputBinding
import com.godotdroid.overlay.model.MouseEmulationMode
import com.godotdroid.overlay.model.TouchControlLayout
import com.godotdroid.overlay.model.VirtualControlElement
import java.util.UUID

@Composable
fun TouchControlsOverlay(
    layout: TouchControlLayout,
    dispatcher: ITouchInputDispatcher?,
    isEditMode: Boolean,
    onSaveLayout: (TouchControlLayout) -> Unit,
    onExitEditMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentLayout by remember(layout) { mutableStateOf(layout) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var showPresetMenu by remember { mutableStateOf(false) }

    // Virtual mouse cursor position (normX, normY)
    var cursorNormX by remember { mutableFloatStateOf(0.5f) }
    var cursorNormY by remember { mutableFloatStateOf(0.5f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        // 1. PC Trackpad Layer (Active in Play Mode when TRACKPAD_RELATIVE is enabled)
        if (!isEditMode && currentLayout.mouseMode == MouseEmulationMode.TRACKPAD_RELATIVE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                dispatcher?.sendMouseButton(1, true)
                                dispatcher?.sendMouseButton(1, false)
                            }
                        )
                    }
                    .pointerInput(currentLayout.trackpadSensitivity) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val sensitivity = currentLayout.trackpadSensitivity
                            val dx = dragAmount.x * sensitivity
                            val dy = dragAmount.y * sensitivity

                            cursorNormX = (cursorNormX + dx / widthPx).coerceIn(0f, 1f)
                            cursorNormY = (cursorNormY + dy / heightPx).coerceIn(0f, 1f)

                            dispatcher?.sendMouseMotion(dx, dy, cursorNormX, cursorNormY)
                        }
                    }
            )

            // Render Virtual Mouse Cursor
            val cursorOffset = with(density) {
                IntOffset(
                    (cursorNormX * widthPx).toInt() - 6.dp.roundToPx(),
                    (cursorNormY * heightPx).toInt() - 6.dp.roundToPx()
                )
            }
            Box(
                modifier = Modifier
                    .offset { cursorOffset }
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f))
                    .border(1.5.dp, Color(0xFF478CBF), CircleShape)
            )
        }

        // 2. Alignment Grid Lines in Edit Mode
        if (isEditMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepX = size.width / 10f
                val stepY = size.height / 10f
                val gridColor = Color.White.copy(alpha = 0.08f)
                for (i in 1..9) {
                    drawLine(gridColor, Offset(stepX * i, 0f), Offset(stepX * i, size.height), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, stepY * i), Offset(size.width, stepY * i), strokeWidth = 1f)
                }
            }
        }

        // 3. Render Virtual Control Elements
        for (element in currentLayout.elements) {
            if (!element.visible && !isEditMode) continue

            val elemPxX = element.normX * widthPx
            val elemPxY = element.normY * heightPx
            val isSelected = isEditMode && selectedElementId == element.id

            val elementOffset = with(density) {
                IntOffset(elemPxX.toInt() - 30.dp.roundToPx(), elemPxY.toInt() - 30.dp.roundToPx())
            }

            Box(
                modifier = Modifier
                    .offset { elementOffset }
                    .then(
                        if (isEditMode) {
                            Modifier
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) Color(0xFF4CE0D2) else Color.White.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedElementId = element.id }
                                .pointerInput(element.id) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        selectedElementId = element.id
                                        val newNormX = ((element.normX * widthPx + dragAmount.x) / widthPx).coerceIn(0.05f, 0.95f)
                                        val newNormY = ((element.normY * heightPx + dragAmount.y) / heightPx).coerceIn(0.05f, 0.95f)
                                        val updatedList = currentLayout.elements.map { el ->
                                            if (el.id == element.id) el.copy(normX = newNormX, normY = newNormY) else el
                                        }
                                        currentLayout = currentLayout.copy(elements = updatedList)
                                    }
                                }
                        } else Modifier
                    )
            ) {
                when (element.type) {
                    ControlElementType.JOYSTICK_LEFT, ControlElementType.JOYSTICK_RIGHT -> {
                        VirtualJoystick(element = element, dispatcher = dispatcher, isEditMode = isEditMode)
                    }
                    ControlElementType.DPAD -> {
                        VirtualDpad(element = element, dispatcher = dispatcher, isEditMode = isEditMode)
                    }
                    ControlElementType.BUTTON, ControlElementType.MOUSE_BUTTON -> {
                        VirtualButtonView(element = element, dispatcher = dispatcher, isEditMode = isEditMode)
                    }
                }
            }
        }

        // 4. In-Game Layout Editor HUD & Inspector Card
        if (isEditMode) {
            // Top Editor Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EDIT CONTROLS",
                        color = Color(0xFF4CE0D2),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    // Preset Selector Button
                    Box {
                        OutlinedButton(
                            onClick = { showPresetMenu = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = currentLayout.presetName, fontSize = 11.sp)
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
                                    text = { Text(presetName) },
                                    onClick = {
                                        currentLayout = DefaultControlPresets.createPreset(presetName, currentLayout.gameId)
                                        showPresetMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Add Custom Button
                    OutlinedButton(
                        onClick = {
                            val newBtn = VirtualControlElement(
                                id = "btn_${UUID.randomUUID().toString().take(6)}",
                                label = "KEY",
                                type = ControlElementType.BUTTON,
                                normX = 0.5f,
                                normY = 0.5f,
                                binding = InputBinding.KeyBinding(KeyEvent.KEYCODE_E, 69, "E")
                            )
                            currentLayout = currentLayout.copy(elements = currentLayout.elements + newBtn)
                            selectedElementId = newBtn.id
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add Button", fontSize = 11.sp)
                    }
                }

                Row {
                    Button(
                        onClick = {
                            onSaveLayout(currentLayout)
                            onExitEditMode()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF478CBF)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Save & Apply", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onExitEditMode,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.7f))
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }

            // Bottom Inspector for Selected Control
            val selected = currentLayout.elements.find { it.id == selectedElementId }
            if (selected != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181A26).copy(alpha = 0.95f))
                        .border(1.dp, Color(0xFF282C40), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column(modifier = Modifier.width(360.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Element: ${selected.label} (${selected.type.name})",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            IconButton(
                                onClick = {
                                    currentLayout = currentLayout.copy(
                                        elements = currentLayout.elements.filter { it.id != selected.id }
                                    )
                                    selectedElementId = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFFF5370),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Scale Slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Scale", color = Color(0xFF9EABC2), fontSize = 11.sp, modifier = Modifier.width(50.dp))
                            Slider(
                                value = selected.scale,
                                onValueChange = { newScale ->
                                    currentLayout = currentLayout.copy(
                                        elements = currentLayout.elements.map { if (it.id == selected.id) it.copy(scale = newScale) else it }
                                    )
                                },
                                valueRange = 0.6f..1.8f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF478CBF), activeTrackColor = Color(0xFF478CBF))
                            )
                        }

                        // Opacity Slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Opacity", color = Color(0xFF9EABC2), fontSize = 11.sp, modifier = Modifier.width(50.dp))
                            Slider(
                                value = selected.opacity,
                                onValueChange = { newOp ->
                                    currentLayout = currentLayout.copy(
                                        elements = currentLayout.elements.map { if (it.id == selected.id) it.copy(opacity = newOp) else it }
                                    )
                                },
                                valueRange = 0.2f..1.0f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF4CE0D2), activeTrackColor = Color(0xFF4CE0D2))
                            )
                        }
                    }
                }
            }
        }
    }
}
