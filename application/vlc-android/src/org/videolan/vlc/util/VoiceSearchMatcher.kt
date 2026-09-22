/*
 * *************************************************************************
 *  VoiceSearchMatcher.kt
 * **************************************************************************
 *  Copyright © 2026 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.util

import org.videolan.medialibrary.interfaces.media.MediaWrapper
import java.text.Normalizer
import java.util.Locale

/**
 * Ranks tracks against a spoken query.
 *
 * Voice assistants hand over a transcription, not a database key. "play alive by
 * pearl jam" has to reach one specific file, and the transcription will differ
 * from the stored title in case, accents, punctuation and often word order. The
 * medialibrary search is a substring match, which finds candidates but says
 * nothing about which of them the person actually meant.
 */
object VoiceSearchMatcher {

    /**
     * A spoken query split into the title and artist it seems to name.
     *
     * Assistants usually pass structured extras, but not always: when the query
     * arrives as one unstructured string, "<title> by <artist>" is by far the
     * most common shape and is worth understanding.
     */
    data class Query(val title: String, val artist: String?)

    /**
     * Split a raw query on a trailing "by <artist>", if there is one.
     */
    fun parse(query: String): Query {
        val separator = BY_SEPARATORS.firstOrNull { query.contains(it, ignoreCase = true) }
            ?: return Query(query.trim(), null)
        val index = query.lastIndexOf(separator, ignoreCase = true)
        val title = query.substring(0, index).trim()
        val artist = query.substring(index + separator.length).trim()
        return if (title.isNotEmpty() && artist.isNotEmpty()) Query(title, artist)
        else Query(query.trim(), null)
    }

    /**
     * Rank tracks against a raw query, trying both readings of it.
     *
     * "stand by me" is a song title, not a request for "stand" by an artist
     * called "me", and nothing in the string says which it is. Both readings are
     * scored and the better one wins, so a title containing the word "by"
     * survives and "alive by pearl jam" still resolves to the right recording.
     */
    fun rankQuery(tracks: List<MediaWrapper>, query: String): List<MediaWrapper> {
        if (tracks.isEmpty()) return emptyList()
        val whole = rank(tracks, query, null)
        val parsed = parse(query)
        if (parsed.artist == null) return whole
        val split = rank(tracks, parsed.title, parsed.artist)
        return if (bestScore(split, parsed.title, parsed.artist) >=
            bestScore(whole, query, null)
        ) split else whole
    }

    /** Score of the best entry in an already ranked list, or zero if empty. */
    private fun bestScore(tracks: List<MediaWrapper>, title: String, artist: String?): Int {
        val first = tracks.firstOrNull() ?: return 0
        val wantedTitle = normalize(title)
        if (wantedTitle.isEmpty()) return 0
        return score(first, wantedTitle, artist?.let { normalize(it) })
    }

    /**
     * Order [tracks] by how well they answer the query, best first.
     *
     * Anything that does not match at all is dropped, so an empty result means
     * the caller should fall back rather than play something arbitrary.
     */
    fun rank(tracks: List<MediaWrapper>, title: String, artist: String?): List<MediaWrapper> {
        val wantedTitle = normalize(title)
        if (wantedTitle.isEmpty()) return emptyList()
        val wantedArtist = artist?.let { normalize(it) }

        return tracks
            .map { it to score(it, wantedTitle, wantedArtist) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Order search results so that tracks matching the query come first, without
     * dropping anything.
     *
     * [rank] is for playback, where a bad match is worse than no match. Browsing
     * is different: the person is looking at a list and can pick for themselves,
     * so non-matching tracks stay, just further down.
     */
    fun orderForBrowsing(tracks: List<MediaWrapper>, query: String): List<MediaWrapper> {
        if (tracks.isEmpty()) return tracks
        val ranked = rankQuery(tracks, query)
        if (ranked.isEmpty()) return tracks
        // Everything that did not match keeps its original order, after the
        // matches: browsing should never make a track disappear.
        val matched = ranked.toSet()
        return ranked + tracks.filterNot { it in matched }
    }

    /**
     * Whether some track's title is a strong enough answer to the query to be
     * worth showing above albums and artists.
     */
    fun hasStrongTitleMatch(tracks: List<MediaWrapper>, query: String): Boolean {
        if (tracks.isEmpty()) return false
        val best = rankQuery(tracks, query).firstOrNull() ?: return false
        val parsed = parse(query)
        val asWhole = bestScore(listOf(best), query, null)
        val asSplit = bestScore(listOf(best), parsed.title, parsed.artist)
        return maxOf(asWhole, asSplit) >= TITLE_PREFIX
    }

    /**
     * How well one track answers the query. Zero means no match at all.
     */
    private fun score(track: MediaWrapper, wantedTitle: String, wantedArtist: String?): Int {
        val trackTitle = normalize(track.title ?: "")
        if (trackTitle.isEmpty()) return 0

        var score = when {
            trackTitle == wantedTitle -> EXACT_TITLE
            trackTitle.startsWith(wantedTitle) -> TITLE_PREFIX
            trackTitle.contains(wantedTitle) -> TITLE_CONTAINS
            wantedTitle.contains(trackTitle) -> QUERY_CONTAINS_TITLE
            else -> 0
        }
        if (score == 0) return 0

        if (wantedArtist != null) {
            val trackArtist = normalize(track.artistName ?: track.albumArtistName ?: "")
            score += when {
                trackArtist.isEmpty() -> 0
                trackArtist == wantedArtist -> EXACT_ARTIST
                trackArtist.contains(wantedArtist) || wantedArtist.contains(trackArtist) ->
                    PARTIAL_ARTIST
                // A named artist that does not match is evidence against this
                // track, but not enough to rule it out: transcription is noisy.
                else -> ARTIST_MISMATCH
            }
        }

        // Among otherwise equal matches, prefer the title closest in length to
        // what was asked for, so "Alive" beats "Alive (Remastered 2011)".
        val excess = (trackTitle.length - wantedTitle.length).coerceAtLeast(0)
        score += (LENGTH_BONUS - excess).coerceAtLeast(0)

        return score.coerceAtLeast(1)
    }

    /**
     * Reduce a title to something comparable: lower case, accents stripped,
     * punctuation removed, whitespace collapsed.
     */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        var lastWasSpace = true
        for (character in decomposed) {
            when {
                // Combining accents left behind by the NFD decomposition.
                character.code in COMBINING_RANGE -> Unit
                character.isLetterOrDigit() -> {
                    builder.append(character)
                    lastWasSpace = false
                }
                character.isWhitespace() -> {
                    if (!lastWasSpace) {
                        builder.append(' ')
                        lastWasSpace = true
                    }
                }
                // Punctuation is dropped entirely rather than turned into a
                // space, so "rock'n'roll" and "rocknroll" compare equal.
                else -> Unit
            }
        }
        return builder.toString().trim().lowercase(Locale.ROOT)
    }

    private val BY_SEPARATORS = listOf(" by ", " from ")

    private val COMBINING_RANGE = 0x0300..0x036F

    private const val EXACT_TITLE = 1000
    private const val TITLE_PREFIX = 700
    private const val TITLE_CONTAINS = 500
    private const val QUERY_CONTAINS_TITLE = 400

    private const val EXACT_ARTIST = 300
    private const val PARTIAL_ARTIST = 150
    private const val ARTIST_MISMATCH = -100

    private const val LENGTH_BONUS = 50
}
