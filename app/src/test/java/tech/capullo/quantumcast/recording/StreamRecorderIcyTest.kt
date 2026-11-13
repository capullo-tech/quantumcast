package tech.capullo.quantumcast.recording

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * A recording against a real local HTTP server: redirect → .pls with a relative entry → Icecast
 * stream with ICY metadata. The file must hold only the audio bytes, and every StreamTitle must
 * surface. The test stops the recorder once the last title arrives, so the reconnect path (and
 * android.util.Log) is never reached.
 */
class StreamRecorderIcyTest {

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private val metaInt = 32
    private val chunks = List(4) { n ->
        ByteArray(metaInt) { i ->
            if (i == 0) {
                0xFF.toByte()
            } else if (i == 1) {
                0xFB.toByte()
            } else {
                (n * 31 + i).toByte()
            }
        }
    }

    private fun icyBlock(title: String?): ByteArray {
        if (title == null) return byteArrayOf(0)
        val text = "StreamTitle='$title';StreamUrl='';".toByteArray()
        val blocks = (text.size + 15) / 16
        return byteArrayOf(blocks.toByte()) + text + ByteArray(blocks * 16 - text.size)
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { ex ->
            ex.responseHeaders.add("Location", "/radio/playlists/listen.pls")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/radio/playlists/listen.pls") { ex ->
            val body = "[playlist]\nNumberOfEntries=1\nFile1=live\nTitle1=Test\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "audio/x-scpls")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/radio/playlists/live") { ex ->
            val icyRequested = ex.requestHeaders.getFirst("Icy-MetaData") == "1"
            ex.responseHeaders.add("Content-Type", "audio/mpeg")
            if (icyRequested) ex.responseHeaders.add("icy-metaint", metaInt.toString())
            val body = ByteArrayOutputStream().apply {
                write(chunks[0])
                write(icyBlock("Artist - First"))
                write(chunks[1])
                write(icyBlock(null)) // zero-length block: no metadata change
                write(chunks[2])
                write(icyBlock("Artist - Second"))
                write(chunks[3])
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    @Test
    fun `follows redirect and relative pls entry, strips ICY metadata and surfaces titles`() {
        val file = ByteArrayOutputStream()
        val titles = mutableListOf<String>()
        var opened: Pair<String, String>? = null
        lateinit var job: Job

        val recorder = StreamRecorder(
            RecStation(uuid = "u", name = "Test", url = "$base/start"),
            openOutput = { ext, mime ->
                opened = ext to mime
                "test.$ext" to file
            },
            onEvent = { e ->
                if (e is StreamRecorder.Event.Title) {
                    titles += e.title
                    if (titles.size == 2) job.cancel()
                }
            },
        )

        runBlocking {
            withTimeout(10_000) {
                job = launch(start = CoroutineStart.LAZY) { recorder.run(endAtMs = null) }
                job.start()
                job.join()
            }
        }

        assertEquals(listOf("Artist - First", "Artist - Second"), titles)
        assertEquals("mp3" to "audio/mpeg", opened)
        // Cancelled right after the second block: chunks 0..2, nothing from the metadata.
        assertArrayEquals(chunks[0] + chunks[1] + chunks[2], file.toByteArray())
    }
}
