package com.xplsh.onnshell.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageDocsTest {
    @Test fun freeChildValidatesAndAvoidsCollisions() {
        val dir = Files.createTempDirectory("docs").toFile()
        for (bad in listOf("", ".", "..", "a/b", "../x")) assertNull(bad, StorageDocs.freeChild(dir, bad, false))

        assertEquals("save.dat", StorageDocs.freeChild(dir, "save.dat", false)!!.name)
        File(dir, "save.dat").writeText("x")
        File(dir, "save (1).dat").writeText("x")
        assertEquals("save (2).dat", StorageDocs.freeChild(dir, "save.dat", false)!!.name)

        File(dir, "v1.2").mkdir()
        assertEquals("v1.2 (1)", StorageDocs.freeChild(dir, "v1.2", true)!!.name)
        File(dir, ".cfg").writeText("x")
        assertEquals(".cfg (1)", StorageDocs.freeChild(dir, ".cfg", false)!!.name)
        dir.deleteRecursively()
    }
}
