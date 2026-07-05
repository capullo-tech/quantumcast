package tech.capullo.quantumcast.shazam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object AudioCapturer {
    @Volatile var lastOutRate: Int = 0
        private set
    @Volatile var lastOutCh: Int = 0
        private set
    @Volatile var lastCodec: String = ""
        private set
    @Volatile var lastBitrate: Int = 0
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun capture(streamUrl: String, context: Context): ShortArray? =
        withContext(Dispatchers.IO) {
            Log.d("Shazam", "capture: $streamUrl")

            // Strategy 1: OkHttp direct download - grabs bytes at live edge immediately.
            // MediaExtractor.setDataSource() pre-buffers live streams (can take 90+ seconds).
            tryDownloadAndDecode(streamUrl, context)?.let { return@withContext it }

            // Strategy 1b: HLS - fetch M3U8 playlist, download content segments directly.
            // Strategy 1 rejects M3U8 content-type/magic; this resolves segments manually.
            Log.d("Shazam", "capture: trying HLS segment strategy")
            tryHlsCapture(streamUrl, context)?.let { return@withContext it }

            Log.d("Shazam", "capture: trying MediaExtractor fallback")

            // Strategy 2: MediaExtractor - last resort for unusual stream formats
            tryDirectExtractor(streamUrl)
        }

    private fun tryDirectExtractor(url: String): ShortArray? {
        val extractor = MediaExtractor()
        try {
            Log.d("Shazam", "directExtractor: setDataSource")
            extractor.setDataSource(url, mapOf("Icy-MetaData" to "0"))
            Log.d("Shazam", "directExtractor: trackCount=${extractor.trackCount}")

            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                Log.d("Shazam", "directExtractor: track $i mime=$mime")
                if (mime?.startsWith("audio/") == true) { trackIdx = i; fmt = f; break }
            }
            if (trackIdx < 0 || fmt == null) {
                Log.w("Shazam", "directExtractor: no audio track")
                return null
            }
            extractor.selectTrack(trackIdx)
            return decodeTrack(extractor, fmt)
        } catch (e: Exception) {
            Log.w("Shazam", "directExtractor: failed", e)
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun tryDownloadAndDecode(url: String, context: Context): ShortArray? {
        val tmp = File(context.cacheDir, "shazam_cap.tmp")
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Icy-MetaData", "0")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w("Shazam", "download: HTTP ${resp.code}"); return null }

                val ct = resp.header("Content-Type", "")?.lowercase() ?: ""
                Log.d("Shazam", "download: Content-Type=$ct")
                if (ct.contains("text/") || ct.contains("html") ||
                    ct.contains("mpegurl") || ct.contains("x-scpls")) {
                    Log.w("Shazam", "download: rejected content type"); return null
                }

                val buf = ByteArray(200_000)
                var total = 0
                (resp.body ?: return null).byteStream().use { stream ->
                    while (total < buf.size) {
                        val n = stream.read(buf, total, buf.size - total)
                        if (n < 0) break
                        total += n
                    }
                }

                if (total < 8192) { Log.w("Shazam", "download: too small ($total bytes)"); return null }

                val hdr = buf.copyOf(minOf(total, 16)).toString(Charsets.ISO_8859_1)
                if (hdr.startsWith("#EXTM3U") || hdr.startsWith("<?xml") ||
                    hdr.startsWith("<html") || hdr.startsWith("[playlist]")) {
                    Log.w("Shazam", "download: rejected by magic bytes: ${hdr.take(15)}"); return null
                }

                Log.d("Shazam", "download: got $total bytes")
                val start = findFrameStart(buf, total)
                if (start > 0) Log.d("Shazam", "download: trimmed $start leading bytes before frame sync")
                tmp.writeBytes(buf.copyOfRange(start, total))
            }

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tmp.absolutePath)
                var trackIdx = -1
                var fmt: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIdx = i; fmt = f; break
                    }
                }
                if (trackIdx < 0 || fmt == null) {
                    Log.w("Shazam", "download: no audio track in chunk"); return null
                }
                extractor.selectTrack(trackIdx)
                return decodeTrack(extractor, fmt)
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("Shazam", "download: exception", e)
            return null
        } finally {
            tmp.delete()
        }
    }

    private fun tryHlsCapture(playlistUrl: String, context: Context): ShortArray? {
        val segments = resolveHlsSegments(playlistUrl) ?: return null
        if (segments.isEmpty()) { Log.w("Shazam", "hls: no content segments"); return null }

        // Take the 2 most recent segments (live edge) - each ~12 s, more than enough for Shazam
        val toFetch = segments.takeLast(2)
        val tmp = File(context.cacheDir, "shazam_hls.tmp")
        try {
            val downloaded = mutableListOf<ByteArray>()
            for (segUrl in toFetch) {
                Log.d("Shazam", "hls: fetching $segUrl")
                runCatching {
                    Request.Builder().url(segUrl).header("User-Agent", "Mozilla/5.0").build()
                        .let { http.newCall(it).execute() }.use { resp ->
                            if (resp.isSuccessful) resp.body?.bytes()?.let { downloaded.add(it) }
                        }
                }.onFailure { Log.w("Shazam", "hls: segment error: ${it.message}") }
            }
            if (downloaded.isEmpty()) { Log.w("Shazam", "hls: no segments downloaded"); return null }

            // Strip ID3/preamble from first segment, then concatenate
            val first = downloaded[0]
            val trimStart = findFrameStart(first, first.size)
            val totalSize = (first.size - trimStart) + downloaded.drop(1).sumOf { it.size }
            val combined = ByteArray(totalSize).also { buf ->
                first.copyInto(buf, 0, trimStart)
                var off = first.size - trimStart
                for (b in downloaded.drop(1)) { b.copyInto(buf, off); off += b.size }
            }
            if (combined.size < 8192) { Log.w("Shazam", "hls: combined too small (${combined.size})"); return null }
            Log.d("Shazam", "hls: ${combined.size} bytes from ${downloaded.size} segments")
            tmp.writeBytes(combined)

            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(tmp.absolutePath)
                var trackIdx = -1; var fmt: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIdx = i; fmt = f; break }
                }
                if (trackIdx < 0 || fmt == null) { Log.w("Shazam", "hls: no audio track"); null }
                else { extractor.selectTrack(trackIdx); decodeTrack(extractor, fmt) }
            } finally { runCatching { extractor.release() } }
        } catch (e: Exception) {
            Log.e("Shazam", "hls: exception", e); return null
        } finally { tmp.delete() }
    }

    private fun resolveHlsSegments(url: String): List<String>? {
        val text = runCatching {
            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                .let { http.newCall(it).execute() }.use { it.body?.string() }
        }.getOrNull() ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null

        // Master playlist - recurse into first variant stream
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variantUrl = text.lines().firstOrNull { !it.startsWith("#") && it.isNotBlank() } ?: return null
            val resolved = if (variantUrl.startsWith("http")) variantUrl
                           else "${url.substringBeforeLast("/")}/$variantUrl"
            return resolveHlsSegments(resolved)
        }

        // Media playlist - collect content segments, skip ad segments
        val baseUrl = url.substringBeforeLast("/")
        val segments = mutableListOf<String>()
        var isAd = false
        for (line in text.lines()) {
            when {
                line.startsWith("#EXT-X-DISCONTINUITY") -> isAd = false
                line.startsWith("#EXTINF:") -> isAd = line.contains("VAST", ignoreCase = true)
                !line.startsWith("#") && line.isNotBlank() -> {
                    if (!isAd) segments.add(if (line.startsWith("http")) line else "$baseUrl/$line")
                }
            }
        }
        Log.d("Shazam", "hls: ${segments.size} content segments found")
        return segments
    }

    private fun decodeTrack(extractor: MediaExtractor, fmt: MediaFormat): ShortArray? {
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
        val srcRate = try { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
        val srcCh = try { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 2 }
        val declaredBitrate = try { fmt.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { 0 }
        Log.d("Shazam", "decode: mime=$mime rate=$srcRate ch=$srcCh bitrate=$declaredBitrate")

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.w("Shazam", "decode: no decoder for $mime", e)
            return null
        }

        try {
            codec.configure(fmt, null, null, 0)
            codec.start()

            val chunks = mutableListOf<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decoded = 0
            var stuckCount = 0
            // HE-AAC (SBR/PS): core fmt reports 24kHz mono but decoder outputs 48kHz stereo.
            // These are updated on INFO_OUTPUT_FORMAT_CHANGED with the real output format.
            var outRate = srcRate
            var outCh = srcCh

            while (!outputDone && decoded < outRate * 12) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ibuf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(ibuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        stuckCount = 0
                    }
                }
                when (val idx = codec.dequeueOutputBuffer(info, 10_000)) {
                    in 0..Int.MAX_VALUE -> {
                        val obuf = codec.getOutputBuffer(idx)!!.asShortBuffer()
                        val arr = ShortArray(obuf.remaining()).also { obuf.get(it) }
                        chunks.add(arr)
                        decoded += arr.size / outCh
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        stuckCount = 0
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outRate = try { of.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { outRate }
                        outCh  = try { of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { outCh }
                        lastOutRate = outRate
                        lastOutCh   = outCh
                        lastBitrate = declaredBitrate
                        lastCodec   = when {
                            mime == "audio/mp4a-latm" && outRate > srcRate * 1.4 && outCh > srcCh -> "HE-AAC v2"
                            mime == "audio/mp4a-latm" && outRate > srcRate * 1.4               -> "HE-AAC"
                            mime == "audio/mp4a-latm"                                           -> "AAC-LC"
                            mime == "audio/mpeg" || mime == "audio/mp3"                         -> "MP3"
                            mime == "audio/ogg"                                                 -> "OGG"
                            mime == "audio/flac"                                                -> "FLAC"
                            else -> mime.substringAfter("/")
                        }
                        Log.d("Shazam", "decode: output format updated → $lastCodec ${outRate}Hz ${outCh}ch")
                        stuckCount = 0
                    }
                    else -> if (++stuckCount > 200) {
                        Log.w("Shazam", "decode: stuck after $decoded samples, aborting")
                        break
                    }
                }
            }

            Log.d("Shazam", "decode: $decoded samples in ${chunks.size} chunks rate=$outRate ch=$outCh")
            if (chunks.isEmpty()) return null

            val raw = ShortArray(chunks.sumOf { it.size }).also {
                var off = 0; for (c in chunks) { c.copyInto(it, off); off += c.size }
            }
            return toMono16k(raw, outCh, outRate)
        } finally {
            try { codec.stop(); codec.release() } catch (_: Exception) {}
        }
    }

    // Scan for first recognizable audio frame sync to discard Icecast/stream preamble bytes.
    // ADTS AAC: 0xFF followed by 0xF0-0xFF (12-bit sync = 0xFFF).
    // MP3:      0xFF followed by 0xE0-0xFF (11-bit sync = 0xFFE), but check layer bits too.
    private fun findFrameStart(buf: ByteArray, size: Int): Int {
        for (i in 0 until size - 3) {
            if (buf[i] != 0xFF.toByte()) continue
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b1 and 0xF0 == 0xF0) return i  // ADTS AAC (0xFFF sync)
            if (b1 and 0xE0 == 0xE0 && b1 and 0x06 != 0x00) return i  // MP3 (0xFFE + non-free layer)
        }
        return 0
    }

    private fun toMono16k(pcm: ShortArray, channels: Int, srcRate: Int): ShortArray {
        val mono = if (channels == 1) pcm else ShortArray(pcm.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c].toInt()
            (sum / channels).toShort()
        }
        if (srcRate == 16000) return mono
        return resampleLinear(mono, srcRate, 16000)
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val lo = pos.toInt().coerceIn(0, input.size - 1)
            val hi = (lo + 1).coerceIn(0, input.size - 1)
            (input[lo] * (1.0 - (pos - lo)) + input[hi] * (pos - lo)).toInt().toShort()
        }
    }
}
