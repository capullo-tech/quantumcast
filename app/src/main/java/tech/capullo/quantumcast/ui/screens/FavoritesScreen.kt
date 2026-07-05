package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tech.capullo.quantumcast.data.db.FavoriteEntity
import tech.capullo.quantumcast.data.db.FavoriteGroupEntity
import tech.capullo.quantumcast.data.model.Station
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun FavoriteEntity.toStation() = Station(
    uuid = uuid, name = name, url = url, favicon = favicon,
    country = country, tags = tags, codec = codec, bitrate = bitrate
)

private sealed class FavListItem {
    abstract val key: Any
    data class GroupHeader(val group: FavoriteGroupEntity, val stationCount: Int) : FavListItem() {
        override val key: String get() = "grp_${group.id}"
    }
    data class StationRow(val fav: FavoriteEntity) : FavListItem() {
        override val key: String get() = "stn_${fav.uuid}"
    }
    object UngroupedLabel : FavListItem() {
        override val key: String = "ungrouped_label"
    }
}

// Scan backwards from `index` to find which group a station belongs to after a drag.
// Returns the group id, or "" for ungrouped.
private fun inferGroupIdAt(flatItems: List<FavListItem>, index: Int): String {
    for (i in index - 1 downTo 0) {
        when (val item = flatItems[i]) {
            is FavListItem.GroupHeader -> return item.group.id
            FavListItem.UngroupedLabel -> return ""
            else -> {}
        }
    }
    return ""
}

private fun buildFlatList(
    groups: List<FavoriteGroupEntity>,
    favorites: List<FavoriteEntity>,
    expandedGroupIds: Set<String>
): List<FavListItem> {
    val byGroup = favorites.groupBy { it.groupId }
    val result = mutableListOf<FavListItem>()
    for (group in groups) {
        val stationsInGroup = byGroup[group.id].orEmpty()
        result.add(FavListItem.GroupHeader(group, stationsInGroup.size))
        if (group.id in expandedGroupIds) {
            stationsInGroup.sortedBy { it.sortOrder }.forEach { result.add(FavListItem.StationRow(it)) }
        }
    }
    val ungrouped = byGroup[""].orEmpty().sortedBy { it.sortOrder }
    if (ungrouped.isNotEmpty() && groups.isNotEmpty()) result.add(FavListItem.UngroupedLabel)
    ungrouped.forEach { result.add(FavListItem.StationRow(it)) }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteEntity>,
    groups: List<FavoriteGroupEntity> = emptyList(),
    expandedGroupIds: Set<String> = emptySet(),
    playerState: PlayerState,
    onPlay: (FavoriteEntity) -> Unit,
    onRemove: (Station) -> Unit,
    onStartRotation: () -> Unit,
    onStartCustomRotation: (List<Station>) -> Unit = {},
    onToggleGroupExpanded: (String) -> Unit = {},
    onCreateGroup: (String, Set<String>) -> Unit = { _, _ -> },
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onAssignToGroup: (Set<String>, String) -> Unit = { _, _ -> },
    onUnassignFromGroup: (Set<String>) -> Unit = {},
    onReorderFavorite: (String, Int) -> Unit = { _, _ -> },
    onReorderGroup: (String, Int) -> Unit = { _, _ -> },
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty() && groups.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text("No favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Long-press any station to add", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    var inSelectMode by remember { mutableStateOf(false) }
    var selectedUuids by remember { mutableStateOf(setOf<String>()) }
    var renameTarget by remember { mutableStateOf<FavoriteGroupEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteGroupEntity?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    var flatItems by remember { mutableStateOf(buildFlatList(groups, favorites, expandedGroupIds)) }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val offset = 1  // one header item before flat list
        val fi = from.index - offset
        val ti = to.index - offset
        val fromItem = flatItems.getOrNull(fi) ?: return@rememberReorderableLazyListState
        val toItem = flatItems.getOrNull(ti) ?: return@rememberReorderableLazyListState
        // Groups reorder only with groups; stations can move to any station position (cross-group OK).
        // Headers and labels are immovable anchors - skip moves that would displace them.
        val canMove = (fromItem is FavListItem.GroupHeader && toItem is FavListItem.GroupHeader) ||
                      (fromItem is FavListItem.StationRow  && toItem is FavListItem.StationRow)
        if (canMove) flatItems = flatItems.toMutableList().apply { add(ti, removeAt(fi)) }
    }

    val isDraggingNow by rememberUpdatedState(reorderState.isAnyItemDragging)
    LaunchedEffect(groups, favorites, expandedGroupIds) {
        if (!isDraggingNow) flatItems = buildFlatList(groups, favorites, expandedGroupIds)
    }

    var wasDragging by remember { mutableStateOf(false) }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (wasDragging && !reorderState.isAnyItemDragging) {
            // Persist group reorder
            flatItems.filterIsInstance<FavListItem.GroupHeader>().forEachIndexed { i, h ->
                if (h.group.sortOrder != i) onReorderGroup(h.group.id, i)
            }
            // For each station infer its new group from position, then persist sort order and group changes
            val byNewGroup = linkedMapOf<String, MutableList<FavListItem.StationRow>>()
            flatItems.forEachIndexed { idx, item ->
                if (item is FavListItem.StationRow) {
                    val newGid = inferGroupIdAt(flatItems, idx)
                    byNewGroup.getOrPut(newGid) { mutableListOf() }.add(item)
                }
            }
            byNewGroup.forEach { (newGroupId, rows) ->
                rows.forEachIndexed { newOrder, row ->
                    if (row.fav.groupId != newGroupId) {
                        if (newGroupId.isEmpty()) onUnassignFromGroup(setOf(row.fav.uuid))
                        else onAssignToGroup(setOf(row.fav.uuid), newGroupId)
                    }
                    if (row.fav.sortOrder != newOrder) onReorderFavorite(row.fav.uuid, newOrder)
                }
            }
        }
        wasDragging = reorderState.isAnyItemDragging
    }

    // Dialogs
    renameTarget?.let { group ->
        var nameText by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(value = nameText, onValueChange = { nameText = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nameText.isNotBlank()) { onRenameGroup(group.id, nameText.trim()); renameTarget = null }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete group") },
            text = { Text("\"${group.name}\" will be deleted. Stations stay in your favorites.") },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteGroup(group.id); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    if (showGroupDialog) {
        GroupAssignDialog(
            existingGroups = groups,
            onCreateNew = { name ->
                onCreateGroup(name, selectedUuids)
                showGroupDialog = false; inSelectMode = false; selectedUuids = emptySet()
            },
            onAssignExisting = { groupId ->
                onAssignToGroup(selectedUuids, groupId)
                showGroupDialog = false; inSelectMode = false; selectedUuids = emptySet()
            },
            onDismiss = { showGroupDialog = false }
        )
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (inSelectMode) 80.dp else 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "top_header", contentType = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${favorites.size} favorite${if (favorites.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (inSelectMode) {
                        TextButton(onClick = {
                            selectedUuids = if (selectedUuids.size == favorites.size)
                                emptySet() else favorites.map { it.uuid }.toSet()
                        }) { Text(if (selectedUuids.size == favorites.size) "None" else "All") }
                        IconButton(onClick = { inSelectMode = false; selectedUuids = emptySet() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, "Exit", modifier = Modifier.size(18.dp))
                        }
                    }
                    FilledTonalButton(onClick = {
                        val sel = favorites.filter { it.uuid in selectedUuids }.map { it.toStation() }
                        inSelectMode = false; selectedUuids = emptySet()
                        if (sel.isEmpty()) onStartRotation() else onStartCustomRotation(sel)
                    }) {
                        Icon(Icons.Default.Radio, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectedUuids.isEmpty()) "Auto" else "Auto (${selectedUuids.size})")
                    }
                }
            }

            items(flatItems, key = { it.key }, contentType = { it::class.simpleName }) { item ->
                when (item) {
                    is FavListItem.GroupHeader -> {
                        ReorderableItem(reorderState, key = item.key) { isDragging ->
                            GroupHeaderRow(
                                group = item.group,
                                stationCount = item.stationCount,
                                isExpanded = item.group.id in expandedGroupIds,
                                isDragging = isDragging,
                                inSelectMode = inSelectMode,
                                dragHandleModifier = Modifier.draggableHandle(),
                                onToggleExpand = { onToggleGroupExpanded(item.group.id) },
                                onLongPress = {
                                    val groupUuids = favorites
                                        .filter { it.groupId == item.group.id }
                                        .map { it.uuid }.toSet()
                                    if (groupUuids.isNotEmpty()) {
                                        inSelectMode = true
                                        selectedUuids = groupUuids
                                        if (item.group.id !in expandedGroupIds) onToggleGroupExpanded(item.group.id)
                                    }
                                },
                                onRename = { renameTarget = item.group },
                                onDelete = { deleteTarget = item.group }
                            )
                        }
                    }
                    is FavListItem.StationRow -> {
                        ReorderableItem(reorderState, key = item.key) { isDragging ->
                            FavoriteCard(
                                fav = item.fav,
                                isPlaying = playerState.station?.uuid == item.fav.uuid && playerState.isPlaying,
                                isDragging = isDragging,
                                inSelectMode = inSelectMode,
                                isSelected = item.fav.uuid in selectedUuids,
                                dragHandleModifier = Modifier.draggableHandle(),
                                onPlay = { onPlay(item.fav) },
                                onRemove = { onRemove(item.fav.toStation()) },
                                onSelect = {
                                    selectedUuids = if (item.fav.uuid in selectedUuids)
                                        selectedUuids - item.fav.uuid else selectedUuids + item.fav.uuid
                                },
                                onEnterSelectMode = { inSelectMode = true; selectedUuids = setOf(item.fav.uuid) }
                            )
                        }
                    }
                    FavListItem.UngroupedLabel -> {
                        Text(
                            "Ungrouped",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                }
            }
        }

        // Selection action bar
        AnimatedVisibility(
            visible = inSelectMode && selectedUuids.isNotEmpty(),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val allSelectedInGroup = favorites.filter { it.uuid in selectedUuids }.all { it.groupId.isNotEmpty() }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedUuids.size} selected", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (allSelectedInGroup && selectedUuids.isNotEmpty()) {
                        TextButton(onClick = {
                            onUnassignFromGroup(selectedUuids)
                            inSelectMode = false; selectedUuids = emptySet()
                        }) { Text("Ungroup") }
                    }
                    TextButton(onClick = { showGroupDialog = true }) { Text("Group") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeaderRow(
    group: FavoriteGroupEntity,
    stationCount: Int,
    isExpanded: Boolean,
    isDragging: Boolean,
    inSelectMode: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onToggleExpand: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onToggleExpand, onLongClick = onLongPress),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                Text(
                    "$stationCount station${if (stationCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!inSelectMode) {
                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
    fav: FavoriteEntity,
    isPlaying: Boolean,
    isDragging: Boolean,
    inSelectMode: Boolean,
    isSelected: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onSelect: () -> Unit,
    onEnterSelectMode: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showInfo) {
        StationInfoSheet(
            station = fav.toStation(),
            isFavorite = true,
            onDismiss = { showInfo = false },
            onPlay = { onPlay(); showInfo = false },
            onToggleFavorite = { onRemove(); showInfo = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove favorite") },
            text = { Text("Remove \"${fav.name}\" from favorites?") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onRemove() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(12.dp))
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
                    Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.size(22.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (fav.favicon.isNotEmpty()) {
                    AsyncImage(model = fav.favicon, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fav.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                if (fav.country.isNotEmpty()) {
                    Text(fav.country, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val allTags = remember(fav.tags) { fav.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
                if (allTags.isNotEmpty()) {
                    Text(
                        allTags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                }
                if (fav.bitrate > 0 || fav.codec.isNotEmpty()) {
                    val q = listOfNotNull(fav.codec.takeIf { it.isNotEmpty() }, if (fav.bitrate > 0) "${fav.bitrate} kbps" else null).joinToString(" · ")
                    Text(q, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!inSelectMode) {
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Info, "Info", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun GroupAssignDialog(
    existingGroups: List<FavoriteGroupEntity>,
    onCreateNew: (String) -> Unit,
    onAssignExisting: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newGroupName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("New group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (existingGroups.isNotEmpty()) {
                    Text("Or assign to:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    existingGroups.forEach { group ->
                        TextButton(onClick = { onAssignExisting(group.id) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(group.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (newGroupName.isNotBlank()) {
                TextButton(onClick = { onCreateNew(newGroupName.trim()) }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
