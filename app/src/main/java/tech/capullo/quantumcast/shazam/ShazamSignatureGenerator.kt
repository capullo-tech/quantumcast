package tech.capullo.quantumcast.shazam

import android.util.Log
import kotlin.math.*

/**
 * Port of shazamio's algorithm.py / SongRec signature format.
 * Input: signed 16-bit PCM at 16 kHz mono (via feedInput).
 * Call getSignature() to retrieve the DecodedMessage for sending to Shazam.
 */
class ShazamSignatureGenerator {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FFT_SIZE = 2048
        private const val BINS = FFT_SIZE / 2 + 1 // 1025
        private const val RING_SIZE = 256 // fft/spread ring buffer size

        // np.hanning(2050)[1:-1]: 0.5*(1 - cos(2π*(i+1)/2049)) for i in 0..2047
        private val HANNING = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * PI * (i + 1) / 2049.0))
        }

        // Frequency-domain neighbor offsets for peak recognition
        // [*range(-10,-3,3), -3, 1, *range(2,9,3)] = [-10,-7,-4,-3,1,2,5,8]
        private val FREQ_OFFSETS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)

        // Time-domain offsets (into spread_fft ring buffer, relative to current write pos)
        // [-53, -45, *range(165,201,7), *range(214,250,7)]
        private val TIME_OFFSETS = intArrayOf(
            -53, -45,
            165, 172, 179, 186, 193, 200,
            214, 221, 228, 235, 242, 249,
        )
    }

    // Sample ring buffer (2048 shorts)
    private val sampleRing = IntArray(FFT_SIZE)
    private var sampleRingPos = 0

    // FFT output ring buffer (256 × 1025 doubles)
    private val fftOutputs = Array(RING_SIZE) { DoubleArray(BINS) }
    private var fftPos = 0
    private var fftWritten = 0

    // Spread FFT ring buffer (256 × 1025 doubles)
    private val spreadFft = Array(RING_SIZE) { DoubleArray(BINS) }
    private var spreadPos = 0
    private var spreadWritten = 0

    // Collected peaks: band id (0-3) → list of FrequencyPeak
    private val peaks = mutableMapOf<Int, MutableList<FrequencyPeak>>()

    var numSamples = 0
        private set

    /** Feed signed 16-bit 16 kHz mono PCM samples. */
    fun feedInput(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 128, samples.size)
            doFft(samples, offset, end)
            doPeakSpreadingAndRecognition()
            numSamples += end - offset
            offset = end
        }
    }

    private fun doFft(samples: ShortArray, from: Int, to: Int) {
        for (i in from until to) {
            sampleRing[sampleRingPos] = samples[i].toInt()
            sampleRingPos = (sampleRingPos + 1) % FFT_SIZE
        }

        // Build windowed input from linearised ring buffer
        val real = DoubleArray(FFT_SIZE) { i ->
            sampleRing[(sampleRingPos + i) % FFT_SIZE] * HANNING[i]
        }
        val imag = DoubleArray(FFT_SIZE)
        fft(real, imag)

        // Magnitude: (re²+im²) / 131072, clamped to ≥ 1e-10
        val mag = fftOutputs[fftPos]
        for (i in 0 until BINS) {
            mag[i] = maxOf((real[i] * real[i] + imag[i] * imag[i]) / (1 shl 17), 1e-10)
        }
        fftPos = (fftPos + 1) % RING_SIZE
        fftWritten++
    }

    private fun doPeakSpreadingAndRecognition() {
        doPeakSpreading()
        if (spreadWritten >= 46) doPeakRecognition()
    }

    private fun doPeakSpreading() {
        val lastFft = fftOutputs[(fftPos - 1 + RING_SIZE) % RING_SIZE]

        // Frequency spreading: each bin = max(bin, bin+1, bin+2); last 3 bins unchanged
        val freqSpread = spreadFft[spreadPos] // reuse slot before incrementing pos
        for (i in 0 until BINS - 3) freqSpread[i] = maxOf(lastFft[i], lastFft[i + 1], lastFft[i + 2])
        for (i in BINS - 3 until BINS) freqSpread[i] = lastFft[i]

        // Time spreading: propagate max into positions -1, -3, -6 from current write pos
        val i1 = (spreadPos - 1 + RING_SIZE) % RING_SIZE
        val i2 = (spreadPos - 3 + RING_SIZE) % RING_SIZE
        val i3 = (spreadPos - 6 + RING_SIZE) % RING_SIZE
        for (b in 0 until BINS) {
            val v = freqSpread[b]
            spreadFft[i1][b] = maxOf(v, spreadFft[i1][b])
            spreadFft[i2][b] = maxOf(v, spreadFft[i2][b])
            spreadFft[i3][b] = maxOf(v, spreadFft[i3][b])
        }

        spreadPos = (spreadPos + 1) % RING_SIZE
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val fftMinus46 = fftOutputs[(fftPos - 46 + RING_SIZE) % RING_SIZE]
        val fftMinus49 = spreadFft[(spreadPos - 49 + RING_SIZE) % RING_SIZE]
        val fftNumber = spreadWritten - 46

        for (bin in 10 until 1015) {
            val v = fftMinus46[bin]
            if (v < 1.0 / 64.0) continue
            if (v < fftMinus49[bin - 1]) continue

            // Frequency-domain neighbourhood check in fft_minus_49
            var maxNeighbor = 0.0
            for (off in FREQ_OFFSETS) maxNeighbor = maxOf(maxNeighbor, fftMinus49[bin + off])
            if (v <= maxNeighbor) continue

            // Time-domain neighbourhood check across spread_fft offsets
            for (off in TIME_OFFSETS) {
                val idx = ((spreadPos + off) % RING_SIZE + RING_SIZE) % RING_SIZE
                maxNeighbor = maxOf(maxNeighbor, spreadFft[idx][bin - 1])
            }
            if (v <= maxNeighbor) continue

            // Quadratic interpolation for sub-bin frequency accuracy
            val mag = ln(maxOf(1.0 / 64, v)) * 1477.3 + 6144.0
            val magBefore = ln(maxOf(1.0 / 64, fftMinus46[bin - 1])) * 1477.3 + 6144.0
            val magAfter = ln(maxOf(1.0 / 64, fftMinus46[bin + 1])) * 1477.3 + 6144.0
            val var1 = mag * 2 - magBefore - magAfter
            if (var1 <= 0) continue
            val var2 = (magAfter - magBefore) * 32.0 / var1

            val correctedBin = (bin * 64 + var2).toInt()
            val freqHz = correctedBin * (SAMPLE_RATE.toDouble() / 2.0 / 1024.0 / 64.0)

            val bandId = when {
                freqHz in 250.0..520.0 -> 0
                freqHz in 520.0..1450.0 -> 1
                freqHz in 1450.0..3500.0 -> 2
                freqHz in 3500.0..5500.0 -> 3
                else -> continue
            }

            peaks.getOrPut(bandId) { mutableListOf() }
                .add(FrequencyPeak(fftNumber, mag.toInt(), correctedBin))
        }
    }

    /** Returns null if fewer than 10 peaks were found. */
    fun getSignature(): DecodedMessage? {
        val total = peaks.values.sumOf { it.size }
        Log.d("Shazam", "signature: $total peaks across ${peaks.size} bands, numSamples=$numSamples")
        if (total < 10) return null
        return DecodedMessage(
            sampleRateHz = SAMPLE_RATE,
            numberSamples = numSamples,
            frequencyBandToPeaks = peaks.toMap(),
        )
    }

    // ── Cooley-Tukey in-place FFT ──────────────────────────────────────────────

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var cRe = 1.0
                var cIm = 0.0
                for (jj in 0 until len / 2) {
                    val uRe = re[i + jj]
                    val uIm = im[i + jj]
                    val vRe = re[i + jj + len / 2] * cRe - im[i + jj + len / 2] * cIm
                    val vIm = re[i + jj + len / 2] * cIm + im[i + jj + len / 2] * cRe
                    re[i + jj] = uRe + vRe
                    im[i + jj] = uIm + vIm
                    re[i + jj + len / 2] = uRe - vRe
                    im[i + jj + len / 2] = uIm - vIm
                    val nRe = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
