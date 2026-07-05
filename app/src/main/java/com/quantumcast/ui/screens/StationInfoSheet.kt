package com.quantumcast.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.quantumcast.data.model.Station

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 0x41
    return String(Character.toChars(code[0].uppercaseChar().code + base)) +
           String(Character.toChars(code[1].uppercaseChar().code + base))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationInfoSheet(
    station: Station,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: favicon + name + play/fav actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (station.favicon.isNotEmpty()) {
                        AsyncImage(
                            model = station.favicon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.PlayArrow, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onPlay(); onDismiss() }) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (isFavorite) "Remove favorite" else "Add favorite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Info rows
            val flag = countryCodeToFlag(station.countryCode)
            if (station.country.isNotEmpty()) {
                InfoRow("Country", "$flag ${station.country}".trim())
            }
            if (station.language.isNotEmpty()) {
                InfoRow("Language", station.language)
            }
            if (station.codec.isNotEmpty() || station.bitrate > 0) {
                val quality = buildString {
                    if (station.codec.isNotEmpty()) append(station.codec)
                    if (station.bitrate > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${station.bitrate} kbps")
                    }
                }
                InfoRow("Quality", quality)
            }
            if (station.votes > 0 || station.clickCount > 0) {
                InfoRow("Stats", "▲ ${station.votes} votes · ${station.clickCount} clicks")
            }

            // All tags
            val allTags = station.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (allTags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(allTags) { tag -> TagChip(tag) }
                    }
                }
            }

            // Stream URL
            if (station.streamUrl.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Stream URL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = station.streamUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("stream_url", station.streamUrl))
                            Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, "Copy URL", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
