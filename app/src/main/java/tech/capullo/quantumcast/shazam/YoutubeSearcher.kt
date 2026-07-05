package tech.capullo.quantumcast.shazam

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

// OkHttp-backed Downloader for NewPipe Extractor
private object NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val builder = OkRequest.Builder().url(request.url())
        request.headers().forEach { (key, values) -> values.forEach { builder.addHeader(key, it) } }

        val body = request.dataToSend()
        val okRequest = when (request.httpMethod()) {
            "POST" -> builder.post((body ?: byteArrayOf()).toRequestBody()).build()
            "PUT"  -> builder.put((body ?: byteArrayOf()).toRequestBody()).build()
            else   -> builder.get().build()
        }

        val resp = client.newCall(okRequest).execute()
        return Response(
            resp.code,
            resp.message,
            resp.headers.toMultimap(),
            resp.body?.string(),
            resp.request.url.toString()
        )
    }
}

object YoutubeSearcher {

    @Volatile private var initialized = false

    private fun ensureInit() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(NewPipeDownloader)
                    initialized = true
                }
            }
        }
    }

    /** Returns the first YouTube watch URL for [query], or null on failure. */
    fun search(query: String): String? = runCatching {
        ensureInit()
        val extractor = YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .firstOrNull()
            ?.url
    }.getOrNull()
}
