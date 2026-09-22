/*****************************************************************************
 * LoudnessRepository.kt
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
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.AppScope
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.database.TrackLoudnessDao
import org.videolan.vlc.mediadb.models.TrackLoudness
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "VLC/LoudnessRepository"

/**
 * Stores and serves per track loudness measurements.
 *
 * Analysis happens opportunistically rather than as one huge library sweep: when
 * a track is about to play and has never been measured, it is queued. The
 * measurement misses the track it was triggered by but is ready for every play
 * after that, and queueing the *next* track in the queue while the current one
 * plays means most tracks are measured before they are needed. A full library
 * scan is available from settings for anyone who would rather not wait.
 *
 * Requests are processed one at a time. Analysis is CPU and codec heavy, and
 * running several at once would compete with the decoder doing the actual
 * playback.
 */
object LoudnessRepository {

    /** Measurements already looked up, so repeat plays do not hit the database. */
    private val cache = ConcurrentHashMap<String, TrackLoudness>()

    /**
     * URIs the platform codecs could not decode. Kept for the process lifetime so
     * we do not retry an unsupported format on every single play.
     */
    private val undecodable: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** URIs currently queued or being measured. */
    private val pending: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val requests = Channel<Request>(Channel.UNLIMITED)

    private val _analysisProgress = MutableStateFlow<AnalysisProgress?>(null)

    /** Progress of a running full library scan, or null when none is running. */
    val analysisProgress: StateFlow<AnalysisProgress?> = _analysisProgress

    data class AnalysisProgress(val done: Int, val total: Int, val currentTitle: String?)

    private data class Request(val context: Context, val uri: Uri)

    init {
        startWorker()
    }

    /**
     * The stored measurement for a track, or null if it has never been measured.
     */
    suspend fun get(context: Context, uri: Uri): TrackLoudness? {
        val key = uri.toString()
        cache[key]?.let { return it }
        val stored = withContext(Dispatchers.IO) {
            try {
                dao(context).get(key, TrackLoudness.CURRENT_ANALYZER_VERSION)
            } catch (e: Exception) {
                Log.w(TAG, "Loudness lookup failed for $key", e)
                null
            }
        }
        if (stored != null) cache[key] = stored
        return stored
    }

    /**
     * Queue a track for measurement if it has not been measured already.
     *
     * Returns immediately; the measurement lands in the database and the cache
     * some time later. Safe to call repeatedly for the same track.
     */
    fun requestAnalysis(context: Context, uri: Uri) {
        val key = uri.toString()
        if (cache.containsKey(key) || undecodable.contains(key) || !pending.add(key)) return
        val applicationContext = context.applicationContext
        AppScope.launch {
            // Check the database before spending a decode on it.
            if (get(applicationContext, uri) != null) {
                pending.remove(key)
                return@launch
            }
            requests.trySend(Request(applicationContext, uri))
        }
    }

    /**
     * Measure every audio track in [uris] that has not been measured yet,
     * reporting progress through [analysisProgress].
     *
     * Runs on the caller's coroutine so it can be cancelled by cancelling that
     * job. Only one scan is useful at a time.
     */
    suspend fun analyzeAll(context: Context, uris: List<Pair<Uri, String?>>) {
        val applicationContext = context.applicationContext
        val outstanding = uris.filterNot { undecodable.contains(it.first.toString()) }
        var done = 0
        try {
            for ((uri, title) in outstanding) {
                _analysisProgress.value = AnalysisProgress(done, outstanding.size, title)
                if (get(applicationContext, uri) == null) {
                    measure(applicationContext, uri)
                }
                done++
            }
            _analysisProgress.value = AnalysisProgress(done, outstanding.size, null)
        } finally {
            _analysisProgress.value = null
        }
    }

    /** How many tracks have a stored measurement. */
    suspend fun analyzedCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            dao(context).count(TrackLoudness.CURRENT_ANALYZER_VERSION)
        } catch (e: Exception) {
            Log.w(TAG, "Could not count measurements", e)
            0
        }
    }

    /** Discard every measurement, for instance after changing audio hardware. */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                dao(context).clear()
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear measurements", e)
            }
        }
        cache.clear()
        undecodable.clear()
    }

    /**
     * Single consumer so that only one track is decoded at a time, leaving the
     * codecs and the CPU free for playback.
     */
    private fun startWorker() {
        AppScope.launch(Dispatchers.Default) {
            for (request in requests) {
                try {
                    measure(request.context, request.uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Analysis failed for ${request.uri}", e)
                } finally {
                    pending.remove(request.uri.toString())
                }
            }
        }
    }

    private suspend fun measure(context: Context, uri: Uri) {
        val key = uri.toString()
        val result = LoudnessAnalyzer.analyze(context, uri)
        if (result == null) {
            undecodable.add(key)
            return
        }
        cache[key] = result
        withContext(Dispatchers.IO) {
            try {
                dao(context).insert(result)
            } catch (e: Exception) {
                Log.w(TAG, "Could not store measurement for $key", e)
            }
        }
    }

    private fun dao(context: Context): TrackLoudnessDao =
        MediaDatabase.getInstance(context).trackLoudnessDao()

    /** Convenience for callers that only have a string location. */
    fun requestAnalysis(context: Context, location: String?) {
        if (location.isNullOrEmpty()) return
        requestAnalysis(context, location.toUri())
    }
}
