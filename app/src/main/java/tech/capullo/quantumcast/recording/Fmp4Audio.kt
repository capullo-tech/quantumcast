package tech.capullo.quantumcast.recording

/**
 * Turns fragmented-MP4 HLS audio (an init segment + moof/mdat fragments) into a plain elementary
 * stream, so fMP4 stations record to the same crash-safe files as every other station:
 *
 * - AAC → `.aac`: each sample gets a 7-byte ADTS header built from the init segment's
 *   AudioSpecificConfig (HE-AAC is written as its AAC-LC core; decoders find SBR implicitly).
 * - MP3 in MP4 → `.mp3`: MP3 frames are self-contained, written as-is.
 * - FLAC in MP4 → `.flac`: [header] ("fLaC" + the dfLa metadata blocks) once, then the raw frames,
 *   renumbered from 0. STREAMINFO's total-samples is unknown while recording;
 *   [FlacFrames.fixTotalSamples] fills it in before playback so the file is seekable.
 *
 * Anything else (Opus, AC-3, AAC with a program config element or an explicit sample rate, an
 * encrypted entry, or a box layout this reader doesn't understand) makes [parseInit] return
 * [Init.KeepAsM4a]; the caller then saves the init + fragments verbatim as `.m4a`, rebasing each
 * fragment's `tfdt` with [rebaseTfdt] so the file's timeline starts at 0. [Init.NoAudio] means the
 * init has no sound track at all.
 */
class Fmp4Audio private constructor(
    val ext: String,
    val mime: String,
    private val adts: ByteArray?, // ADTS header template (length bytes filled per frame), null = passthrough
    private val defaultSampleSize: Int,
    private val trackId: Int,
    /** Written once at the top of the file, before the first sample (FLAC only). */
    val header: ByteArray? = null,
) {
    /** Writes the audio samples of one fragment-carrying segment. */
    fun extract(seg: ByteArray, emit: (ByteArray, Int, Int) -> Unit) {
        var p = 0
        var moofStart = -1
        var samples: List<Int> = emptyList()
        var dataStart = -1
        while (p + 8 <= seg.size) {
            val (size, hdr) = boxSize(seg, p) ?: return
            val type = fourcc(seg, p + 4)
            val end = if (size == 0L) seg.size else (p + size).toInt().coerceAtMost(seg.size)
            when (type) {
                "moof" -> {
                    moofStart = p
                    val r = parseMoof(seg, p + hdr, end, moofStart)
                    samples = r.first
                    dataStart = r.second
                }
                "mdat" -> {
                    var o = if (dataStart >= 0) dataStart else p + hdr
                    for (s in samples) {
                        if (s <= 0 || o + s > end) break
                        write(seg, o, s, emit)
                        o += s
                    }
                    samples = emptyList()
                    dataStart = -1
                }
            }
            if (end <= p) return
            p = end
        }
    }

    private fun write(b: ByteArray, off: Int, len: Int, emit: (ByteArray, Int, Int) -> Unit) {
        val h = adts ?: return emit(b, off, len)
        val total = len + 7
        val out = h.copyOf()
        out[3] = (out[3].toInt() or ((total shr 11) and 0x03)).toByte()
        out[4] = ((total shr 3) and 0xFF).toByte()
        out[5] = (((total and 0x07) shl 5) or 0x1F).toByte()
        emit(out, 0, 7)
        emit(b, off, len)
    }

    /** Sample sizes of the audio track's run, and where its data starts (-1 = start of mdat payload). */
    private fun parseMoof(b: ByteArray, from: Int, to: Int, moofStart: Int): Pair<List<Int>, Int> {
        var p = from
        while (p + 8 <= to) {
            val (size, hdr) = boxSize(b, p) ?: break
            val end = (p + size).toInt().coerceAtMost(to)
            if (fourcc(b, p + 4) == "traf") {
                val r = parseTraf(b, p + hdr, end, moofStart)
                if (r.first.isNotEmpty()) return r
            }
            if (end <= p) break
            p = end
        }
        return emptyList<Int>() to -1
    }

    private fun parseTraf(b: ByteArray, from: Int, to: Int, moofStart: Int): Pair<List<Int>, Int> {
        var p = from
        var base = moofStart.toLong()
        var defSize = defaultSampleSize
        var sizes = emptyList<Int>()
        var dataStart = -1
        while (p + 8 <= to) {
            val (size, hdr) = boxSize(b, p) ?: break
            val end = (p + size).toInt().coerceAtMost(to)
            val c = p + hdr
            when (fourcc(b, p + 4)) {
                "tfhd" -> {
                    val flags = u24(b, c + 1)
                    if (u32(b, c + 4).toInt() != trackId) return emptyList<Int>() to -1 // other track (video)
                    var q = c + 8 // version/flags + track_ID
                    if (flags and 0x01 != 0) {
                        base = u64(b, q)
                        q += 8
                    }
                    if (flags and 0x02 != 0) q += 4
                    if (flags and 0x08 != 0) q += 4
                    if (flags and 0x10 != 0) {
                        defSize = u32(b, q).toInt()
                        q += 4
                    }
                }
                "trun" -> {
                    val flags = u24(b, c + 1)
                    val count = u32(b, c + 4).toInt()
                    var q = c + 8
                    if (flags and 0x01 != 0) {
                        dataStart = (base + s32(b, q)).toInt()
                        q += 4
                    }
                    if (flags and 0x04 != 0) q += 4
                    val list = ArrayList<Int>(count.coerceAtMost(4096))
                    repeat(count.coerceAtMost(4096)) {
                        if (flags and 0x100 != 0) q += 4
                        val s = if (flags and 0x200 != 0) u32(b, q).toInt().also { q += 4 } else defSize
                        if (flags and 0x400 != 0) q += 4
                        if (flags and 0x800 != 0) q += 4
                        list += s
                    }
                    sizes = list
                }
            }
            if (end <= p) break
            p = end
        }
        return sizes to dataStart
    }

    companion object {
        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** What an init segment allows. */
        sealed interface Init {
            /** Audio becomes .aac/.mp3. */
            data class Elementary(val audio: Fmp4Audio) : Init

            /** Audio we can't unwrap (Opus, FLAC…): keep init + fragments as .m4a. */
            data object KeepAsM4a : Init

            /** No audio track at all (a video-only variant). */
            data object NoAudio : Init
        }

        fun parseInit(init: ByteArray): Init = runCatching { parse(init) }.getOrDefault(Init.KeepAsM4a)

        private fun parse(init: ByteArray): Init {
            val moov = findPath(init, 0, init.size, listOf("moov")) ?: return Init.KeepAsM4a
            // The audio track ("soun" handler); muxed inits also carry video, which must be skipped.
            var trackId = -1
            var stsd: Pair<Int, Int>? = null
            for ((type, start, end) in children(init, moov.first, moov.second)) {
                if (type != "trak") continue
                val hdlr = findPath(init, start, end, listOf("mdia", "hdlr")) ?: continue
                if (fourcc(init, hdlr.first + 8) != "soun") continue
                val tkhd = findPath(init, start, end, listOf("tkhd")) ?: continue
                trackId = u32(init, tkhd.first + if (init[tkhd.first].toInt() == 1) 20 else 12).toInt()
                stsd = findPath(init, start, end, listOf("mdia", "minf", "stbl", "stsd"))
                break
            }
            if (trackId < 0) return Init.NoAudio
            val sd = stsd ?: return Init.KeepAsM4a
            val defSize = findPath(init, moov.first, moov.second, listOf("mvex"))?.let { mvex ->
                children(init, mvex.first, mvex.second)
                    .firstOrNull { (t, s, _) -> t == "trex" && u32(init, s + 4).toInt() == trackId }
                    ?.let { (_, s, _) -> u32(init, s + 16).toInt() }
            } ?: 0
            // stsd: version/flags(4) + entry_count(4), then the first sample entry.
            val e = sd.first + 8
            val (eSize, eHdr) = boxSize(init, e) ?: return Init.KeepAsM4a
            val type = fourcc(init, e + 4)
            if (type != "mp4a" && type != "fLaC") return Init.KeepAsM4a // enca (encrypted), Opus, ac-3…
            val version = u16(init, e + eHdr + 8)
            val childStart = e + eHdr + 28 + when (version) {
                1 -> 16
                2 -> 36
                else -> 0
            }
            if (type == "fLaC") {
                val dfla = findPath(init, childStart, (e + eSize).toInt(), listOf("dfLa")) ?: return Init.KeepAsM4a
                val header = flacHeader(init, dfla.first + 4, dfla.second) ?: return Init.KeepAsM4a
                return Init.Elementary(Fmp4Audio("flac", "audio/flac", null, defSize, trackId, header))
            }
            val esds = findPath(init, childStart, (e + eSize).toInt(), listOf("esds")) ?: return Init.KeepAsM4a
            val (oti, asc) = parseEsds(init, esds.first + 4, esds.second) ?: return Init.KeepAsM4a
            val audio = when (oti) {
                0x69, 0x6B -> Fmp4Audio("mp3", "audio/mpeg", null, defSize, trackId)
                0x40, 0x66, 0x67, 0x68 -> adtsFor(asc)?.let { Fmp4Audio("aac", "audio/aac", it, defSize, trackId) }
                else -> null
            }
            return audio?.let { Init.Elementary(it) } ?: Init.KeepAsM4a
        }

        /**
         * A native FLAC stream header from a dfLa payload (FLAC metadata blocks, STREAMINFO first):
         * "fLaC" + the blocks, with the last-block flag on the final one only.
         */
        private fun flacHeader(b: ByteArray, from: Int, to: Int): ByteArray? {
            if (from + 4 > to || (b[from].toInt() and 0x7F) != 0) return null // must start with STREAMINFO
            val out = java.io.ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            var p = from
            while (p + 4 <= to) {
                val len = u24(b, p + 1)
                if (p + 4 + len > to) return null
                val last = p + 4 + len + 4 > to
                out.write((b[p].toInt() and 0x7F) or (if (last) 0x80 else 0))
                out.write(b, p + 1, 3 + len)
                p += 4 + len
            }
            return out.toByteArray()
        }

        private fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
            val out = mutableListOf<Triple<String, Int, Int>>()
            var p = from
            while (p + 8 <= to) {
                val (size, hdr) = boxSize(b, p) ?: break
                val end = if (size == 0L) to else (p + size).toInt().coerceAtMost(to)
                out += Triple(fourcc(b, p + 4), p + hdr, end)
                if (end <= p) break
                p = end
            }
            return out
        }

        /** Every `moof/traf/tfdt` box of a segment: (payload offset after version/flags, version). */
        private fun tfdts(seg: ByteArray): List<Pair<Int, Int>> = children(seg, 0, seg.size)
            .filter { it.first == "moof" }
            .flatMap { (_, s, e) -> children(seg, s, e).filter { it.first == "traf" } }
            .flatMap { (_, s, e) -> children(seg, s, e).filter { it.first == "tfdt" } }
            .mapNotNull { (_, s, e) ->
                val version = seg[s].toInt() and 0xFF
                if (s + 4 + (if (version == 1) 8 else 4) <= e) s + 4 to version else null
            }

        /** The first fragment's baseMediaDecodeTime, or null if the segment has none. */
        fun firstTfdt(seg: ByteArray): Long? = tfdts(seg).firstOrNull()?.let { (p, v) -> if (v == 1) u64(seg, p) else u32(seg, p) }

        /** Subtracts [base] from every baseMediaDecodeTime in [seg], in place (clamped at 0). */
        fun rebaseTfdt(seg: ByteArray, base: Long) {
            for ((p, v) in tfdts(seg)) {
                val bytes = if (v == 1) 8 else 4
                val old = if (v == 1) u64(seg, p) else u32(seg, p)
                var t = (old - base).coerceAtLeast(0)
                for (i in bytes - 1 downTo 0) {
                    seg[p + i] = (t and 0xFF).toByte()
                    t = t ushr 8
                }
            }
        }

        /** ADTS header template from an AudioSpecificConfig, or null if ADTS can't express it. */
        private fun adtsFor(asc: ByteArray): ByteArray? {
            if (asc.size < 2) return null
            val bits = BitReader(asc)
            var aot = bits.read(5)
            if (aot == 31) aot = 32 + bits.read(6)
            val freqIdx = bits.read(4)
            if (freqIdx == 15) return null // explicit 24-bit rate: not expressible in ADTS
            val chan = bits.read(4)
            if (aot == 5 || aot == 29) { // SBR/PS: first index is the core rate; core object type follows
                val ext = bits.read(4)
                if (ext == 15) bits.read(24)
                aot = bits.read(5)
            }
            if (aot !in 1..4 || chan == 0 || freqIdx >= SAMPLE_RATES.size) return null
            val profile = aot - 1
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, no CRC
                ((profile shl 6) or (freqIdx shl 2) or ((chan shr 2) and 0x01)).toByte(),
                ((chan and 0x03) shl 6).toByte(),
                0,
                0x1F,
                0xFC.toByte(),
            )
        }

        /** objectTypeIndication + DecoderSpecificInfo from an esds payload (after version/flags). */
        private fun parseEsds(b: ByteArray, from: Int, to: Int): Pair<Int, ByteArray>? {
            var p = from
            if (b[p].toInt() != 0x03) return null
            p = descLen(b, p + 1).second
            val esFlags = b[p + 2].toInt() and 0xFF
            p += 3
            if (esFlags and 0x80 != 0) p += 2
            if (esFlags and 0x40 != 0) p += 1 + (b[p].toInt() and 0xFF)
            if (esFlags and 0x20 != 0) p += 2
            if (b[p].toInt() != 0x04) return null
            p = descLen(b, p + 1).second
            val oti = b[p].toInt() and 0xFF
            p += 13
            if (p >= to || b[p].toInt() != 0x05) return oti to ByteArray(0)
            val (len, start) = descLen(b, p + 1)
            return oti to b.copyOfRange(start, (start + len).coerceAtMost(to))
        }

        private fun descLen(b: ByteArray, at: Int): Pair<Int, Int> {
            var len = 0
            var p = at
            repeat(4) {
                val v = b[p++].toInt() and 0xFF
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) return len to p
            }
            return len to p
        }

        /** Content range (start, end) of the box reached by following [path] from [from]..[to]. */
        private fun findPath(b: ByteArray, from: Int, to: Int, path: List<String>): Pair<Int, Int>? {
            var start = from
            var end = to
            for (name in path) {
                var p = start
                var found: Pair<Int, Int>? = null
                while (p + 8 <= end) {
                    val (size, hdr) = boxSize(b, p) ?: return null
                    val boxEnd = if (size == 0L) end else (p + size).toInt().coerceAtMost(end)
                    if (fourcc(b, p + 4) == name) {
                        found = (p + hdr) to boxEnd
                        break
                    }
                    if (boxEnd <= p) return null
                    p = boxEnd
                }
                found ?: return null
                start = found.first
                end = found.second
            }
            return start to end
        }

        private fun boxSize(b: ByteArray, p: Int): Pair<Long, Int>? {
            if (p + 8 > b.size) return null
            val s = u32(b, p)
            return when (s) {
                1L -> if (p + 16 <= b.size) u64(b, p + 8) to 16 else null
                else -> s to 8
            }
        }

        private fun fourcc(b: ByteArray, p: Int) = String(b, p, 4, Charsets.ISO_8859_1)
        private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        private fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
        private fun u32(b: ByteArray, p: Int): Long = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
        private fun s32(b: ByteArray, p: Int): Int = u32(b, p).toInt()
        private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
    }

    private class BitReader(private val b: ByteArray) {
        private var pos = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = b.getOrNull(pos / 8)?.toInt() ?: 0
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
    }
}
