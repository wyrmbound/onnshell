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
        assertEquals(listOf("game.iso"), found.map { it.name })
        var last = 0f
        Requirements.install(pick, found.single()) { last = it }
        assertTrue(Requirements.present(pick)); assertEquals(1f, last); assertEquals("disc", File(target, "game.iso").readText())
        assertTrue(Requirements.candidates(pick, target.listFiles()!!.toList()).isEmpty()) // the copy in place is not offered again
        root.deleteRecursively()
    }

    @Test fun folderGamesAreFoundByTheirMarker() {
        val root = createTempDirectory("req").toFile()
        val game = File(root, "rip/NFS").apply { mkdirs() }; File(game, "default.xex").writeText("x")
        val pick = Requirements.Pick(folder = true, ext = emptySet(), marker = "default.xex", dir = File(root, "target").path)
        assertEquals(listOf(game), Requirements.candidates(pick, root.walkTopDown().filter { it.isFile }.toList()))
        root.deleteRecursively()
    }
}
