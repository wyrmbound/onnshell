package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameFilesTest {
    private fun sha1(b: ByteArray) = GameFiles.sha1(b.inputStream())

    @Test fun matchesByNameShaAndZipEntry() {
        val dir = Files.createTempDirectory("gamefiles").toFile()
        val rom = "majora".toByteArray()
        val zipped = "zipped rom".toByteArray()
        File(dir, "Data.RSDK").writeText("x") // name match, any case
        File(dir, "mm.z64").writeBytes(rom) // sha1 match
        File(dir, "notes.txt").writeBytes(rom) // same bytes, but .txt is never hashed
        File(dir, "other.z64").writeText("nope")
        val zip = File(dir, "roms.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("n64/game.n64")); z.write(zipped); z.closeEntry()
            z.putNextEntry(ZipEntry("readme.txt")); z.write(zipped); z.closeEntry()
        }
        File(dir, ".hidden").mkdir(); File(dir, ".hidden/mm.z64").writeBytes(rom)
        File(dir, "Android").mkdir(); File(dir, "Android/mm.z64").writeBytes(rom)

        val wants = setOf("data.rsdk", sha1(rom), sha1(zipped))
        val found = GameFiles.matches(GameFiles.walk(listOf(dir)), wants)
            .map { it.file.relativeTo(dir).path.replace('\\', '/') + (it.entry?.let { e -> "!$e" } ?: "") }.toSet()
        assertEquals(setOf("Data.RSDK", "mm.z64", "roms.zip!n64/game.n64"), found)

        assertTrue(GameFiles.matches(GameFiles.walk(listOf(dir)), emptySet()).isEmpty())

        val out = GameFiles.extract(zip, "n64/game.n64", File(dir, "picked"))
        assertEquals("game.n64", out.name)
        assertEquals(sha1(zipped), sha1(out.readBytes()))
        dir.deleteRecursively()
    }
}
