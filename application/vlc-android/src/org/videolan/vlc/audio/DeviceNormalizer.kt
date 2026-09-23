/*****************************************************************************
 * DeviceNormalizer.kt
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

package org.videolan.vlc.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlin.math.abs
import org.videolan.resources.VLCOptions
import org.videolan.resources.normalization.NormalizationConfig
import org.videolan.resources.normalization.NormalizationMethod

private const val TAG = "VLC/DeviceNormalizer"

/**
 * Volume normalization applied through Android's own audio effect framework,
 * attached to the AudioTrack session libVLC plays into
 * ([VLCOptions.audiotrackSessionId]).
 *
 * This is the half of normalization that can change while a track is playing.
 * The libVLC filters in [NormalizationConfig.libVlcOptions] only take effect when
 * the libVLC instance is created, so anything that has to respond immediately —
 * a per track gain, or the toggle exposed in the car — goes through here instead.
 *
 * Two implementations, picked by API level:
 *  - [DynamicsProcessing] (API 28+) gives us signed input gain plus a real
 *    limiter, so we can both boost and attenuate and still protect against
 *    clipping.
 *  - [LoudnessEnhancer] is the fallback. It can only boost, so negative gains
 *    are dropped rather than applied.
 *
 * Every effect call is guarded: audio effects are optional on Android, and
 * several vendors ship implementations that throw on construction or on
 * individual setters. A failure here downgrades normalization, it never takes
 * playback down with it.
 */
class DeviceNormalizer {

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var config: NormalizationConfig? = null

    /** Per track gain from measured loudness, in dB. Null when we have no measurement. */
    private var trackGainDb: Double? = null

    /** True peak of the current track in dBFS, used to hold back a boost that would clip. */
    private var trackTruePeakDb: Double? = null

    /** Set once an effect has failed, so we stop retrying on every track change. */
    private var unavailable = false

    val isActive: Boolean
        get() = dynamicsProcessing != null || loudnessEnhancer != null

    /**
     * Apply a new configuration. Attaches, detaches or retunes the effect as needed.
     */
    @MainThread
    fun setConfig(config: NormalizationConfig) {
        this.config = config
        if (!config.enabled || !config.method.usesDeviceEffect) {
            release()
            return
        }
        applyGain()
    }

    /**
     * Supply the measured loudness of the track about to play, so the exact gain
     * needed to hit the target can be applied.
     *
     * @param integratedLufs measured integrated loudness, or null if unknown
     * @param truePeakDb measured true peak in dBFS, or null if unknown
     */
    @MainThread
    fun setTrackLoudness(integratedLufs: Double?, truePeakDb: Double?) {
        val config = config ?: return
        trackTruePeakDb = truePeakDb
        trackGainDb = if (integratedLufs != null && config.method.usesMeasuredLoudness)
            config.targetLufs - integratedLufs
        else null
        if (isActive) applyGain()
    }

    /**
     * Detach from the audio session and free the effects.
     */
    @MainThread
    fun release() {
        dynamicsProcessing?.let { effect ->
            try {
                effect.enabled = false
                effect.release()
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing teardown failed", e)
            }
        }
        loudnessEnhancer?.let { effect ->
            try {
                effect.enabled = false
                effect.release()
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer teardown failed", e)
            }
        }
        dynamicsProcessing = null
        loudnessEnhancer = null
    }

    /**
     * Create the effect if it is not attached yet.
     *
     * @return true when an effect is attached and usable
     */
    private fun attach(): Boolean {
        if (isActive) return true
        if (unavailable) return false
        val sessionId = VLCOptions.audiotrackSessionId
        if (sessionId == 0) {
            // libVLC has not created its AudioTrack yet. Not an error; we will be
            // called again when the next track starts.
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) attachDynamicsProcessing(sessionId)
        else attachLoudnessEnhancer(sessionId)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun attachDynamicsProcessing(sessionId: Int): Boolean {
        try {
            // Input gain plus a limiter, and nothing else. An earlier version
            // also built a one band multiband compressor and used the FFT
            // variant; that combination silenced playback outright on at least
            // one device, and none of it was needed to apply a per track gain.
            val effectConfig = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                CHANNEL_COUNT,
                /* preEqInUse = */ false, /* preEqBandCount = */ 0,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ true
            ).build()
            dynamicsProcessing = DynamicsProcessing(EFFECT_PRIORITY, sessionId, effectConfig)
                .apply { enabled = true }
            Log.i(TAG, "DynamicsProcessing attached to audio session $sessionId")
            return true
        } catch (e: Exception) {
            // UnsupportedOperationException, RuntimeException and IllegalArgumentException
            // are all reachable here depending on the vendor implementation.
            Log.w(TAG, "DynamicsProcessing unavailable, falling back to LoudnessEnhancer", e)
            dynamicsProcessing = null
            return attachLoudnessEnhancer(sessionId)
        }
    }

    private fun attachLoudnessEnhancer(sessionId: Int): Boolean {
        return try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
            Log.i(TAG, "LoudnessEnhancer attached to audio session $sessionId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "No usable audio effect on this device; " +
                    "normalization will rely on the libVLC filters only", e)
            loudnessEnhancer = null
            unavailable = true
            false
        }
    }

    /**
     * Push the current gain and limiter settings into whichever effect is attached.
     */
    private fun applyGain() {
        val config = config ?: return
        val gain = effectiveGainDb(config)
        if (!isActive) {
            // Nothing to correct on this track, so leave the audio path alone
            // rather than attaching an effect to apply 0 dB.
            if (abs(gain) < MIN_MEANINGFUL_GAIN_DB) return
            if (!attach()) return
        }
        Log.i(
            TAG,
            "normalization gain %.1f dB (method=%s target=%.1f measured=%s peak=%s)".format(
                gain, config.method.key, config.targetLufs,
                trackGainDb?.let { "%.1f".format(config.targetLufs - it) } ?: "none",
                trackTruePeakDb?.let { "%.1f".format(it) } ?: "none"
            )
        )
        dynamicsProcessing?.let { applyToDynamicsProcessing(it, config, gain) }
        loudnessEnhancer?.let { applyToLoudnessEnhancer(it, gain) }
    }

    /**
     * The gain to apply right now, in dB.
     *
     * Prefers a measured per track gain. Without one, a device-only method still
     * has to get from the reference loudness to the target somehow, so it falls
     * back to the target offset. Both are capped by the user's boost ceiling and
     * by the track's true peak where we know it.
     */
    private fun effectiveGainDb(config: NormalizationConfig): Double {
        val raw = trackGainDb ?: when (config.method) {
            // AUTO and MEASURED leave untagged, unmeasured tracks to the libVLC
            // compressor rather than guessing at a gain here.
            NormalizationMethod.DEVICE -> config.targetOffsetDb
            else -> 0.0
        }
        var gain = raw.coerceAtMost(config.maxBoostDb)
        if (config.peakLimiter) {
            // Never push the loudest sample past the ceiling. Without a limiter
            // stage this is the only thing standing between a boost and clipping.
            trackTruePeakDb?.let { peak -> gain = gain.coerceAtMost(PEAK_CEILING_DB - peak) }
        }
        return gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyToDynamicsProcessing(
        effect: DynamicsProcessing,
        config: NormalizationConfig,
        gainDb: Double
    ) {
        try {
            effect.setInputGainAllChannelsTo(gainDb.toFloat())
            val limiter = effect.getLimiterByChannelIndex(0).apply {
                isEnabled = config.peakLimiter
                threshold = PEAK_CEILING_DB.toFloat()
                ratio = LIMITER_RATIO
                attackTime = LIMITER_ATTACK_MS
                releaseTime = LIMITER_RELEASE_MS
                postGain = 0f
            }
            effect.setLimiterAllChannelsTo(limiter)
        } catch (e: Exception) {
            // Never leave a half configured effect sitting on the audio session:
            // a broken DynamicsProcessing can silence playback entirely. Drop it
            // and let the libVLC side of normalization carry on alone.
            Log.w(TAG, "Failed to configure DynamicsProcessing; detaching it", e)
            release()
            unavailable = true
        }
    }


    private fun applyToLoudnessEnhancer(effect: LoudnessEnhancer, gainDb: Double) {
        try {
            // LoudnessEnhancer only boosts. An attenuation request is dropped
            // rather than applied, which is why DynamicsProcessing is preferred.
            effect.setTargetGain((gainDb.coerceAtLeast(0.0) * MILLIBELS_PER_DB).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure LoudnessEnhancer", e)
        }
    }

    companion object {
        /**
         * Above zero so we sit ahead of an external equalizer that attached to the
         * same session, but well below the values a system effect would claim.
         */
        private const val EFFECT_PRIORITY = 100

        /**
         * DynamicsProcessing needs a channel count up front. Stereo covers music
         * playback, which is what normalization is for.
         */
        private const val CHANNEL_COUNT = 2

        /** Leave a little headroom below full scale. */
        private const val PEAK_CEILING_DB = -1.0

        private const val LIMITER_RATIO = 20f
        private const val LIMITER_ATTACK_MS = 1f
        private const val LIMITER_RELEASE_MS = 60f


        private const val MILLIBELS_PER_DB = 100.0

        /** Below this the correction is inaudible and not worth an audio effect. */
        private const val MIN_MEANINGFUL_GAIN_DB = 0.2

        /** Hard bounds on the applied gain, whatever the measurement says. */
        private const val MIN_GAIN_DB = -24.0
        private const val MAX_GAIN_DB = 24.0
    }
}
