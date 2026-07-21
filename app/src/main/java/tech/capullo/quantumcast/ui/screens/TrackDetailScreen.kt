package tech.capullo.quantumcast.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import tech.capullo.audio.snapcast.Group
import tech.capullo.audio.ui.SnapcastControlSheet
import tech.capullo.quantumcast.R
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import tech.capullo.quantumcast.viewmodel.RepeatMode
import tech.capullo.quantumcast.viewmodel.RotationMode
import tech.capullo.quantumcast.viewmodel.RotationOrder
import tech.capullo.quantumcast.viewmodel.RotationState
import tech.capullo.source.radiobrowser.data.model.Station
import tech.capullo.source.radiobrowser.data.model.TrackLookup

private fun countryCodeToFlagTD(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 0x41
    return String(Character.toChars(code[0].uppercaseChar().code + base)) +
        String(Character.toChars(code[1].uppercaseChar().code + base))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrackDetailScreen(
    playerState: PlayerState,
    trackHistory: List<TrackLookup>,
    isShazamRunning: Boolean = false,
    rotationState: RotationState = RotationState(),
    rotationQueue: List<Station> = emptyList(),
    favoriteUuids: Set<String> = emptySet(),
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onIdentifyNow: () -> Unit = {},
    onCancelIdentify: () -> Unit = {},
    onSkip: () -> Unit = {},
    onSkipPrev: () -> Unit = {},
    onToggleTimerPause: () -> Unit = {},
    onToggleRotationOrder: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onStopRotation: () -> Unit = {},
    onToggleFavorite: (Station) -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onJumpToQueueStation: (Int) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onDeleteHistoryAt: (Int) -> Unit = {},
    sleepTimerActive: Boolean = false,
    sleepTimerSecondsRemaining: Int = 0,
    onToggleSleepTimer: () -> Unit = {},
    streamStats: RadioViewModel.StreamStats? = null,
    snapcastGroups: List<Group> = emptyList(),
    broadcastHttpPort: Int = 1680,
    snapclientChannel: String = "stereo",
    onAdjustClientVolume: (clientId: String, muted: Boolean, percent: Int) -> Unit = { _, _, _ -> },
    onAdjustClientLatency: (clientId: String, latencyMs: Int) -> Unit = { _, _ -> },
    onSetSnapclientChannel: (String) -> Unit = {},
    onChangeClientChannel: (clientId: String, channel: String) -> Unit = { _, _ -> },
    isSnapclientMode: Boolean = false,
    snapclientHost: String = "",
    onDisconnect: () -> Unit = {},
    streamCanGoNext: Boolean = false,
    streamCanGoPrevious: Boolean = false,
    isStreamLocked: Boolean = false,
    onToggleStreamLock: () -> Unit = {},
    onResetSelf: () -> Unit = {},
    onResetAll: () -> Unit = {},
    onCalibrateSync: (() -> Unit)? = null,
    calibrationRunning: Boolean = false,
    calibrationStatus: String = "",
    shareService: tech.capullo.quantumcast.data.settings.ShareService = tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE,
    onSetShareService: (tech.capullo.quantumcast.data.settings.ShareService) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ownClientId = remember { tech.capullo.audio.snapcast.SnapclientProcess.localHostId(context) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSnapcastSheet by remember { mutableStateOf(false) }
    var swipeDx by remember { mutableFloatStateOf(0f) }

    // Blur the screen content behind the snapcast sheet, matching the web player's
    // `backdrop-filter: blur` overlay (the sheet itself lives in its own window and
    // stays sharp; the sheet darkens the backdrop via its scrim).
    val snapBackdropBlur by animateDpAsState(
        targetValue = if (showSnapcastSheet) 12.dp else 0.dp,
        label = "snapBackdropBlur",
    )

    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .blur(snapBackdropBlur)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> swipeDx += delta },
                onDragStarted = { swipeDx = 0f },
                onDragStopped = { if (swipeDx > 120f) onBack() else swipeDx = 0f },
            ),
    ) {
        val station = playerState.station
        val identifiedTrack = playerState.currentTrack

        // Art: shows Shazam artwork when identified, falls back to station favicon.
        // In snapclient mode, station.favicon is updated from StreamOnProperties art URL.
        var stableArtUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(station?.uuid) {
            stableArtUrl = station?.favicon?.takeIf { it.isNotEmpty() }
        }
        LaunchedEffect(identifiedTrack?.artworkUrl) {
            stableArtUrl = identifiedTrack?.artworkUrl?.takeIf { it.isNotBlank() }
                ?: station?.favicon?.takeIf { it.isNotEmpty() }
        }
        LaunchedEffect(station?.favicon) {
            if (identifiedTrack == null) {
                stableArtUrl = station?.favicon?.takeIf { it.isNotEmpty() }
            }
        }

        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            actions = {
                if (!isSnapclientMode) {
                    HearingButton(
                        isRunning = isShazamRunning,
                        onClick = if (isShazamRunning) onCancelIdentify else onIdentifyNow,
                        onLongPress = { showHistorySheet = true },
                    )
                } else {
                    // Listening in to a remote broadcast - leave it (closes this detail; the
                    // volume/latency/channel controls live in the snapcast sheet below).
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = "Disconnect",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Snapcast clients (o) and the rotation queue moved OUT of the top bar into the
                // transport-controls row (snapcast left of prev, queue right of next).
                IconButton(onClick = onToggleSleepTimer) {
                    if (sleepTimerActive && sleepTimerSecondsRemaining > 0) {
                        val m = sleepTimerSecondsRemaining / 60
                        val s = sleepTimerSecondsRemaining % 60
                        Text(
                            text = if (m > 0) "$m:${s.toString().padStart(2, '0')}" else "${s}s",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Snooze,
                            contentDescription = "Sleep timer",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // Portrait layout - fit-to-space art with the transport pinned below (never squeezed).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val canSwipeArt = rotationState.isActive || (isSnapclientMode && (streamCanGoNext || streamCanGoPrevious))
            var artSwipeDx by remember { mutableFloatStateOf(0f) }
            val artScope = rememberCoroutineScope()
            val isFavorite = station?.uuid?.let { it in favoriteUuids } == true

            Spacer(Modifier.height(8.dp))
            // Fit-to-space art: the art is the SOLE weight holder, so the layout hands it exactly
            // the space left after the (fixed-sibling) info + transport - at any font scale or
            // metadata density, with no magic reserve constant. It's a square capped at the screen
            // width: full-width when it fits, shrinking only when the screen genuinely can't fit
            // full art + info + transport. It can never overlap or squeeze them. BottomCenter keeps
            // the art hugging the info below; any slack on a tall screen sits above the art.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val artSide = minOf(maxWidth, maxHeight)
                Box(
                    modifier = Modifier
                        .size(artSide)
                        .pointerInput(canSwipeArt, onSkip, onSkipPrev) {
                            if (!canSwipeArt) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { artSwipeDx = 0f },
                                onDragEnd = {
                                    val dx = artSwipeDx
                                    when {
                                        dx < -80f -> {
                                            onSkip()
                                            artSwipeDx = 0f
                                        }
                                        dx > 80f -> {
                                            onSkipPrev()
                                            artSwipeDx = 0f
                                        }
                                        else -> artScope.launch {
                                            Animatable(dx).animateTo(
                                                0f,
                                                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                                            ) { artSwipeDx = value }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    val dx = artSwipeDx
                                    artScope.launch {
                                        Animatable(dx).animateTo(
                                            0f,
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                                        ) { artSwipeDx = value }
                                    }
                                },
                                onHorizontalDrag = { _, delta -> artSwipeDx += delta },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = artSwipeDx
                                alpha = (1f - kotlin.math.abs(artSwipeDx) / 600f).coerceAtLeast(0.5f)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (stableArtUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(stableArtUrl).crossfade(300).build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                onError = { stableArtUrl = null },
                            )
                        } else {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(80.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Track info - a fixed sibling (not inside the weighted art box), so the art above can
            // never overlap it and it can never be squeezed.
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NowPlayingHero(
                    station, identifiedTrack, playerState.icyTitle, context,
                    shareService = shareService,
                    onSetShareService = onSetShareService,
                    canSwipe = canSwipeArt,
                    onSwipeNext = onSkip,
                    onSwipePrev = onSkipPrev,
                    isSnapclientMode = isSnapclientMode,
                )
                if (station != null) {
                    NowPlayingInfo(
                        station,
                        streamStats,
                        context,
                        isFavorite = isFavorite,
                        isSnapclientMode = isSnapclientMode,
                        onToggleFavorite = { onToggleFavorite(station) },
                    )
                }
            }
            // Transport pinned to the bottom.
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NowPlayingControls(playerState.isPlaying, playerState.isBuffering, playerState.bufferingPercent, rotationState, onTogglePlayPause, onSkip, onSkipPrev, onToggleTimerPause, isSnapclientMode, streamCanGoNext, streamCanGoPrevious, isStreamLocked, snapcastGroups = snapcastGroups, ownClientId = ownClientId, onOpenSnapcast = { showSnapcastSheet = true }, onOpenQueue = { showQueueSheet = true }, onToggleOrder = onToggleRotationOrder, onCycleRepeat = onCycleRepeat)
            }
            Spacer(Modifier.height(24.dp))
            // This screen draws edge-to-edge OUTSIDE the app Scaffold, so it gets no automatic
            // bottom inset - reserve the system nav-bar height so the transport clears it.
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (showHistorySheet) {
        TrackHistorySheet(
            trackHistory = trackHistory,
            onDismiss = { showHistorySheet = false },
            onClearAll = onClearHistory,
            onDeleteAt = onDeleteHistoryAt,
            context = context,
        )
    }

    if (showQueueSheet && rotationState.isActive) {
        RotationQueueSheet(
            rotationState = rotationState,
            queue = rotationQueue,
            favoriteUuids = favoriteUuids,
            onDismiss = { showQueueSheet = false },
            onToggleFavorite = onToggleFavorite,
            onRemoveAt = { index ->
                onRemoveFromQueue(index)
                if (rotationQueue.size <= 1) showQueueSheet = false
            },
            onJumpTo = { index ->
                onJumpToQueueStation(index)
                showQueueSheet = false
            },
        )
    }

    if (showSnapcastSheet) {
        SnapcastControlSheet(
            groups = snapcastGroups,
            snapclientChannel = snapclientChannel,
            onClientVolumeChange = onAdjustClientVolume,
            onClientLatencyChange = onAdjustClientLatency,
            onSetChannel = onSetSnapclientChannel,
            onChangeClientChannel = onChangeClientChannel,
            isBroadcaster = !isSnapclientMode,
            isStreamLocked = isStreamLocked,
            onToggleStreamLock = onToggleStreamLock,
            localClientId = ownClientId,
            onResetSelf = onResetSelf,
            onResetAll = onResetAll,
            onCalibrateSync = onCalibrateSync,
            calibrationRunning = calibrationRunning,
            calibrationStatus = calibrationStatus,
            httpPort = broadcastHttpPort,
            onDismiss = { showSnapcastSheet = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HearingButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val transition = rememberInfiniteTransition(label = "hear_breathe")
    val breatheScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val resolvedTint = when {
        tint != Color.Unspecified -> tint
        isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Hearing,
            contentDescription = if (isRunning) "Cancel recognition" else "Identify song",
            tint = resolvedTint,
            modifier = Modifier
                .size(24.dp)
                .scale(if (isRunning) breatheScale else 1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotationQueueSheet(
    rotationState: RotationState,
    queue: List<Station>,
    favoriteUuids: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onToggleFavorite: (Station) -> Unit = {},
    onRemoveAt: (Int) -> Unit = {},
    onJumpTo: (Int) -> Unit = {},
) {
    var infoStation by remember { mutableStateOf<Station?>(null) }
    val listState = rememberLazyListState()
    val currentIndex = (rotationState.stationIndex - 1).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        if (queue.isNotEmpty()) listState.scrollToItem(currentIndex)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (rotationState.totalStations > 0) {
                        Text(
                            "Station ${rotationState.stationIndex} of ${rotationState.totalStations}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(queue, key = { _, s -> s.uuid }) { index, station ->
                    val isCurrent = index + 1 == rotationState.stationIndex
                    val currentIndex by rememberUpdatedState(index)
                    val dismissState = rememberSwipeToDismissBoxState(
                        positionalThreshold = { it * 0.72f },
                    )
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onRemoveAt(currentIndex)
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
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
                                .background(
                                    if (isCurrent) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { onJumpTo(index) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (station.favicon.isNotEmpty()) {
                                    AsyncImage(
                                        model = station.favicon,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Radio,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    station.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (station.country.isNotEmpty()) {
                                    Text(
                                        station.country,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { infoStation = station },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Info",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { onToggleFavorite(station) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                val isFav = station.uuid in favoriteUuids
                                Icon(
                                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFav) "Remove favorite" else "Add favorite",
                                    tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    infoStation?.let { s ->
        StationInfoSheet(
            station = s,
            isFavorite = s.uuid in favoriteUuids,
            onDismiss = { infoStation = null },
            onPlay = { infoStation = null },
            onToggleFavorite = {
                onToggleFavorite(s)
                infoStation = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TrackHistorySheet(
    trackHistory: List<TrackLookup>,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    onDeleteAt: (Int) -> Unit,
    context: Context,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Detection History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (trackHistory.isNotEmpty()) {
                    TextButton(onClick = onClearAll) { Text("Clear All") }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(trackHistory, key = { _, t -> t.timestamp }) { index, track ->
                    val currentIndex by rememberUpdatedState(index)
                    val dismissState = rememberSwipeToDismissBoxState(
                        positionalThreshold = { it * 0.72f },
                    )
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDeleteAt(currentIndex)
                    }
                    SwipeToDismissBox(
                        state = dismissState,
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
                        val timeStr = remember(track.timestamp) {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(track.timestamp))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (track.artworkUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = track.artworkUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else if (track.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.secondary,
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
                                    text = track.trackName.ifBlank { track.icyTitle },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                )
                                if (track.artistName.isNotBlank()) {
                                    Text(
                                        text = track.artistName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                    )
                                }
                                if (track.stationName.isNotBlank()) {
                                    val flag = countryCodeToFlagTD(track.stationCountryCode)
                                    Text(
                                        text = if (flag.isNotEmpty()) "$flag ${track.stationName}" else track.stationName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                    )
                                }
                            }
                            if (!track.isLoading) {
                                val availableServices = remember(track.youtubeUrl, track.spotifyUrl, track.appleMusicUrl) {
                                    buildList {
                                        if (track.youtubeUrl.isNotBlank()) add(tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE)
                                        if (track.spotifyUrl.isNotBlank()) add(tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY)
                                        if (track.appleMusicUrl.isNotBlank()) add(tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC)
                                    }
                                }
                                if (availableServices.isNotEmpty()) {
                                    var selectedService by remember(track.youtubeUrl, track.spotifyUrl, track.appleMusicUrl) {
                                        mutableStateOf(availableServices.first())
                                    }
                                    val selectedUrl = when (selectedService) {
                                        tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE -> track.youtubeUrl
                                        tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY -> track.spotifyUrl
                                        tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC -> track.appleMusicUrl
                                    }
                                    val serviceIconRes = when (selectedService) {
                                        tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE -> R.drawable.ic_youtube
                                        tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY -> R.drawable.ic_spotify
                                        tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC -> R.drawable.ic_apple_music
                                    }
                                    IconButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedUrl))) },
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
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingHero(
    station: Station?,
    identifiedTrack: TrackLookup?,
    icyTitle: String,
    context: Context,
    shareService: tech.capullo.quantumcast.data.settings.ShareService = tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE,
    onSetShareService: (tech.capullo.quantumcast.data.settings.ShareService) -> Unit = {},
    canSwipe: Boolean = false,
    onSwipeNext: () -> Unit = {},
    onSwipePrev: () -> Unit = {},
    isSnapclientMode: Boolean = false,
) {
    if (identifiedTrack != null) {
        val urls = mapOf(
            tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE to identifiedTrack.youtubeUrl,
            tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY to identifiedTrack.spotifyUrl,
            tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC to identifiedTrack.appleMusicUrl,
        )
        val availableServices = tech.capullo.quantumcast.data.settings.ShareService.entries
            .filter { urls[it]?.isNotBlank() == true }
        val activeService = if (urls[shareService]?.isNotBlank() == true) {
            shareService
        } else {
            availableServices.firstOrNull() ?: shareService
        }
        val activeUrl = urls[activeService] ?: ""

        fun shareUrl(url: String) {
            if (url.isBlank()) return
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    },
                    null,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(40.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = identifiedTrack.trackName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
                if (identifiedTrack.artistName.isNotBlank()) {
                    Text(
                        text = identifiedTrack.artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
            }

            // Right: smart share - tap shares active service, long-press cycles to next
            if (availableServices.isNotEmpty()) {
                val serviceIconRes = when (activeService) {
                    tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE -> R.drawable.ic_youtube
                    tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY -> R.drawable.ic_spotify
                    tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC -> R.drawable.ic_apple_music
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .combinedClickable(
                            onClick = { shareUrl(activeUrl) },
                            onLongClick = {
                                val next = availableServices[(availableServices.indexOf(activeService) + 1) % availableServices.size]
                                onSetShareService(next)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(serviceIconRes),
                        contentDescription = "Share (hold to cycle)",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(40.dp))
            }
        }
    } else if (station != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isSnapclientMode) {
                val parts = station.name.split(" - ", limit = 2)
                val serverName = parts.getOrElse(0) { station.name }
                val stationName = parts.getOrNull(1)
                val redColor = MaterialTheme.colorScheme.error
                val text = buildAnnotatedString {
                    pushStyle(SpanStyle(color = redColor, fontWeight = FontWeight.Bold))
                    append(serverName)
                    pop()
                    if (stationName != null) {
                        append(" - $stationName")
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
            } else {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
            if (icyTitle.isNotBlank() && !icyTitle.equals(station.name, ignoreCase = true)) {
                Text(
                    text = icyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingInfo(
    station: Station,
    streamStats: RadioViewModel.StreamStats?,
    context: Context,
    isFavorite: Boolean = false,
    isSnapclientMode: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    val flag = countryCodeToFlagTD(station.countryCode)
    val quality = buildString {
        if (station.codec.isNotEmpty()) append(station.codec)
        if (station.bitrate > 0) {
            if (isNotEmpty()) append(" · ")
            append("${station.bitrate} kbps")
        }
    }
    val allTags = remember(station.tags) {
        station.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    var showStats by remember { mutableStateOf(false) }

    if (showStats) {
        StatsDialog(station = station, streamStats = streamStats, onDismiss = { showStats = false })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (station.country.isNotEmpty()) {
            Text(
                text = "$flag ${station.country}".trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            )
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (quality.isNotEmpty()) {
                Text(
                    text = quality,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(
                onClick = { showStats = true },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Stats",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (station.name.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val nameText = if (isSnapclientMode) {
                    val parts = station.name.split(" - ", limit = 2)
                    val serverName = parts[0]
                    val stationPart = parts.getOrNull(1)
                    buildAnnotatedString {
                        pushStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        append(serverName)
                        pop()
                        if (stationPart != null) append(" - $stationPart")
                    }
                } else {
                    buildAnnotatedString { append(station.name) }
                }
                Text(
                    text = nameText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (station.streamUrl.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(28.dp))
                Text(
                    text = station.streamUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
                )
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("stream_url", station.streamUrl))
                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy URL",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (allTags.isNotEmpty()) {
            Text(
                text = allTags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            )
        }
    }
}

// Snapcast clients (o) button - the count of OTHER connected clients (self excluded, same rule
// as the web player) sits in the icon center; alone = plain surround-sound. Tint keys on any
// connection so it stays lit while broadcasting solo. Lives in the transport row now.
@Composable
private fun SnapcastClientsButton(
    snapcastGroups: List<tech.capullo.audio.snapcast.Group>,
    ownClientId: String,
    isSnapclientMode: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        val totalConnected = snapcastGroups.sumOf { g -> g.clients.count { c -> c.connected } }
        val otherCount = snapcastGroups.sumOf { g ->
            g.clients.count { c -> c.connected && c.id != ownClientId }
        }
        val clientsTint = if (totalConnected > 0) {
            if (isSnapclientMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        }
        if (otherCount > 0) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_surround_sound_nodot),
                    contentDescription = "Connected devices",
                    tint = clientsTint,
                )
                Text(
                    text = if (otherCount > 99) "99" else "$otherCount",
                    color = clientsTint,
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Icon(Icons.Default.SurroundSound, contentDescription = "Connected devices", tint = clientsTint)
        }
    }
}

// TC-standard now-playing transport. Two rows, matching Telecloud's PlayerScreen:
//   row 1 (transport) - play/pause ALWAYS screen-centered, flanked by two equal-weight zones so
//     the primary button never moves off-centre regardless of which side buttons are present:
//     [order] [prev]  ( play )  [next+ring] [repeat]
//   row 2 (secondary) - snapcast (o) left · queue right (SpaceBetween).
// The full transport shows only while a local rotation is active; single-station and snapclient/
// listen-in modes keep their minimal set (just restyled + centered).
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingControls(
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    bufferingPercent: Float = 0f,
    rotationState: RotationState,
    onTogglePlayPause: () -> Unit,
    onSkip: () -> Unit,
    onSkipPrev: () -> Unit = {},
    onToggleTimerPause: () -> Unit = {},
    isSnapclientMode: Boolean = false,
    streamCanGoNext: Boolean = false,
    streamCanGoPrevious: Boolean = false,
    isStreamLocked: Boolean = false,
    snapcastGroups: List<tech.capullo.audio.snapcast.Group> = emptyList(),
    ownClientId: String = "",
    onOpenSnapcast: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onToggleOrder: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
) {
    val lockedInClient = isSnapclientMode && isStreamLocked
    val rotationActive = rotationState.isActive && !isSnapclientMode

    // Blinking alpha for a paused rotation timer - always create the transition, gate usage
    val inf = rememberInfiniteTransition(label = "blink")
    val blinkAnimAlpha by inf.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        label = "blink",
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
    )
    val blinkAlpha = if (rotationState.timerPaused) blinkAnimAlpha else 1f

    val snapFilledColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
        disabledContentColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.38f),
    )
    val sideTint = if (isSnapclientMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- Transport row: two equal-weight zones flank a fixed, always-centered play button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEFT zone (hugs play): order (custom rotation only) + prev
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rotationActive && rotationState.mode == RotationMode.CUSTOM) {
                    // Shuffle on/off (like repeat) - on = reshuffled upcoming, off = listed order
                    val shuffled = rotationState.order == RotationOrder.SHUFFLED
                    IconButton(onClick = onToggleOrder) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = if (shuffled) "Shuffle on" else "Shuffle off",
                            modifier = Modifier.size(26.dp),
                            tint = if (shuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (rotationActive || (isSnapclientMode && streamCanGoPrevious) || lockedInClient) {
                    IconButton(
                        onClick = onSkipPrev,
                        enabled = !lockedInClient,
                        modifier = Modifier.size(56.dp).alpha(if (lockedInClient) 0.38f else 1f),
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(40.dp), tint = sideTint)
                    }
                }
            }

            // CENTER: play / pause - fixed 68dp, never moves off-centre; shows lock when locked.
            // 8dp horizontal padding keeps a gap to prev/next (parity with TC's 8dp spacing).
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    enabled = !lockedInClient,
                    modifier = Modifier.size(68.dp).alpha(if (lockedInClient) 0.38f else 1f),
                    colors = if (isSnapclientMode) snapFilledColors else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (lockedInClient) {
                        Icon(Icons.Default.Lock, "Locked by broadcaster", modifier = Modifier.size(40.dp))
                    } else if (isBuffering) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { (bufferingPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                            )
                            Text(
                                text = "${bufferingPercent.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            // RIGHT zone (hugs play): next (+ in-button countdown ring in rotation) + repeat
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    rotationActive -> NextButtonWithRing(
                        progress = rotationState.progress,
                        timerPaused = rotationState.timerPaused,
                        blinkAlpha = blinkAlpha,
                        onSkip = onSkip,
                        onToggleTimerPause = onToggleTimerPause,
                    )
                    isSnapclientMode && (streamCanGoNext || lockedInClient) -> {
                        IconButton(
                            onClick = onSkip,
                            enabled = !lockedInClient,
                            modifier = Modifier.size(56.dp).alpha(if (lockedInClient) 0.38f else 1f),
                        ) {
                            Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(40.dp), tint = sideTint)
                        }
                    }
                }
                // Repeat cycle OFF -> LOOP -> DISCOVER - finite rotation only (moot for endless RANDOM).
                if (rotationActive && rotationState.mode != RotationMode.RANDOM) {
                    val (repeatIcon, repeatTint, repeatDesc) = when (rotationState.repeatMode) {
                        RepeatMode.OFF -> Triple(Icons.Default.Repeat, MaterialTheme.colorScheme.onSurfaceVariant, "Repeat off")
                        RepeatMode.LOOP -> Triple(Icons.Default.Repeat, MaterialTheme.colorScheme.primary, "Repeat queue")
                        RepeatMode.DISCOVER -> Triple(Icons.Default.TravelExplore, MaterialTheme.colorScheme.primary, "Discovery (fresh stations each loop)")
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            repeatIcon,
                            contentDescription = repeatDesc,
                            modifier = Modifier.size(26.dp),
                            tint = repeatTint,
                        )
                    }
                }
            }
        }

        // ---- Secondary row: snapcast (o) left · queue right (queue only while rotating).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnapcastClientsButton(
                snapcastGroups = snapcastGroups,
                ownClientId = ownClientId,
                isSnapclientMode = isSnapclientMode,
                onClick = onOpenSnapcast,
            )
            if (rotationActive) {
                IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

// Next button with the rotation countdown drawn as a ring ON the button's own 56dp border -
// fixed size, no gap between ring and button, so the countdown never resizes/re-centres the row.
// Tap = skip · long-press = pause/resume the timer.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextButtonWithRing(
    progress: Float,
    timerPaused: Boolean,
    blinkAlpha: Float,
    onSkip: () -> Unit,
    onToggleTimerPause: () -> Unit,
) {
    val ringColor = if (timerPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                val sw = 3.dp.toPx()
                val inset = sw / 2f
                val arcSize = Size(size.width - sw, size.height - sw)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(sw),
                )
                drawArc(
                    color = ringColor.copy(alpha = blinkAlpha),
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
            .pointerInput(onSkip, onToggleTimerPause) {
                detectTapGestures(onTap = { onSkip() }, onLongPress = { onToggleTimerPause() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.SkipNext,
            contentDescription = "Skip (long-press to pause timer)",
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatsDialog(
    station: Station,
    streamStats: RadioViewModel.StreamStats?,
    onDismiss: () -> Unit,
) {
    val rows = buildList {
        val codec = streamStats?.codec?.takeIf { it.isNotEmpty() } ?: station.codec.takeIf { it.isNotEmpty() }
        val bitrate = streamStats?.bitrate?.takeIf { it > 0 } ?: station.bitrate.takeIf { it > 0 }
        if (codec != null) add("Codec" to codec)
        if (bitrate != null) add("Bitrate" to "$bitrate kbps")
        if (streamStats != null) {
            add("Sample rate" to "${streamStats.sampleRate} Hz")
            add(
                "Channels" to when (streamStats.channels) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    else -> "${streamStats.channels}ch"
                },
            )
        } else {
            add("Sample rate" to "-")
            add("Channels" to "-")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stats for nerds", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(88.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
