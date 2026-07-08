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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GimbalPreset(
    val name: String,
    val totalTime: Int,
    val useDelay: Boolean,
    val useSound: Boolean,
    val cameraMode: String = "Normal",
    val timelapseInterval: Int = 2,
    val waypoints: List<WaypointData>
)

data class WaypointData(val yaw: Float, val pitch: Float)

object PresetManager {
    private const val TAG = "PresetManager"

    // Resolves to: /storage/emulated/0/Downloads/GimbalAutoPaths
    private fun getStorageDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val gimbalDir = File(downloadsDir, "GimbalAutoPaths")
        if (!gimbalDir.exists()) {
            gimbalDir.mkdirs()
        }
        return gimbalDir
    }

    fun savePreset(preset: GimbalPreset): Boolean {
        return try {
            val file = File(getStorageDir(), "${preset.name}.json")
            val json = JSONObject().apply {
                put("name", preset.name)
                put("totalTime", preset.totalTime)
                put("useDelay", preset.useDelay)
                put("useSound", preset.useSound)
                put("cameraMode", preset.cameraMode)
                put("timelapseInterval", preset.timelapseInterval)
                
                val wpArray = JSONArray()
                preset.waypoints.forEach { wp ->
                    val wpObj = JSONObject()
                    wpObj.put("yaw", wp.yaw)
                    wpObj.put("pitch", wp.pitch)
                    wpArray.put(wpObj)
                }
                put("waypoints", wpArray)
            }
            file.writeText(json.toString(4))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preset", e)
            false
        }
    }

    fun loadPresets(): List<GimbalPreset> {
        val presets = mutableListOf<GimbalPreset>()
        try {
            val files = getStorageDir().listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
            
            for (file in files) {
                try {
                    val content = file.readText()
                    val json = JSONObject(content)
                    
                    val wpArray = json.optJSONArray("waypoints")
                    val waypoints = mutableListOf<WaypointData>()
                    
                    if (wpArray != null) {
                        for (i in 0 until wpArray.length()) {
                            val wpObj = wpArray.getJSONObject(i)
                            waypoints.add(
                                WaypointData(
                                    yaw = wpObj.optDouble("yaw").toFloat(),
                                    pitch = wpObj.optDouble("pitch").toFloat()
                                )
                            )
                        }
                    }
                    
                    presets.add(
                        GimbalPreset(
                            name = json.optString("name", file.nameWithoutExtension),
                            totalTime = json.optInt("totalTime", 10),
                            useDelay = json.optBoolean("useDelay", false),
                            useSound = json.optBoolean("useSound", false),
                            cameraMode = json.optString("cameraMode", "Normal"),
                            timelapseInterval = json.optInt("timelapseInterval", 2),
                            waypoints = waypoints
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load presets", e)
        }
        return presets
    }
    
    fun deletePreset(presetName: String): Boolean {
        return try {
            val file = File(getStorageDir(), "$presetName.json")
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete preset", e)
            false
        }
    }
}