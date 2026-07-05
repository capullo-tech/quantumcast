package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.*
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tech.capullo.quantumcast.data.model.TrackLookup
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RotationState

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 0x41
    return String(Character.toChars(code[0].uppercaseChar().code + base)) +
           String(Character.toChars(code[1].uppercaseChar().code + base))
}

@Composable
fun NowPlayingBar(
    state: PlayerState,
    latestTrack: TrackLookup? = null,
    rotationState: RotationState = RotationState(),
    isSnapclientMode: Boolean = false,
    streamCanGoNext: Boolean = false,
    streamCanGoPrevious: Boolean = false,
    isStreamLocked: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onSkipPrev: () -> Unit = {},
    onSkip: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.station != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        val station = state.station ?: return@AnimatedVisibility

        LaunchedEffect(station.name) {
            Log.d("RG2UI", "bar: station → ${station.name}")
        }
        LaunchedEffect(state.icyTitle) {
            Log.d("RG2UI", "bar: icyTitle → ${state.icyTitle}")
        }

        // Stable art: advances to track art when available, resets to favicon on station change.
        // In snapclient mode, station.favicon is updated from StreamOnProperties art URL.
        var stableBarArt by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(station.uuid) {
            stableBarArt = station.favicon.takeIf { it.isNotEmpty() }
        }
        LaunchedEffect(latestTrack?.timestamp) {
            if (latestTrack?.isLoading == true) return@LaunchedEffect
            val art = latestTrack?.takeIf { !it.notFound && it.artworkUrl.isNotBlank() }?.artworkUrl
            stableBarArt = art ?: station.favicon.takeIf { it.isNotEmpty() }
        }
        LaunchedEffect(station.favicon) {
            if (latestTrack == null || latestTrack.notFound || latestTrack.artworkUrl.isBlank()) {
                stableBarArt = station.favicon.takeIf { it.isNotEmpty() }
            }
        }
        val barArt = stableBarArt ?: station.favicon.takeIf { it.isNotEmpty() }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDetail)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = barArt,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val flag = countryCodeToFlag(station.countryCode)
                    val nameWithFlag = if (flag.isNotEmpty()) "$flag ${station.name}" else station.name
                    Text(
                        text = nameWithFlag,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    val shazamLine = latestTrack
                        ?.takeIf { !it.notFound && it.trackName.isNotBlank() }
                        ?.let { t ->
                            if (t.artistName.isNotBlank()) "${t.artistName} - ${t.trackName}"
                            else t.trackName
                        }
                    val subtitle = shazamLine ?: state.icyTitle.ifEmpty { station.country }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                    if (state.isBuffering) {
                        Text(
                            text = "Connecting...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val lockedInClient = isSnapclientMode && isStreamLocked
                if (rotationState.isActive || (isSnapclientMode && streamCanGoPrevious) || lockedInClient) {
                    IconButton(onClick = onSkipPrev, enabled = !lockedInClient) {
                        Icon(Icons.Default.SkipPrevious, "Previous",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(if (lockedInClient) 0.38f else 1f))
                    }
                }
                IconButton(onClick = onTogglePlayPause, enabled = !lockedInClient) {
                    if (lockedInClient) {
                        Icon(Icons.Default.Lock, "Locked",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(0.38f))
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying || state.isBuffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying || state.isBuffering) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (rotationState.isActive || (isSnapclientMode && streamCanGoNext) || lockedInClient) {
                    IconButton(onClick = onSkip, enabled = !lockedInClient) {
                        Icon(Icons.Default.SkipNext, "Next",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(if (lockedInClient) 0.38f else 1f))
                    }
                }
            }
        }
    }
}
