/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aonozan.gimbalauto.ble.GimbalBleManager
import com.aonozan.gimbalauto.model.Waypoint
import com.aonozan.gimbalauto.service.GimbalPathingService
import com.aonozan.gimbalauto.utils.GimbalPreset
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GimbalViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = GimbalBleManager(application)
    private var pathingService: GimbalPathingService? = null
    private var isServiceBound = false
    private var toneGenerator: ToneGenerator? = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    val scannedDevices = bleManager.scannedDevices
    val connectedDeviceName = bleManager.connectedDeviceName

    private val _isScanning = MutableStateFlow(false); val isScanning = _isScanning.asStateFlow()
    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList()); val waypoints = _waypoints.asStateFlow()
    private val _totalTime = MutableStateFlow(20); val totalTime = _totalTime.asStateFlow()
    private val _useDelay = MutableStateFlow(false); val useDelay = _useDelay.asStateFlow()
    private val _useSound = MutableStateFlow(false); val useSound = _useSound.asStateFlow()
    private val _currentProject = MutableStateFlow("Default"); val currentProject = _currentProject.asStateFlow()
    private val _autoRecord = MutableStateFlow(false); val autoRecord = _autoRecord.asStateFlow()
    private val _isRunning = MutableStateFlow(false); val isRunning = _isRunning.asStateFlow()
    private val _countdown = MutableStateFlow<Int?>(null); val countdown = _countdown.asStateFlow()
    private val _lastKeyEvent = MutableStateFlow("None"); val lastKeyEvent = _lastKeyEvent.asStateFlow()
    private val _cameraLens = MutableStateFlow(CameraSelector.LENS_FACING_BACK); val cameraLens = _cameraLens.asStateFlow()
    private val _cameraMode = MutableStateFlow("Normal"); val cameraMode = _cameraMode.asStateFlow()
    private val _timelapseInterval = MutableStateFlow(2); val timelapseInterval = _timelapseInterval.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            pathingService = (service as GimbalPathingService.LocalBinder).getService()
            isServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { pathingService = null; isServiceBound = false }
    }

    init {
        application.bindService(Intent(application, GimbalPathingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        viewModelScope.launch { bleManager.hardwareKeyEvents.collectLatest { handleHardwareKey(it) } }
    }

    private fun playBeep(durationMs: Int = 200) {
        if (_useSound.value) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
    }

    fun applyPreset(preset: GimbalPreset) {
        _totalTime.value = preset.totalTime; _useDelay.value = preset.useDelay; _useSound.value = preset.useSound
        _cameraMode.value = preset.cameraMode; _timelapseInterval.value = preset.timelapseInterval
        _waypoints.value = preset.waypoints.map { Waypoint(yaw = it.yaw, pitch = it.pitch, timeMultiplier = it.timeMultiplier) }
    }

    fun startScan() = bleManager.startScan().also { _isScanning.value = !it }
    fun stopScan() { _isScanning.value = false; bleManager.stopScan() }
    fun connectGimbal(mac: String) = bleManager.connect(mac)
    fun disconnectGimbal() = bleManager.disconnect()

    fun addWaypointAt(pitch: Float? = null, yaw: Float? = null) {
        val curr = bleManager.telemetry.value
        val clampedYaw = (yaw ?: curr.second).coerceIn(-160f, 160f)
        val clampedPitch = (pitch ?: curr.first).coerceIn(-35f, 35f)
        
        _waypoints.update { it + Waypoint(pitch = clampedPitch, yaw = clampedYaw) }
        _lastKeyEvent.value = "Point Added: Yaw ${String.format("%.1f", clampedYaw)}°, Pitch ${String.format("%.1f", clampedPitch)}°"
    }

    fun removeLastWaypoint() {
        if (_waypoints.value.isNotEmpty()) {
            _waypoints.update { it.dropLast(1) }
            _lastKeyEvent.value = "Removed Last Waypoint"
        }
    }

    fun clearAllWaypoints() {
        _waypoints.value = emptyList(); pathingService?.cancelPathing()
        _isRunning.value = false; _countdown.value = null
    }

    fun updateWaypointMultiplier(index: Int, multiplier: Float) {
        _waypoints.update { list -> 
            list.toMutableList().apply { if (index in indices) this[index] = this[index].copy(timeMultiplier = multiplier) } 
        }
    }

    fun pausePathing() = pathingService?.pausePathing()
    fun resumePathing() = pathingService?.resumePathing()

    fun adjustTime(delta: Int) { _totalTime.update { (it + delta).coerceIn(5, 120) } }
    fun toggleDelay() { _useDelay.update { !it } }
    fun toggleSound() { _useSound.update { !it } }
    fun toggleAutoRecord() { _autoRecord.update { !it } }
    fun setCameraLens(lensIndex: Int) { _cameraLens.value = lensIndex }
    fun adjustTimelapseInterval(delta: Int) { _timelapseInterval.update { (it + delta).coerceIn(1, 60) } }
    fun setCurrentProject(name: String) { _currentProject.value = name }

    fun cycleCameraLens() {
        _cameraLens.update { if (it == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }
    }

    fun cycleCameraMode() {
        _cameraMode.update { if (it == "Normal") "Slow-Mo" else if (it == "Slow-Mo") "Timelapse" else "Normal" }
    }

    fun triggerPathPlay() {
        if (_waypoints.value.size < 2) return
        _isRunning.value = true

        pathingService?.startPathing(
            ble = bleManager, waypoints = _waypoints.value, totalDurationSec = _totalTime.value, useDelay = _useDelay.value,
            onCountdown = { sec ->
                _countdown.value = sec.takeIf { it > 0 }
                playBeep(if (sec > 0) 200 else 600)
            },
            onComplete = { _isRunning.value = false; playBeep(300) }
        )
    }

    fun handleHardwareKey(code: Int) = when (code) {
        1111 -> adjustTime(5).also { _lastKeyEvent.value = "Zoom Up (Time +5s)" }
        1112 -> adjustTime(-5).also { _lastKeyEvent.value = "Zoom Down (Time -5s)" }
        1113 -> addWaypointAt().also { _lastKeyEvent.value = "M-Button Pressed (System)" }
        1114 -> clearAllWaypoints().also { _lastKeyEvent.value = "Trigger Button Pressed" }
        24, 25 -> triggerPathPlay().also { _lastKeyEvent.value = "Record Button Pressed" }
        else -> _lastKeyEvent.value = "Key code: $code"
    }

    override fun onCleared() {
        if (isServiceBound) getApplication<Application>().unbindService(serviceConnection)
        bleManager.disconnect()
        toneGenerator?.release()
        toneGenerator = null
        super.onCleared()
    }
}