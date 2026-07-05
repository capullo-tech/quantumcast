package com.quantumcast.shazam

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

data class FrequencyPeak(
    val fftPassNumber: Int,
    val peakMagnitude: Int,
    val correctedPeakFrequencyBin: Int
)

data class DecodedMessage(
    val sampleRateHz: Int,
    val numberSamples: Int,
    val frequencyBandToPeaks: Map<Int, List<FrequencyPeak>>
) {
    companion object {
        private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        private const val SAMPLE_RATE_ID_16000 = 3  // SampleRate._16000 = 3
    }

    fun encodeToBinary(): ByteArray {
        // Encode peaks per band into contents buffer
        val contentsBuf = ByteArrayOutputStream()
        for ((bandId, peaks) in frequencyBandToPeaks.entries.sortedBy { it.key }) {
            val peaksBuf = ByteArrayOutputStream()
            var prevFftPass = 0
            for (peak in peaks) {
                val diff = peak.fftPassNumber - prevFftPass
                if (diff >= 255) {
                    peaksBuf.write(0xFF)
                    peaksBuf.write(leInt(peak.fftPassNumber.toLong()))
                    prevFftPass = peak.fftPassNumber
                }
                peaksBuf.write((peak.fftPassNumber - prevFftPass) and 0xFF)
                peaksBuf.write(leShort(peak.peakMagnitude))
                peaksBuf.write(leShort(peak.correctedPeakFrequencyBin))
                prevFftPass = peak.fftPassNumber
            }
            val peaksData = peaksBuf.toByteArray()
            val padding = (4 - peaksData.size % 4) % 4
            contentsBuf.write(leInt((0x60030040L + bandId)))
            contentsBuf.write(leInt(peaksData.size.toLong()))
            contentsBuf.write(peaksData)
            repeat(padding) { contentsBuf.write(0) }
        }
        val contents = contentsBuf.toByteArray()

        // Build full message buffer
        val buf = ByteArrayOutputStream(48 + 8 + contents.size)

        // Header (48 bytes)
        buf.write(leInt(0xCAFE2580L))                                   // magic1
        buf.write(ByteArray(4))                                          // crc32 placeholder
        buf.write(leInt((contents.size + 8).toLong()))                  // size_minus_header
        buf.write(leInt(0x94119C00L))                                   // magic2
        buf.write(ByteArray(12))                                         // void1 (3 × uint32)
        buf.write(leInt((SAMPLE_RATE_ID_16000.toLong() shl 27)))        // shifted_sample_rate_id
        buf.write(ByteArray(8))                                          // void2 (2 × uint32)
        buf.write(leInt((numberSamples + sampleRateHz * 0.24).toLong())) // number_samples_plus_divided_sample_rate
        buf.write(leInt(((15L shl 19) + 0x40000L)))                    // fixed_value

        // Fixed TLV entry after header
        buf.write(leInt(0x40000000L))
        buf.write(leInt((contents.size + 8).toLong()))

        buf.write(contents)

        val bytes = buf.toByteArray()

        // Compute CRC32 over bytes[8..end] and write back at offset 4
        val crc = CRC32()
        crc.update(bytes, 8, bytes.size - 8)
        val crcVal = crc.value
        bytes[4] = (crcVal and 0xFF).toByte()
        bytes[5] = (crcVal shr 8 and 0xFF).toByte()
        bytes[6] = (crcVal shr 16 and 0xFF).toByte()
        bytes[7] = (crcVal shr 24 and 0xFF).toByte()

        return bytes
    }

    fun encodeToUri(): String =
        DATA_URI_PREFIX + Base64.encodeToString(encodeToBinary(), Base64.NO_WRAP)

    fun sampleMs(): Long = (numberSamples.toLong() * 1000) / sampleRateHz

    private fun leInt(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(),
        (v shr 24 and 0xFF).toByte()
    )

    private fun leShort(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte()
    )
}
