package com.quantumcast.shazam

import android.content.Context
import android.util.Log
import com.quantumcast.data.model.TrackLookup
import java.net.URLEncoder

object ShazamRecognizer {

    suspend fun recognize(streamUrl: String, context: Context): TrackLookup? {
        val pcm = AudioCapturer.capture(streamUrl, context)
        if (pcm == null) { Log.w("Shazam", "recognize: no PCM"); return null }

        val gen = ShazamSignatureGenerator()
        gen.feedInput(pcm)
        val sig = gen.getSignature()
        if (sig == null) { Log.w("Shazam", "recognize: signature null (too few peaks)"); return null }
        Log.d("Shazam", "recognize: signature ready, calling API")

        val resp = ShazamApiClient.recognize(sig)
        if (resp == null) { Log.w("Shazam", "recognize: API returned null"); return null }
        Log.d("Shazam", "recognize: API response track=${resp.track?.title}")

        val track = resp.track
        if (track == null) { Log.w("Shazam", "recognize: no track in response"); return null }

        val query = "${track.subtitle} ${track.title}".trim()
        val youtubeUrl = resolveYoutubeUrl(query, track.hub)
        val spotifyUrl = resolveSpotifyUrl(track.hub)
        val appleMusicUrl = resolveAppleMusicUrl(track.hub)

        return TrackLookup(
            icyTitle = query,
            trackName = track.title,
            artistName = track.subtitle,
            artworkUrl = track.images?.coverarthq?.ifBlank { track.images.coverart } ?: "",
            youtubeUrl = youtubeUrl,
            spotifyUrl = spotifyUrl,
            appleMusicUrl = appleMusicUrl,
        )
    }

    private fun resolveYoutubeUrl(query: String, hub: ShazamHub?): String {
        YoutubeSearcher.search(query)?.let { return it }
        hub?.providers
            ?.find { it.type == "YOUTUBE" }
            ?.actions?.firstOrNull()?.id?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?.let { return "https://www.youtube.com/watch?v=$it" }
        val q = URLEncoder.encode(query, "UTF-8")
        return "https://www.youtube.com/results?search_query=$q"
    }

    private fun resolveSpotifyUrl(hub: ShazamHub?): String {
        val uri = hub?.providers
            ?.find { it.type == "SPOTIFY" }
            ?.actions?.firstOrNull()?.uri?.trim() ?: return ""
        return when {
            uri.startsWith("spotify:track:") -> {
                val id = uri.removePrefix("spotify:track:").trim()
                if (id.isNotEmpty()) "https://open.spotify.com/track/$id" else ""
            }
            uri.startsWith("spotify:search:") -> {
                val q = uri.removePrefix("spotify:search:").trim()
                if (q.isNotEmpty()) "https://open.spotify.com/search/${URLEncoder.encode(q, "UTF-8")}" else ""
            }
            else -> ""
        }
    }

    private fun resolveAppleMusicUrl(hub: ShazamHub?): String {
        val id = hub?.actions
            ?.find { it.type == "applemusicplay" }?.id?.trim() ?: return ""
        return if (id.isNotEmpty()) "https://music.apple.com/us/song/-/$id" else ""
    }
}
