package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
    // content() must stay at ONE call site: calling it from two branches made Compose treat the
    // app as new UI whenever recording started/stopped, rebuilding every screen (Now Playing
    // replayed its fade-in). Only the clock is conditional.
    val idle = remember { mutableFloatStateOf(0f) }
    // Keep the clock alive briefly after the last recording stops so the play button can finish
    // morphing back to its icon instead of snapping.
    var running by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            running = true
        } else {
            kotlinx.coroutines.delay(300)
            running = false
        }
    }
    val clock: State<Float> = if (active || running) rememberBlinkClock() else idle
    CompositionLocalProvider(LocalRecBlink provides clock, content = content)
}

/** Runs only while something records, so idle screens don't redraw every frame. */
@Composable
private fun rememberBlinkClock(): State<Float> = rememberInfiniteTransition(label = "recBlink").animateFloat(
    initialValue = 0f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
        keyframes {
            durationMillis = 1400
            // Long enough each way for the play/pause ⇄ dot morph to read as a shape change.
            0f at 0
            0f at 450
            1f at 700
            1f at 1150
            0f at 1400
        },
    ),
    label = "recBlinkPhase",
)

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
 * Play/pause button. While [recordingThis] is true its glyph morphs back and forth between the
 * play triangle / pause bars and a red dot (the station being played is recording): the outline
 * itself changes shape, it doesn't cross-fade. [glyph] says which icon is showing; null when the
 * button shows something that isn't play/pause (lock, buffering ring) - that content never morphs.
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
    glyph: PlayGlyph? = null,
    glyphSize: Dp = 24.dp,
    glyphColor: Color = LocalContentColor.current,
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
        // 1 while this station records, 0 otherwise; animated so stop/start morph instead of snap.
        val weight = animateFloatAsState(if (recordingThis) 1f else 0f, tween(250), label = "recWeight")
        if (glyph == null || (!recordingThis && weight.value == 0f)) {
            // At weight 0 the morph glyph matches the icon exactly, so this swap is invisible.
            content()
        } else {
            MorphingGlyph(glyph, blink, weight, glyphColor, Modifier.size(glyphSize))
        }
    }
}

enum class PlayGlyph { PLAY, PAUSE }

/**
 * Draws [glyph] morphed toward the record dot by the shared clock's value (0 = icon, 1 = dot).
 *
 * Each shape is two convex pieces (left/right), matching the Material icons' geometry exactly so
 * the start frame is pixel-identical to the regular icon. Every piece is resampled as N points along
 * rays from its own centre; interpolating those point lists point-by-point gives a real outline
 * morph with no twisting (convex pieces have exactly one boundary point per ray).
 */
@Composable
private fun MorphingGlyph(glyph: PlayGlyph, blink: State<Float>, weight: State<Float>, color: Color, modifier: Modifier) {
    val path = remember { androidx.compose.ui.graphics.Path() }
    androidx.compose.foundation.Canvas(modifier) {
        // weight eases the whole effect in/out when recording starts/stops, so the glyph never
        // jumps between icon and dot mid-blink.
        val raw = blink.value * weight.value
        val t = raw * raw * (3 - 2 * raw) // smoothstep: eases in and out of each end
        val from = if (glyph == PlayGlyph.PLAY) GlyphShapes.play else GlyphShapes.pause
        val k = size.minDimension / 24f
        path.reset()
        for (piece in 0..1) {
            val a = from[piece]
            val b = GlyphShapes.dot[piece]
            for (i in 0 until GlyphShapes.N) {
                val x = (a[2 * i] + (b[2 * i] - a[2 * i]) * t) * k
                val y = (a[2 * i + 1] + (b[2 * i + 1] - a[2 * i + 1]) * t) * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        drawPath(path, androidx.compose.ui.graphics.lerp(color, RecRed, t))
    }
}

/** Outline samples for the morph, in Material's 24×24 icon space. */
private object GlyphShapes {
    const val N = 64

    // Material PlayArrow: M8,5 v14 l11,-7 z, split at x=13.5 into a quad and a triangle.
    // Material Pause: bars x 6..10 and 14..18, y 5..19.
    // Dot: r=4.2 at (12,12), split into left/right halves so each half meets one piece.
    val play = listOf(
        sample(listOf(8f to 5f, 13.5f to 8.5f, 13.5f to 15.5f, 8f to 19f)),
        sample(listOf(13.5f to 8.5f, 19f to 12f, 13.5f to 15.5f)),
    )
    val pause = listOf(
        sample(listOf(6f to 5f, 10f to 5f, 10f to 19f, 6f to 19f)),
        sample(listOf(14f to 5f, 18f to 5f, 18f to 19f, 14f to 19f)),
    )
    val dot = listOf(halfDisc(left = true), halfDisc(left = false))

    private fun halfDisc(left: Boolean): FloatArray {
        val r = 4.2f
        val pts = (0..32).map { i ->
            // left: arc from top (−90°) through 180° to bottom (90°); right: top → 0° → bottom.
            val deg = if (left) -90f - 180f * i / 32 else -90f + 180f * i / 32
            val rad = Math.toRadians(deg.toDouble())
            (12f + r * kotlin.math.cos(rad).toFloat()) to (12f + r * kotlin.math.sin(rad).toFloat())
        }
        return sample(pts)
    }

    /** N boundary points of a convex polygon, on rays from its centroid at even angles from straight up, clockwise. */
    private fun sample(poly: List<Pair<Float, Float>>): FloatArray {
        val cx = poly.sumOf { it.first.toDouble() } / poly.size
        val cy = poly.sumOf { it.second.toDouble() } / poly.size
        val out = FloatArray(2 * N)
        for (i in 0 until N) {
            val ang = -Math.PI / 2 + 2 * Math.PI * i / N
            val dx = kotlin.math.cos(ang)
            val dy = kotlin.math.sin(ang)
            var best = Double.MAX_VALUE
            for (e in poly.indices) {
                val (x1, y1) = poly[e]
                val (x2, y2) = poly[(e + 1) % poly.size]
                val ex = (x2 - x1).toDouble()
                val ey = (y2 - y1).toDouble()
                val den = dx * ey - dy * ex
                if (kotlin.math.abs(den) < 1e-9) continue
                val wx = x1 - cx
                val wy = y1 - cy
                val s = (wx * ey - wy * ex) / den // distance along the ray
                val u = (wx * dy - wy * dx) / den // position along the edge
                if (s > 0 && u >= -1e-6 && u <= 1 + 1e-6 && s < best) best = s
            }
            out[2 * i] = (cx + dx * best).toFloat()
            out[2 * i + 1] = (cy + dy * best).toFloat()
        }
        return out
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
    val label = if (seconds < 60) {
        "$seconds"
    } else if (seconds % 60 == 0) {
        "${seconds / 60}m"
    } else {
        "${seconds / 60}:${"%02d".format(seconds % 60)}"
    }
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
