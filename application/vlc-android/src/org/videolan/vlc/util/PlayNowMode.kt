/*
 * *************************************************************************
 *  PlayNowMode.kt
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

import android.content.SharedPreferences
import org.videolan.tools.KEY_PLAY_NOW_MODE

/**
 * What happens to the current queue when a single track is tapped in a list.
 *
 * Replacing the queue is destructive in a way that is easy to trigger by
 * accident: one tap during a shuffle-all session throws away everything that was
 * lined up, and there is no undo.
 */
enum class PlayNowMode(val key: String) {

    /** Replace the queue with the tapped track. VLC's long standing behaviour. */
    REPLACE("replace"),

    /** Keep the queue only while shuffling, when losing it costs the most. */
    SHUFFLE("shuffle"),

    /** Keep the queue whenever there is one. */
    ALWAYS("always");

    companion object {
        val DEFAULT = ALWAYS

        fun current(prefs: SharedPreferences) =
            entries.firstOrNull { it.key == prefs.getString(KEY_PLAY_NOW_MODE, null) } ?: DEFAULT
    }
}
