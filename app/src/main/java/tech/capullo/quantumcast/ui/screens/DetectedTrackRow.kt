package tech.capullo.quantumcast.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tech.capullo.quantumcast.R
import tech.capullo.quantumcast.data.playlists.CapulloTube
import tech.capullo.quantumcast.data.playlists.DetectedTrack
import tech.capullo.quantumcast.data.settings.ShareService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The regional-indicator flag emoji for an ISO 3166 alpha-2 code, empty for anything else. */
internal fun countryFlag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 0x41
    return String(Character.toChars(code[0].uppercaseChar().code + base)) +
        String(Character.toChars(code[1].uppercaseChar().code + base))
}

/** Midnight of the current day, local time: the cut between "HH:mm" and a date in [detectionTimeLabel]. */
internal fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Clock time for a detection made today, a short date ("24 Nov") for an older one. */
internal fun detectionTimeLabel(timestamp: Long, todayStart: Long): String = SimpleDateFormat(
    if (timestamp >= todayStart) "HH:mm" else "d MMM",
    Locale.getDefault(),
).format(Date(timestamp))

/** A short date ("24 Nov") for a detection, whatever day it was. */
internal fun detectionDateLabel(timestamp: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))

// Wide enough for "24 Nov" in labelSmall, so clock times and dates line up in one column.
private val LeadingColumnWidth = 44.dp

/**
 * One identified song, shared by the detection history sheet and a station's playlist: artwork,
 * title, artist, optionally the station it was heard on, an open button for the chosen service and
 * a share icon whose long press cycles YouTube / Spotify / Apple Music. Swiping left deletes it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetectedTrackRow(
    track: DetectedTrack,
    leadingText: String,
    showStation: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showDetections: Boolean = false,
) {
    val context = LocalContext.current
    val song = track.track
    val currentOnDelete by rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.72f },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete()
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = leadingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(LeadingColumnWidth),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (song.artworkUrl.isNotBlank()) {
                    AsyncImage(
                        model = song.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                val artistLine = if (showDetections && song.detections > 1) {
                    listOf(song.artist, "${song.detections}×").filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    song.artist
                }
                if (artistLine.isNotBlank()) {
                    Text(
                        text = artistLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
                if (showStation && track.stationName.isNotBlank()) {
                    val flag = countryFlag(track.stationCountryCode)
                    Text(
                        text = if (flag.isNotEmpty()) "$flag ${track.stationName}" else track.stationName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
            }
            val availableServices = remember(song.youtubeUrl, song.spotifyUrl, song.appleMusicUrl) {
                buildList {
                    if (song.youtubeUrl.isNotBlank()) add(ShareService.YOUTUBE)
                    if (song.spotifyUrl.isNotBlank()) add(ShareService.SPOTIFY)
                    if (song.appleMusicUrl.isNotBlank()) add(ShareService.APPLE_MUSIC)
                }
            }
            if (availableServices.isNotEmpty()) {
                var selectedService by remember(song.youtubeUrl, song.spotifyUrl, song.appleMusicUrl) {
                    mutableStateOf(availableServices.first())
                }
                val selectedUrl = when (selectedService) {
                    ShareService.YOUTUBE -> song.youtubeUrl
                    ShareService.SPOTIFY -> song.spotifyUrl
                    ShareService.APPLE_MUSIC -> song.appleMusicUrl
                }
                val serviceIconRes = when (selectedService) {
                    ShareService.YOUTUBE -> R.drawable.ic_youtube
                    ShareService.SPOTIFY -> R.drawable.ic_spotify
                    ShareService.APPLE_MUSIC -> R.drawable.ic_apple_music
                }
                IconButton(
                    onClick = { CapulloTube.openLink(context, selectedUrl) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = "Open",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .combinedClickable(
                            onClick = {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, selectedUrl)
                                        },
                                        null,
                                    ),
                                )
                            },
                            onLongClick = {
                                selectedService = availableServices[(availableServices.indexOf(selectedService) + 1) % availableServices.size]
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(serviceIconRes),
                        contentDescription = "Share (hold to cycle)",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The placeholder at the top of the history while the first identification of the current station
 * runs: the clock time it started and a spinner where the artwork goes. Not a song, so no swipe.
 */
@Composable
fun IdentifyingTrackRow(modifier: Modifier = Modifier) {
    val startedAt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = startedAt,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(LeadingColumnWidth),
        )
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Identifying…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
