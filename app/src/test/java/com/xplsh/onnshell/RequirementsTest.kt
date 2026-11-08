package com.xplsh.onnshell

import com.xplsh.onnshell.data.Requirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RequirementsTest {
    @Test fun pickIsFoundCopiedAndThenPresent() {
        val root = createTempDirectory("req").toFile()
        val target = File(root, "MostWanted")
        val usb = File(root, "usb").apply { mkdirs() }
        File(usb, "game.iso").writeText("disc"); File(usb, "notes.txt").writeText("x")
        val need = Requirements.parse("""{"mw":{"game":"G","pick":{"type":"file","ext":["iso"],"dir":"${target.path.replace("\\", "/")}"}}}""")["mw"]!!
        val pick = need.pick!!
        assertFalse(Requirements.present(pick))
        val found = Requirements.candidates(pick, usb.walkTopDown().filter { it.isFile }.toList())
        assertEquals(listOf("game.iso"), found.map { it.file.name })
        var last = 0f
        Requirements.install(pick, found.single()) { last = it }
        assertTrue(Requirements.present(pick)); assertEquals(1f, last); assertEquals("disc", File(target, "game.iso").readText())
        assertTrue(Requirements.candidates(pick, target.listFiles()!!.toList()).isEmpty()) // the copy in place is not offered again
        root.deleteRecursively()
    }

    /** A cartridge ROM: found inside a zip by the title it carries, other games' ROMs ignored, placed in cartridge byte order. */
    @Test fun namedRomIsFoundInAZipByTitleAndPlacedInCartridgeOrder() {
        val root = createTempDirectory("req").toFile()
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(28) + "Diddy Kong Racing   ".toByteArray() + ByteArray(12)
        val n64 = z64.copyOf().also { b -> for (i in b.indices step 4) { b.reverse(i, i + 4) } } // the .n64 order of the same dump
        assertEquals(4, Requirements.n64Swap(n64)); assertEquals(0, Requirements.n64Swap(z64))
        val zip = File(root, "drop/Diddy.zip").apply { parentFile!!.mkdirs() }
        java.util.zip.ZipOutputStream(zip.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("sub/Diddy (USA).n64")); it.write(n64); it.closeEntry() }
        File(root, "drop/kart.z64").writeBytes(z64.copyOf().also { "MARIOKART64      ".toByteArray().copyInto(it, 32) })
        val need = Requirements.parse("""{"dkr-r":{"game":"G","pick":{"type":"file","ext":["z64","n64"],"has":"Diddy Kong Racing",
            "dir":"/data/local/tmp/games/dkr-r/data","name":"dkr.z64","seed":["extra"],"seedDir":"/storage/emulated/0/X","arg":true}}}""")["dkr-r"]!!
        val pick = need.pick!!
        val found = Requirements.candidates(pick, root.walkTopDown().filter { it.isFile }.toList())
        assertEquals(listOf("sub/Diddy (USA).n64"), found.map { it.entry })
        val cmds = ArrayList<String>(); var staged: ByteArray? = null
        val stage = File(root, "staging")
        Requirements.install(pick, found.single(), "dkr-r", stage, { c -> cmds += c; staged = File(stage, "dkr.z64").readBytes(); 0 }) {}
        assertTrue(staged!!.contentEquals(z64)); assertFalse(File(stage, "dkr.z64").exists())
        val cmd = cmds.single()
        assertTrue(cmd, "mv '/data/local/tmp/games/dkr-r/data/dkr.z64.part' '/data/local/tmp/games/dkr-r/data/dkr.z64'" in cmd)
        assertTrue(cmd, "mkdir -p '/storage/emulated/0/X' && cp -r '/data/local/tmp/games/dkr-r/data/extra' '/storage/emulated/0/X/'" in cmd)
        assertTrue(cmd, "echo 'exec sh /data/local/tmp/games/run_apk.sh /data/local/tmp/games/dkr-r /data/local/tmp/games/dkr-r/data/dkr.z64' > '/data/local/tmp/games/dkr-r/run.sh'" in cmd)
        assertTrue(Requirements.present(pick) { c -> if (c == "[ -f '/data/local/tmp/games/dkr-r/data/dkr.z64' ]") 0 else 1 })
        assertFalse(Requirements.present(pick) { null }) // no shell user: not known to be there
        root.deleteRecursively()
    }

    @Test fun aProvenDumpWinsWhenSeveralFit() {
        val root = createTempDirectory("req").toFile()
        fun rom(rev: Byte) = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(28) + "Diddy Kong Racing   ".toByteArray() + byteArrayOf(rev)
        File(root, "a.z64").writeBytes(rom(0)); File(root, "b.z64").writeBytes(rom(1))
        val sum = java.security.MessageDigest.getInstance("SHA-1").digest(rom(1)).joinToString("") { "%02x".format(it) }
        val base = Requirements.Pick(false, setOf("z64"), null, File(root, "t").path, name = "dkr.z64", has = "Diddy Kong Racing")
        assertEquals(2, Requirements.candidates(base, root.listFiles()!!.toList()).size)
        assertEquals(listOf("b.z64"), Requirements.candidates(base.copy(sha1 = setOf(sum)), root.listFiles()!!.toList()).map { it.file.name })
        root.deleteRecursively()
    }

    @Test fun folderGamesAreFoundByTheirMarker() {
        val root = createTempDirectory("req").toFile()
        val game = File(root, "rip/NFS").apply { mkdirs() }; File(game, "default.xex").writeText("x")
        val pick = Requirements.Pick(folder = true, ext = emptySet(), marker = "default.xex", dir = File(root, "target").path)
        assertEquals(listOf(game), Requirements.candidates(pick, root.walkTopDown().filter { it.isFile }.toList()).map { it.file })
        root.deleteRecursively()
    }
}
