/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.utils

import android.os.Environment
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GimbalPreset(
    val name: String,
    val totalTime: Int,
    val useDelay: Boolean,
    val useSound: Boolean,
    val cameraMode: String = "Normal",
    val timelapseInterval: Int = 2,
    val waypoints: List<WaypointData>
)

@Serializable
data class WaypointData(val yaw: Float, val pitch: Float, val timeMultiplier: Float = 1.0f)

object PresetManager {
    private const val TAG = "PresetManager"
    private val jsonFormatter = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val storageDir: File get() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "GimbalAutoPaths"
    ).apply { if (!exists()) mkdirs() }

    fun savePreset(preset: GimbalPreset): Boolean = runCatching {
        val jsonString = jsonFormatter.encodeToString(preset)
        File(storageDir, "${preset.name}.json").writeText(jsonString)
        true
    }.onFailure { Log.e(TAG, "Failed to save preset", it) }.getOrDefault(false)

    fun loadPresets(): List<GimbalPreset> = runCatching {
        storageDir.listFiles { _, name -> name.endsWith(".json") }?.mapNotNull { file ->
            runCatching {
                jsonFormatter.decodeFromString<GimbalPreset>(file.readText())
            }.onFailure { Log.e(TAG, "Failed to parse ${file.name}", it) }.getOrNull()
        } ?: emptyList()
    }.onFailure { Log.e(TAG, "Failed to load presets", it) }.getOrDefault(emptyList())
    
    fun deletePreset(presetName: String): Boolean = runCatching {
        File(storageDir, "$presetName.json").takeIf { it.exists() }?.delete() ?: false
    }.onFailure { Log.e(TAG, "Failed to delete preset", it) }.getOrDefault(false)
}