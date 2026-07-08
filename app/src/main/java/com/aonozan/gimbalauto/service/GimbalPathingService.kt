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
                for (sec in 5 downTo 1) {
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

    fun pausePathing() {
        isPaused = true
    }

    fun resumePathing() {
        isPaused = false
    }

    private suspend fun CoroutineScope.moveHome(ble: GimbalBleManager, home: Waypoint) {
        val startHomeTime = System.currentTimeMillis()
        
        val initialTelemetry = ble.telemetry.value
        val startPitch = initialTelemetry.first
        val startYaw = initialTelemetry.second

        val diffY = normalizeAngle(home.yaw - startYaw)
        val diffP = normalizeAngle(home.pitch - startPitch)
        val totalTravelDistance = kotlin.math.sqrt((diffY * diffY + diffP * diffP).toDouble()).toFloat()

        val durationMs = Math.max(4000L, (totalTravelDistance * 1000f / 20f).toLong())

        var lastHomeSpeedY = 0.0f
        var lastHomeSpeedP = 0.0f

        while (isActive) {
            val elapsed = System.currentTimeMillis() - startHomeTime

            val progress = Math.min(1.0f, elapsed.toFloat() / durationMs.toFloat())
            val t = progress * progress * (3f - 2f * progress)


            val currentTargetYaw = startYaw + normalizeAngle(home.yaw - startYaw) * t
            val currentTargetPitch = startPitch + normalizeAngle(home.pitch - startPitch) * t

            val curr = ble.telemetry.value
            val errY = normalizeAngle(currentTargetYaw - curr.second)
            val errP = normalizeAngle(currentTargetPitch - curr.first)

            val distFromTarget = kotlin.math.sqrt((errY * errY + errP * errP).toDouble()).toFloat()

            if (progress >= 1.0f && distFromTarget < 1.5f) break
            if (elapsed > durationMs + 10000L) break

            val absErrY = Math.abs(errY)
            val absErrP = Math.abs(errP)

            val momentumY = Math.max(0.0f, Math.min(0.6f, (absErrY - 1.0f) / 10.0f))
            val momentumP = Math.max(0.0f, Math.min(0.6f, (absErrP - 1.0f) / 10.0f))
            
            val pGainY = Math.max(2.0f, Math.min(4.5f, absErrY))
            val pGainP = Math.max(2.0f, Math.min(4.5f, absErrP))
            
            val sY = (errY * pGainY) + (lastHomeSpeedY * momentumY)
            val sP = (errP * pGainP) + (lastHomeSpeedP * momentumP)
            lastHomeSpeedY = sY
            lastHomeSpeedP = sP

            val speedMultY = Math.max(1.5f, Math.min(15.0f, absErrY * 1.2f))
            val speedMultP = Math.max(1.5f, Math.min(15.0f, absErrP * 1.2f))

            val speedY = Math.max(-100, Math.min(100, (sY * speedMultY).toInt())).toShort()
            val speedP = Math.max(-100, Math.min(100, (sP * speedMultP).toInt())).toShort()

            ble.sendVelocityCommand(speedY, speedP)
            delay(40)
        }
        ble.sendVelocityCommand(0, 0)
    }

    private suspend fun CoroutineScope.executeSplineTravel(ble: GimbalBleManager, originalWaypoints: List<Waypoint>, durationSec: Int) {
        val adjustedPoints = originalWaypoints.toMutableList()
        for (i in 1 until adjustedPoints.size) {
            adjustedPoints[i] = adjustedPoints[i].copy(
                pitch = adjustedPoints[i - 1].pitch + normalizeAngle(adjustedPoints[i].pitch - adjustedPoints[i - 1].pitch),
                yaw = adjustedPoints[i - 1].yaw + normalizeAngle(adjustedPoints[i].yaw - adjustedPoints[i - 1].yaw)
            )
        }

       val segmentWeights = FloatArray(adjustedPoints.size - 1)
        var totalWeight = 0.0f
        for (i in 0 until adjustedPoints.size - 1) {
            val dist = kotlin.math.sqrt(
                Math.pow((adjustedPoints[i + 1].yaw - adjustedPoints[i].yaw).toDouble(), 2.0) +
                Math.pow((adjustedPoints[i + 1].pitch - adjustedPoints[i].pitch).toDouble(), 2.0)
            ).toFloat()
            
            val safeDist = Math.max(0.1f, dist)
            val m1 = adjustedPoints[i].timeMultiplier
            val m2 = adjustedPoints[i + 1].timeMultiplier
            
            segmentWeights[i] = safeDist * ((m1 + m2) / 2.0f)
            totalWeight += segmentWeights[i]
        }

        val accumulatedWeights = FloatArray(segmentWeights.size + 1)
        accumulatedWeights[0] = 0.0f
        for (i in 0 until segmentWeights.size) {
            accumulatedWeights[i + 1] = accumulatedWeights[i] + segmentWeights[i]
        }

        val extendedPoints = listOf(adjustedPoints.first()) + adjustedPoints + adjustedPoints.last()
        val totalMs = durationSec * 1000L
        var elapsedMs = 0L
        var lastTick = System.currentTimeMillis()
        var lastGhost = adjustedPoints.first()
        var lastSpeedY = 0.0f
        var lastSpeedP = 0.0f
        val initialTele = ble.telemetry.value
        var lastSeenTelemetryY = initialTele.second
        var lastSeenTelemetryP = initialTele.first
        var activeErrY = 0.0f
        var activeErrP = 0.0f

        while (isActive) {
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            if (!isPaused) {
                elapsedMs += delta
            }

            if (elapsedMs >= totalMs) break

            if (isPaused) {
                ble.sendVelocityCommand(0, 0)
                lastSpeedY = 0.0f
                lastSpeedP = 0.0f
                delay(40)
                continue
            }

            val progress = elapsedMs.toFloat() / totalMs
            val targetWeight = progress * totalWeight

            var segIdx = 0
            for (j in 0 until accumulatedWeights.size - 1) {
                if (targetWeight >= accumulatedWeights[j] && targetWeight <= accumulatedWeights[j + 1]) {
                    segIdx = j
                    break
                }
            }
            if (segIdx >= accumulatedWeights.size - 1) segIdx = accumulatedWeights.size - 2

            val weightInSeg = targetWeight - accumulatedWeights[segIdx]

            val m1 = adjustedPoints[segIdx].timeMultiplier
            val m2 = adjustedPoints[segIdx + 1].timeMultiplier
            val L = Math.max(0.1f, kotlin.math.sqrt(
                Math.pow((adjustedPoints[segIdx + 1].yaw - adjustedPoints[segIdx].yaw).toDouble(), 2.0) +
                Math.pow((adjustedPoints[segIdx + 1].pitch - adjustedPoints[segIdx].pitch).toDouble(), 2.0)
            ).toFloat())
            
            val A = L * (m2 - m1) / 2.0f
            val B = L * m1
            val C = -weightInSeg
            
            var u = 0.0f
            if (Math.abs(A) < 0.0001f) {
                u = -C / B
            } else {
                val discriminant = B * B - 4 * A * C
                if (discriminant >= 0) {
                    u = (-B + kotlin.math.sqrt(discriminant)) / (2 * A)
                }
            }
            u = Math.max(0.0f, Math.min(1.0f, u))

            val gY = SplineMath.getSplinePoint(extendedPoints[segIdx].yaw, extendedPoints[segIdx+1].yaw, extendedPoints[segIdx+2].yaw, extendedPoints[segIdx+3].yaw, u)
            val gP = SplineMath.getSplinePoint(extendedPoints[segIdx].pitch, extendedPoints[segIdx+1].pitch, extendedPoints[segIdx+2].pitch, extendedPoints[segIdx+3].pitch, u)

            val gVelY = normalizeAngle(gY - lastGhost.yaw)
            val gVelP = normalizeAngle(gP - lastGhost.pitch)
            lastGhost = Waypoint(pitch = gP, yaw = gY)

            val currTele = ble.telemetry.value
            if (currTele.second != lastSeenTelemetryY || currTele.first != lastSeenTelemetryP) {
                activeErrY = normalizeAngle(gY - currTele.second)
                activeErrP = normalizeAngle(gP - currTele.first)
                
                activeErrY = Math.max(-10f, Math.min(10f, activeErrY))
                activeErrP = Math.max(-10f, Math.min(10f, activeErrP))
                
                lastSeenTelemetryY = currTele.second
                lastSeenTelemetryP = currTele.first
            }

            val baseSpeedY = gVelY * 400.0f
            val baseSpeedP = gVelP * 400.0f

            val nudgeY = activeErrY * 5.0f
            val nudgeP = activeErrP * 5.0f

            val targetSpeedY = baseSpeedY + nudgeY
            val targetSpeedP = baseSpeedP + nudgeP

            activeErrY *= 0.88f
            activeErrP *= 0.88f

            val sY = (targetSpeedY * 0.15f) + (lastSpeedY * 0.85f)
            val sP = (targetSpeedP * 0.15f) + (lastSpeedP * 0.85f)
            lastSpeedY = sY
            lastSpeedP = sP

            val speedY = Math.max(-500, Math.min(500, sY.toInt())).toShort()
            val speedP = Math.max(-500, Math.min(500, sP.toInt())).toShort()

            ble.sendVelocityCommand(speedY, speedP)
            delay(40)
        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel("gimbal_path", "Gimbal Controller Active", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

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