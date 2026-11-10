package tech.capullo.quantumcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tech.capullo.quantumcast.recording.RecStation
import tech.capullo.quantumcast.recording.StationZones
import tech.capullo.source.radiobrowser.data.db.FavoriteEntity
import tech.capullo.source.radiobrowser.data.db.FavoriteGroupEntity

/**
 * Full-height station picker for schedules: search on top, "Now playing" first, then favorites in
 * the Favorites screen's groups and order (logo + flag), and a "Search Radio Browser" row so a
 * station can be scheduled without favoriting it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPickerSheet(
    current: RecStation?,
    favorites: List<FavoriteEntity>,
    groups: List<FavoriteGroupEntity>,
    searchDirectory: suspend (String) -> List<RecStation>,
    onPick: (RecStation) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var directoryResults by remember { mutableStateOf<List<RecStation>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val q = query.trim()

    fun matches(name: String, country: String) =
        q.isEmpty() || name.contains(q, ignoreCase = true) || country.contains(q, ignoreCase = true)

    // Same order as the Favorites screen: groups by their sort order (members by theirs), then the rest.
    val sections: List<Pair<String, List<FavoriteEntity>>> = remember(favorites, groups, q) {
        val byGroup = favorites.filter { matches(it.name, it.country) }.groupBy { it.groupId }
        groups.sortedBy { it.sortOrder }.mapNotNull { g ->
            byGroup[g.id]?.sortedBy { it.sortOrder }?.let { g.name to it }
        } + listOfNotNull(
            favorites.filter { f -> groups.none { it.id == f.groupId } && matches(f.name, f.country) }
                .sortedBy { it.sortOrder }.takeIf { it.isNotEmpty() }?.let { (if (groups.isEmpty()) "Favorites" else "Other favorites") to it },
        )
    }
    val showCurrent = current != null && matches(current.name, current.country)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; directoryResults = null },
                placeholder = { Text("Search station or country") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; directoryResults = null }) { Icon(Icons.Default.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (showCurrent) {
                    item { SectionLabel("Now playing") }
                    item { PickRow(current!!.name, current.country, current.favicon, StationZones.countryCode(current)) { onPick(current) } }
                }
                sections.forEach { (title, list) ->
                    item(key = "h-$title") { SectionLabel(title) }
                    items(list, key = { "f-" + it.uuid }) { f ->
                        val st = RecStation(f.uuid, f.name, f.url, f.favicon, f.country)
                        PickRow(f.name, f.country, f.favicon, StationZones.countryCode(st)) { onPick(st) }
                    }
                }
                if (q.isNotEmpty()) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable(enabled = !searching) {
                                searching = true
                                scope.launch {
                                    directoryResults = searchDirectory(q)
                                    searching = false
                                }
                            },
                            leadingContent = { Icon(Icons.Default.TravelExplore, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text("Search Radio Browser for \"$q\"") },
                            trailingContent = { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                        )
                    }
                    directoryResults?.let { results ->
                        if (results.isEmpty()) {
                            item { Text("No stations found", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            item { SectionLabel("Radio Browser") }
                            items(results, key = { "d-" + it.uuid }) { r ->
                                PickRow(r.name, r.country, r.favicon, StationZones.countryCode(r)) { onPick(r) }
                            }
                        }
                    }
                } else if (!showCurrent && sections.isEmpty()) {
                    item {
                        Text(
                            "No favorites yet. Type a name to search Radio Browser.",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickRow(name: String, country: String, favicon: String, countryCode: String?, onClick: () -> Unit) {
    val flag = countryCode?.let(::flagEmoji).orEmpty()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            AsyncImage(
                model = favicon.takeIf { it.isNotEmpty() },
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (country.isNotEmpty()) { { Text(listOf(flag, country).filter { it.isNotEmpty() }.joinToString(" ")) } } else null,
    )
}

private fun flagEmoji(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(code[0].uppercaseChar().code + base)) + String(Character.toChars(code[1].uppercaseChar().code + base))
}
