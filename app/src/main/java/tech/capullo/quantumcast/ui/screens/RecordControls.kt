package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.quantumcast.player.PlaybackService
import tech.capullo.quantumcast.recording.RecordingRepository
import java.util.Locale

val RecRed = Color(0xFFE53935)

// --- Shared blink clock -----------------------------------------------------------------------

/**
 * 0 → normal, 1 → red dot. One clock for the whole app so the play button, the dot next to the ear
 * and the Recordings row pulse in step. ≈0.7 s each way with a short crossfade (reads as a blink,
 * not a flicker).
 */
val LocalRecBlink = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

@Composable
fun ProvideRecBlink(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        // No running animation (no per-frame recomposition) while nothing records.
        CompositionLocalProvider(LocalRecBlink provides remember { mutableFloatStateOf(0f) }, content = content)
        return
    }
    val t = rememberInfiniteTransition(label = "recBlink")
    val v = t.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1400
                0f at 0
                0f at 600
                1f at 750
                1f at 1300
                0f at 1400
            },
        ),
        label = "recBlinkPhase",
    )
    CompositionLocalProvider(LocalRecBlink provides v, content = content)
}

/** A red dot pulsing on the shared clock: "something is recording". */
@Composable
fun BlinkingRecDot(size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val v by LocalRecBlink.current
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = 0.2f + 0.8f * v }
            .clip(CircleShape)
            .background(RecRed),
    )
}

// --- Play / pause ---------------------------------------------------------------------------

/**
 * Play/pause button. While [recordingThis] is true it alternates between its normal icon and a
 * centred red dot (the station being played is recording). Tap and long-press are the caller's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayPauseButton(
    size: Dp,
    enabled: Boolean,
    recordingThis: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val blink = LocalRecBlink.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                enabled = enabled,
                interactionSource = null,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick?.let { lc ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        lc()
                    }
                },
                onLongClickLabel = longClickLabel,
            )
            .then(if (recordingThis) Modifier.semantics { contentDescription = "Recording" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!recordingThis) {
            content()
        } else {
            Box(Modifier.graphicsLayer { alpha = 1f - blink.value }, contentAlignment = Alignment.Center) { content() }
            Box(
                Modifier
                    .size(size * 0.32f)
                    .graphicsLayer { alpha = blink.value }
                    .clip(CircleShape)
                    .background(RecRed),
            )
        }
    }
}

// --- Recording playback controls ----------------------------------------------------------

/** Everything the players need while a recording (not a station) is playing. */
data class RecordingPlaybackUi(
    val progress: StateFlow<PlaybackService.MediaProgress>,
    val skipSeconds: Int,
    val speed: Float,
    val onSeek: (Long) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSetSpeed: (Float) -> Unit,
)

/** Circular-arrow skip button showing its interval; tap = skip, long-press = previous/next recording. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkipIntervalButton(
    forward: Boolean,
    seconds: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    size: Dp = 56.dp,
    iconSize: Dp = 40.dp,
    tint: Color = LocalContentColor.current,
) {
    val haptics = LocalHapticFeedback.current
    val label = if (seconds < 60) "$seconds" else if (seconds % 60 == 0) "${seconds / 60}m" else "${seconds / 60}:${"%02d".format(seconds % 60)}"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onClickLabel = if (forward) "Forward ${RecordingPrefsLabels.interval(seconds)}" else "Back ${RecordingPrefsLabels.interval(seconds)}",
                onLongClickLabel = if (forward) "Next recording" else "Previous recording",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Replay,
            contentDescription = null,
            tint = tint,
            // Replay is a back arrow; mirrored it reads as forward.
            modifier = Modifier.size(iconSize).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            label,
            color = tint,
            fontSize = (iconSize.value * 0.26f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = iconSize * 0.08f),
        )
    }
}

private object RecordingPrefsLabels {
    fun interval(s: Int) = tech.capullo.quantumcast.recording.RecordingPrefs.intervalLabel(s)
}

fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Seek bar, Telecloud's look: slim pill thumb, 3 dp track, elapsed left, −remaining right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSeekBar(ui: RecordingPlaybackUi, modifier: Modifier = Modifier) {
    val p by ui.progress.collectAsState()
    val durationMs = p.durationMs
    // The thumb follows the finger; the seek happens on release, so it never fights the ticker.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownMs = (dragValue ?: p.positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()).toLong()
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = dragValue ?: p.positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { ui.onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L,
            thumb = {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(3.dp),
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatClock(shownMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "-" + formatClock((durationMs - shownMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Thin progress line for the mini player. */
@Composable
fun MiniProgressLine(progress: StateFlow<PlaybackService.MediaProgress>, modifier: Modifier = Modifier) {
    val p by progress.collectAsState()
    val f = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { f },
        modifier = modifier.fillMaxWidth().height(2.dp),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

fun speedLabel(speed: Float): String {
    val r = Math.round(speed * 100) / 100f
    val s = if (r * 10 == Math.round(r * 10).toFloat()) String.format(Locale.US, "%.1f", r) else String.format(Locale.US, "%.2f", r)
    return s.removeSuffix(".0") + "×"
}

/** Top-bar speed control: speedometer at 1×, the value (accent) otherwise. Tap opens the panel. */
@Composable
fun SpeedChip(speed: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, onClickLabel = "Playback speed"),
        contentAlignment = Alignment.Center,
    ) {
        if (speed == 1f) {
            Icon(Icons.Default.Speed, contentDescription = "Playback speed 1×", tint = MaterialTheme.colorScheme.onSurface)
        } else {
            Text(
                speedLabel(speed),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Speed panel: slider 0.5–4.0× snapping to 0.1; (−) 1× 2× 3× (+), where −/+ step 0.05. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPanel(speed: Float, onSetSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val min = RecordingRepository.MIN_SPEED
    val max = RecordingRepository.MAX_SPEED
    fun clamp(v: Float) = (Math.round(v * 100) / 100f).coerceIn(min, max)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(speedLabel(speed), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Slider(
                value = speed,
                onValueChange = { onSetSpeed(clamp(Math.round(it * 10) / 10f)) },
                valueRange = min..max,
                steps = Math.round((max - min) / 0.1f) - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { onSetSpeed(clamp(speed - 0.05f)) }, enabled = speed > min) {
                    Icon(Icons.Default.Remove, "Slower by 0.05")
                }
                listOf(1f, 2f, 3f).forEach { preset ->
                    FilterChip(
                        selected = speed == preset,
                        onClick = { onSetSpeed(preset) },
                        label = { Text(speedLabel(preset)) },
                    )
                }
                FilledTonalIconButton(onClick = { onSetSpeed(clamp(speed + 0.05f)) }, enabled = speed < max) {
                    Icon(Icons.Default.Add, "Faster by 0.05")
                }
            }
        }
    }
}

/** Kept tiny so it can sit next to icons: the blinking dot used next to the ear icon. */
@Composable
fun RecordingActiveIndicator(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, onClickLabel = "Recording in progress, open Recordings"),
        contentAlignment = Alignment.Center,
    ) {
        BlinkingRecDot(10.dp)
    }
}

