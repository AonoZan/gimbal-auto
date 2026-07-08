/*
 * Copyright (c) 2026 Dejan Petrovic <7921470+AonoZan@users.noreply.github.com>
 * * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aonozan.gimbalauto.utils

object CrcUtils {

    fun computeCrc8(buffer: ByteArray, length: Int): Int {
        var crc = 0x00
        for (i in 0 until length) {
            val b = buffer[i].toInt() and 0xFF
            var reflectedB = 0
            for (j in 0 until 8) {
                if (((b shr j) and 1) != 0) {
                    reflectedB = reflectedB or (1 shl (7 - j))
                }
            }
            crc = crc xor reflectedB
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x31) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        var reflectedCrc = 0
        for (j in 0 until 8) {
            if (((crc shr j) and 1) != 0) {
                reflectedCrc = reflectedCrc or (1 shl (7 - j))
            }
        }
        return (reflectedCrc xor 0x95) and 0xFF
    }

    fun computeCrc16(buffer: ByteArray, length: Int): Int {
        var crc = 0x3692
        for (i in 0 until length) {
            val b = buffer[i].toInt() and 0xFF
            crc = crc xor b
            for (j in 0 until 8) {
                crc = if ((crc and 1) != 0) {
                    (crc shr 1) xor 0x8408
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}