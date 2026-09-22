package org.videolan.vlc.mediadb.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A loudness measurement for one track, used by volume normalization to work out
 * the exact gain that brings it to the configured target.
 *
 * Keyed by URI rather than by medialibrary id: ids are reassigned when the
 * library is reset, and re-measuring an entire music collection because of a
 * rescan would be wasteful.
 */
@Entity(tableName = "track_loudness")
data class TrackLoudness(
    @PrimaryKey
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,

    /** Integrated loudness in LUFS, per ITU-R BS.1770-4 with EBU R128 gating. */
    @ColumnInfo(name = "integrated_lufs")
    val integratedLufs: Double,

    /**
     * Highest absolute sample in the track, in dBFS.
     *
     * This is sample peak, not true peak: measuring true peak needs oversampling,
     * and the difference only matters for heavily limited material. Normalization
     * leaves headroom below full scale to cover the gap.
     */
    @ColumnInfo(name = "sample_peak_db")
    val samplePeakDb: Double,

    @ColumnInfo(name = "analyzed_at")
    val analyzedAt: Long,

    /**
     * Version of the analyzer that produced this measurement. Bump
     * [CURRENT_ANALYZER_VERSION] to invalidate everything measured by an older
     * one rather than mixing results from two different algorithms.
     */
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: Int = CURRENT_ANALYZER_VERSION
) {
    companion object {
        const val CURRENT_ANALYZER_VERSION = 1
    }
}
