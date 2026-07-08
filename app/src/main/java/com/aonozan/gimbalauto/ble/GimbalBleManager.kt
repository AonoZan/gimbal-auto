/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.aonozan.gimbalauto.utils.CrcUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class GimbalBleManager(private val context: Context) {

    companion object {
        private const val TAG = "GimbalBleManager"
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var keepAliveJob: Job? = null
    private var seqNumber = 0x0100

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState

    private val _telemetry = MutableStateFlow(Pair(0.0f, 0.0f))
    val telemetry: StateFlow<Pair<Float, Float>> = _telemetry

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _motorLoad = MutableStateFlow<Int?>(null)
    val motorLoad: StateFlow<Int?> = _motorLoad

    private val _gimbalMode = MutableStateFlow("Follow / Ready")
    val gimbalMode: StateFlow<String> = _gimbalMode

    private val _hardwareKeyEvents = MutableSharedFlow<Int>(replay = 0)
    val hardwareKeyEvents: SharedFlow<Int> = _hardwareKeyEvents

    // Added: Delta Tracker Cache to prevent spam
    private val lastPayloads = mutableMapOf<String, String>()
    
    private var mButtonJob: Job? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to Gimbal. Discovering services...")
                try {
                    _connectedDeviceName.value = gatt.device.name ?: "Unknown Gimbal"
                } catch (e: SecurityException) {
                    _connectedDeviceName.value = "Gimbal"
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected. Cleaning up...")
                stopKeepAlive()
                bluetoothGatt = null
                lastPayloads.clear()
                _connectedDeviceName.value = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupNotification(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NOTIFY_UUID) {
                parseNotification(characteristic.value)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val currentList = _scannedDevices.value
            if (currentList.none { it.address == device.address }) {
                // Optionally we could filter for specific names, but here we add all discovered BLE devices
                try {
                    if (!device.name.isNullOrBlank()) {
                        _scannedDevices.value = currentList + device
                    }
                } catch (e: SecurityException) {
                    // Ignore devices if we don't have permission to read their name yet
                }
            }
        }
    }

    private fun isGimbalDevice(device: BluetoothDevice): Boolean {
        return try {
            val name = device.name ?: return false
            name.startsWith("OM", ignoreCase = true) || 
            name.contains("DJI", ignoreCase = true) || 
            name.contains("Osmo", ignoreCase = true)
        } catch (e: SecurityException) {
            false
        }
    }

    fun startScan(): Boolean {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            
            val bondedGimbals = adapter.bondedDevices?.filter { isGimbalDevice(it) } ?: emptyList()
            val connectedGatt = manager.getConnectedDevices(BluetoothProfile.GATT)?.filter { isGimbalDevice(it) } ?: emptyList()
            
            val knownGimbals = (bondedGimbals + connectedGatt).distinctBy { it.address }
            
            if (knownGimbals.isNotEmpty()) {
                Log.d(TAG, "Auto-connecting to known gimbal: ${knownGimbals.first().address}")
                connect(knownGimbals.first().address)
                return true
            }

            bluetoothLeScanner = adapter.bluetoothLeScanner
            _scannedDevices.value = emptyList()
            bluetoothLeScanner?.startScan(scanCallback)
            Log.d(TAG, "Started BLE scan")
            return false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan", e)
            return false
        }
    }

    fun stopScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scan")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan", e)
        }
    }

    fun connect(macAddress: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(macAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        stopKeepAlive()
    }

    private fun setupNotification(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(NOTIFY_UUID) ?: return
        
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID).apply {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        gatt.writeDescriptor(descriptor)

        scope.launch {
            delay(400)
            initializeGimbalAppMode()
            startKeepAlive()
        }
    }

    private suspend fun initializeGimbalAppMode() {
        Log.d(TAG, "Initializing OM App Mode Handshake...")
        writePacket(0x00, 0x2b, byteArrayOf(0x04.toByte(), 0x00.toByte()))
        delay(100)

        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val timePayload = byteArrayOf(
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            calendar.get(Calendar.SECOND).toByte()
        )
        writePacket(0x00, 0x4a, timePayload)
        delay(100)

        writePacket(0x00, 0x32, byteArrayOf(0x11.toByte()))
        delay(100)

        writePacket(0x00, 0x34, byteArrayOf(0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
        delay(100)

        writePacket(0x04, 0x10, byteArrayOf(0x0a.toByte()))
        delay(150)

        writePacket(0x04, 0x10, byteArrayOf(0x69.toByte()))
        delay(150)

        writePacket(0x04, 0x54, byteArrayOf(
            0x01.toByte(), 0xb0.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        ))
        Log.d(TAG, "Gimbal Handshake Completed.")
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                writePacket(0x00, 0x4f, byteArrayOf(
                    0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()
                ))
                delay(1500)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun sendVelocityCommand(yawSpeed: Short, pitchSpeed: Short) {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(yawSpeed)
        buffer.putShort(0.toShort())      
        buffer.putShort(pitchSpeed)
        buffer.putShort(0x0087.toShort()) 
        writePacket(0x04, 0x0C, buffer.array())
    }

    fun setGimbalMode(on: Boolean) {
        val state = if (on) 0x03.toByte() else 0x01.toByte()
        writePacket(0x04, 0x4C, byteArrayOf(state))
    }

    private fun writePacket(cmdSet: Int, cmdId: Int, payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val writeChar = service.getCharacteristic(WRITE_UUID) ?: return

        val totalLen = 13 + payload.size
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(0x55.toByte())
        buffer.put((totalLen and 0xFF).toByte())
        buffer.put((((totalLen shr 8) and 0x03) or (1 shl 2)).toByte())
        buffer.put(CrcUtils.computeCrc8(buffer.array(), 3).toByte())
        buffer.putShort(0x0402.toShort())
        
        val seq = seqNumber++
        if (seqNumber > 0xFF00) seqNumber = 0x0100
        buffer.put(((seq shr 8) and 0xFF).toByte())
        buffer.put((seq and 0xFF).toByte())
        
        buffer.put(0x40.toByte())
        buffer.put(cmdSet.toByte())
        buffer.put(cmdId.toByte())
        buffer.put(payload)
        
        val crc16 = CrcUtils.computeCrc16(buffer.array(), totalLen - 2)
        buffer.putShort(crc16.toShort())

        writeChar.value = buffer.array()
        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(writeChar)
    }

    private fun parseNotification(bytes: ByteArray) {
        if (bytes.size < 12) return
        val cmdSet = bytes[9].toInt() and 0xFF
        val cmdId = bytes[10].toInt() and 0xFF

        if (cmdSet == 0x04 && cmdId == 0x05 && bytes.size >= 17) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            
            val rawPitch = buffer.getShort(11).toFloat() / 10.0f
            val yaw = buffer.getShort(15).toFloat() / 10.0f
            
            var centeredPitch = if (rawPitch > 0) rawPitch - 180f else rawPitch + 180f
            
            while (centeredPitch > 180f) centeredPitch -= 360f
            while (centeredPitch < -180f) centeredPitch += 360f

            _telemetry.value = Pair(centeredPitch, yaw)
        }
        else if (cmdSet == 0x05 && cmdId == 0x06) {
            _batteryPercent.value = bytes[11].toInt() and 0xFF
        }
        else if (cmdSet == 0x04 && cmdId == 0x02 && bytes.size >= 17) {
            _motorLoad.value = bytes[16].toInt() and 0xFF
        }
        else if (cmdSet == 0x04 && cmdId == 76) {
            _gimbalMode.value = when (bytes[11].toInt() and 0xFF) {
                0x00 -> "Follow / Ready"
                0x01 -> "Pan Follow"
                0x02 -> "FPV Mode"
                0xE3 -> "Lock / Sport"
                else -> "Custom Mode"
            }
        }

        if (bytes.size >= 13) {
            val payload = bytes.copyOfRange(11, bytes.size - 2)
            val payloadHex = payload.joinToString("") { "%02X".format(it) }

            val key = "${cmdSet}_${cmdId}"
            val prevHex = lastPayloads[key]
            
            val isTransition = (prevHex == null || prevHex != payloadHex)

            if (isTransition) {
                if (cmdSet == 0x04 && cmdId == 87) {
                    val zoomVal = bytes[15].toInt() and 0xFF
                    val btnMask = bytes[16].toInt() and 0xFF

                    val isZoomUp = (zoomVal and 0x80) == 0x80
                    val isZoomDown = (zoomVal and 0x80) == 0 && (btnMask and 0x04) == 0x04
                    val isMClick = zoomVal == 3

                    if (isMClick) {
                        mButtonJob?.cancel()
                        mButtonJob = scope.launch {
                            delay(800)
                            _hardwareKeyEvents.emit(1116) // 1116 = M Button Long Press
                            mButtonJob = null
                        }
                    } else {
                        if (mButtonJob != null) {
                            mButtonJob?.cancel()
                            mButtonJob = null
                            scope.launch { _hardwareKeyEvents.emit(1113) } // 1113 = M Button Short Press
                        }
                    }

                    scope.launch {
                        if (isZoomUp) _hardwareKeyEvents.emit(1111)
                        else if (isZoomDown) _hardwareKeyEvents.emit(1112)
                    }
                }
                else if (cmdSet == 0x04 && cmdId == 25) {
                    val isTriggerPressed = bytes[11].toInt() == 16
                    scope.launch {
                        _hardwareKeyEvents.emit(if (isTriggerPressed) 1114 else 1115)
                    }
                }
            }
            lastPayloads[key] = payloadHex
        }
    }
}