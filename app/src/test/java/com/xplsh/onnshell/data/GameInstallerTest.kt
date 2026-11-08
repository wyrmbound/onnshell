package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameInstallerTest {
    @Test fun classifiesEngines() {
        val sdl64 = GameInstaller.classify(listOf("lib/arm64-v8a/libmain.so", "lib/arm64-v8a/libSDL2.so", "assets/x"))
        assertEquals(GameInstaller.Engine.SDL2, sdl64.engine); assertFalse(sdl64.has32); assertTrue(sdl64.runnable)
        val gm = GameInstaller.classify(listOf("lib/arm64-v8a/libyoyo.so"))
        assertEquals(GameInstaller.Engine.GAMEMAKER, gm.engine); assertFalse(gm.runnable)
        val with32 = GameInstaller.classify(listOf("lib/arm64-v8a/libunity.so", "lib/armeabi-v7a/libunity.so"))
        assertTrue(with32.has32); assertTrue(with32.runnable) // installs as a normal app
        val sdl3 = GameInstaller.classify(listOf("lib/arm64-v8a/libmain.so", "lib/arm64-v8a/libSDL3.so", "lib/armeabi-v7a/libSDL3.so"))
        assertEquals(GameInstaller.Engine.SDL3, sdl3.engine); assertTrue(sdl3.runnable)
        assertEquals(GameInstaller.Engine.NONE, GameInstaller.classify(listOf("classes.dex")).engine)
    }

    @Test fun readsZipCentralDirectory() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for (n in listOf("lib/arm64-v8a/libmain.so", "assets/data.bin")) { z.putNextEntry(ZipEntry(n)); z.write(1); z.closeEntry() } }
        val zip = bos.toByteArray()
        var i = zip.size - 22
        while (ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).getInt(i) != 0x06054b50) i--
        val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val size = b.getInt(i + 12); val off = b.getInt(i + 16)
        assertEquals(listOf("lib/arm64-v8a/libmain.so", "assets/data.bin"), GameInstaller.centralNames(zip.copyOfRange(off, off + size)))
    }

    @Test fun renamesStubAndElfStillParses() {
        val stub = File("src/main/assets/onnstub.so").readBytes()
        val renamed = GameInstaller.renameStub(stub, "libOpenSLES.so")
        assertEquals(stub.size, renamed.size)
        assertTrue(String(renamed, Charsets.ISO_8859_1).contains("libOpenSLES.so\u0000"))
        assertFalse(String(renamed, Charsets.ISO_8859_1).contains(GameInstaller.PLACEHOLDER))
        val elf = Elf.parse(renamed)
        assertTrue("stub links libc: ${elf.needed}", elf.needed.contains("libc.so"))
        assertFalse(elf.defines("SDL_main"))
    }
}
