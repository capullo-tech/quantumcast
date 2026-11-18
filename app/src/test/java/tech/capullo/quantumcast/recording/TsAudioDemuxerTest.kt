package tech.capullo.quantumcast.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** HLS TS segments → the audio elementary stream bytes that end up in the recording file. */
class TsAudioDemuxerTest {

    private val pmtPid = 0x100
    private val videoPid = 0x101
    private val audioPid = 0x102

    /** One 188-byte TS packet; [payload] is padded to the end with adaptation-field stuffing. */
    private fun packet(pid: Int, pusi: Boolean, payload: ByteArray): ByteArray {
        require(payload.size <= 184)
        val p = ByteArray(188)
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        val stuffing = 184 - payload.size
        if (stuffing == 0) {
            p[3] = 0x10 // payload only
            payload.copyInto(p, 4)
        } else {
            p[3] = 0x30 // adaptation field + payload
            p[4] = (stuffing - 1).toByte() // adaptation_field_length
            if (stuffing > 1) {
                p[5] = 0 // no flags
                for (i in 6 until 4 + stuffing) p[i] = 0xFF.toByte()
            }
            payload.copyInto(p, 4 + stuffing)
        }
        return p
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** PAT with a NIT entry (program 0) first, then program 1 → [pmtPid]. */
    private fun pat(): ByteArray {
        val entries = bytes(0x00, 0x00, 0xE0, 0x10) + bytes(0x00, 0x01, 0xE0 or (pmtPid shr 8), pmtPid and 0xFF)
        val secLen = 5 + entries.size + 4
        val section = bytes(0x00, 0xB0, secLen, 0x00, 0x01, 0xC1, 0x00, 0x00) + entries + ByteArray(4)
        return packet(0, true, bytes(0x00) + section)
    }

    /** PMT with a 3-byte program descriptor, then the given (streamType, pid) entries. */
    private fun pmt(vararg streams: Pair<Int, Int>): ByteArray {
        val progInfo = bytes(0x05, 0x01, 0x00)
        val es = streams.fold(ByteArray(0)) { acc, (type, pid) -> acc + bytes(type, 0xE0 or (pid shr 8), pid and 0xFF, 0xF0, 0x00) }
        val secLen = 9 + progInfo.size + es.size + 4
        val section = bytes(0x02, 0xB0, secLen, 0x00, 0x01, 0xC1, 0x00, 0x00, 0xE1, 0x00, 0xF0, progInfo.size) + progInfo + es + ByteArray(4)
        return packet(pmtPid, true, bytes(0x00) + section)
    }

    /** PES header with a 5-byte PTS (header data length 5 → ES starts at byte 14). */
    private fun pesHeader() = bytes(0x00, 0x00, 0x01, 0xC0, 0x00, 0x00, 0x80, 0x80, 0x05, 0x21, 0x00, 0x01, 0x00, 0x01)

    private fun es(size: Int, seed: Int) = ByteArray(size) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun TsAudioDemuxer.collect(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in segments) feed(s) { b, off, len -> out.write(b, off, len) }
        return out.toByteArray()
    }

    @Test
    fun `audio PES split across packets and segments comes out as the exact elementary stream`() {
        val es1a = es(100, 1)
        val es1b = es(184, 2)
        val es1c = es(50, 3)
        val es2 = es(60, 4)
        val segment1 = bytes(0x00, 0x12, 0x34) + // junk before the first sync byte
            pat() + pmt(0x1B to videoPid, 0x0F to audioPid) +
            packet(videoPid, true, es(150, 9)) + // video must be ignored
            packet(audioPid, true, pesHeader() + es1a) +
            packet(audioPid, false, es1b)
        val segment2 = packet(audioPid, false, es1c) + packet(audioPid, true, pesHeader() + es2)

        val d = TsAudioDemuxer()
        // A PES is only complete when the next one starts, so segment 1 alone emits nothing.
        assertEquals(0, d.collect(segment1).size)
        assertArrayEquals(es1a + es1b + es1c, d.collect(segment2))
    }

    @Test
    fun `MPEG audio stream types are accepted as well as AAC`() {
        for (type in listOf(0x03, 0x04)) {
            val payload = es(80, type)
            val seg = pat() + pmt(type to audioPid) +
                packet(audioPid, true, pesHeader() + payload) +
                packet(audioPid, true, pesHeader())
            assertArrayEquals("stream type $type", payload, TsAudioDemuxer().collect(seg))
        }
    }

    @Test(expected = UnsupportedStreamException::class)
    fun `a program without a supported audio stream is reported as unsupported`() {
        // 0x81 = AC-3 (ATSC), 0x1B = H.264: neither is recordable.
        TsAudioDemuxer().collect(pat() + pmt(0x1B to videoPid, 0x81 to audioPid))
    }
}
