/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.utils

object SplineMath {
    fun getSplinePoint(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        return 0.5f * (
            (2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t
        )
    }

    /**
     * Calculates which segment of the path we are in, and the local 't' [0..1]
     * within that segment, dynamically distributing the global progress
     * based on individual point multipliers to speed up or slow down movement.
     * Keeps path accurate and compensates for telemetry timing limits.
     *
     * @param globalT Total time progress normalized from 0.0 to 1.0.
     * @param multipliers List of time multipliers for each waypoint.
     * @return Pair(segmentIndex, localT)
     */
    fun getSegmentAndLocalT(globalT: Float, multipliers: List<Float>): Pair<Int, Float> {
        if (multipliers.size < 2) return Pair(0, 0f)
        val nSegments = multipliers.size - 1
        
        // Weight mapping: Movement approaching point 'i+1' uses its multiplier
        val weights = FloatArray(nSegments) { i -> multipliers[i + 1] }
        val totalWeight = weights.sum()

        if (totalWeight <= 0f || globalT <= 0f) return Pair(0, 0f)
        if (globalT >= 1f) return Pair(nSegments - 1, 1f)

        val targetWeight = globalT * totalWeight
        var accumulatedWeight = 0f

        for (i in 0 until nSegments) {
            val nextWeight = accumulatedWeight + weights[i]
            if (targetWeight <= nextWeight) {
                // Ensure local T scales perfectly within non-uniform bounds
                val segmentT = (targetWeight - accumulatedWeight) / weights[i]
                return Pair(i, segmentT.coerceIn(0f, 1f))
            }
            accumulatedWeight = nextWeight
        }
        
        return Pair(nSegments - 1, 1f)
    }
}