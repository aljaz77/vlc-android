package org.videolan.vlc.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.videolan.vlc.mediadb.models.TrackLoudness

/**
 * Blocking, like every other DAO here: Room's processor cannot generate suspend
 * DAO methods at the Room and KSP versions this project builds against. Callers
 * run these on an IO dispatcher.
 */
@Dao
interface TrackLoudnessDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(loudness: TrackLoudness)

    @Query("SELECT * FROM track_loudness WHERE media_uri = :uri AND analyzer_version = :version")
    fun get(uri: String, version: Int): TrackLoudness?

    @Query("SELECT media_uri FROM track_loudness WHERE analyzer_version = :version")
    fun analyzedUris(version: Int): List<String>

    @Query("SELECT COUNT(*) FROM track_loudness WHERE analyzer_version = :version")
    fun count(version: Int): Int

    @Query("DELETE FROM track_loudness WHERE media_uri = :uri")
    fun delete(uri: String)

    @Query("DELETE FROM track_loudness")
    fun clear()
}
