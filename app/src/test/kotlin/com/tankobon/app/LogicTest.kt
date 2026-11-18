package com.tankobon.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun naturalOrder() {
        val names = listOf("Chapter 10.cbz", "Chapter 2.cbz", "chapter 1.cbz", "Chapter 002.5.cbz", "Extra.cbz")
        assertEquals(listOf("chapter 1.cbz", "Chapter 2.cbz", "Chapter 002.5.cbz", "Chapter 10.cbz", "Extra.cbz"),
            names.sortedWith(Logic.natural))
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(Logic.natural))
    }

    @Test fun cleanNames() {
        assertEquals("Chainsaw Man v01", Logic.clean("[Group] Chainsaw Man (Digital) v01.cbz"))
        assertEquals("Frieren", Logic.clean("Frieren"))
        assertEquals("One_Piece", Logic.clean("One_Piece").replace(' ', '_'))
    }

    @Test fun controlsTurnWithTheBigScreen() {
        // Clamshell: both screens landscape, nothing to turn.
        assertEquals(0, Logic.controlsRotation(1, 1, false))
        // Book-style: big screen portrait (0), small still landscape (1): turn a quarter back.
        assertEquals(-90, Logic.controlsRotation(0, 1, false))
        assertEquals(90, Logic.controlsRotation(2, 1, false))
        assertEquals(90, Logic.controlsRotation(0, 1, true))
    }

    @Test fun volumesBeforeLooseChapters() {
        val files = listOf("Tower Dungeon c012 (2024) (Digital) (Aquila).cbz", "Tower Dungeon v02 (2025) (Digital) (lft-DCP).cbz",
            "Tower Dungeon c011 (2024) (Digital) (JDS).cbz", "Tower Dungeon v01 (2025) (Digital) (lft-DCP).cbz")
        assertEquals(listOf("Tower Dungeon v01 (2025) (Digital) (lft-DCP).cbz", "Tower Dungeon v02 (2025) (Digital) (lft-DCP).cbz",
            "Tower Dungeon c011 (2024) (Digital) (JDS).cbz", "Tower Dungeon c012 (2024) (Digital) (Aquila).cbz"), files.sortedWith(Logic.reading))
        // Only chapters, or names that just happen to start with v/c: plain natural order.
        assertEquals(listOf("Chapter 2", "Chapter 10"), listOf("Chapter 10", "Chapter 2").sortedWith(Logic.reading))
        assertEquals(listOf("Vinland 2", "Vinland 10"), listOf("Vinland 10", "Vinland 2").sortedWith(Logic.reading))
        assertTrue(Logic.isVolume("Witch Hat Atelier v01 (2019) (Digital) (F) (danke-Empire).cbz"))
        assertTrue(Logic.isVolume("Berserk Vol. 3.cbz"))
        assertFalse(Logic.isVolume("Tower Dungeon c011 (2024) (Digital) (JDS).cbz"))
        assertFalse(Logic.isVolume("Vinland 2.cbz"))
        assertEquals(listOf("Pluto - Urasawa x Tezuka v01", "Pluto - Urasawa x Tezuka v02"),
            listOf("Pluto - Urasawa x Tezuka v02", "Pluto - Urasawa x Tezuka v01").sortedWith(Logic.reading))
    }

    @Test fun holdingPicksRotation() {
        assertEquals(0, Logic.rotationFor(5, 1))      // upright: book-style, small screen on the left
        assertEquals(1, Logic.rotationFor(268, 0))    // right side up: clamshell landscape
        assertEquals(2, Logic.rotationFor(183, 0))    // book-style the other way round
        assertEquals(3, Logic.rotationFor(90, 0))
        assertEquals(0, Logic.rotationFor(-1, 0))     // flat on the table: keep what we had
        assertEquals(2, Logic.rotationFor(45, 2))     // diagonal: keep what we had
        assertEquals(0, Logic.rotationFor(355, 1))
    }

    @Test fun padFollowsThePicture() {
        // Clamshell: the pad means what it says.
        assertEquals(Logic.Dir.LEFT, Logic.onScreen(KeyEvent.KEYCODE_DPAD_LEFT, 1))
        // Turned a quarter clockwise into portrait: pad-up now points right on screen.
        assertEquals(Logic.Dir.RIGHT, Logic.onScreen(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertEquals(Logic.Dir.LEFT, Logic.onScreen(KeyEvent.KEYCODE_DPAD_UP, 2))
    }

    @Test fun pageSteps() {
        // Manga: the left side is the next page.
        assertEquals(1, Logic.step(KeyEvent.KEYCODE_DPAD_LEFT, 1, rightToLeft = true))
        assertEquals(-1, Logic.step(KeyEvent.KEYCODE_DPAD_LEFT, 1, rightToLeft = false))
        assertEquals(1, Logic.step(KeyEvent.KEYCODE_BUTTON_A, 0, true))
        assertEquals(null, Logic.step(KeyEvent.KEYCODE_BUTTON_X, 0, true))
    }

    @Test fun sampleSizes() {
        assertEquals(1, Logic.sampleSize(1200, 1800, 1080, 1920))
        assertEquals(2, Logic.sampleSize(2400, 3600, 1080, 1600))
        assertEquals(4, Logic.sampleSize(1600, 2400, 360, 480))
    }
}
