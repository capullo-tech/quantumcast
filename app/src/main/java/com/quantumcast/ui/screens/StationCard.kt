package com.quantumcast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.quantumcast.data.model.Station

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationCard(
    station: Station,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    inSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onEnterSelectMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo) {
        StationInfoSheet(
            station = station,
            isFavorite = isFavorite,
            onDismiss = { showInfo = false },
            onPlay = { onPlay(); showInfo = false },
            onToggleFavorite = onToggleFavorite
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = if (inSelectMode) onSelect else onPlay,
                onLongClick = if (inSelectMode) null else onEnterSelectMode
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (inSelectMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (station.favicon.isNotEmpty()) {
                    AsyncImage(
                        model = station.favicon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                if (station.country.isNotEmpty()) {
                    Text(
                        text = station.country,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                val allTags = remember(station.tags) {
                    station.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (allTags.isNotEmpty()) {
                    Text(
                        text = allTags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .basicMarquee(iterations = Int.MAX_VALUE)
                    )
                }
                if (station.bitrate > 0 || station.codec.isNotEmpty()) {
                    val qualityText = listOfNotNull(
                        station.codec.takeIf { it.isNotEmpty() },
                        if (station.bitrate > 0) "${station.bitrate} kbps" else null
                    ).joinToString(" · ")
                    Text(
                        text = qualityText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!inSelectMode) {
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Info, "Info", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TagChip(tag: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
