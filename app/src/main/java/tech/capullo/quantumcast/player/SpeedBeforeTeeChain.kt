package tech.capullo.quantumcast.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Audio chain that applies playback speed/pitch BEFORE [tee] (the Snapcast FIFO tap).
 *
 * DefaultAudioSink.DefaultAudioProcessorChain always appends its own speed stage after every
 * processor it is given, so with the tee in that list the FIFO would receive 1× audio while the
 * player clock (and the silent local AudioTrack) ran at the chosen speed: Snapcast listeners would
 * hear the wrong speed and the FIFO would over/underrun. Here the order is
 * `[pre…] → speed → tee`, so every listener gets the same, already-sped-up PCM.
 *
 * [getMediaDuration] maps played-out time back to media time through the speed stage, which keeps
 * the player's position (seek bar, resume points) correct at any speed. At 1× the Sonic stage is
 * inactive and the chain is the same as before for live radio.
 */
@UnstableApi
class SpeedBeforeTeeChain(pre: Array<AudioProcessor>, tee: AudioProcessor) : DefaultAudioSink.AudioProcessorChain {
    private val speed = SonicAudioProcessor()
    private val processors: Array<AudioProcessor> = pre + speed + tee

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        speed.setSpeed(playbackParameters.speed)
        speed.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (speed.isActive) speed.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0
}
