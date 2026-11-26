package tech.capullo.quantumcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tech.capullo.quantumcast.data.playlists.DetectedTrack
import tech.capullo.quantumcast.data.playlists.PlaylistStationEntity
import tech.capullo.quantumcast.data.playlists.StationPlaylist
import tech.capullo.quantumcast.data.playlists.YoutubeLinks

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** Every station that has identified songs, most recently updated first. Tapping one opens it. */
@Composable
fun PlaylistsScreen(
    playlists: List<StationPlaylist>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<StationPlaylist?>(null) }

    if (playlists.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Songs identified while a station plays are kept here, one playlist per station.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(playlists, key = { it.station.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onOpen = { onOpen(playlist.station.id) },
                    onDelete = { deleteTarget = playlist },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this playlist?") },
            text = { Text("${target.station.name}: ${songCount(target.trackCount)} will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.station.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistRow(playlist: StationPlaylist, onOpen: () -> Unit, onDelete: () -> Unit) {
    val station = playlist.station
    val updated = remember(playlist.updatedAt) { detectionDateLabel(playlist.updatedAt) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationFavicon(station.favicon)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val flag = countryFlag(station.countryCode)
            Text(
                text = if (flag.isNotEmpty()) "$flag ${station.name}" else station.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${songCount(playlist.trackCount)} · $updated",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Same tile as StationCard: the station's favicon, or a radio glyph when it has none.
@Composable
private fun StationFavicon(favicon: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (favicon.isNotEmpty()) {
            AsyncImage(
                model = favicon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * One station's playlist: hand it to CapulloTube or tune the station again, then its songs, last
 * detected first. Swiping a song left removes it from the playlist (and from the history).
 */
@Composable
fun PlaylistScreen(
    station: PlaylistStationEntity?,
    tracks: List<DetectedTrack>,
    capulloTubeInstalled: Boolean,
    onPlayInCapulloTube: () -> Unit,
    onPlayStation: () -> Unit,
    onDeleteTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // CapulloTube plays videos only: a song whose YouTube link is a search is skipped there.
    val youtubeCount = remember(tracks) { tracks.count { YoutubeLinks.videoId(it.track.youtubeUrl) != null } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (station != null) {
            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (capulloTubeInstalled && youtubeCount > 0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledTonalButton(onClick = onPlayInCapulloTube) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play in CapulloTube")
                            }
                            if (youtubeCount < tracks.size) {
                                Text(
                                    "$youtubeCount of ${tracks.size} songs have a YouTube link",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = onPlayStation) {
                        Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play station")
                    }
                }
            }
        }
        items(tracks, key = { it.track.id }) { track ->
            val label = remember(track.track.lastDetectedAt) { detectionDateLabel(track.track.lastDetectedAt) }
            DetectedTrackRow(
                track = track,
                leadingText = label,
                showStation = false,
                showDetections = true,
                onDelete = { onDeleteTrack(track.track.id) },
            )
        }
    }
}
