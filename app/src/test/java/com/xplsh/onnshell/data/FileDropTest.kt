package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class FileDropTest {
    @Test fun safePathKeepsFoldersAndDropsTricks() {
        assertEquals("NFS/Movies/intro.vp6", FileDrop.safePath("NFS/Movies/intro.vp6"))
        assertEquals("game/baserom.us.z64", FileDrop.safePath("\\game\\baserom.us.z64"))
        assertEquals("C/rom.z64", FileDrop.safePath("C:/rom.z64"))
        assertEquals("a/b.bin", FileDrop.safePath("//a///b.bin"))
        assertNull(FileDrop.safePath("../secret"))
        assertNull(FileDrop.safePath("a/../../b"))
        assertNull(FileDrop.safePath("./"))
        assertNull(FileDrop.safePath(""))
        assertNull(FileDrop.safePath((1..9).joinToString("/") { "d$it" }))
        assertEquals("ab", FileDrop.safePath("a\u0000b"))
    }

    @Test fun onlyHomeNetworkClients() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.20", "169.254.1.1", "fe80::1", "fd12::5").forEach {
            assertTrue(it, FileDrop.isPrivate(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "172.32.0.1", "2001:4860::1").forEach { assertFalse(it, FileDrop.isPrivate(InetAddress.getByName(it))) }
    }

    @Test fun statusReadyWrongMissing() {
        val dir = Files.createTempDirectory("status").toFile()
        val us = "us rom".toByteArray(); val eu = "eu rom".toByteArray()
        val usSum = GameFiles.sha1(us.inputStream())
        File(dir, "Super Mario 64 (Europe).z64").writeBytes(eu)
        val wants = setOf("baserom.us.z64", usSum)
        fun idx() = GameFiles.walk(listOf(dir)).let { GameFiles.index(it, GameFiles.hashExts(wants)) }
        assertEquals(GameFiles.Status.WRONG, GameFiles.status(wants, true, idx()))
        // a listing with only a fingerprint and the file kind ("*.z64", see Catalog.wants)
        assertEquals(GameFiles.Status.WRONG, GameFiles.status(setOf(usSum, "*.z64"), true, idx()))
        assertEquals(GameFiles.Status.MISSING, GameFiles.status(setOf(usSum, "*.iso"), true, idx()))
        File(dir, "mario.z64").writeBytes(us) // any name, the fingerprint decides
        assertEquals(GameFiles.Status.READY, GameFiles.status(wants, true, idx()))
        assertEquals(GameFiles.Status.MISSING, GameFiles.status(setOf("other.wad"), true, idx()))
        assertEquals(GameFiles.Status.MISSING, GameFiles.status(emptySet(), true, idx()))
        assertEquals(GameFiles.Status.NONE, GameFiles.status(emptySet(), false, idx()))
        assertEquals(GameFiles.Status.READY, GameFiles.status(emptySet(), true, idx(), present = false, candidates = true))
        assertEquals(GameFiles.Status.MISSING, GameFiles.status(emptySet(), true, idx(), present = false))
        dir.deleteRecursively()
    }
}
