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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.normalization.NormalizationConfig
import org.videolan.resources.AppContextProvider
import org.videolan.tools.AppScope
import org.videolan.tools.KEY_NORMALIZATION_ANALYSIS_ENABLED
import org.videolan.tools.Settings
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.database.TrackLoudnessDao
import org.videolan.vlc.mediadb.models.TrackLoudness
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "VLC/LoudnessRepository"

/**
 * Stores and serves per track loudness measurements.
 *
 * Measurements arrive three ways. Tracks are queued as they play, so a library
 * converges on being fully measured as it is listened to. New tracks are picked
 * up automatically when the medialibrary reports additions. And the settings
 * screen can sweep the whole library in one go.
 *
 * The two background paths deliberately decode one track at a time so they stay
 * out of the way of playback. The user driven sweep runs several in parallel,
 * since nothing else is competing for the decoders while it runs.
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

    /** Serialises database writes; see [measure]. */
    private val writeLock = Mutex()

    private val _analysisProgress = MutableStateFlow<AnalysisProgress?>(null)

    /** Progress of a running full library sweep, or null when none is running. */
    val analysisProgress: StateFlow<AnalysisProgress?> = _analysisProgress

    data class AnalysisProgress(val done: Int, val total: Int, val currentTitle: String?)

    private data class Request(val context: Context, val uri: Uri)

    private var watchingLibrary = false
    private var sweepJob: Job? = null

    /**
     * Whether a full library sweep is running right now.
     *
     * Answered by the service that actually does the work. Deriving it from the
     * progress flow meant that if the sweep ended without clearing progress, the
     * button stayed stuck on "stop" and there was no way to start another one.
     */
    val isSweeping: Boolean
        get() = LoudnessAnalysisService.isRunning

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
        if (cache.containsKey(key) || undecodable.contains(key) || pending.contains(key)) return
        val applicationContext = context.applicationContext
        AppScope.launch {
            // Check the database before spending a decode on it.
            if (get(applicationContext, uri) != null) return@launch
            enqueue(applicationContext, uri)
        }
    }

    /** Convenience for callers that only have a string location. */
    fun requestAnalysis(context: Context, location: String?) {
        if (location.isNullOrEmpty()) return
        requestAnalysis(context, location.toUri())
    }

    /**
     * Hand a track to the background worker, skipping the database check.
     *
     * Callers that have already established a track is unmeasured use this, so
     * that sweeping a large library does not issue a query per track twice over.
     */
    private fun enqueue(context: Context, uri: Uri) {
        if (!pending.add(uri.toString())) return
        requests.trySend(Request(context.applicationContext, uri))
    }

    /**
     * Measure every track in [tracks] that has not been measured yet, reporting
     * progress through [analysisProgress].
     *
     * Decodes several tracks in parallel. Cancelling the calling job stops it,
     * and anything already measured is kept, so restarting resumes rather than
     * starting from the beginning.
     */
    suspend fun analyzeAll(context: Context, tracks: List<Pair<Uri, String?>>) = coroutineScope {
        val applicationContext = context.applicationContext
        // Ask the database once which tracks are already measured, rather than
        // per track as the sweep walks past them. Resuming a part finished sweep
        // then starts where it left off instead of grinding through hundreds of
        // lookups first, and the count it reports is the real one on disk.
        val measured = withContext(Dispatchers.IO) {
            try {
                dao(applicationContext)
                    .analyzedUris(TrackLoudness.CURRENT_ANALYZER_VERSION)
                    .toHashSet()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list measured tracks", e)
                hashSetOf()
            }
        }
        val outstanding = tracks.filterNot {
            val key = it.first.toString()
            undecodable.contains(key) || key in measured
        }
        // Progress counts the whole library so the number means the same thing
        // across runs, starting from whatever is already done.
        val total = tracks.size
        val done = AtomicInteger(total - outstanding.size)

        val queue = Channel<Pair<Uri, String?>>(Channel.UNLIMITED)
        outstanding.forEach { queue.trySend(it) }
        queue.close()

        try {
            _analysisProgress.value = AnalysisProgress(0, total, null)
            val deferred = Collections.synchronizedList(mutableListOf<Pair<Uri, String?>>())
            List(sweepConcurrency(applicationContext)) {
                launch(Dispatchers.Default) {
                    for (track in queue) {
                        _analysisProgress.value =
                            AnalysisProgress(done.get(), total, track.second)
                        try {
                            measure(applicationContext, track.first)
                        } catch (e: CodecUnavailableException) {
                            // Too many decoders in flight. Set it aside and come
                            // back to it once the queue has drained.
                            deferred.add(track)
                            continue
                        }
                        done.incrementAndGet()
                    }
                }
            }.joinAll()

            // Whatever could not get a decoder first time round, one at a time.
            for (track in deferred.toList()) {
                _analysisProgress.value = AnalysisProgress(done.get(), total, track.second)
                try {
                    measure(applicationContext, track.first)
                } catch (e: CodecUnavailableException) {
                    Log.w(TAG, "Still no decoder available for ${track.first}")
                }
                done.incrementAndGet()
            }
            _analysisProgress.value = AnalysisProgress(done.get(), total, null)
        } finally {
            _analysisProgress.value = null
        }
    }

    /**
     * Measure the whole library, in the background.
     *
     * Deliberately not scoped to whichever screen started it: a library of any
     * size takes minutes, and having it die because the user navigated away
     * would mean it could never finish. It runs for as long as the process does,
     * or until [cancelFullSweep].
     */
    fun startFullSweep(context: Context) {
        if (isSweeping) return
        LoudnessAnalysisService.start(context.applicationContext)
    }

    fun cancelFullSweep() = LoudnessAnalysisService.stop(AppContextProvider.appContext)

    /**
     * Queue every audio track in the library that has no measurement yet.
     *
     * Goes through the single track background worker rather than the parallel
     * sweep, because this runs unprompted and should not be noticeable.
     */
    suspend fun analyzeMissing(context: Context) {
        val applicationContext = context.applicationContext
        val analyzed = withContext(Dispatchers.IO) {
            try {
                dao(applicationContext)
                    .analyzedUris(TrackLoudness.CURRENT_ANALYZER_VERSION)
                    .toHashSet()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list measured tracks", e)
                null
            }
        } ?: return

        val missing = withContext(Dispatchers.IO) {
            Medialibrary.getInstance().audio
                ?.mapNotNull { it.uri }
                ?.filter { it.toString() !in analyzed && it.toString() !in undecodable }
                ?: emptyList()
        }
        if (missing.isEmpty()) return
        Log.i(TAG, "Queueing ${missing.size} unmeasured tracks for loudness analysis")
        missing.forEach { enqueue(applicationContext, it) }
    }

    /**
     * Watch the medialibrary and measure tracks as they are added.
     *
     * Called once at application start. A scan reports additions in bursts, so
     * the sweep is debounced rather than run once per track.
     */
    fun startWatchingLibrary(context: Context) {
        if (watchingLibrary) return
        watchingLibrary = true
        val applicationContext = context.applicationContext
        Medialibrary.getInstance().addMediaCb(object : Medialibrary.MediaCb {
            override fun onMediaAdded() = scheduleSweep(applicationContext)
            override fun onMediaModified() {}
            override fun onMediaDeleted(id: LongArray?) {}
            override fun onMediaConvertedToExternal(id: LongArray?) {}
        })
    }

    private fun scheduleSweep(context: Context) {
        sweepJob?.cancel()
        sweepJob = AppScope.launch(Dispatchers.Default) {
            delay(SWEEP_DEBOUNCE_MS)
            val settings = Settings.getInstance(context)
            if (!settings.getBoolean(KEY_NORMALIZATION_ANALYSIS_ENABLED, true)) return@launch
            val config = NormalizationConfig.from(settings)
            // No point measuring for a method that will not use the result.
            if (!config.enabled || !config.method.usesMeasuredLoudness) return@launch
            analyzeMissing(context)
        }
    }

    /**
     * Forget any reported progress.
     *
     * Called when the sweep's service goes away, so a stalled or failed sweep
     * does not leave the settings screen showing a figure that will never move.
     */
    fun clearProgress() {
        _analysisProgress.value = null
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
        pending.clear()
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
                } catch (e: CodecUnavailableException) {
                    Log.d(TAG, "No decoder free for ${request.uri}; will retry later")
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
        val stored = withContext(Dispatchers.IO) {
            // One writer at a time. Several workers inserting at once contend on
            // the database, and a write that loses the race used to be logged and
            // forgotten while the cache still claimed the track was measured, so
            // the sweep looked complete and the next run found the rows missing.
            writeLock.withLock {
                try {
                    dao(context).insert(result)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not store measurement for $key", e)
                    false
                }
            }
        }
        // Only remember it once it is safely on disk. A track that failed to
        // store stays unmeasured and gets picked up again rather than silently
        // going missing.
        if (stored) cache[key] = result
    }

    /**
     * How many tracks to decode at once during a user driven sweep, from the
     * performance profile the user picked.
     */
    private fun sweepConcurrency(context: Context) =
        NormalizationConfig.from(Settings.getInstance(context))
            .analysisPerformance
            .concurrencyFor(Runtime.getRuntime().availableProcessors())

    private fun dao(context: Context): TrackLoudnessDao =
        MediaDatabase.getInstance(context).trackLoudnessDao()

    /** A library scan reports additions in bursts; wait for it to settle. */
    private const val SWEEP_DEBOUNCE_MS = 10_000L
}
