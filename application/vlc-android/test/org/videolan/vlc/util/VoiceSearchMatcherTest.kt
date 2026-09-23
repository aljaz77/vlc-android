/*
 * *************************************************************************
 *  VoiceSearchMatcherTest.kt
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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The text folding behind voice search.
 *
 * A stored title and a spoken query only ever meet through
 * [VoiceSearchMatcher.normalize]. If two spellings of the same name do not
 * normalise to the same string then no amount of ranking afterwards can
 * recover it, which is why these cases are worth pinning down.
 */
class VoiceSearchMatcherTest {

    private fun assertSame(spoken: String, stored: String) = assertEquals(
        "\"$spoken\" and \"$stored\" should compare equal",
        VoiceSearchMatcher.normalize(stored),
        VoiceSearchMatcher.normalize(spoken)
    )

    /** An assistant says "two"; the file is called "2". */
    @Test
    fun `spoken numbers match digits`() {
        assertSame("two of us", "2 of us")
        assertSame("Love Me Two Times", "Love Me 2 Times")
        assertSame("seven nation army", "7 nation army")
    }

    @Test
    fun `teens and tens match digits`() {
        assertSame("nineteen", "19")
        assertSame("twenty one", "21")
        assertSame("ninety nine", "99")
        assertSame("summer of sixty nine", "summer of 69")
    }

    /** Punctuation is dropped, so an ampersand has to be read before that. */
    @Test
    fun `ampersand matches the word and`() {
        assertSame("rock and roll", "rock & roll")
        assertSame("Simon and Garfunkel", "Simon & Garfunkel")
    }

    @Test
    fun `accents punctuation and case are folded`() {
        assertSame("Tiesto", "Tiësto")
        assertSame("rocknroll", "rock'n'roll")
        assertSame("dont stop", "DON'T STOP")
    }

    @Test
    fun `leading spoken filler is removed`() {
        assertEquals("two of us", VoiceSearchMatcher.stripLeadingNoise("play the song two of us"))
        assertEquals("alive", VoiceSearchMatcher.stripLeadingNoise("play alive"))
        assertEquals(
            "nothing else matters",
            VoiceSearchMatcher.stripLeadingNoise("listen to nothing else matters")
        )
    }

    /** Stripping filler must not eat a title that genuinely begins with "the". */
    @Test
    fun `a title that legitimately starts with the survives`() {
        assertEquals("the wall", VoiceSearchMatcher.stripLeadingNoise("the wall"))
        assertEquals(
            "the number of the beast",
            VoiceSearchMatcher.stripLeadingNoise("the number of the beast")
        )
    }

    @Test
    fun `by splits the artist off`() {
        val parsed = VoiceSearchMatcher.parse("play alive by pearl jam")
        assertEquals("alive", parsed.title)
        assertEquals("pearl jam", parsed.artist)
    }

    @Test
    fun `text without numbers is left alone`() {
        assertEquals("nothing else matters", VoiceSearchMatcher.normalize("Nothing Else Matters"))
    }

    /** The case that prompted all of this: "play the song two of us" -> "2 of us". */
    @Test
    fun `a spoken request reaches a title written with a digit`() {
        assertEquals(
            VoiceSearchMatcher.normalize("2 of us"),
            VoiceSearchMatcher.normalize(
                VoiceSearchMatcher.stripLeadingNoise("play the song two of us")
            )
        )
    }
}
