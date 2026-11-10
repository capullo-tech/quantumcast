package tech.capullo.quantumcast.recording

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Records one station by opening its own connection and writing the audio bytes as sent - no
 * decoding, no re-encoding. Independent of the playback engine on purpose: a recording must not
 * make sound, and must survive pause, station changes, rotation and the app being swiped away.
 *
 * Handles Icecast/Shoutcast (ICY metadata stripped, StreamTitle surfaced), .pls/.m3u/.asx
 * playlists, and HLS (TS segments demuxed to their audio elementary stream, packed-audio segments
 * with their ID3 timestamp stripped). The output is .mp3 or .aac (or .ogg/.flac), chosen by
 * sniffing the audio itself. Reconnects with backoff until [run]'s end time; a reconnect appends
 * to the same file (MP3/ADTS decoders resync on the next frame).
 */
class StreamRecorder(
    private val station: RecStation,
    /** Opens the output file once the format is known: (extension, mime) → (file name, stream). */
    private val openOutput: (ext: String, mime: String) -> Pair<String, OutputStream>,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data class State(val state: RecState) : Event
        data class FileOpened(val name: String) : Event
        data class Progress(val bytes: Long) : Event
        data class Title(val title: String) : Event
    }

    private var out: OutputStream? = null
    private var bytes = 0L
    private var lastProgress = 0L

    /** Records until [endAtMs] (wall clock) or cancellation. Throws only for unsupported streams. */
    suspend fun run(endAtMs: Long?) {
        var backoffMs = 2_000L
        try {
            while (currentCoroutineContext().isActive && (endAtMs == null || System.currentTimeMillis() < endAtMs)) {
                val before = bytes
                try {
                    session(station.url, endAtMs, depth = 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedStreamException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${station.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
                if (endAtMs != null && System.currentTimeMillis() >= endAtMs) break
                onEvent(Event.State(RecState.RECONNECTING))
                // Data flowed since the last failure → a fresh drop, retry quickly.
                if (bytes > before) backoffMs = 2_000L
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        } finally {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            onEvent(Event.Progress(bytes))
        }
    }

    // --- connection / format detection ---

    private suspend fun session(url: String, endAtMs: Long?, depth: Int) {
        if (depth > 3) throw IOException("Playlist nesting too deep")
        onEvent(Event.State(if (out == null) RecState.CONNECTING else RecState.RECONNECTING))
        val conn = open(url, icy = true)
        try {
            val ct = conn.contentType?.lowercase().orEmpty()
            val path = URL(conn.url.toString()).path.lowercase()
            val looksLikeText = "mpegurl" in ct || "scpls" in ct || "x-ms-asf" in ct || ct.startsWith("text/") ||
                path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".pls") || path.endsWith(".asx")
            if (looksLikeText) {
                val text = conn.inputStream.use { readText(it) }
                val finalUrl = conn.url.toString()
                if ("#EXT-X-" in text) {
                    hls(finalUrl, text, endAtMs)
                    return
                }
                val next = firstPlaylistEntry(text) ?: throw IOException("Empty playlist")
                session(resolve(finalUrl, next), endAtMs, depth + 1)
                return
            }
            val metaInt = conn.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: 0
            conn.inputStream.use { progressive(it, metaInt, endAtMs) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(start: String, icy: Boolean): HttpURLConnection {
        var url = start
        repeat(8) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false // manual: HttpURLConnection won't cross http↔https
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "QuantumCast")
            if (icy) c.setRequestProperty("Icy-MetaData", "1")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                c.disconnect()
                url = resolve(url, loc)
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    // --- progressive (Icecast / Shoutcast) ---

    private suspend fun progressive(input: InputStream, metaInt: Int, endAtMs: Long?) {
        val buf = ByteArray(16 * 1024)
        var untilMeta = metaInt
        while (true) {
            currentCoroutineContext().ensureActive()
            if (endAtMs != null && System.currentTimeMillis() >= endAtMs) return
            val want = if (metaInt > 0) minOf(buf.size, untilMeta) else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) throw IOException("Stream ended")
            writeAudio(buf, 0, n)
            if (metaInt > 0) {
                untilMeta -= n
                if (untilMeta == 0) {
                    readIcyBlock(input)
                    untilMeta = metaInt
                }
            }
        }
    }

    private fun readIcyBlock(input: InputStream) {
        val len = input.read()
        if (len < 0) throw IOException("Stream ended")
        if (len == 0) return
        val meta = ByteArray(len * 16)
        readFully(input, meta)
        val text = String(meta, Charsets.UTF_8)
        Regex("""StreamTitle='(.*?)';""").find(text)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { onEvent(Event.Title(it)) }
    }

    // --- HLS ---

    private suspend fun hls(playlistUrl: String, firstText: String, endAtMs: Long?) {
        var mediaUrl = playlistUrl
        var text = firstText
        if ("#EXT-X-STREAM-INF" in text) {
            mediaUrl = resolve(playlistUrl, pickVariant(text))
            text = fetchText(mediaUrl)
        }
        val ts = TsAudioDemuxer()
        var nextSeq = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (endAtMs != null && System.currentTimeMillis() >= endAtMs) return
            if ("#EXT-X-MAP" in text) throw UnsupportedStreamException("This HLS station uses fMP4 segments, not supported yet")
            Regex("""#EXT-X-KEY:METHOD=([A-Z0-9-]+)""").find(text)?.groupValues?.get(1)?.let {
                if (it != "NONE") throw UnsupportedStreamException("This HLS station is encrypted")
            }
            val mediaSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
            val segs = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            // First load: start near the live edge, like a player would.
            if (nextSeq < 0) nextSeq = mediaSeq + (segs.size - 2).coerceAtLeast(0)
            // Fell behind the window (long stall): jump to what is still listed.
            if (nextSeq < mediaSeq) nextSeq = mediaSeq
            for ((i, seg) in segs.withIndex()) {
                val seq = mediaSeq + i
                if (seq < nextSeq) continue
                val data = fetchBytes(resolve(mediaUrl, seg))
                if (data.isNotEmpty() && data[0] == 0x47.toByte()) {
                    ts.feed(data) { b, off, len -> writeAudio(b, off, len) }
                } else {
                    val skip = id3Size(data)
                    writeAudio(data, skip, data.size - skip)
                }
                nextSeq = seq + 1
                if (endAtMs != null && System.currentTimeMillis() >= endAtMs) return
            }
            if ("#EXT-X-ENDLIST" in text) throw IOException("HLS stream ended")
            val target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(text)?.groupValues?.get(1)?.toLong() ?: 6L
            delay((target * 1000 / 2).coerceIn(1_000, 10_000))
            text = fetchText(mediaUrl)
        }
    }

    /** Prefer an audio-only variant (highest bandwidth); else the lowest-bandwidth variant. */
    private fun pickVariant(master: String): String {
        data class V(val bw: Long, val audioOnly: Boolean, val uri: String)
        val lines = master.lines().map { it.trim() }
        val vs = mutableListOf<V>()
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLong() ?: 0
            val codecs = Regex("""CODECS="([^"]*)"""").find(l)?.groupValues?.get(1).orEmpty()
            val video = "avc" in codecs || "hvc" in codecs || "hev" in codecs || "RESOLUTION=" in l
            vs += V(bw, !video, uri)
        }
        val audio = vs.filter { it.audioOnly }
        return (audio.maxByOrNull { it.bw } ?: vs.minByOrNull { it.bw })?.uri
            ?: throw IOException("HLS master playlist without variants")
    }

    private fun fetchText(url: String): String {
        val c = open(url, icy = false)
        try { return c.inputStream.use { readText(it) } } finally { c.disconnect() }
    }

    private fun fetchBytes(url: String): ByteArray {
        val c = open(url, icy = false)
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    // --- output ---

    private fun writeAudio(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val os = out ?: run {
            val (ext, mime) = sniff(b, off, len)
            val (name, stream) = openOutput(ext, mime)
            onEvent(Event.FileOpened(name))
            BufferedOutputStream(stream, 64 * 1024).also { out = it }
        }
        os.write(b, off, len)
        bytes += len
        val now = System.currentTimeMillis()
        if (now - lastProgress >= 1_000) {
            lastProgress = now
            os.flush() // bound the loss on a hard kill to ~1 s
            onEvent(Event.Progress(bytes))
            onEvent(Event.State(RecState.RECORDING))
        }
    }

    companion object {
        private const val TAG = "StreamRecorder"

        /** Format from the audio bytes themselves; Content-Type is too often wrong on radio. */
        fun sniff(b: ByteArray, off: Int, len: Int): Pair<String, String> {
            fun at(i: Int) = if (i < len) b[off + i].toInt() and 0xFF else -1
            return when {
                at(0) == 'O'.code && at(1) == 'g'.code && at(2) == 'g'.code && at(3) == 'S'.code -> "ogg" to "audio/ogg"
                at(0) == 'f'.code && at(1) == 'L'.code && at(2) == 'a'.code && at(3) == 'C'.code -> "flac" to "audio/flac"
                at(0) == 'I'.code && at(1) == 'D'.code && at(2) == '3'.code -> "mp3" to "audio/mpeg"
                else -> {
                    // First frame sync: 0xFFF/0xFFE + layer bits; layer 00 = ADTS (AAC), else MPEG audio.
                    var i = 0
                    var kind: Pair<String, String>? = null
                    while (i + 1 < len && kind == null) {
                        if (at(i) == 0xFF && (at(i + 1) and 0xE0) == 0xE0) {
                            kind = if ((at(i + 1) and 0x06) == 0) "aac" to "audio/aac" else "mp3" to "audio/mpeg"
                        }
                        i++
                    }
                    kind ?: ("mp3" to "audio/mpeg")
                }
            }
        }

        /** Size of a leading ID3v2 tag (HLS packed audio carries one per segment), else 0. */
        fun id3Size(d: ByteArray): Int {
            if (d.size < 10 || d[0] != 'I'.code.toByte() || d[1] != 'D'.code.toByte() || d[2] != '3'.code.toByte()) return 0
            val size = ((d[6].toInt() and 0x7F) shl 21) or ((d[7].toInt() and 0x7F) shl 14) or
                ((d[8].toInt() and 0x7F) shl 7) or (d[9].toInt() and 0x7F)
            val footer = if (d[5].toInt() and 0x10 != 0) 10 else 0
            return (10 + size + footer).coerceAtMost(d.size)
        }

        fun firstPlaylistEntry(text: String): String? {
            Regex("""(?im)^\s*File\d+\s*=\s*(\S+)""").find(text)?.let { return it.groupValues[1] }
            Regex("""(?i)<ref[^>]*\bhref\s*=\s*["']([^"']+)["']""").find(text)?.let { return it.groupValues[1] }
            return text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && "://" in it }
        }

        fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

        private fun readText(input: InputStream): String {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (bos.size() < 256 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
            return bos.toString(Charsets.UTF_8.name())
        }

        private fun readFully(input: InputStream, dst: ByteArray) {
            var o = 0
            while (o < dst.size) {
                val n = input.read(dst, o, dst.size - o)
                if (n < 0) throw IOException("Stream ended")
                o += n
            }
        }
    }
}

/**
 * Minimal MPEG-TS demuxer: finds the first audio stream (AAC/ADTS or MPEG audio) through the
 * PAT/PMT and emits its PES payloads. Stateful across segments, as HLS splits PES freely.
 */
class TsAudioDemuxer {
    private var pmtPid = -1
    private var audioPid = -1
    private val pes = ByteArrayOutputStream()

    fun feed(data: ByteArray, emit: (ByteArray, Int, Int) -> Unit) {
        var p = 0
        while (p + 188 <= data.size) {
            if (data[p] != 0x47.toByte()) { p++; continue } // resync
            val b1 = data[p + 1].toInt() and 0xFF
            val pid = ((b1 and 0x1F) shl 8) or (data[p + 2].toInt() and 0xFF)
            val pusi = b1 and 0x40 != 0
            val afc = (data[p + 3].toInt() shr 4) and 0x3
            var off = p + 4
            if (afc and 0x2 != 0) off += 1 + (data[p + 4].toInt() and 0xFF)
            val end = p + 188
            if (afc and 0x1 != 0 && off < end) {
                when (pid) {
                    0 -> if (pusi) parsePat(data, off, end)
                    pmtPid -> if (pusi) parsePmt(data, off, end)
                    audioPid -> {
                        if (pusi) flush(emit)
                        pes.write(data, off, end - off)
                    }
                }
            }
            p += 188
        }
    }

    private fun flush(emit: (ByteArray, Int, Int) -> Unit) {
        val b = pes.toByteArray()
        pes.reset()
        if (b.size < 9 || b[0].toInt() != 0 || b[1].toInt() != 0 || b[2].toInt() != 1) return
        val start = 9 + (b[8].toInt() and 0xFF)
        if (start < b.size) emit(b, start, b.size - start)
    }

    private fun parsePat(d: ByteArray, off: Int, end: Int) {
        val s = off + 1 + (d[off].toInt() and 0xFF)
        if (s + 8 > end) return
        val secLen = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        var i = s + 8
        val stop = minOf(s + 3 + secLen - 4, end)
        while (i + 4 <= stop) {
            val program = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            val pid = ((d[i + 2].toInt() and 0x1F) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (program != 0) { pmtPid = pid; return }
            i += 4
        }
    }

    private fun parsePmt(d: ByteArray, off: Int, end: Int) {
        val s = off + 1 + (d[off].toInt() and 0xFF)
        if (s + 12 > end) return
        val secLen = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        val progInfo = ((d[s + 10].toInt() and 0x0F) shl 8) or (d[s + 11].toInt() and 0xFF)
        var i = s + 12 + progInfo
        val stop = minOf(s + 3 + secLen - 4, end)
        while (i + 5 <= stop) {
            val type = d[i].toInt() and 0xFF
            val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
            val esLen = ((d[i + 3].toInt() and 0x0F) shl 8) or (d[i + 4].toInt() and 0xFF)
            if (type == 0x0F || type == 0x03 || type == 0x04) {
                if (audioPid != pid) pes.reset()
                audioPid = pid
                return
            }
            i += 5 + esLen
        }
        if (audioPid < 0) throw UnsupportedStreamException("This HLS station's audio format is not supported")
    }
}
