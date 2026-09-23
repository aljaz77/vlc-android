/*****************************************************************************
 * LoudnessAnalyzer.kt
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

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.vlc.mediadb.models.TrackLoudness
import java.nio.ByteOrder

private const val TAG = "VLC/LoudnessAnalyzer"

/**
 * Decodes a track with the platform codecs and measures its loudness.
 *
 * Decoding runs as fast as the hardware allows rather than in real time, so a
 * typical song is measured in a couple of seconds. Nothing is rendered: the
 * decoded PCM goes straight into a [LoudnessMeter] and is discarded.
 *
 * Not every file VLC can play can be decoded by the platform codecs, so failure
 * is an expected outcome, not an exceptional one. [analyze] returns null and the
 * caller falls back to whichever realtime method is configured.
 */
/** The device had no decoder free. The file itself may be perfectly fine. */
class CodecUnavailableException(cause: Throwable) : Exception(cause)

object LoudnessAnalyzer {

    /**
     * Measure one track.
     *
     * Runs on [Dispatchers.Default] regardless of the caller, because this
     * decodes and filters an entire track and must never touch the main thread.
     * Cancellable through the calling coroutine.
     *
     * @return the measurement, or null if the track could not be decoded
     */
    suspend fun analyze(context: Context, uri: Uri): TrackLoudness? =
        withContext(Dispatchers.Default) { analyzeBlocking(context, uri) }

    private suspend fun analyzeBlocking(context: Context, uri: Uri): TrackLoudness? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) {
                Log.d(TAG, "No audio track in $uri")
                return null
            }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            // Float output avoids a needless round trip through 16 bit integers
            // for sources that decode to float anyway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            val meter = decodeInto(codec, extractor)
                ?: return null

            return TrackLoudness(
                mediaUri = uri.toString(),
                integratedLufs = meter.integratedLoudness,
                samplePeakDb = meter.samplePeakDb,
                analyzedAt = System.currentTimeMillis()
            )
        } catch (e: MediaCodec.CodecException) {
            // Running several analyses at once can exhaust the codec pool. That
            // says nothing about the file, so let the caller retry it later
            // rather than writing the track off as undecodable.
            if (e.isTransient || e.isRecoverable) throw CodecUnavailableException(e)
            Log.d(TAG, "Could not analyze $uri: ${e.message}")
            return null
        } catch (e: IllegalStateException) {
            // createDecoderByType throws this when no decoder can be allocated.
            throw CodecUnavailableException(e)
        } catch (e: Exception) {
            // Unsupported codec, DRM, missing file, malformed container: all of
            // these are ordinary and mean only that this track stays unmeasured.
            Log.d(TAG, "Could not analyze $uri: ${e.message}")
            return null
        } finally {
            try {
                codec?.stop()
            } catch (ignored: Exception) {
            }
            try {
                codec?.release()
            } catch (ignored: Exception) {
            }
            try {
                extractor?.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /**
     * Run the decode loop, feeding every decoded buffer into a meter.
     *
     * @return the meter once the stream is exhausted, or null if the decoder
     * never produced a usable output format or no audio cleared the gate
     */
    private suspend fun decodeInto(codec: MediaCodec, extractor: MediaExtractor): LoudnessMeter? {
        val bufferInfo = MediaCodec.BufferInfo()
        var meter: LoudnessMeter? = null
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var samples = FloatArray(DEFAULT_SAMPLE_BUFFER)
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            currentCoroutineContext().ensureActive()

            if (!inputDone) {
                // Never wait for an input buffer: if none is free the decoder is
                // busy and the output dequeue below is where we should block.
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                    val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
                    // The format can be announced more than once; only the first
                    // one can define the meter, since it carries the filter state.
                    if (meter == null && sampleRate > 0 && channels > 0) {
                        meter = LoudnessMeter(sampleRate, channels)
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && bufferInfo.size > 0 && meter != null) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val needed = sampleCountFor(bufferInfo.size, pcmEncoding)
                            if (samples.size < needed) samples = FloatArray(needed)
                            val count = readSamples(buffer, pcmEncoding, samples)
                            meter.addSamples(samples, count)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }
        return meter?.takeIf { it.hasEnoughData }
    }

    private fun sampleCountFor(byteCount: Int, pcmEncoding: Int) = when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> byteCount / 4
        AudioFormat.ENCODING_PCM_8BIT -> byteCount
        else -> byteCount / 2
    }

    /**
     * Convert one decoded buffer into normalised floats in the range -1..1.
     *
     * @return the number of samples written into [out]
     */
    private fun readSamples(
        buffer: java.nio.ByteBuffer,
        pcmEncoding: Int,
        out: FloatArray
    ): Int {
        buffer.order(ByteOrder.nativeOrder())
        var count = 0
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.hasRemaining() && count < out.size) out[count++] = floats.get()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                // Unsigned, centred on 128.
                while (buffer.hasRemaining() && count < out.size) {
                    out[count++] = ((buffer.get().toInt() and 0xFF) - 128) / 128f
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.hasRemaining() && count < out.size) {
                    out[count++] = shorts.get() / 32768f
                }
            }
        }
        return count
    }

    /** Half a second of stereo audio at 48 kHz, grown on demand. */
    private const val DEFAULT_SAMPLE_BUFFER = 48000

    /**
     * How long to wait for a decoded buffer. Short enough that the loop keeps
     * the decoder fed, long enough not to spin when it has nothing ready.
     */
    private const val TIMEOUT_US = 5_000L
}
