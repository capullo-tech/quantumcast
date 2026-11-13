package tech.capullo.quantumcast.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The pure format/playlist helpers StreamRecorder uses to decide what to fetch and how to save it. */
class StreamRecorderParsingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // --- firstPlaylistEntry ---

    @Test
    fun `pls - first FileN entry, case and spacing tolerant`() {
        val pls = "[playlist]\r\nnumberofentries=2\r\nfile1 = http://a.example/live\r\nTitle1=A\r\nFile2=http://b.example/live\r\n"
        assertEquals("http://a.example/live", StreamRecorder.firstPlaylistEntry(pls))
    }

    @Test
    fun `asx - ref href in either quote style`() {
        val asx = "<ASX version=\"3.0\"><Entry><Ref HREF = 'mms://x.example/s' /></Entry></ASX>"
        assertEquals("mms://x.example/s", StreamRecorder.firstPlaylistEntry(asx))
    }

    @Test
    fun `m3u - skips comments and blank lines, strips CRLF`() {
        val m3u = "#EXTM3U\r\n\r\n#EXTINF:-1,Station\r\n  http://c.example/stream.mp3  \r\nhttp://d.example/other\r\n"
        assertEquals("http://c.example/stream.mp3", StreamRecorder.firstPlaylistEntry(m3u))
    }

    @Test
    fun `playlist without any URL yields null`() {
        assertNull(StreamRecorder.firstPlaylistEntry("#EXTM3U\n#EXTINF:-1,Nothing here\n"))
        assertNull(StreamRecorder.firstPlaylistEntry(""))
    }

    // --- sniff ---

    @Test
    fun `sniff recognises container magic`() {
        assertEquals("ogg", StreamRecorder.sniff("OggS\u0000".toByteArray(), 0, 5).first)
        assertEquals("flac", StreamRecorder.sniff("fLaC\u0000".toByteArray(), 0, 5).first)
        assertEquals("mp3", StreamRecorder.sniff("ID3\u0004\u0000".toByteArray(), 0, 5).first)
    }

    @Test
    fun `sniff tells ADTS AAC from MPEG audio by the layer bits of the first frame sync`() {
        assertEquals("aac" to "audio/aac", StreamRecorder.sniff(bytes(0xFF, 0xF1, 0x50, 0x80), 0, 4))
        assertEquals("mp3" to "audio/mpeg", StreamRecorder.sniff(bytes(0xFF, 0xFB, 0x90, 0x64), 0, 4))
        assertEquals("mp3" to "audio/mpeg", StreamRecorder.sniff(bytes(0xFF, 0xE3, 0x18, 0xC4), 0, 4)) // MPEG 2.5
    }

    @Test
    fun `sniff searches past leading garbage for the first frame sync`() {
        assertEquals("aac", StreamRecorder.sniff(bytes(0x00, 0x12, 0xFF, 0x00, 0xFF, 0xF9, 0x50), 0, 7).first)
    }

    @Test
    fun `sniff honours the offset and length window`() {
        val buf = bytes('x'.code, 'x'.code, 'O'.code, 'g'.code, 'g'.code, 'S'.code)
        assertEquals("ogg", StreamRecorder.sniff(buf, 2, 4).first)
        // Only "Ogg" is inside the window: not enough to call it Ogg.
        assertEquals("mp3", StreamRecorder.sniff(buf, 2, 3).first)
        // An ADTS sync just past the window must not be seen.
        val adts = bytes(0x00, 0x00, 0xFF, 0xF1)
        assertEquals("mp3", StreamRecorder.sniff(adts, 0, 3).first)
        assertEquals("aac", StreamRecorder.sniff(adts, 0, 4).first)
    }

    @Test
    fun `sniff defaults to mp3 for unrecognised or empty input`() {
        assertEquals("mp3" to "audio/mpeg", StreamRecorder.sniff(ByteArray(0), 0, 0))
        assertEquals("mp3" to "audio/mpeg", StreamRecorder.sniff(bytes(1, 2, 3, 4), 0, 4))
    }

    // --- id3Size ---

    private fun id3Header(flags: Int, vararg synchsafe: Int, total: Int) = ByteArray(total).also {
        "ID3".toByteArray().copyInto(it)
        it[3] = 4
        it[5] = flags.toByte()
        for (i in 0..3) it[6 + i] = synchsafe[i].toByte()
    }

    @Test
    fun `id3Size decodes the synchsafe size plus the 10-byte header`() {
        // 0x02, 0x01 → 2 * 128 + 1 = 257 (not 2 * 256 + 1).
        assertEquals(267, StreamRecorder.id3Size(id3Header(0, 0, 0, 2, 1, total = 400)))
        // All four bytes contribute 7 bits each.
        assertEquals(10 + (1 shl 21) + (1 shl 14) + (1 shl 7) + 1, StreamRecorder.id3Size(id3Header(0, 1, 1, 1, 1, total = 3_000_000)))
    }

    @Test
    fun `id3Size includes the footer when the footer flag is set`() {
        assertEquals(277, StreamRecorder.id3Size(id3Header(0x10, 0, 0, 2, 1, total = 400)))
    }

    @Test
    fun `id3Size never exceeds the data, for a truncated tag`() {
        assertEquals(100, StreamRecorder.id3Size(id3Header(0, 0, 0, 2, 1, total = 100)))
    }

    @Test
    fun `id3Size is 0 without a tag or with fewer than 10 bytes`() {
        assertEquals(0, StreamRecorder.id3Size(bytes(0xFF, 0xF1, 0x50, 0x80, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(0, StreamRecorder.id3Size("ID3\u0004\u0000\u0000\u0000\u0000\u0001".toByteArray()))
    }

    // --- pickVariant ---

    @Test
    fun `pickVariant prefers the highest-bandwidth audio-only variant`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
            low/aac.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401e,mp4a.40.2",RESOLUTION=640x360
            video/360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"

            hi/aac.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            video/720.m3u8
        """.trimIndent()
        assertEquals("hi/aac.m3u8", StreamRecorder.pickVariant(master))
    }

    @Test
    fun `pickVariant falls back to the lowest-bandwidth variant when all carry video`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.64001f,mp4a.40.2"
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="hvc1.1.6.L93,mp4a.40.2"
            240.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360
            360.m3u8
        """.trimIndent()
        assertEquals("240.m3u8", StreamRecorder.pickVariant(master))
    }

    @Test(expected = IOException::class)
    fun `pickVariant fails on a master playlist without variants`() {
        StreamRecorder.pickVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n")
    }
}
