/*
 * *************************************************************************
 *  TrackSortOrder.kt
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

import android.content.Context
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.tools.KEY_ANDROID_AUTO_TRACK_SORT
import org.videolan.tools.Settings

/**
 * Order of the Tracks list in the car.
 *
 * The phone offers a sort menu; Android Auto has nowhere to put one, because
 * browse screens carry no controls beyond the list itself. So the order is a
 * preference on the phone that the car obeys.
 *
 * It matters more in the car than it looks. Android Auto browse lists are paged,
 * so with hundreds of tracks the first page is all most people will ever scroll,
 * and what lands on it is decided entirely by this.
 */
enum class TrackSortOrder(val key: String, val mlSort: Int, val descending: Boolean) {

    /** A to Z, the long standing behaviour. */
    NAME("name", Medialibrary.SORT_ALPHA, false),

    /** Most recently added to the library first. */
    DATE_ADDED("date_added", Medialibrary.SORT_INSERTIONDATE, true),

    /**
     * Newest file first, by the file's own modification date.
     *
     * Usually what people mean by "recent" for music copied onto a phone in
     * batches, where everything shares a library insertion date but the files
     * themselves carry the dates that distinguish them.
     */
    FILE_DATE("file_date", Medialibrary.SORT_LASTMODIFICATIONDATE, true);

    companion object {
        val DEFAULT = NAME

        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun current(context: Context) = fromKey(
            Settings.getInstance(context).getString(KEY_ANDROID_AUTO_TRACK_SORT, null)
        )
    }
}
