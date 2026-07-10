package com.aonozan.gimbalauto.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraLens: Int = CameraSelector.LENS_FACING_BACK,
    cameraMode: String = "Normal",
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val isPermissionGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    val recorder = remember(cameraMode) {
        val builder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        builder.build()
    }
    val videoCapture = remember(recorder) { VideoCapture.withOutput(recorder) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(videoCapture, imageCapture) {
        onVideoCaptureReady(videoCapture)
        onImageCaptureReady(imageCapture)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (exc: Exception) {
                android.util.Log.e("CameraPreview", "Failed to unbind camera", exc)
            }
        }
    }

    if (isPermissionGranted) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = modifier,
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(cameraLens)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val useCases = mutableListOf<UseCase>(preview)
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