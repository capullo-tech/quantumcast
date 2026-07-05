package tech.capullo.quantumcast.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Resolves plain playlist station URLs (.pls / .m3u / .asx) to their first
 * stream entry. ExoPlayer has no extractor for these container-less text
 * playlists - VLC parsed them internally - and radio-browser lists many
 * stations whose url_resolved still points at one (fails with
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED otherwise).
 *
 * HLS is NOT unwrapped here: when the fetched body is a real HLS manifest
 * (#EXT-X-*) the original URL is returned with an APPLICATION_M3U8 mime
 * hint so ExoPlayer's HLS module takes over - needed when the URL lacks
 * the .m3u8 extension DefaultMediaSourceFactory sniffs by.
 *
 * All methods do blocking network I/O - call from Dispatchers.IO.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaylistResolver {

    data class Resolved(val url: String, val mimeType: String? = null)

    // Playlists are tiny; this also caps how much of a live audio body we
    // pull before the binary check rejects it (~1s at 128kbps).
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_DEPTH = 3 // playlist → playlist → stream

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun hasPlaylistExtension(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".pls") || path.endsWith(".m3u") || path.endsWith(".asx")
    }

    /**
     * Fetch [url] and, if the body is a recognizable playlist, return its
     * first entry (recursing through nested playlists up to [MAX_DEPTH]).
     * Returns null when the body isn't a playlist or on any network error.
     */
    fun resolve(url: String, depth: Int = 0): Resolved? {
        if (depth >= MAX_DEPTH) return null
        val text = try {
            fetchText(url)
        } catch (e: Exception) {
            null
        } ?: return null
        if (text.contains("#EXT-X-")) return Resolved(url, MimeTypes.APPLICATION_M3U8)
        val body = text.trimStart('\uFEFF').trimStart()
        val entry = when {
            body.startsWith("[playlist]", ignoreCase = true) -> firstPlsEntry(body)
            body.startsWith("#EXTM3U") -> firstM3uEntry(body)
            body.startsWith("<") && body.contains("<asx", ignoreCase = true) -> firstAsxEntry(body)
            looksLikeUrlList(body) -> firstM3uEntry(body) // headerless .m3u
            else -> null
        } ?: return null
        val abs = try {
            URL(URL(url), entry).toString()
        } catch (e: Exception) {
            return null
        }
        if (abs == url) return null // self-reference guard
        return if (hasPlaylistExtension(abs)) resolve(abs, depth + 1) else Resolved(abs)
    }

    /** Reads at most MAX_BYTES; null for HTTP errors, empty or binary bodies. */
    private fun fetchText(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "QuantumCast").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ins = resp.body?.byteStream() ?: return null
            val bytes = ByteArray(MAX_BYTES)
            var n = 0
            while (n < MAX_BYTES) {
                val r = ins.read(bytes, n, MAX_BYTES - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) return null
            for (i in 0 until minOf(n, 512)) {
                if (bytes[i] == 0.toByte()) return null // binary → an actual audio stream
            }
            return String(bytes, 0, n, Charsets.UTF_8)
        }
    }

    private val plsFileLine = Regex("""^\s*File(\d+)\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)

    private fun firstPlsEntry(text: String): String? = text.lineSequence()
        .mapNotNull { plsFileLine.find(it) }
        .sortedBy { it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
        .firstOrNull()?.groupValues?.get(2)?.trim()

    private fun firstM3uEntry(text: String): String? = text.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private val asxHref =
        Regex("""<ref[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun firstAsxEntry(text: String): String? = asxHref.find(text)?.groupValues?.get(1)?.trim()

    private val urlScheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")

    /** Bare list of stream URLs - common for .m3u served without #EXTM3U. */
    private fun looksLikeUrlList(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        return urlScheme.containsMatchIn(first)
    }
}
