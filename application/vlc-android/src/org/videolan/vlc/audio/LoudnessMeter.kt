/*****************************************************************************
 * LoudnessMeter.kt
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

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.tan

/**
 * Integrated loudness measurement following ITU-R BS.1770-4 with the gating
 * defined by EBU R128. The result is directly comparable to a ReplayGain 2.0
 * value, which is what makes it usable as a normalization reference.
 *
 * The measurement runs in three stages:
 *  1. K-weighting, a pair of biquads that approximate how loud the ear
 *     perceives a signal to be rather than how much energy it carries.
 *  2. Mean square power over overlapping 400 ms blocks.
 *  3. Two-pass gating: drop near-silent blocks outright, then drop everything
 *     more than 10 LU below the average of what remains, so fades and quiet
 *     passages do not drag the measurement down.
 *
 * Feed interleaved samples with [addSamples] and read the answer from
 * [integratedLoudness]. Not thread safe: one instance per track.
 */
class LoudnessMeter(private val sampleRate: Int, private val channelCount: Int) {

    private val preFilter = Array(channelCount) { Biquad(highShelfCoefficients(sampleRate)) }
    private val highPass = Array(channelCount) { Biquad(highPassCoefficients(sampleRate)) }

    /** Samples per gating block (400 ms) and per step between blocks (100 ms). */
    private val blockSize = (sampleRate * BLOCK_DURATION_S).toInt().coerceAtLeast(1)
    private val stepSize = (sampleRate * STEP_DURATION_S).toInt().coerceAtLeast(1)

    /**
     * Ring buffer of per-channel squared, weighted samples covering one block.
     * Indexed as [channel * blockSize + position].
     */
    private val window = DoubleArray(channelCount * blockSize)
    private var windowPosition = 0

    /**
     * Running sum of the window per channel, so a block costs O(1) instead of
     * re-adding 400 ms of samples every 100 ms.
     */
    private val windowSums = DoubleArray(channelCount)
    private var samplesSeen = 0L
    private var samplesSinceLastBlock = 0

    /** Mean square power of every block that cleared the absolute gate. */
    private val blockPowers = ArrayList<Double>(BLOCK_LIST_INITIAL_CAPACITY)

    /** Largest absolute sample value seen, before weighting. */
    private var peak = 0.0

    /**
     * Add interleaved samples in the range -1..1.
     *
     * @param samples interleaved buffer
     * @param count number of valid entries in [samples], a whole number of frames
     */
    fun addSamples(samples: FloatArray, count: Int) {
        var index = 0
        while (index + channelCount <= count) {
            for (channel in 0 until channelCount) {
                val raw = samples[index + channel].toDouble()
                val magnitude = abs(raw)
                if (magnitude > peak) peak = magnitude
                val weighted = highPass[channel].process(preFilter[channel].process(raw))
                val square = weighted * weighted
                val slot = channel * blockSize + windowPosition
                windowSums[channel] += square - window[slot]
                window[slot] = square
            }
            index += channelCount
            windowPosition = (windowPosition + 1) % blockSize
            samplesSeen++
            samplesSinceLastBlock++

            // A block is only meaningful once the window has been filled once.
            if (samplesSeen >= blockSize && samplesSinceLastBlock >= stepSize) {
                samplesSinceLastBlock = 0
                recordBlock()
            }
        }
    }

    /**
     * Mean square power of the window, summed across channels with the BS.1770
     * channel weights.
     */
    private fun recordBlock() {
        var power = 0.0
        for (channel in 0 until channelCount) {
            power += channelWeight(channel) * (windowSums[channel] / blockSize)
        }
        // Blocks below the absolute gate are silence or near silence and are
        // discarded here rather than carried into the relative gating pass.
        if (loudnessOf(power) >= ABSOLUTE_GATE_LUFS) blockPowers.add(power)
    }

    /**
     * Integrated loudness in LUFS, or [SILENCE_LUFS] when nothing cleared the gate.
     */
    val integratedLoudness: Double
        get() {
            if (blockPowers.isEmpty()) return SILENCE_LUFS

            // Relative gate: everything more than 10 LU below the ungated average
            // is treated as not contributing to the programme loudness.
            val ungatedMean = blockPowers.sum() / blockPowers.size
            val threshold = loudnessOf(ungatedMean) + RELATIVE_GATE_LU

            var sum = 0.0
            var kept = 0
            for (power in blockPowers) {
                if (loudnessOf(power) >= threshold) {
                    sum += power
                    kept++
                }
            }
            if (kept == 0) return SILENCE_LUFS
            // Callers get a finite floor rather than an infinity to reason about.
            return loudnessOf(sum / kept).coerceAtLeast(SILENCE_LUFS)
        }

    /** Highest absolute sample in dBFS, or [SILENT_PEAK_DB] for digital silence. */
    val samplePeakDb: Double
        get() = if (peak <= 0.0) SILENT_PEAK_DB else 20.0 * log10(peak)

    /** True once enough audio has been measured for the result to mean anything. */
    val hasEnoughData: Boolean
        get() = blockPowers.isNotEmpty()

    /**
     * BS.1770 channel weights. Surround channels count for more because they
     * contribute more to perceived loudness than their energy alone suggests.
     * Anything beyond a 5.x layout is weighted as a front channel.
     */
    private fun channelWeight(channel: Int) = when {
        channelCount >= 5 && (channel == 4 || channel == 5) -> SURROUND_WEIGHT
        else -> 1.0
    }

    companion object {
        /** Offset from mean square power to LKFS, fixed by BS.1770. */
        private const val LOUDNESS_OFFSET = -0.691

        private const val BLOCK_DURATION_S = 0.4
        private const val STEP_DURATION_S = 0.1

        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = -10.0

        private const val SURROUND_WEIGHT = 1.41

        /** Reported when a track is silent or entirely below the absolute gate. */
        const val SILENCE_LUFS = -70.0

        /** Peak reported for digital silence: below anything a real recording reaches. */
        const val SILENT_PEAK_DB = -120.0

        /** Roughly ten minutes of blocks, to avoid repeated array growth. */
        private const val BLOCK_LIST_INITIAL_CAPACITY = 6000

        /**
         * Loudness of a mean square power value.
         *
         * Zero power is negative infinity, not [SILENCE_LUFS]: the absolute gate
         * keeps blocks at or above -70 LUFS, so mapping silence onto exactly -70
         * would let digital silence through the gate it is meant to catch.
         */
        private fun loudnessOf(power: Double) =
            if (power <= 0.0) Double.NEGATIVE_INFINITY
            else LOUDNESS_OFFSET + 10.0 * log10(power)

        /**
         * Stage 1 of K-weighting: a high shelf that models the acoustic effect of
         * a listener's head. Coefficients are defined in BS.1770 for 48 kHz and
         * derived here for the actual rate, so that other sample rates are not
         * measured through a slightly wrong filter.
         */
        private fun highShelfCoefficients(sampleRate: Int): Coefficients {
            val f0 = 1681.974450955533
            val gain = 3.999843853973347
            val q = 0.7071752369554196

            val k = tan(Math.PI * f0 / sampleRate)
            val vh = Math.pow(10.0, gain / 20.0)
            val vb = Math.pow(vh, 0.4996667741545416)
            val denominator = 1.0 + k / q + k * k

            return Coefficients(
                b0 = (vh + vb * k / q + k * k) / denominator,
                b1 = 2.0 * (k * k - vh) / denominator,
                b2 = (vh - vb * k / q + k * k) / denominator,
                a1 = 2.0 * (k * k - 1.0) / denominator,
                a2 = (1.0 - k / q + k * k) / denominator
            )
        }

        /**
         * Stage 2 of K-weighting: a high pass that removes the low frequency
         * energy the ear barely registers as loudness.
         */
        private fun highPassCoefficients(sampleRate: Int): Coefficients {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773

            val k = tan(Math.PI * f0 / sampleRate)
            val denominator = 1.0 + k / q + k * k

            return Coefficients(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / denominator,
                a2 = (1.0 - k / q + k * k) / denominator
            )
        }
    }

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    /** Direct form I biquad, one instance per channel so the state stays separate. */
    private class Biquad(private val c: Coefficients) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Double): Double {
            val output = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }
    }
}
