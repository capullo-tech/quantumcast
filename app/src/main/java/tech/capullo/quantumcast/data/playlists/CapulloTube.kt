package tech.capullo.quantumcast.data.playlists

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hand-off to CapulloTube. */
object CapulloTube {
    const val PACKAGE = "tech.capullo.tubecapullo"

    // Needs the manifest's <queries> entry on Android 11+, else the package is invisible.
    fun isInstalled(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** Opens the playlist in CapulloTube. False when CapulloTube is missing or refused the intent. */
    fun playPlaylist(context: Context, playlistId: Long): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(PlaylistContract.tracksUri(playlistId), PlaylistContract.MIME_TRACK_DIR)
            .setPackage(PACKAGE)
            // The prefix grant also covers the same path with a query (`?link=youtube`), so the
            // receiver can read this playlist even if it does not hold the read permission.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        return start(context, intent)
    }

    /** Opens a song link: a YouTube video link goes to CapulloTube when installed, anything else (and the fallback) to Android's default handler. */
    fun openLink(context: Context, url: String) {
        val uri = Uri.parse(url)
        // CapulloTube's YouTube link handler can be switched off in its settings, hence the fallback.
        if (YoutubeLinks.videoId(url) != null && isInstalled(context) &&
            start(context, Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE))
        ) {
            return
        }
        start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    // Always in the target's own task. Started from the activity without NEW_TASK, CapulloTube
    // opened inside QuantumCast's task, and QuantumCast's launcher icon then reopened
    // CapulloTube's player instead of QuantumCast (razr, 2026-11-26).
    private fun start(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
