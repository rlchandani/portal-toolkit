/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicSuggestionsTest {
    @Test fun cleanTrimsCollapsesAndCaps() {
        assertEquals("taylor swift", MusicSuggestions.clean("  taylor \t  swift "))
        assertEquals(MusicSuggestions.MAX_QUERY, MusicSuggestions.clean("x".repeat(500)).length)
        assertNull(MusicSuggestions.remoteQuery("   "))
        assertEquals("a", MusicSuggestions.remoteQuery(" a "))
    }

    @Test fun rankPrefersPrefixThenWordStartThenContains() {
        assertEquals(0, MusicSuggestions.rank("Beatles", "bea"))
        assertEquals(1, MusicSuggestions.rank("The Beatles", "bea"))
        assertEquals(2, MusicSuggestions.rank("Abeam", "bea"))
        assertNull(MusicSuggestions.rank("Queen", "bea"))
        assertNull(MusicSuggestions.rank("Queen", " "))
    }

    @Test fun matchingIgnoresCaseAndAccents() {
        assertEquals(0, MusicSuggestions.rank("Beyoncé Halo", "BEYONCE"))
        assertEquals(0, MusicSuggestions.rank("Sigur Ros", "sigur rós"))
    }

    @Test fun recentMatchesAreRankedThenNewestFirst() {
        val recent = listOf("abba gold", "the abba songs", "abba live", "queen")
        assertEquals(listOf("abba gold", "abba live", "the abba songs"), MusicSuggestions.matchingRecent(recent, "abba"))
    }

    @Test fun mergePutsRecentFirstDropsRepeatsAndCaps() {
        val remote = listOf(
            Suggestion("Abba Gold", SuggestionKind.SEARCH),
            Suggestion("Dancing Queen", SuggestionKind.SONG, SongRef("v1", "Dancing Queen", "ABBA", null)),
            Suggestion("ABBA", SuggestionKind.ARTIST),
        ) + (1..10).map { Suggestion("abba $it", SuggestionKind.SEARCH) }
        val merged = MusicSuggestions.merge("abba", listOf("abba gold"), remote)
        assertEquals("abba gold", merged[0].text)
        assertEquals(1, merged.count { MusicSuggestions.fold(it.text) == "abba gold" })
        assertEquals(MusicLibraryParser.MAX_SUGGESTIONS, merged.size)
    }

    @Test fun emptyQueryShowsRecentSearchesOnly() {
        val recent = (1..12).map { "song $it" }
        val merged = MusicSuggestions.merge("", recent, listOf(Suggestion("x", SuggestionKind.SEARCH)))
        assertEquals(recent.take(MusicLibraryParser.MAX_SUGGESTIONS), merged.take(MusicLibraryParser.MAX_SUGGESTIONS).map { it.text })
    }

    @Test fun pickPlaysSongsAndSearchesEverythingElse() {
        val song = SongRef("v1", "Hello", "Adele", null)
        assertEquals(SuggestionPick.Play(song), MusicSuggestions.pick(Suggestion("Hello", SuggestionKind.SONG, song)))
        assertEquals(SuggestionPick.Search("Adele"), MusicSuggestions.pick(Suggestion("Adele", SuggestionKind.ARTIST)))
        assertEquals(SuggestionPick.Search("Hello"), MusicSuggestions.pick(Suggestion("Hello", SuggestionKind.SONG)))
    }

    @Test fun recentSearchesMoveToFrontWithoutRepeats() {
        val once = RecentSearches.add(listOf("queen", "abba"), "  Abba ")
        assertEquals(listOf("Abba", "queen"), once)
        assertEquals(once, RecentSearches.add(once, "   "))
    }

    @Test fun recentSearchesKeepTheLastTwenty() {
        val many = (1..25).fold(emptyList<String>()) { list, i -> RecentSearches.add(list, "q$i") }
        assertEquals(RecentSearches.MAX, many.size)
        assertEquals("q25", many.first())
        assertEquals("q6", many.last())
    }

    @Test fun storedRecentSearchesAreSanitized() {
        val stored = listOf(" a ", "", "A", "b") + (1..30).map { "x$it" }
        val clean = RecentSearches.sanitize(stored)
        assertEquals(listOf("a", "b"), clean.take(2))
        assertEquals(RecentSearches.MAX, clean.size)
    }

    @Test fun memoryStoreKeepsACopy() {
        val store = MemoryRecentSearchStore()
        val list = listOf("a")
        store.save(list)
        assertEquals(list, store.load())
    }
}
