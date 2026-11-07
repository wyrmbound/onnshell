package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsTest {
    // Netflix is preinstalled but Play updated, Apps.load reports that as isSystem = false
    private val netflix = AppEntry("Netflix", "com.netflix.ninja", isGame = false, isSystem = false)
    private val store = AppEntry("Play Store", "com.android.vending", isGame = false, isSystem = true)
    private val tvset = AppEntry("Settings", "com.android.tv.settings", isGame = false, isSystem = true)
    private val game = AppEntry("My Port", "org.example.port", isGame = true, isSystem = false)

    @Test fun gamesAlwaysShowAppsOnlyWhenWhitelisted() {
        val (games, apps) = Apps.split(listOf(netflix, store, tvset, game), setOf("com.netflix.ninja"))
        assertEquals(listOf(game), games)
        assertEquals(listOf(netflix), apps)
    }

    @Test fun ownerCanMoveAnAppToGames() {
        val (games, apps) = Apps.split(listOf(netflix, game), setOf("com.netflix.ninja"), games = setOf("com.netflix.ninja"))
        assertEquals(listOf(netflix, game), games)
        assertEquals(emptyList<AppEntry>(), apps)
    }

    @Test fun newInstallsJoinButRemovedAppsStayRemoved() {
        val recompa = AppEntry("Recompa", "io.example.recompa", isGame = false, isSystem = false, installed = 500)
        // First run of refresh: only apps installed after OnnShell (installed at 100) are new
        val first = Apps.refresh(listOf(netflix.copy(installed = 50), recompa), setOf("x"), known = null, ownInstall = 100)
        assertEquals(setOf("x", "io.example.recompa"), first)
        // Later: the owner removed Recompa, it is known, so it does not come back
        val later = Apps.refresh(listOf(recompa), emptySet(), known = setOf("io.example.recompa"), ownInstall = 100)
        assertEquals(emptySet<String>(), later)
        // A brand new install appears
        val fresh = Apps.refresh(listOf(recompa, game.copy(isGame = false)), emptySet(), known = setOf("io.example.recompa"), ownInstall = 100)
        assertEquals(setOf("org.example.port"), fresh)
    }

    @Test fun closeCommandKillsOnlyOwnerAppsAndNothingOdd() {
        val store = AppEntry("Play Store", "com.android.vending", isGame = false, isSystem = false)
        val evil = AppEntry("x", "com.bad; reboot", isGame = false, isSystem = false)
        val cmd = Apps.closeCommand(listOf(netflix, tvset, store, evil, game))
        assertEquals("am kill --user 0 com.netflix.ninja; am kill --user 0 org.example.port", cmd)
        assertEquals(null, Apps.closeCommand(listOf(tvset, store)))
    }

    @Test fun seedKeepsUserAppsAndDefaultsButNotSystemOrGames() {
        val seed = Apps.seed(listOf(netflix, store, tvset, game))
        assertEquals(true, "com.netflix.ninja" in seed)
        assertEquals(true, "com.android.vending" in seed) // a default, even though it is a system app
        assertEquals(false, "com.android.tv.settings" in seed)
        assertEquals(false, "org.example.port" in seed)
    }
}
