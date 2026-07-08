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
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aonozan.gimbalauto.ble.GimbalBleManager
import com.aonozan.gimbalauto.model.Waypoint
import com.aonozan.gimbalauto.service.GimbalPathingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.camera.core.CameraSelector
import com.aonozan.gimbalauto.utils.GimbalPreset

class GimbalViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = GimbalBleManager(application)
    private var pathingService: GimbalPathingService? = null
    private var isServiceBound = false

    val scannedDevices = bleManager.scannedDevices
    val connectedDeviceName = bleManager.connectedDeviceName
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints

    private val _totalTime = MutableStateFlow(20)
    val totalTime: StateFlow<Int> = _totalTime

    private val _useDelay = MutableStateFlow(false)
    val useDelay: StateFlow<Boolean> = _useDelay

    private val _useSound = MutableStateFlow(false)
    val useSound: StateFlow<Boolean> = _useSound

    private val _currentProject = MutableStateFlow("Default")
    val currentProject: StateFlow<String> = _currentProject

    // NEW: Auto-Record toggle to sync video capture with pathing
    private val _autoRecord = MutableStateFlow(false)
    val autoRecord: StateFlow<Boolean> = _autoRecord

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown

    private val _lastKeyEvent = MutableStateFlow("None")
    val lastKeyEvent: StateFlow<String> = _lastKeyEvent

    // Camera features state
    private val _cameraLens = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val cameraLens: StateFlow<Int> = _cameraLens

    private val _cameraMode = MutableStateFlow("Normal")
    val cameraMode: StateFlow<String> = _cameraMode

    private val _timelapseInterval = MutableStateFlow(2) // in seconds
    val timelapseInterval: StateFlow<Int> = _timelapseInterval

    private var toneGenerator: ToneGenerator? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GimbalPathingService.LocalBinder
            pathingService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pathingService = null
            isServiceBound = false
        }
    }

    init {
        val intent = Intent(application, GimbalPathingService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        viewModelScope.launch {
            bleManager.hardwareKeyEvents.collectLatest { code ->
                handleHardwareKey(code)
            }
        }
    }

    private fun playBeep(durationMs: Int = 200) {
        if (useSound.value) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        }
    }

    fun applyPreset(preset: GimbalPreset) {
        _totalTime.value = preset.totalTime
        _useDelay.value = preset.useDelay
        _useSound.value = preset.useSound
        _cameraMode.value = preset.cameraMode
        _timelapseInterval.value = preset.timelapseInterval
        
        val loadedWaypoints = preset.waypoints.map { 
            Waypoint(yaw = it.yaw, pitch = it.pitch) 
        }
        
        _waypoints.value = loadedWaypoints
    }

    fun startScan(): Boolean {
        val autoConnected = bleManager.startScan()
        if (autoConnected) {
            _isScanning.value = false
        } else {
            _isScanning.value = true
        }
        return autoConnected
    }

    fun stopScan() {
        _isScanning.value = false
        bleManager.stopScan()
    }

    fun connectGimbal(macAddress: String) {
        bleManager.connect(macAddress)
    }

    fun disconnectGimbal() {
        bleManager.disconnect()
    }

    fun addWaypoint() {
        val curr = bleManager.telemetry.value
        val clampedYaw = curr.second.coerceIn(-160f, 160f)
        val clampedPitch = curr.first.coerceIn(-35f, 35f)
        val newWp = Waypoint(pitch = clampedPitch, yaw = clampedYaw)
        _waypoints.value = _waypoints.value + newWp
        _lastKeyEvent.value = "Recorded Point: Yaw ${newWp.yaw}°, Pitch ${newWp.pitch}°"
    }

    fun addWaypointAt(pitch: Float, yaw: Float) {
        val clampedYaw = yaw.coerceIn(-160f, 160f)
        val clampedPitch = pitch.coerceIn(-35f, 35f)
        val newWp = Waypoint(pitch = clampedPitch, yaw = clampedYaw)
        _waypoints.value = _waypoints.value + newWp
        _lastKeyEvent.value = "Click-Added Point: Yaw ${String.format("%.1f", clampedYaw)}°, Pitch ${String.format("%.1f", clampedPitch)}°"
    }

    fun removeLastWaypoint() {
        val list = _waypoints.value
        if (list.isNotEmpty()) {
            _waypoints.value = list.dropLast(1)
            _lastKeyEvent.value = "Removed Last Waypoint"
        }
    }

    fun clearAllWaypoints() {
        _waypoints.value = emptyList()
        pathingService?.cancelPathing()
        _isRunning.value = false
        _countdown.value = null
    }

    fun pausePathing() {
        pathingService?.pausePathing()
    }

    fun resumePathing() {
        pathingService?.resumePathing()
    }

    fun adjustTime(delta: Int) {
        val next = (_totalTime.value + delta).coerceIn(5, 120)
        _totalTime.value = next
    }

    fun toggleDelay() { _useDelay.value = !_useDelay.value }

    fun toggleSound() { _useSound.value = !_useSound.value }

    fun toggleAutoRecord() { _autoRecord.value = !_autoRecord.value }

    fun cycleCameraLens() {
        _cameraLens.value = if (_cameraLens.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }
    
    fun setCameraLens(lensIndex: Int) {
        _cameraLens.value = lensIndex
    }

    fun cycleCameraMode() {
        _cameraMode.value = when (_cameraMode.value) {
            "Normal" -> "Slow-Mo"
            "Slow-Mo" -> "Timelapse"
            else -> "Normal"
        }
    }

    fun adjustTimelapseInterval(delta: Int) {
        val next = (_timelapseInterval.value + delta).coerceIn(1, 60)
        _timelapseInterval.value = next
    }

    fun setCurrentProject(name: String) { _currentProject.value = name }

    fun triggerPathPlay() {
        val pathService = pathingService ?: return
        if (_waypoints.value.size < 2) return
        _isRunning.value = true

        pathService.startPathing(
            ble = bleManager,
            waypoints = _waypoints.value,
            totalDurationSec = _totalTime.value,
            useDelay = _useDelay.value,
            onCountdown = { sec ->
                _countdown.value = if (sec > 0) sec else null
                if (sec > 0) playBeep(200) else playBeep(600) 
            },
            onComplete = {
                _isRunning.value = false
                playBeep(300) 
            }
        )
    }


    fun handleHardwareKey(code: Int) {

        when (code) {
            1111 -> {
                adjustTime(5)
                _lastKeyEvent.value = "Zoom Up (Time +5s)"
            }
            1112 -> {
                adjustTime(-5)
                _lastKeyEvent.value = "Zoom Down (Time -5s)"
            }
            1113 -> {
                _lastKeyEvent.value = "M-Button Pressed (System)"
                addWaypoint()
            }
            1114 -> {

                _lastKeyEvent.value = "Trigger Button Pressed"
                clearAllWaypoints()
            }
            24, 25 -> {
                triggerPathPlay()
                _lastKeyEvent.value = "Record Button Pressed"
            }
            else -> {
                _lastKeyEvent.value = "Key code: $code"
            }
        }
    }

    override fun onCleared() {
        if (isServiceBound) getApplication<Application>().unbindService(serviceConnection)
        bleManager.disconnect()
        super.onCleared()
        toneGenerator?.release()
        toneGenerator = null
    }
}