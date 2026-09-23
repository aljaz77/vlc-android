/*****************************************************************************
 * NormalizationConfig.kt
 *
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */

package org.videolan.resources.normalization

import android.content.SharedPreferences
import org.videolan.tools.KEY_NORMALIZATION_ALBUM_MODE
import org.videolan.tools.KEY_NORMALIZATION_ANALYSIS_ENABLED
import org.videolan.tools.KEY_NORMALIZATION_APPLY_TO_VIDEO
import org.videolan.tools.KEY_NORMALIZATION_CUSTOM_TARGET
import org.videolan.tools.KEY_NORMALIZATION_ENABLED
import org.videolan.tools.KEY_NORMALIZATION_MAX_BOOST
import org.videolan.tools.KEY_NORMALIZATION_METHOD
import org.videolan.tools.KEY_NORMALIZATION_PEAK_LIMITER
import org.videolan.tools.KEY_NORMALIZATION_STRENGTH
import org.videolan.tools.KEY_NORMALIZATION_TARGET
import java.util.Locale
import kotlin.math.pow

/**
 * How loud "normal" should be, expressed as an integrated loudness target in LUFS.
 *
 * LUFS values are negative and measured against digital full scale, so a larger
 * number means louder: -11 LUFS is considerably louder than -23 LUFS.
 */
enum class LoudnessTarget(val key: String, val lufs: Double) {
    /** EBU R128 broadcast reference. Quiet, wide dynamic range. */
    QUIET("quiet", -23.0),

    /** The reference ReplayGain itself is built around. */
    STANDARD("standard", -18.0),

    /** Roughly what Spotify, YouTube and Apple Music normalise to. */
    STREAMING("streaming", -14.0),

    /** Loud and dense, to stay audible over road noise. */
    CAR("car", -11.0),

    /** Whatever the user typed. */
    CUSTOM("custom", Double.NaN);

    companion object {
        const val MIN_LUFS = -30.0
        const val MAX_LUFS = -5.0
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: STREAMING
    }
}

/**
 * How the target is reached.
 *
 * [needsLibVlcRestart] matters for the UI: libVLC only reads its audio filter
 * configuration when the instance is created, so changing one of those methods
 * interrupts whatever is playing. The device effect methods attach to the
 * AudioTrack session instead and can be changed while a track plays.
 */
enum class NormalizationMethod(val key: String, val needsLibVlcRestart: Boolean) {
    /**
     * Measured loudness where we have it, with a gentle compressor underneath to
     * catch whatever has not been measured yet.
     *
     * Deliberately does not also run libVLC's ReplayGain stage. Both compute the
     * same correction, target minus track loudness, so running them together
     * applied it twice and overshot by roughly a factor of two on any track that
     * had both tags and a measurement. Our own measurement covers every track
     * once analysed rather than only tagged ones, so it wins; anyone who would
     * rather rely on tags alone can select [REPLAYGAIN] explicitly.
     */
    AUTO("auto", true),

    /** ReplayGain tags only. Exact, but silent on files that carry no tags. */
    REPLAYGAIN("replaygain", true),

    /** Per track gain from our own loudness analysis. Applied per track, live. */
    MEASURED("measured", false),

    /** libVLC's compressor: squeezes dynamic range so everything sits closer together. */
    COMPRESSOR("compressor", true),

    /** libVLC's normvol: a running average that pulls down anything too loud. */
    LEVELER("leveler", true),

    /** Android's own DynamicsProcessing / LoudnessEnhancer on VLC's audio session. */
    DEVICE("device", false);

    /**
     * Whether this method wants libVLC's ReplayGain stage switched on.
     *
     * Only [REPLAYGAIN]. See the note on [AUTO] for why it must not be combined
     * with the measured loudness stage.
     */
    val usesReplayGain: Boolean
        get() = this == REPLAYGAIN

    /** Whether this method wants a per track gain from the loudness database. */
    val usesMeasuredLoudness: Boolean
        get() = this == AUTO || this == MEASURED

    /** Whether this method is applied through an Android audio effect rather than libVLC. */
    val usesDeviceEffect: Boolean
        get() = this == AUTO || this == MEASURED || this == DEVICE

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * A snapshot of the user's volume normalization preferences, plus the derivation
 * of the libVLC command line options that implement them.
 *
 * Read it with [from]. It is a value object, so it is safe to hand around and to
 * compare against a previous snapshot to decide whether libVLC needs restarting.
 */
data class NormalizationConfig(
    val enabled: Boolean,
    val method: NormalizationMethod,
    val target: LoudnessTarget,
    val customTargetLufs: Double,
    /** 0..100. Higher means more aggressive levelling and more makeup gain. */
    val strength: Int,
    val peakLimiter: Boolean,
    /** Ceiling on positive gain, in dB, so quiet tracks do not get blown up. */
    val maxBoostDb: Double,
    val applyToVideo: Boolean,
    /** Use album rather than track ReplayGain, preserving relative loudness within an album. */
    val albumMode: Boolean,
    /** Measure tracks in the background as they are played. */
    val analyzeWhilePlaying: Boolean
) {

    /** The effective target, resolving [LoudnessTarget.CUSTOM]. */
    val targetLufs: Double
        get() = if (target == LoudnessTarget.CUSTOM)
            customTargetLufs.coerceIn(LoudnessTarget.MIN_LUFS, LoudnessTarget.MAX_LUFS)
        else target.lufs

    /**
     * Offset between our target and the -18 LUFS reference that ReplayGain and
     * most loudness metadata are written against.
     */
    val targetOffsetDb: Double
        get() = targetLufs - REFERENCE_LUFS

    /**
     * libVLC options implementing this configuration.
     *
     * Empty when normalization is off, or when the selected method is applied
     * through an Android audio effect instead of through libVLC.
     */
    fun libVlcOptions(): List<String> {
        if (!enabled) return emptyList()
        val options = ArrayList<String>(12)

        if (method.usesReplayGain) {
            options.add("--audio-replay-gain-mode=" + if (albumMode) "album" else "track")
            options.add("--audio-replay-gain-preamp=" + format(targetOffsetDb))
            // Gain applied to streams that carry no ReplayGain information at all.
            // Untagged material is usually mastered loud, so pull it down slightly.
            options.add("--audio-replay-gain-default=" + format(UNTAGGED_DEFAULT_GAIN_DB))
            options.add(
                if (peakLimiter) "--audio-replay-gain-peak-protection"
                else "--no-audio-replay-gain-peak-protection"
            )
        }

        when (method) {
            NormalizationMethod.AUTO, NormalizationMethod.COMPRESSOR -> {
                // AUTO runs the compressor gently underneath ReplayGain, so tracks
                // with no tags and no measurement still get evened out.
                val effectiveStrength =
                    if (method == NormalizationMethod.AUTO) strength / 2 else strength
                options.add("--audio-filter=compressor")
                options.addAll(compressorOptions(effectiveStrength))
            }
            NormalizationMethod.LEVELER -> {
                options.add("--audio-filter=normvol")
                options.addAll(levelerOptions())
            }
            else -> Unit
        }
        return options
    }

    /**
     * Parameters for libVLC's compressor filter.
     *
     * The mapping from an LUFS target to a dBFS threshold is a heuristic, not an
     * equivalence: loudness and sample level are different measurements. It holds
     * up well enough in practice because music at a given integrated loudness has
     * fairly predictable short term peaks above it.
     */
    private fun compressorOptions(effectiveStrength: Int): List<String> {
        val s = effectiveStrength.coerceIn(0, 100) / 100.0
        val threshold = (targetLufs + THRESHOLD_ABOVE_TARGET_DB).coerceIn(-30.0, 0.0)
        val ratio = (2.0 + s * 8.0).coerceIn(1.0, 20.0)
        // Only restore roughly what the compressor itself took away. Makeup used
        // to include the distance from the reference to the target as well, which
        // meant every track came out several dB louder than its source regardless
        // of how loud it already was: a compressor cannot know a track's loudness,
        // so it has no business chasing a target. The threshold above tracks the
        // target; this just stops the compression sounding lifeless.
        val makeup = (s * 4.0).coerceIn(0.0, MAX_MAKEUP_GAIN_DB)
        val attack = (50.0 - s * 40.0).coerceIn(1.5, 400.0)
        val release = (500.0 - s * 300.0).coerceIn(2.0, 800.0)
        val knee = (10.0 - s * 7.0).coerceIn(1.0, 10.0)
        return listOf(
            "--compressor-rms-peak=" + format(RMS_PEAK),
            "--compressor-threshold=" + format(threshold),
            "--compressor-ratio=" + format(ratio),
            "--compressor-makeup-gain=" + format(makeup),
            "--compressor-attack=" + format(attack),
            "--compressor-release=" + format(release),
            "--compressor-knee=" + format(knee)
        )
    }

    /**
     * Parameters for libVLC's normvol filter, which only ever attenuates: it
     * measures average power over a window of buffers and pulls the volume down
     * when that average exceeds the maximum level.
     */
    private fun levelerOptions(): List<String> {
        val maxLevel = 10.0.pow((targetLufs + NORMVOL_LEVEL_OFFSET_DB) / 20.0).coerceIn(0.5, 5.0)
        // A shorter window reacts faster and levels harder.
        val buffSize = (40.0 - strength.coerceIn(0, 100) * 0.3).coerceIn(10.0, 40.0)
        return listOf(
            "--norm-buff-size=" + buffSize.toInt(),
            "--norm-max-level=" + format(maxLevel)
        )
    }

    companion object {
        /** Reference loudness for ReplayGain 2.0 and for most loudness metadata. */
        const val REFERENCE_LUFS = -18.0

        /** Applied to streams carrying no ReplayGain information. */
        private const val UNTAGGED_DEFAULT_GAIN_DB = -7.0

        /** How far above the loudness target the compressor starts working. */
        private const val THRESHOLD_ABOVE_TARGET_DB = 3.0

        /** Well below the filter's own 24 dB ceiling, to stay clear of distortion. */
        private const val MAX_MAKEUP_GAIN_DB = 6.0

        /** 0 is pure RMS, 1 is pure peak. A little peak sensitivity sounds more natural. */
        private const val RMS_PEAK = 0.2

        /** Lines normvol's linear level up with our LUFS scale. */
        private const val NORMVOL_LEVEL_OFFSET_DB = 20.0

        const val DEFAULT_STRENGTH = 50
        const val DEFAULT_MAX_BOOST_DB = 12.0

        /**
         * libVLC parses option values with the C locale, so numbers must be
         * formatted with a dot regardless of the device's locale.
         */
        private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)

        fun from(prefs: SharedPreferences) = NormalizationConfig(
            enabled = prefs.getBoolean(KEY_NORMALIZATION_ENABLED, false),
            method = NormalizationMethod.fromKey(prefs.getString(KEY_NORMALIZATION_METHOD, null)),
            target = LoudnessTarget.fromKey(prefs.getString(KEY_NORMALIZATION_TARGET, null)),
            customTargetLufs = prefs.getString(KEY_NORMALIZATION_CUSTOM_TARGET, null)
                ?.toDoubleOrNull() ?: LoudnessTarget.STREAMING.lufs,
            strength = prefs.getInt(KEY_NORMALIZATION_STRENGTH, DEFAULT_STRENGTH),
            peakLimiter = prefs.getBoolean(KEY_NORMALIZATION_PEAK_LIMITER, true),
            maxBoostDb = prefs.getString(KEY_NORMALIZATION_MAX_BOOST, null)
                ?.toDoubleOrNull() ?: DEFAULT_MAX_BOOST_DB,
            applyToVideo = prefs.getBoolean(KEY_NORMALIZATION_APPLY_TO_VIDEO, false),
            albumMode = prefs.getBoolean(KEY_NORMALIZATION_ALBUM_MODE, false),
            analyzeWhilePlaying = prefs.getBoolean(KEY_NORMALIZATION_ANALYSIS_ENABLED, true)
        )
    }
}
