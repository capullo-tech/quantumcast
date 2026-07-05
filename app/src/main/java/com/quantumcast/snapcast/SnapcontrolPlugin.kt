package com.quantumcast.snapcast

/**
 * Implements the Snapcast stream-plugin JSON-RPC 2.0 protocol.
 *
 * Snapserver spawns libsnapcontrol.so as a `controlscript`; that binary is a
 * stdio <-> Unix-abstract-socket proxy and connects to the abstract socket
 * bound here. This class accepts connections, sends Plugin.Stream.Ready,
 * answers Plugin.Stream.Player.GetProperties, and pushes
 * Plugin.Stream.Player.Properties notifications when metadata changes.
 *
 * Adapted from vlc-capullo SnapcontrolPlugin.kt, replacing VLC's PlaybackService
 * with our PlaybackService interface.
 */

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

private const val TAG = "SnapcontrolPlugin"
private const val SOCKET_NAME = "snapcontrol"
private const val NOTIFY_READY = """{"jsonrpc":"2.0","method":"Plugin.Stream.Ready"}"""

interface SnapcontrolCallbacks {
    val isPlaying: Boolean
    val isPaused: Boolean
    // True only when skipping is meaningful (station rotation/queue active).
    // Drives canGoNext/canGoPrevious so clients hide prev/next for a single station.
    val canSkip: Boolean get() = true
    val currentTitle: String
    val currentArtist: String   // track artist - empty when no Shazam identification
    val currentAlbum: String    // radio station name
    val currentUrl: String      // station stream URL
    val currentCountry: String
    val currentCountryCode: String
    val currentCodec: String
    val currentBitrate: Int
    val currentYoutubeUrl: String
    val currentSpotifyUrl: String
    val currentAppleMusicUrl: String
    val currentTags: String
    val currentUuid: String
    val currentArtworkUrl: String
    val currentArtworkFile: String?
    fun onPlay() {}
    fun onPause() {}
    fun onSkipNext() {}
    fun onSkipPrev() {}
}

class SnapcontrolPlugin(
    private val callbacks: SnapcontrolCallbacks,
    parentJob: Job,
) {
    private val pluginJob = SupervisorJob(parentJob)
    private val scope = CoroutineScope(Dispatchers.IO + pluginJob)

    private var listener: LocalServerSocket? = null

    @Volatile private var currentSession: SnapcontrolSession? = null

    @Volatile var isStreamLocked: Boolean = false
        set(value) { field = value; notifyPropertiesChanged() }

    fun start() {
        if (listener != null) return
        listener = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind abstract:$SOCKET_NAME - is another instance running?", e)
            return
        }
        Log.d(TAG, "Listening on abstract:$SOCKET_NAME")
        scope.launch { acceptLoop() }
    }

    fun stop() {
        val srv = listener; listener = null
        try { srv?.close() } catch (_: IOException) {}
        currentSession?.close()
        currentSession = null
        pluginJob.cancel()
    }

    fun notifyPropertiesChanged() {
        currentSession?.notifyProperties()
    }

    private suspend fun acceptLoop() {
        val srv = listener ?: return
        while (scope.isActive) {
            val sock = try { srv.accept() } catch (e: IOException) {
                Log.d(TAG, "Accept loop ending: ${e.message}")
                return
            }
            Log.d(TAG, "New session")
            val session = SnapcontrolSession(sock, callbacks, scope, { isStreamLocked })
            currentSession = session
            try { session.run() } finally {
                currentSession = null
                Log.d(TAG, "Session ended")
            }
        }
    }
}

private class SnapcontrolSession(
    private val socket: LocalSocket,
    private val callbacks: SnapcontrolCallbacks,
    parentScope: CoroutineScope,
    private val getLocked: () -> Boolean,
) {
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)

    @Volatile private var cachedArtworkUrl: String? = null
    @Volatile private var cachedArtData: JSONObject? = null
    @Volatile private var lastArtworkFile: String? = null

    // Serializes refreshArtwork+send. Without it two notifyProperties can
    // interleave: a slow artwork download from an earlier track finishes AFTER
    // a later "art cleared" notification and re-broadcasts the stale artData -
    // snapserver stores the last properties, so web clients keep showing the
    // old song's art indefinitely.
    private val propsMutex = Mutex()

    suspend fun run() {
        outbox.trySend(NOTIFY_READY)
        val writerJob = scope.launch(Dispatchers.IO) { writerLoop() }
        try {
            refreshArtwork()
            withContext(Dispatchers.IO) { readerLoop() }
        } finally {
            outbox.close()
            writerJob.cancel()
            try { socket.close() } catch (_: IOException) {}
            sessionJob.cancel()
        }
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
        outbox.close()
        sessionJob.cancel()
    }

    fun notifyProperties() {
        scope.launch {
            propsMutex.withLock {
                refreshArtwork()
                val notif = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "Plugin.Stream.Player.Properties")
                    .put("params", buildProperties())
                outbox.trySend(notif.toString())
            }
        }
    }

    private suspend fun refreshArtwork() {
        val artFile = callbacks.currentArtworkFile
        val artUrl = callbacks.currentArtworkUrl

        if (artFile != null && artFile != lastArtworkFile) {
            lastArtworkFile = artFile
            cachedArtData = null
            cachedArtworkUrl = null
            try {
                val file = File(artFile)
                if (file.exists() && file.length() > 0) {
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    val ext = file.extension.lowercase().ifEmpty { "jpg" }
                    cachedArtData = JSONObject()
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("extension", ext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read artwork file: ${e.message}")
            }
        } else if (artUrl.isEmpty() && artFile == null && cachedArtworkUrl != null) {
            cachedArtworkUrl = null
            cachedArtData = null
        } else if (artUrl.isNotEmpty() && artFile == null && artUrl != cachedArtworkUrl) {
            // Download the image and send as artData so clients that don't follow artUrl
            // (e.g. RadioCapullo) still receive the artwork as embedded bytes.
            cachedArtworkUrl = artUrl
            cachedArtData = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    java.net.URL(artUrl).openStream().readBytes()
                }
                val ext = artUrl.substringAfterLast('.').substringBefore('?')
                    .lowercase().ifEmpty { "jpg" }
                // Guard against the art having been cleared/changed while the
                // download was in flight (belt-and-braces next to propsMutex)
                if (cachedArtworkUrl == artUrl) {
                    cachedArtData = JSONObject()
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("extension", ext)
                    Log.d(TAG, "Artwork cached: ${bytes.size} bytes ($ext)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download artwork: ${e.message}")
            }
        }
    }

    private fun buildProperties(): JSONObject {
        val status = when {
            callbacks.isPlaying -> "playing"
            callbacks.isPaused -> "paused"
            else -> "stopped"
        }
        val obj = JSONObject()
            .put("playbackStatus", status)
            .put("loopStatus", "none")
            .put("shuffle", false)
            .put("volume", 100)
            .put("mute", false)
            .put("canPlay", !getLocked())
            .put("canPause", !getLocked())
            .put("canSeek", false)
            .put("canGoNext", !getLocked() && callbacks.canSkip && (callbacks.isPlaying || callbacks.isPaused))
            .put("canGoPrevious", !getLocked() && callbacks.canSkip && (callbacks.isPlaying || callbacks.isPaused))
            .put("canControl", true)

        val title        = callbacks.currentTitle
        val artist       = callbacks.currentArtist
        val album        = callbacks.currentAlbum
        val url          = callbacks.currentUrl
        val country      = callbacks.currentCountry
        val countryCode  = callbacks.currentCountryCode
        val codec        = callbacks.currentCodec
        val bitrate      = callbacks.currentBitrate
        val youtubeUrl   = callbacks.currentYoutubeUrl
        val spotifyUrl   = callbacks.currentSpotifyUrl
        val appleMusicUrl = callbacks.currentAppleMusicUrl
        val tags         = callbacks.currentTags
        val uuid         = callbacks.currentUuid
        if (title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
            val meta = JSONObject()
            if (title.isNotEmpty())        meta.put("title", title)
            if (artist.isNotEmpty())       meta.put("artist", JSONArray().put(artist))
            if (album.isNotEmpty())        meta.put("station", album)
            if (url.isNotEmpty())          meta.put("url", url)
            if (country.isNotEmpty())      meta.put("country", country)
            if (countryCode.isNotEmpty())  meta.put("countrycode", countryCode)
            if (codec.isNotEmpty())        meta.put("codec", codec)
            if (bitrate > 0)               meta.put("bitrate", bitrate)
            if (youtubeUrl.isNotEmpty())   meta.put("youtubeUrl", youtubeUrl)
            if (spotifyUrl.isNotEmpty())   meta.put("spotifyUrl", spotifyUrl)
            if (appleMusicUrl.isNotEmpty()) meta.put("appleMusicUrl", appleMusicUrl)
            if (tags.isNotEmpty())         meta.put("tags", tags)
            if (uuid.isNotEmpty())         meta.put("uuid", uuid)
            cachedArtworkUrl?.let { meta.put("artUrl", it) }
            cachedArtData?.let { meta.put("artData", it) }
            obj.put("metadata", meta)
        }
        return obj
    }

    private suspend fun readerLoop() {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        while (scope.isActive) {
            val line = try { reader.readLine() ?: return } catch (e: IOException) {
                Log.d(TAG, "Reader end: ${e.message}"); return
            }
            if (line.isBlank()) continue
            handleLine(line)
        }
    }

    private suspend fun writerLoop() {
        val out = socket.outputStream
        try {
            for (line in outbox) {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Writer end: ${e.message}")
        }
    }

    private suspend fun handleLine(line: String) {
        val req = try { JSONObject(line) } catch (e: JSONException) {
            Log.w(TAG, "JSON parse error: ${e.message}"); return
        }
        val id: Any? = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        val method = req.optString("method", "")
        if (method.isEmpty()) return

        try {
            val result: Any = when (method) {
                "Plugin.Stream.Player.GetProperties" -> buildProperties()
                "Plugin.Stream.Player.Control" -> {
                    val command = req.optJSONObject("params")?.optString("command") ?: ""
                    Log.d(TAG, "Control command received: $command isPlaying=${callbacks.isPlaying} isPaused=${callbacks.isPaused} locked=${getLocked()}")
                    if (getLocked()) {
                        Log.d(TAG, "Stream is locked - ignoring control command: $command")
                    } else {
                        when (command) {
                            "play"      -> { Log.d(TAG, "Dispatching onPlay"); callbacks.onPlay() }
                            "pause"     -> { Log.d(TAG, "Dispatching onPause"); callbacks.onPause() }
                            "playPause" -> if (callbacks.isPlaying) { Log.d(TAG, "Dispatching onPause (playPause)"); callbacks.onPause() }
                                           else { Log.d(TAG, "Dispatching onPlay (playPause)"); callbacks.onPlay() }
                            "stop"      -> { Log.d(TAG, "Dispatching onPause (stop)"); callbacks.onPause() }
                            "next"      -> { Log.d(TAG, "Dispatching onSkipNext"); callbacks.onSkipNext() }
                            "previous"  -> { Log.d(TAG, "Dispatching onSkipPrev"); callbacks.onSkipPrev() }
                            else        -> Log.d(TAG, "Unhandled control command: $command")
                        }
                    }
                    "ok"
                }
                "Plugin.Stream.Player.SetProperty" -> "ok"
                else -> { if (id != null) sendError(id, -32601, "Method not found: $method"); return }
            }
            if (id != null) sendResult(id, result)
        } catch (e: Throwable) {
            Log.e(TAG, "Dispatch error", e)
            if (id != null) sendError(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sendResult(id: Any, result: Any) {
        outbox.trySend(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    private fun sendError(id: Any, code: Int, message: String) {
        outbox.trySend(
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)).toString()
        )
    }
}
