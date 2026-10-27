package eu.darken.capod.pods.core.apple.aap.protocol

import eu.darken.capod.reaction.core.headgesture.HeadMotion
import eu.darken.capod.reaction.core.headgesture.HeadTrackingSample
import java.io.ByteArrayOutputStream

/** RTBuddy sensor subset derived from LibrePods rewrite 80aa7461 (GPL-3.0-or-later). */
object BuddySensorCodec {
    fun configure(service: Int, intervalMicros: Int, sequence: Int): ByteArray {
        require(service in 1..127 && intervalMicros >= 0 && sequence >= 0)
        val configuration = byteArrayOf(1) + ByteArray(4) { (intervalMicros ushr (8 * it)).toByte() }
        val setting = byteArrayOf(8, service.toByte(), 0x10, 2, 0x1A, 5) + configuration
        val seq = if (sequence == 0) byteArrayOf() else byteArrayOf(8) + varint(sequence)
        val protobuf = seq + byteArrayOf(0x42) + varint(setting.size) + setting
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, protobuf.size.toByte(), (protobuf.size ushr 8).toByte()) + protobuf
    }

    fun decodeHeartRate(raw: ByteArray): Int? {
        val report = report(raw) ?: return null
        if (report.service !in setOf(19, 20) || report.payload.size != 18 || report.payload[17] != 0.toByte()) return null
        return (report.payload[1].toInt() and 0xFF).takeIf { it != 0 }
    }

    fun decodeMotion(raw: ByteArray): HeadMotion? = decodeHead(raw, 0L)?.motion

    fun decodeHead(raw: ByteArray, receivedAtMs: Long): HeadTrackingSample? {
        val report = report(raw) ?: return null
        if (report.service !in setOf(14, 15, 16) || report.payload.size != 58) return null
        fun i16(offset: Int) = ((report.payload[offset].toInt() and 0xFF) or (report.payload[offset + 1].toInt() shl 8)).toShort().toInt()
        return HeadTrackingSample(HeadMotion(i16(28), i16(30)), i16(20), i16(22), i16(24), receivedAtMs)
    }

    private data class Report(val service: Int, val payload: ByteArray)
    private data class Field(val number: Int, val integer: Long? = null, val bytes: ByteArray? = null)

    private fun report(raw: ByteArray): Report? {
        if (raw.size < 12 || !raw.copyOfRange(0, 10).contentEquals(byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0))) return null
        val length = (raw[10].toInt() and 0xFF) or ((raw[11].toInt() and 0xFF) shl 8)
        if (length > 4_096 || length > raw.size - 12) return null
        val top = fields(raw.copyOfRange(12, 12 + length)) ?: return null
        val command = top.lastOrNull { it.number == 7 }?.bytes ?: return null
        val nested = fields(command) ?: return null
        val service = nested.lastOrNull { it.number == 1 }?.integer ?: return null
        val payload = nested.lastOrNull { it.number == 3 }?.bytes ?: return null
        if (service !in 1..127) return null
        return Report(service.toInt(), payload)
    }

    /** Bounded protobuf parser: skip unknown fields without assuming positional offsets. */
    private fun fields(data: ByteArray): List<Field>? {
        var offset = 0
        fun readVarint(): Long? {
            var result = 0L
            for (index in 0..9) {
                if (offset >= data.size) return null
                val b = data[offset++].toInt() and 0xFF
                if (index == 9 && b > 1) return null
                result = result or ((b and 0x7F).toLong() shl (index * 7))
                if (b and 0x80 == 0) return result
            }
            return null
        }
        val result = mutableListOf<Field>()
        while (offset < data.size) {
            val tag = readVarint() ?: return null
            if (tag <= 0 || tag > Int.MAX_VALUE) return null
            val number = (tag ushr 3).toInt()
            if (number == 0) return null
            when ((tag and 7).toInt()) {
                0 -> result += Field(number, integer = readVarint() ?: return null)
                1, 5 -> {
                    val size = if (tag and 7 == 1L) 8 else 4
                    if (size > data.size - offset) return null
                    offset += size
                }
                2 -> {
                    val size = readVarint() ?: return null
                    if (size < 0 || size > data.size - offset) return null
                    result += Field(number, bytes = data.copyOfRange(offset, offset + size.toInt()))
                    offset += size.toInt()
                }
                else -> return null
            }
        }
        return result
    }

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val bytes = ByteArrayOutputStream()
        while (remaining and -128 != 0) { bytes.write((remaining and 127) or 128); remaining = remaining ushr 7 }
        bytes.write(remaining)
        return bytes.toByteArray()
    }
}
