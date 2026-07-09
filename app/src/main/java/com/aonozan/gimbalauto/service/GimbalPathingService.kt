/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aonozan.gimbalauto.MainActivity
import com.aonozan.gimbalauto.ble.GimbalBleManager
import com.aonozan.gimbalauto.model.Waypoint
import com.aonozan.gimbalauto.utils.SplineMath
import kotlinx.coroutines.*
import kotlin.math.*

class GimbalPathingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pathingJob: Job? = null
    private var bleManager: GimbalBleManager? = null
    
    @Volatile
    private var isPaused = false

    inner class LocalBinder : Binder() {
        fun getService(): GimbalPathingService = this@GimbalPathingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun startPathing(
        ble: GimbalBleManager,
        waypoints: List<Waypoint>,
        totalDurationSec: Int,
        useDelay: Boolean,
        onCountdown: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        bleManager = ble
        pathingJob?.cancel()
        pathingJob = serviceScope.launch {
            startForegroundNotification()
            ble.setGimbalMode(true)
            delay(500)

            moveHome(ble, waypoints.first())

            if (useDelay) {
                (5 downTo 1).forEach { sec ->
                    onCountdown(sec)
                    delay(1000)
                }
                onCountdown(0)
            }

            executeSplineTravel(ble, waypoints, totalDurationSec)

            ble.setGimbalMode(false)
            ble.sendVelocityCommand(0, 0)
            onComplete()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun cancelPathing() {
        pathingJob?.cancel()
        bleManager?.setGimbalMode(false)
        bleManager?.sendVelocityCommand(0, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun pausePathing() { isPaused = true }
    fun resumePathing() { isPaused = false }

    private suspend fun CoroutineScope.moveHome(ble: GimbalBleManager, home: Waypoint) {
        val startHomeTime = System.currentTimeMillis()
        val (startPitch, startYaw) = ble.telemetry.value

        val diffY = normalizeAngle(home.yaw - startYaw)
        val diffP = normalizeAngle(home.pitch - startPitch)
        val durationMs = maxOf(4000L, (hypot(diffY, diffP) * 50f).toLong())

        var lastHomeSpeedY = 0.0f
        var lastHomeSpeedP = 0.0f

        while (isActive) {
            val elapsed = System.currentTimeMillis() - startHomeTime
            val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceAtMost(1.0f)
            val t = progress * progress * (3f - 2f * progress)

            val targetYaw = startYaw + normalizeAngle(home.yaw - startYaw) * t
            val targetPitch = startPitch + normalizeAngle(home.pitch - startPitch) * t

            val (currP, currY) = ble.telemetry.value
            val errY = normalizeAngle(targetYaw - currY)
            val errP = normalizeAngle(targetPitch - currP)

            if (progress >= 1.0f && hypot(errY, errP) < 1.5f || elapsed > durationMs + 10000L) break

            val absErrY = abs(errY)
            val absErrP = abs(errP)

            lastHomeSpeedY = (errY * absErrY.coerceIn(2.0f, 4.5f)) + (lastHomeSpeedY * ((absErrY - 1.0f) / 10.0f).coerceIn(0.0f, 0.6f))
            lastHomeSpeedP = (errP * absErrP.coerceIn(2.0f, 4.5f)) + (lastHomeSpeedP * ((absErrP - 1.0f) / 10.0f).coerceIn(0.0f, 0.6f))

            ble.sendVelocityCommand(
                (lastHomeSpeedY * (absErrY * 1.2f).coerceIn(1.5f, 15.0f)).toInt().coerceIn(-100, 100).toShort(),
                (lastHomeSpeedP * (absErrP * 1.2f).coerceIn(1.5f, 15.0f)).toInt().coerceIn(-100, 100).toShort()
            )
            delay(40)
        }
        ble.sendVelocityCommand(0, 0)
    }

    private suspend fun CoroutineScope.executeSplineTravel(ble: GimbalBleManager, originalWaypoints: List<Waypoint>, durationSec: Int) {
        val adjustedPoints = originalWaypoints.toMutableList().apply {
            for (i in 1 until size) {
                this[i] = this[i].copy(
                    pitch = this[i - 1].pitch + normalizeAngle(this[i].pitch - this[i - 1].pitch),
                    yaw = this[i - 1].yaw + normalizeAngle(this[i].yaw - this[i - 1].yaw)
                )
            }
        }

        val segmentWeights = FloatArray(adjustedPoints.size - 1)
        var totalWeight = 0.0f
        for (i in segmentWeights.indices) {
            val dist = hypot(adjustedPoints[i + 1].yaw - adjustedPoints[i].yaw, adjustedPoints[i + 1].pitch - adjustedPoints[i].pitch)
            segmentWeights[i] = maxOf(0.1f, dist) * ((adjustedPoints[i].timeMultiplier + adjustedPoints[i + 1].timeMultiplier) / 2.0f)
            totalWeight += segmentWeights[i]
        }

        val accumulatedWeights = FloatArray(segmentWeights.size + 1).apply {
            for (i in segmentWeights.indices) this[i + 1] = this[i] + segmentWeights[i]
        }

        val extendedPoints = listOf(adjustedPoints.first()) + adjustedPoints + adjustedPoints.last()
        val totalMs = durationSec * 1000L
        var elapsedMs = 0L
        var lastTick = System.currentTimeMillis()
        var lastGhost = adjustedPoints.first()
        var lastSpeedY = 0.0f
        var lastSpeedP = 0.0f
        
        val (initialPitch, initialYaw) = ble.telemetry.value
        var lastSeenTelemetryY = initialYaw
        var lastSeenTelemetryP = initialPitch
        var activeErrY = 0.0f
        var activeErrP = 0.0f

        while (isActive) {
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            if (!isPaused) elapsedMs += delta
            if (elapsedMs >= totalMs) break

            if (isPaused) {
                ble.sendVelocityCommand(0, 0)
                lastSpeedY = 0.0f
                lastSpeedP = 0.0f
                delay(40)
                continue
            }

            val targetWeight = (elapsedMs.toFloat() / totalMs) * totalWeight
            val segIdx = (accumulatedWeights.indexOfFirst { targetWeight <= it }.takeIf { it >= 0 }?.minus(1) ?: segmentWeights.lastIndex).coerceAtLeast(0)
            val weightInSeg = targetWeight - accumulatedWeights[segIdx]

            val m1 = adjustedPoints[segIdx].timeMultiplier
            val m2 = adjustedPoints[segIdx + 1].timeMultiplier
            val L = maxOf(0.1f, hypot(adjustedPoints[segIdx + 1].yaw - adjustedPoints[segIdx].yaw, adjustedPoints[segIdx + 1].pitch - adjustedPoints[segIdx].pitch))
            
            val A = L * (m2 - m1) / 2.0f
            val B = L * m1
            val C = -weightInSeg
            
            val u = (if (abs(A) < 0.0001f) -C / B else {
                val discriminant = B * B - 4 * A * C
                if (discriminant >= 0) (-B + sqrt(discriminant)) / (2 * A) else 0.0f
            }).coerceIn(0.0f, 1.0f)

            val gY = SplineMath.getSplinePoint(extendedPoints[segIdx].yaw, extendedPoints[segIdx+1].yaw, extendedPoints[segIdx+2].yaw, extendedPoints[segIdx+3].yaw, u)
            val gP = SplineMath.getSplinePoint(extendedPoints[segIdx].pitch, extendedPoints[segIdx+1].pitch, extendedPoints[segIdx+2].pitch, extendedPoints[segIdx+3].pitch, u)

            val gVelY = normalizeAngle(gY - lastGhost.yaw)
            val gVelP = normalizeAngle(gP - lastGhost.pitch)
            lastGhost = Waypoint(pitch = gP, yaw = gY)

            val (currP, currY) = ble.telemetry.value
            if (currY != lastSeenTelemetryY || currP != lastSeenTelemetryP) {
                activeErrY = normalizeAngle(gY - currY).coerceIn(-10f, 10f)
                activeErrP = normalizeAngle(gP - currP).coerceIn(-10f, 10f)
                lastSeenTelemetryY = currY
                lastSeenTelemetryP = currP
            }

            lastSpeedY = ((gVelY * 400.0f + activeErrY * 5.0f) * 0.15f) + (lastSpeedY * 0.85f)
            lastSpeedP = ((gVelP * 400.0f + activeErrP * 5.0f) * 0.15f) + (lastSpeedP * 0.85f)

            activeErrY *= 0.88f
            activeErrP *= 0.88f

            ble.sendVelocityCommand(
                lastSpeedY.toInt().coerceIn(-500, 500).toShort(),
                lastSpeedP.toInt().coerceIn(-500, 500).toShort()
            )
            delay(40)
        }
    }

    private fun normalizeAngle(angle: Float): Float = ((angle + 180f).mod(360f) - 180f)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel("gimbal_path", "Gimbal Controller Active", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "gimbal_path")
            .setContentTitle("Gimbal Pathing Active")
            .setContentText("The gimbal is currently executing your spline path.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1001, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}