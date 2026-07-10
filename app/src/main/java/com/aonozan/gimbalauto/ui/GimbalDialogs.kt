package com.aonozan.gimbalauto.ui

import android.os.Environment
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aonozan.gimbalauto.utils.GimbalPreset
import java.io.File

@Composable
fun WaypointSpeedDialog(
    currentIndex: Int,
    initialMultiplier: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit
) {
    var sliderValue by remember(currentIndex) { mutableStateOf(initialMultiplier) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Point ${currentIndex + 1} Speed", color = Color.White) },
        text = {
            Column {
                Text("Time Multiplier: ${String.format("%.1f", sliderValue)}x", color = Color.White)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0.5f..5.0f,
                    steps = 44
                )
                Text("Higher multiplier = Slower point approach.", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sliderValue) }) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
fun MenuDialog(
    connectionState: Int,
    currentProject: String,
    waypointsNotEmpty: Boolean,
    onDisconnect: () -> Unit,
    onChangeProject: () -> Unit,
    onSavePath: () -> Unit,
    onLoadPath: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Menu Options", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (connectionState == 2) {
                    Text("GIMBAL CONNECTION", color = Color.Gray, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect Gimbal") }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("PROJECT MANAGEMENT", color = Color.Gray, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

                Button(
                    onClick = onChangeProject,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Project: $currentProject (Change)") }

                Spacer(modifier = Modifier.height(8.dp))
                Text("PATH MANAGEMENT", color = Color.Gray, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

                Button(
                    onClick = onSavePath,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = waypointsNotEmpty
                ) { Text("Save Current Path") }

                Button(
                    onClick = onLoadPath,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Browse / Load Paths") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
fun SavePathDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var presetNameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
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
            Button(onClick = { onSave(presetNameInput) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
fun LoadPathDialog(
    savedPresets: List<GimbalPreset>,
    onLoad: (GimbalPreset) -> Unit,
    onDelete: (GimbalPreset) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Load Path Preset", color = Color.White) },
        text = {
            if (savedPresets.isEmpty()) {
                Text("No presets found.", color = Color.Gray)
            } else {
                LazyColumn {
                    items(savedPresets) { preset ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onLoad(preset) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text("${preset.waypoints.size} Pts | ${preset.totalTime}s | ${preset.cameraMode}", color = Color.Gray, fontSize = 12.sp)
                                }
                                IconButton(onClick = { onDelete(preset) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
fun VideoGalleryDialog(
    videoFiles: List<File>,
    onSelectFile: (File) -> Unit,
    onSelectDir: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Video Gallery", color = Color.White) },
        text = {
            if (videoFiles.isEmpty()) {
                Text("No media found in DCIM/GimbalAutoPaths.", color = Color.Gray)
            } else {
                LazyColumn {
                    items(videoFiles) { file ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    if (file.isDirectory) {
                                        onSelectDir(file)
                                    } else {
                                        onSelectFile(file)
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val isTimelapse = file.isDirectory
                                    Text(if (isTimelapse) "Timelapse (${file.listFiles()?.size ?: 0} imgs)" else file.name, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp)
                                    val sizeLabel = if (isTimelapse) {
                                        val totalSize = file.listFiles()?.sumOf { it.length() } ?: 0L
                                        "${totalSize / (1024 * 1024)} MB"
                                    } else {
                                        "${file.length() / (1024 * 1024)} MB"
                                    }
                                    Text(sizeLabel, color = Color.Gray, fontSize = 12.sp)
                                }
                                IconButton(onClick = { onDeleteFile(file) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
fun VideoPreviewDialog(
    previewVideoFile: File,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Playback", fontSize = 16.sp, color = Color.White) },
        text = {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(previewVideoFile.absolutePath)
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
fun TimelapsePreviewDialog(
    previewTimelapseDir: File,
    onDismiss: () -> Unit
) {
    val images = remember(previewTimelapseDir) {
        previewTimelapseDir.listFiles { file -> file.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
    }
    var currentIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Timelapse Skimmer", fontSize = 16.sp, color = Color.White) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (images.isNotEmpty()) {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black), contentAlignment = Alignment.Center) {
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
                            Image(
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
fun DevicePickerDialog(
    scannedDevices: List<android.bluetooth.BluetoothDevice>,
    isScanning: Boolean,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Available Gimbals", color = Color.White) },
        text = {
            Column {
                if (scannedDevices.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(scannedDevices) { device ->
                            val deviceName = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onDeviceSelected(device) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(deviceName, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        }
    )
}

@Composable
fun ProjectManagementDialog(
    currentProject: String,
    onSelectProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newProjectInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSelectProject(project) },
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
                            onCreateProject(name)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("Create & Select") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}