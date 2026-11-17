package tech.capullo.quantumcast.recording

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Native FLAC files recorded from fMP4 HLS. Two fixes make them seekable:
 *
 * - While recording, [Rebaser] renumbers frames so the file starts at sample 0 (a live stream's
 *   frame numbers count from when its encoder started; seekers search by those numbers).
 * - STREAMINFO's total-samples can't be known until the recording ends, and an append-only file
 *   has no "end" step (a killed process must still leave a playable file). So before playback,
 *   [fixTotalSamples] reads the last frame's header and fills it in once.
 */
object FlacFrames {
    private const val TAG = "FlacFrames"

    private class Header(val number: Long, val numberLen: Int, val variable: Boolean, val blockSize: Int, val size: Int)

    /** Parses a frame header at [p] (sync, fields, CRC-8); null if it isn't one. */
    private fun header(b: ByteArray, p: Int, end: Int): Header? {
        if (p + 6 > end) return null
        val b1 = b[p + 1].toInt() and 0xFF
        if ((b[p].toInt() and 0xFF) != 0xFF || (b1 and 0xFE) != 0xF8) return null
        val bsCode = (b[p + 2].toInt() and 0xF0) shr 4
        val srCode = b[p + 2].toInt() and 0x0F
        if (bsCode == 0 || srCode == 15 || (b[p + 3].toInt() and 0x01) != 0) return null
        // Frame/sample number, UTF-8 style coded (up to 7 bytes for 36 bits).
        val b0 = b[p + 4].toInt() and 0xFF
        val n = when {
            b0 and 0x80 == 0 -> 1
            b0 and 0xE0 == 0xC0 -> 2
            b0 and 0xF0 == 0xE0 -> 3
            b0 and 0xF8 == 0xF0 -> 4
            b0 and 0xFC == 0xF8 -> 5
            b0 and 0xFE == 0xFC -> 6
            b0 == 0xFE -> 7
            else -> return null
        }
        if (p + 4 + n > end) return null
        var v = if (n == 1) b0.toLong() else (b0 and (0x7F shr n)).toLong()
        for (i in 1 until n) {
            val c = b[p + 4 + i].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return null
            v = (v shl 6) or (c and 0x3F).toLong()
        }
        var q = p + 4 + n
        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> (if (q + 1 > end) return null else (b[q].toInt() and 0xFF) + 1).also { q += 1 }
            7 -> (if (q + 2 > end) return null else (((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)) + 1).also { q += 2 }
            else -> 256 shl (bsCode - 8)
        }
        q += when (srCode) {
            12 -> 1
            13, 14 -> 2
            else -> 0
        }
        if (q >= end || crc8(b, p, q) != (b[q].toInt() and 0xFF)) return null
        return Header(v, n, b1 and 1 == 1, blockSize, q + 1 - p)
    }

    private fun encodeNumber(v: Long): ByteArray {
        if (v < 0x80) return byteArrayOf(v.toByte())
        val n = when {
            v < 0x800 -> 2
            v < 0x10000 -> 3
            v < 0x200000 -> 4
            v < 0x4000000 -> 5
            v < 0x80000000L -> 6
            else -> 7
        }
        val out = ByteArray(n)
        var x = v
        for (i in n - 1 downTo 1) {
            out[i] = (0x80 or (x and 0x3F).toInt()).toByte()
            x = x shr 6
        }
        out[0] = if (n == 7) 0xFE.toByte() else ((0xFF00 shr n) and 0xFF or x.toInt()).toByte()
        return out
    }

    /** Renumbers the frames of one recording from 0, re-encoding the header and both CRCs. */
    class Rebaser {
        private var base = -1L

        /** The frame rewritten (a new array), or null to write it unchanged. */
        fun rebase(b: ByteArray, off: Int, len: Int): ByteArray? {
            val h = header(b, off, off + len) ?: return null
            if (len < h.size + 2) return null
            if (base < 0) base = h.number
            if (base == 0L) return null
            val num = encodeNumber((h.number - base).coerceAtLeast(0))
            val out = ByteArray(len - h.numberLen + num.size)
            System.arraycopy(b, off, out, 0, 4)
            System.arraycopy(num, 0, out, 4, num.size)
            // Rest of the header (block size / sample rate extensions), CRC-8 slot excluded.
            val rest = h.size - 1 - 4 - h.numberLen
            System.arraycopy(b, off + 4 + h.numberLen, out, 4 + num.size, rest)
            val crc8At = 4 + num.size + rest
            out[crc8At] = crc8(out, 0, crc8At).toByte()
            System.arraycopy(b, off + h.size, out, crc8At + 1, len - h.size)
            val crc16 = crc16(out, 0, out.size - 2)
            out[out.size - 2] = (crc16 shr 8).toByte()
            out[out.size - 1] = crc16.toByte()
            return out
        }
    }

    /**
     * Fills STREAMINFO's total-samples of a `.flac` recording if it's still 0, from the last frame's
     * header. Done once; later calls see a non-zero count and return immediately.
     */
    fun fixTotalSamples(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                // Channels over the descriptor; not closed themselves (that would close pfd's fd).
                val r = FileInputStream(pfd.fileDescriptor).channel
                val w = FileOutputStream(pfd.fileDescriptor).channel
                val samples = fixTotalSamples(r.size(), { buf, at -> r.read(buf, at) }, { buf, at -> w.write(buf, at) })
                if (samples > 0) Log.i(TAG, "$uri: total samples $samples")
            }
        }.onFailure { Log.w(TAG, "$uri: ${it.message}") }
    }

    /** [fixTotalSamples] over positional read/write; returns the count written, or 0 if none. */
    fun fixTotalSamples(size: Long, read: (ByteBuffer, Long) -> Int, write: (ByteBuffer, Long) -> Int): Long {
        val head = ByteBuffer.allocate(26)
        if (read(head, 0) < 26) return 0
        val h = head.array()
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "fLaC" || (h[4].toInt() and 0x7F) != 0) return 0
        val total = ((h[21].toLong() and 0x0F) shl 32) or ((h[22].toLong() and 0xFF) shl 24) or
            ((h[23].toLong() and 0xFF) shl 16) or ((h[24].toLong() and 0xFF) shl 8) or (h[25].toLong() and 0xFF)
        if (total != 0L) return 0
        val fixedBlock = ((h[10].toInt() and 0xFF) shl 8) or (h[11].toInt() and 0xFF) // max = fixed block size
        val tailLen = minOf(size - 26, 1L shl 18).toInt()
        if (tailLen <= 0) return 0
        val tail = ByteBuffer.allocate(tailLen)
        read(tail, size - tailLen)
        val t = tail.array()
        // The last frame header that its predecessor confirms (a lone sync + CRC-8 match in audio
        // data is possible; two consecutive frame numbers are not by chance).
        var samples = 0L
        var later: Header? = null
        for (p in t.size - 6 downTo 0) {
            val f = header(t, p, t.size) ?: continue
            val l = later
            if (l != null && l.variable == f.variable && l.number == f.number + if (f.variable) f.blockSize.toLong() else 1L) {
                samples = (if (l.variable) l.number else l.number * fixedBlock) + l.blockSize
                break
            }
            later = f
        }
        if (samples <= 0 || samples >= 1L shl 36) return 0
        val patched = byteArrayOf(
            ((h[21].toInt() and 0xF0) or (samples shr 32).toInt()).toByte(),
            (samples shr 24).toByte(),
            (samples shr 16).toByte(),
            (samples shr 8).toByte(),
            samples.toByte(),
        )
        write(ByteBuffer.wrap(patched), 21)
        return samples
    }

    private fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
