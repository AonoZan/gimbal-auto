package com.aonozan.gimbalauto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.aonozan.gimbalauto.utils.WaypointData

@Composable
fun SplineVisualizer(
    modifier: Modifier = Modifier,
    waypoints: List<WaypointData>,
    telemetry: Pair<Float, Float>
) {
    Canvas(modifier = modifier) {
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
}