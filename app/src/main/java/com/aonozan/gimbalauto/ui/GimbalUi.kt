/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.camera.video.*
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aonozan.gimbalauto.utils.GimbalPreset
import com.aonozan.gimbalauto.utils.PresetManager
import com.aonozan.gimbalauto.utils.WaypointData
import com.aonozan.gimbalauto.viewmodel.GimbalViewModel
import java.io.File

@OptIn(ExperimentalAnimationApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GimbalUi(viewModel: GimbalViewModel) {
    val context = LocalContext.current
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val telemetry by viewModel.bleManager.telemetry.collectAsState()
    val batteryPercent by viewModel.bleManager.batteryPercent.collectAsState()
    val motorLoad by viewModel.bleManager.motorLoad.collectAsState()
    val gimbalMode by viewModel.bleManager.gimbalMode.collectAsState()
    val waypoints by viewModel.waypoints.collectAsState()
    val totalTime by viewModel.totalTime.collectAsState()
    val useDelay by viewModel.useDelay.collectAsState()
    val useSound by viewModel.useSound.collectAsState()
    
    val useCamera by viewModel.autoRecord.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val countdown by viewModel.countdown.collectAsState()
    
    val cameraLens by viewModel.cameraLens.collectAsState()
    val cameraMode by viewModel.cameraMode.collectAsState()
    val timelapseInterval by viewModel.timelapseInterval.collectAsState()
    
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val currentProject by viewModel.currentProject.collectAsState()
    
    // Menu & Dialog States
    var showMenuDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var newProjectInput by remember { mutableStateOf("") }
    var savedPresets by remember { mutableStateOf(listOf<GimbalPreset>()) }
    var macAddressInput by remember { mutableStateOf("C6:B7:1A:06:2D:D3") }

    // Video Recording State
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var imageCapture by remember { mutableStateOf<androidx.camera.core.ImageCapture?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var timelapseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Video Gallery State
    var showGalleryDialog by remember { mutableStateOf(false) }
    var videoFiles by remember { mutableStateOf(emptyList<File>()) }
    var previewVideoFile by remember { mutableStateOf<File?>(null) }
    var previewTimelapseDir by remember { mutableStateOf<File?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "GimbalAutoPaths")
        val defaultDir = File(baseDir, "Default")
        if (!defaultDir.exists()) defaultDir.mkdirs()
    }

    fun loadVideoFiles(projectName: String): List<File> {
        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "GimbalAutoPaths")
        val projectDir = File(baseDir, projectName)
        if (!projectDir.exists()) projectDir.mkdirs()
        return projectDir.listFiles { file -> 
            file.extension == "mp4" || (file.isDirectory && file.name.startsWith("Timelapse_"))
        }?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    val coroutineScope = rememberCoroutineScope()

    // Hidden manual toggle, automatically triggered by tracking state
    val toggleRecording: () -> Unit = {
        if (cameraMode == "Timelapse") {
            if (isRecordingVideo || activeRecording != null || timelapseJob != null) {
                // Stop Timelapse
                timelapseJob?.cancel()
                timelapseJob = null
                isRecordingVideo = false
            } else {
                if (imageCapture != null) {
                    isRecordingVideo = true
                    val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "GimbalAutoPaths")
                    val projectDir = File(baseDir, currentProject)
                    if (!projectDir.exists()) projectDir.mkdirs()
                    val tlDir = File(projectDir, "Timelapse_${System.currentTimeMillis()}")
                    tlDir.mkdirs()
                    
                    timelapseJob = coroutineScope.launch {
                        while(isActive) {
                            // Pause the gimbal and wait for it to stabilize
                            viewModel.pausePathing()
                            kotlinx.coroutines.delay(500) // Stabilization delay

                            val file = File(tlDir, "IMG_${System.currentTimeMillis()}.jpg")
                            val outputOptions = androidx.camera.core.ImageCapture.OutputFileOptions.Builder(file).build()
                            
                            // Use a continuation to wait for the callback without blocking the loop
                            kotlin.coroutines.suspendCoroutine<Unit> { continuation ->
                                imageCapture?.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: androidx.camera.core.ImageCapture.OutputFileResults) {
                                            android.util.Log.d("Timelapse", "Saved ${file.name}")
                                            continuation.resumeWith(Result.success(Unit))
                                        }
                                        override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                            android.util.Log.e("Timelapse", "Error: ${exception.message}")
                                            continuation.resumeWith(Result.success(Unit))
                                        }
                                    }
                                )
                            }
                            
                            // Resume pathing after image is captured
                            viewModel.resumePathing()
                            
                            // Wait for the next interval
                            kotlinx.coroutines.delay(timelapseInterval * 1000L)
                        }
                    }
                } else {
                    Toast.makeText(context, "ImageCapture not ready.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Normal or Slow-Mo
            if (isRecordingVideo || activeRecording != null) {
                activeRecording?.stop()
                isRecordingVideo = false
                activeRecording = null
            } else {
                if (videoCapture != null) {
                    isRecordingVideo = true
                    val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "GimbalAutoPaths")
                    val projectDir = File(baseDir, currentProject)
                    if (!projectDir.exists()) projectDir.mkdirs()
                    val file = File(projectDir, "PathVideo_${System.currentTimeMillis()}.mp4")
                    
                    val outputOptions = FileOutputOptions.Builder(file).build()
                    activeRecording = videoCapture?.output
                        ?.prepareRecording(context, outputOptions)
                        ?.apply {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                withAudioEnabled()
                            }
                        }
                        ?.start(ContextCompat.getMainExecutor(context)) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> isRecordingVideo = true
                                is VideoRecordEvent.Finalize -> {
                                    isRecordingVideo = false
                                    activeRecording = null
                                    if (event.hasError()) {
                                        Toast.makeText(context, "Video Error: ${event.error}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                } else {
                    Toast.makeText(context, "Camera not ready. Make sure Camera Toggle is ON.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Auto-Record Trigger Logic
    LaunchedEffect(isRunning, useCamera) {
        if (useCamera) {
            if (isRunning) {
                if (useDelay) {
                    while (countdown == null) delay(50)
                    while (countdown != null && countdown != 0) delay(50)
                    delay(300)
                } else {
                    delay(500)
                }

                if (!isRecordingVideo) toggleRecording()
            } else {
                if (isRecordingVideo) toggleRecording()
            }
        }
    }

    // MAIN UI: Full Screen Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- 1. Full Screen Background View ---
        if (useCamera) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraLens = cameraLens,
                cameraMode = cameraMode,
                onVideoCaptureReady = { capture -> videoCapture = capture },
                onImageCaptureReady = { capture -> imageCapture = capture }
            )
        } else {
            // Camera toggle is off
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "CAMERA OFF", 
                    color = Color.DarkGray, 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // --- 2. Spline Visualizer Canvas (Overlaying Full Screen) ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            
            // Subdued crosshairs
            drawLine(Color(0x44FFFFFF), Offset(0f, h/2), Offset(w, h/2), strokeWidth = 1f)
            drawLine(Color(0x44FFFFFF), Offset(w/2, 0f), Offset(w/2, h), strokeWidth = 1f)

            if (waypoints.isNotEmpty()) {
                val camYawRad = Math.toRadians(telemetry.second.toDouble())
                val camPitchRad = Math.toRadians(telemetry.first.toDouble())
                val sphereRadius = w * 0.8f // Scale factor for the sphere

                var lastPoint: Offset? = null
                var lastRz: Double? = null

                waypoints.forEach { wp ->
                    val wpYawRad = Math.toRadians(wp.yaw.toDouble())
                    val wpPitchRad = Math.toRadians(wp.pitch.toDouble())

                    // 3D coordinates on a unit sphere
                    val wpX = Math.cos(wpPitchRad) * Math.sin(wpYawRad)
                    val wpY = Math.sin(wpPitchRad)
                    val wpZ = Math.cos(wpPitchRad) * Math.cos(wpYawRad)

                    // Y-axis rotation (Yaw)
                    val yawRot = -camYawRad
                    val dx = wpX * Math.cos(yawRot) + wpZ * Math.sin(yawRot)
                    val dy = wpY
                    val dz = -wpX * Math.sin(yawRot) + wpZ * Math.cos(yawRot)

                    // X-axis rotation (Pitch)
                    val pitchRot = -camPitchRad
                    val rx = dx
                    val ry = dy * Math.cos(pitchRot) + dz * Math.sin(pitchRot)
                    val rz = -dy * Math.sin(pitchRot) + dz * Math.cos(pitchRot)

                    val screenX = (w / 2f) + (rx * sphereRadius).toFloat()
                    val screenY = (h / 2f) + (ry * sphereRadius).toFloat()
                    val currOffset = Offset(screenX, screenY)

                    // Draw point
                    val alpha = if (rz > 0) 0.8f else 0.3f
                    val pointRadius = if (rz > 0) 10f else 6f
                    drawCircle(Color.Green.copy(alpha = alpha), radius = pointRadius, center = currOffset)

                    // Draw line
                    lastPoint?.let { prevOffset ->
                        val lineAlpha = if (rz > 0 || (lastRz != null && lastRz!! > 0)) 0.6f else 0.2f
                        drawLine(Color(0xFF2196F3).copy(alpha = lineAlpha), prevOffset, currOffset, strokeWidth = 4f)
                    }
                    
                    lastPoint = currOffset
                    lastRz = rz
                }
            }
        }

        // --- 3. Telemetry (Top Left, Minimalist text HUD) ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val textShadow = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 6f))

            Text(text = "X:${String.format("%.0f", telemetry.second)} Y:${String.format("%.0f", telemetry.first)}", color = Color.White, fontSize = 12.sp, style = textShadow)
            
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "BAT: ${batteryPercent ?: "0"}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = textShadow)
            
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "LOD: ${motorLoad ?: "0"}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = textShadow)
            
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = gimbalMode.uppercase(), color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = textShadow)
            
            if (isRecordingVideo) {
                Spacer(modifier = Modifier.height(8.dp))
                // Red Recording Dot
                Box(modifier = Modifier.size(12.dp).background(Color.Red, CircleShape))
            }
        }

        // --- 3b. Connection Status (Top Right, Minimalist) ---
        val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
        
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val textShadow = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 6f))
            
            if (connectionState != 2) {
                Text(
                    text = "DSC", 
                    color = Color.Red, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold, 
                    style = textShadow,
                    modifier = Modifier.clickable {
                        val autoConnected = viewModel.startScan()
                        if (!autoConnected) {
                            showDevicePicker = true
                        }
                    }.padding(4.dp)
                )
            } else {
                Text(
                    text = connectedDeviceName ?: "GIMBAL", 
                    color = Color.Green, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold, 
                    style = textShadow,
                    modifier = Modifier.clickable {
                        showMenuDialog = true
                    }.padding(4.dp)
                )
            }
        }

        // --- 4. Left Toggles (HUD Text Buttons) ---
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .background(Color(0x66000000), RoundedCornerShape(24.dp))
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = { viewModel.toggleDelay() }) {
                Text(text = "DLY", color = if (useDelay) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { viewModel.toggleSound() }) {
                Text(text = "SND", color = if (useSound) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { viewModel.toggleAutoRecord() }) {
                Text(text = "CAM", color = if (useCamera) Color(0xFFE91E63) else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            
            // New Toggles
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = { viewModel.cycleCameraLens() }) {
                Text(text = if (cameraLens == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) "FRT" else "BCK", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { viewModel.cycleCameraMode() }) {
                Text(text = when (cameraMode) {
                    "Normal" -> "NRM"
                    "Slow-Mo" -> "SLO"
                    "Timelapse" -> "TLP"
                    else -> "NRM"
                }, color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (cameraMode == "Timelapse") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(20.dp).clickable { viewModel.adjustTimelapseInterval(1) })
                    Text(text = "${timelapseInterval}s", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrease", tint = Color.White, modifier = Modifier.size(20.dp).clickable { viewModel.adjustTimelapseInterval(-1) })
                }
            }
        }

        // --- 5. Right Time Adjuster ---
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .background(Color(0x66000000), RoundedCornerShape(24.dp))
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = { viewModel.adjustTime(5) }) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = Color.White)
            }
            Text(text = "${totalTime}s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { viewModel.adjustTime(-5) }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = Color.White)
            }
        }

        // --- 6. Bottom Controls Bar ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Menu Button (Left)
            IconButton(
                onClick = { showMenuDialog = true },
                modifier = Modifier.background(Color(0x66000000), CircleShape)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
            }

            // Camera Action Buttons (Center)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // Record Point Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (connectionState == 2) Color.White else Color.Gray, CircleShape)
                        .combinedClickable(
                            enabled = connectionState == 2,
                            onClick = { viewModel.addWaypoint() },
                            onLongClick = { viewModel.clearAllWaypoints() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${waypoints.size}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                // Play Track Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (isRunning) Color(0xFFFF9800) else if (waypoints.size >= 2) Color(0xFF2196F3) else Color.Gray, CircleShape)
                        .clickable(enabled = waypoints.size >= 2) { viewModel.triggerPathPlay() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRunning) {
                        // Stop Icon (White Square)
                        Box(modifier = Modifier.size(20.dp).background(Color.White, RoundedCornerShape(2.dp)))
                    } else {
                        // Play Icon
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }

            // Gallery Button (Right)
            IconButton(
                onClick = { 
                    videoFiles = loadVideoFiles(currentProject)
                    showGalleryDialog = true 
                },
                modifier = Modifier.background(Color(0x66000000), CircleShape)
            ) {
                // Using standard List icon to act as Gallery/Library button
                Icon(Icons.Default.List, contentDescription = "Gallery", tint = Color.White)
            }
        }

        // --- Overlays & Dialogs ---

        // Countdown Overlay
        AnimatedVisibility(
            visible = countdown != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier.size(120.dp).background(Color(0x88000000), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${countdown ?: ""}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Main Settings & Path Menu
        if (showMenuDialog) {
            AlertDialog(
                onDismissRequest = { showMenuDialog = false },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Menu Options", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        
                        // Connection Section (Disconnect only in menu)
                        if (connectionState == 2) {
                            Text("GIMBAL CONNECTION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { viewModel.disconnectGimbal() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Disconnect Gimbal") }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("PROJECT MANAGEMENT", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        
                        Button(
                            onClick = { showMenuDialog = false; showProjectDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Project: $currentProject (Change)") }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("PATH MANAGEMENT", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        
                        Button(
                            onClick = { showMenuDialog = false; showSaveDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = waypoints.isNotEmpty()
                        ) { Text("Save Current Path") }
                        
                        Button(
                            onClick = { 
                                savedPresets = PresetManager.loadPresets()
                                showMenuDialog = false
                                showLoadDialog = true 
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Browse / Load Paths") }
                    }
                },
                confirmButton = { TextButton(onClick = { showMenuDialog = false }) { Text("Close", color = Color.White) } }
            )
        }

        // Save Path Dialog
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Save Path Preset", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        label = { Text("Preset Name", color = Color.LightGray) },
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val finalName = presetNameInput.ifBlank { "Preset_${System.currentTimeMillis()}" }
                        PresetManager.savePreset(
                            GimbalPreset(
                                finalName, 
                                totalTime, 
                                useDelay, 
                                useSound, 
                                cameraMode, 
                                timelapseInterval, 
                                waypoints.map { WaypointData(it.yaw, it.pitch) }
                            )
                        )
                        showSaveDialog = false
                        presetNameInput = ""
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel", color = Color.Gray) } }
            )
        }

        // Load Path Dialog
        if (showLoadDialog) {
            AlertDialog(
                onDismissRequest = { showLoadDialog = false },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Load Path Preset", color = Color.White) },
                text = {
                    if (savedPresets.isEmpty()) {
                        Text("No presets found.", color = Color.Gray)
                    } else {
                        LazyColumn {
                            items(savedPresets) { preset ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        .clickable { viewModel.applyPreset(preset); showLoadDialog = false },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(preset.name, color = Color.White, fontWeight = FontWeight.Bold)
                                            Text("${preset.waypoints.size} Pts | ${preset.totalTime}s | ${preset.cameraMode}", color = Color.Gray, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { 
                                            if (PresetManager.deletePreset(preset.name)) savedPresets = PresetManager.loadPresets()
                                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350)) }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLoadDialog = false }) { Text("Close", color = Color.White) } }
            )
        }

        // Video Gallery Dialog
        if (showGalleryDialog) {
            AlertDialog(
                onDismissRequest = { showGalleryDialog = false },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Video Gallery", color = Color.White) },
                text = {
                    if (videoFiles.isEmpty()) {
                        Text("No media found in DCIM/GimbalAutoPaths.", color = Color.Gray)
                    } else {
                        LazyColumn {
                            items(videoFiles) { file ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        if (file.isDirectory) {
                                            previewTimelapseDir = file
                                        } else {
                                            previewVideoFile = file
                                        }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val isTimelapse = file.isDirectory
                                            Text(if (isTimelapse) "Timelapse (${file.listFiles()?.size ?: 0} imgs)" else file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            val sizeLabel = if (isTimelapse) {
                                                val totalSize = file.listFiles()?.sumOf { it.length() } ?: 0L
                                                "${totalSize / (1024 * 1024)} MB"
                                            } else {
                                                "${file.length() / (1024 * 1024)} MB"
                                            }
                                            Text(sizeLabel, color = Color.Gray, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { 
                                            if (file.isDirectory) {
                                                file.deleteRecursively()
                                            } else {
                                                file.delete()
                                            }
                                            videoFiles = loadVideoFiles(currentProject)
                                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showGalleryDialog = false }) { Text("Close", color = Color.White) } }
            )
        }

        // Video Preview Dialog
        if (previewVideoFile != null) {
            AlertDialog(
                onDismissRequest = { previewVideoFile = null },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Playback", fontSize = 16.sp, color = Color.White) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp).background(Color.Black)) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(previewVideoFile!!.absolutePath)
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    start()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { previewVideoFile = null }) { Text("Close", color = Color.White) } }
            )
        }

        // Timelapse Preview Dialog
        if (previewTimelapseDir != null) {
            val images = remember(previewTimelapseDir) {
                previewTimelapseDir!!.listFiles { file -> file.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
            }
            var currentIndex by remember { mutableStateOf(0) }
            
            AlertDialog(
                onDismissRequest = { previewTimelapseDir = null },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Timelapse Skimmer", fontSize = 16.sp, color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (images.isNotEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().height(300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                val currentImage = images[currentIndex]
                                val bitmap = remember(currentImage) {
                                    val bmp = android.graphics.BitmapFactory.decodeFile(currentImage.absolutePath)
                                    if (bmp != null) {
                                        val exif = android.media.ExifInterface(currentImage.absolutePath)
                                        val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                                        val matrix = android.graphics.Matrix()
                                        when (orientation) {
                                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                        }
                                        if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED) {
                                            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                        } else bmp
                                    } else null
                                }
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Timelapse Image",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text("Error loading image", color = Color.Red)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Slider(
                                value = currentIndex.toFloat(),
                                onValueChange = { currentIndex = it.toInt() },
                                valueRange = 0f..(images.size - 1).coerceAtLeast(0).toFloat(),
                                steps = if (images.size > 2) images.size - 2 else 0
                            )
                            Text("${currentIndex + 1} / ${images.size}", color = Color.White)
                        } else {
                            Text("No images in this timelapse.", color = Color.Gray)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { previewTimelapseDir = null }) { Text("Close", color = Color.White) } }
            )
        }

        // Device Picker Dialog
        if (showDevicePicker) {
            AlertDialog(
                onDismissRequest = { 
                    showDevicePicker = false 
                    viewModel.stopScan()
                },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Available Gimbals", color = Color.White) },
                text = {
                    Column {
                        if (scannedDevices.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(scannedDevices) { device ->
                                    val deviceName = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
                                            viewModel.stopScan()
                                            viewModel.connectGimbal(device.address)
                                            showDevicePicker = false
                                        },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(deviceName, color = Color.White, fontWeight = FontWeight.Bold)
                                            Text(device.address, color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(if (isScanning) "Scanning..." else "No devices found.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                        }
                    }
                },
                confirmButton = { 
                    TextButton(onClick = { 
                        showDevicePicker = false 
                        viewModel.stopScan()
                    }) { Text("Cancel", color = Color.White) } 
                }
            )
        }

        // Project Management Dialog
        if (showProjectDialog) {
            AlertDialog(
                onDismissRequest = { showProjectDialog = false },
                containerColor = Color(0xFF1E1E1E),
                title = { Text("Projects", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "GimbalAutoPaths")
                        if (!baseDir.exists()) baseDir.mkdirs()
                        val projects = baseDir.listFiles { file -> file.isDirectory }?.map { it.name } ?: emptyList()
                        
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(projects) { project ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
                                        viewModel.setCurrentProject(project)
                                        showProjectDialog = false 
                                    },
                                    colors = CardDefaults.cardColors(containerColor = if (project == currentProject) Color(0xFF009688) else Color(0xFF2A2A2A))
                                ) {
                                    Text(project, color = Color.White, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                        
                        OutlinedTextField(
                            value = newProjectInput,
                            onValueChange = { newProjectInput = it },
                            label = { Text("New Project Name", color = Color.LightGray) },
                            colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Button(
                            onClick = {
                                val name = newProjectInput.trim()
                                if (name.isNotEmpty()) {
                                    val newDir = File(baseDir, name)
                                    if (!newDir.exists()) newDir.mkdirs()
                                    viewModel.setCurrentProject(name)
                                    newProjectInput = ""
                                    showProjectDialog = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                        ) { Text("Create & Select") }
                    }
                },
                confirmButton = { TextButton(onClick = { showProjectDialog = false }) { Text("Close", color = Color.White) } }
            )
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraLens: Int = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
    cameraMode: String = "Normal",
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onImageCaptureReady: (androidx.camera.core.ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val isPermissionGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    val recorder = remember(cameraMode) {
        val builder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        // High speed capability config logic would go here if needed via CameraX 1.5.0
        builder.build()
    }
    val videoCapture = remember(recorder) { VideoCapture.withOutput(recorder) }
    val imageCapture = remember { androidx.camera.core.ImageCapture.Builder().build() }

    LaunchedEffect(videoCapture, imageCapture) {
        onVideoCaptureReady(videoCapture)
        onImageCaptureReady(imageCapture)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (exc: Exception) {
                android.util.Log.e("CameraPreview", "Failed to unbind camera", exc)
            }
        }
    }

    if (isPermissionGranted) {
        AndroidView(
            factory = { ctx ->
                androidx.camera.view.PreviewView(ctx).apply {
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                    implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = modifier,
            update = { previewView ->
                val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(previewView.context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = androidx.camera.core.CameraSelector.Builder()
                        .requireLensFacing(cameraLens)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
                        if (cameraMode == "Timelapse") {
                            useCases.add(imageCapture)
                        } else {
                            useCases.add(videoCapture)
                        }
                        
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            *useCases.toTypedArray()
                        )
                    } catch (exc: Exception) {
                        android.util.Log.e("CameraPreview", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(previewView.context))
            }
        )
    } else {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                text = "Camera Permission Required",
                color = Color.Gray,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}