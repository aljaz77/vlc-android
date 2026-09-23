/*****************************************************************************
 * LoudnessAnalysisService.kt
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

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.NotificationIds
import org.videolan.resources.util.startForegroundCompat
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.MISC_CHANNEL_ID
import org.videolan.vlc.gui.helpers.NotificationHelper

/**
 * Runs a whole library loudness sweep as a foreground service.
 *
 * Measuring a large library takes minutes. Without a foreground service the work
 * is at the mercy of background execution limits, so leaving the app stops it
 * partway through and the user has no way of knowing how far it got. A service
 * with an ongoing notification keeps it running and shows progress, and gives
 * somewhere to put a stop button.
 */
class LoudnessAnalysisService : LifecycleService() {

    private var sweep: Job? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createNotificationChannels(applicationContext)
        // Android 8+ expects startForeground almost immediately after the
        // service starts, well before there is any progress to report.
        startForegroundCompat(
            NotificationIds.LOUDNESS_ANALYSIS,
            buildNotification(0, 0, null),
            foregroundType()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSweep()
            return Service.START_NOT_STICKY
        }
        if (sweep?.isActive != true) startSweep()
        return Service.START_NOT_STICKY
    }

    private fun startSweep() {
        observeProgress()
        sweep = lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                Medialibrary.getInstance().audio
                    ?.mapNotNull { mw -> mw.uri?.let { it to mw.title } }
                    ?: emptyList()
            }
            LoudnessRepository.analyzeAll(applicationContext, tracks)
            stopSelf()
        }
    }

    private fun stopSweep() {
        sweep?.cancel()
        sweep = null
        stopSelf()
    }

    /**
     * Mirror the repository's progress into the notification.
     *
     * collectLatest rather than collect: progress updates arrive far faster than
     * a notification should be redrawn, and only the newest one matters.
     */
    private fun observeProgress() {
        lifecycleScope.launch {
            LoudnessRepository.analysisProgress.collectLatest { progress ->
                if (progress == null) return@collectLatest
                NotificationManagerCompat.from(this@LoudnessAnalysisService).notify(
                    NotificationIds.LOUDNESS_ANALYSIS.id,
                    buildNotification(progress.done, progress.total, progress.currentTitle)
                )
            }
        }
    }

    private fun buildNotification(done: Int, total: Int, title: String?) =
        NotificationCompat.Builder(this, MISC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_scan)
            .setContentTitle(getString(R.string.normalization_analyze_notification_title))
            .setContentText(
                when {
                    total <= 0 -> getString(R.string.normalization_analyze_notification_preparing)
                    else -> getString(
                        R.string.normalization_analyze_running, done + 1, total
                    ) + (title?.let { " — $it" } ?: "")
                }
            )
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_pause_notif,
                getString(R.string.stop),
                PendingIntent.getService(
                    this, 0,
                    Intent(this, LoudnessAnalysisService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun foregroundType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else 0

    override fun onDestroy() {
        isRunning = false
        sweep?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "org.videolan.vlc.action.STOP_LOUDNESS_ANALYSIS"

        /**
         * Whether a sweep is in progress, for the settings screen to decide
         * whether its button starts one or stops the running one.
         */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, LoudnessAnalysisService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LoudnessAnalysisService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
