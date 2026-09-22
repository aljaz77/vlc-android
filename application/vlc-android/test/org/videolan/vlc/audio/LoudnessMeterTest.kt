/*****************************************************************************
 * LoudnessMeterTest.kt
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Conformance checks for the loudness meter, based on the test signals in
 * EBU Tech 3341.
 *
 * The headline property is that a 1 kHz sine at a given dBFS level must measure
 * the same number in LUFS. That single case exercises the K-weighting filters,
 * the block power calculation and the -0.691 offset together: if any of them is
 * wrong, the number moves.
 */
class LoudnessMeterTest {

    /** EBU Tech 3341 case 1: stereo 1 kHz sine at -23 dBFS reads -23.0 LUFS. */
    @Test
    fun `stereo sine at minus 23 dBFS measures minus 23 LUFS`() {
        val meter = measureSine(levelDb = -23.0, channels = 2)
        assertEquals(-23.0, meter.integratedLoudness, TOLERANCE_LU)
    }

    /** EBU Tech 3341 case 2: the same signal 10 dB louder reads 10 LU louder. */
    @Test
    fun `stereo sine at minus 33 dBFS measures minus 33 LUFS`() {
        val meter = measureSine(levelDb = -33.0, channels = 2)
        assertEquals(-33.0, meter.integratedLoudness, TOLERANCE_LU)
    }

    /**
     * A mono source carries half the power of the same signal in stereo, so it
     * must measure 3 dB quieter. This is what catches a channel summing mistake.
     */
    @Test
    fun `mono sine measures 3 dB below the stereo equivalent`() {
        val stereo = measureSine(levelDb = -23.0, channels = 2).integratedLoudness
        val mono = measureSine(levelDb = -23.0, channels = 1).integratedLoudness
        assertEquals(3.0, stereo - mono, TOLERANCE_LU)
    }

    /** Measurement must not depend on the sample rate. */
    @Test
    fun `sample rate does not change the measurement`() {
        val at48k = measureSine(levelDb = -23.0, channels = 2, sampleRate = 48000)
        val at44k = measureSine(levelDb = -23.0, channels = 2, sampleRate = 44100)
        assertEquals(
            at48k.integratedLoudness,
            at44k.integratedLoudness,
            TOLERANCE_LU
        )
    }

    /** Sample peak is taken before weighting, so it matches the signal amplitude. */
    @Test
    fun `sample peak reports the signal amplitude`() {
        val meter = measureSine(levelDb = -23.0, channels = 2)
        assertEquals(-23.0, meter.samplePeakDb, 0.1)
    }

    /** Digital silence falls below the absolute gate and yields no measurement. */
    @Test
    fun `silence produces no usable measurement`() {
        val meter = LoudnessMeter(48000, 2)
        val silence = FloatArray(48000 * 2)
        repeat(SECONDS) { meter.addSamples(silence, silence.size) }
        assertFalse(meter.hasEnoughData)
        assertEquals(LoudnessMeter.SILENCE_LUFS, meter.integratedLoudness, 0.001)
    }

    /**
     * The relative gate exists so that quiet passages do not drag the programme
     * loudness down. A loud section followed by an equally long very quiet one
     * should measure close to the loud section alone.
     */
    @Test
    fun `quiet passages are gated out of the integrated measurement`() {
        val meter = LoudnessMeter(48000, 2)
        appendSine(meter, levelDb = -23.0, seconds = SECONDS)
        appendSine(meter, levelDb = -60.0, seconds = SECONDS)
        assertTrue(
            "a -60 dBFS passage should barely move the measurement",
            meter.integratedLoudness > -24.0
        )
    }

    private fun measureSine(
        levelDb: Double,
        channels: Int,
        sampleRate: Int = 48000
    ): LoudnessMeter {
        val meter = LoudnessMeter(sampleRate, channels)
        appendSine(meter, levelDb, SECONDS, channels, sampleRate)
        return meter
    }

    /**
     * Feed [seconds] of 1 kHz sine at the given dBFS level into the meter, one
     * second at a time.
     *
     * [levelDb] is the peak amplitude in dBFS, which is the convention EBU Tech
     * 3341 uses. That a sine at -23 dBFS reads -23 LUFS is not a coincidence:
     * the sine's half power factor, the 3 dB from summing two channels, the
     * +0.69 dB of K-weighting at 1 kHz and the -0.691 offset all cancel out.
     */
    private fun appendSine(
        meter: LoudnessMeter,
        levelDb: Double,
        seconds: Int,
        channels: Int = 2,
        sampleRate: Int = 48000
    ) {
        val amplitude = 10.0.pow(levelDb / 20.0)
        val buffer = FloatArray(sampleRate * channels)
        var phase = 0L
        repeat(seconds) {
            var i = 0
            while (i < buffer.size) {
                val value = (amplitude * sin(2.0 * PI * FREQUENCY_HZ * phase / sampleRate))
                for (channel in 0 until channels) buffer[i + channel] = value.toFloat()
                i += channels
                phase++
            }
            meter.addSamples(buffer, buffer.size)
        }
    }

    companion object {
        private const val FREQUENCY_HZ = 1000.0

        /** Long enough for the gating blocks to settle. */
        private const val SECONDS = 5

        /**
         * EBU Tech 3341 allows ±0.1 LU for a compliant meter. A little more room
         * is left here because the test signal starts abruptly, so the very first
         * blocks include the filters' transient response.
         */
        private const val TOLERANCE_LU = 0.2
    }
}
