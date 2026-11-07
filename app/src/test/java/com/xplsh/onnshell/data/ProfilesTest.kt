package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test fun roundTripKeepsModeAndRect() {
        val m = mapOf(
            "a.game" to Profile(touch = true, rect = listOf(0, 69, 1920, 942)),
            "b.app" to Profile(touch = false),
        )
        assertEquals(m, Profiles.parse(Profiles.format(m)))
    }

    @Test fun badJsonOrBadRectIsIgnoredNotFatal() {
        assertEquals(emptyMap<String, Profile>(), Profiles.parse("{not json"))
        assertEquals(Profile(touch = true, rect = null), Profiles.parse("""{"x":{"touch":true,"rect":[1,2]}}""")["x"])
    }

    @Test fun defaultRectKeepsThePadAspectCentered() {
        // DS4 pad on this box is 1921 x 943, the screen 1920 x 1080
        assertEquals(listOf(0, 69, 1920, 942), Profiles.defaultRect(1920, 1080, 1921, 943))
        // a pad taller than the screen aspect gets bands left and right
        assertEquals(listOf(420, 0, 1080, 1080), Profiles.defaultRect(1920, 1080, 1000, 1000))
    }

    @Test fun validRejectsRectsOutsideTheScreen() {
        assertTrue(Profiles.valid(listOf(0, 69, 1920, 942), 1920, 1080))
        assertFalse(Profiles.valid(listOf(100, 0, 1920, 1080), 1920, 1080))
        assertFalse(Profiles.valid(listOf(0, 0, 0, 10), 1920, 1080))
    }
}
