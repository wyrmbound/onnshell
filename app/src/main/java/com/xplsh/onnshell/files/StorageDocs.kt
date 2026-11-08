package com.xplsh.onnshell.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.xplsh.onnshell.R
import com.xplsh.onnshell.data.GameFiles
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * The box has no system document picker, so OnnShell serves storage itself. Document id = absolute path.
 * Only paths under the storage roots or our cacheDir/picked (extracted zip entries) are served.
 */
class StorageDocs : DocumentsProvider() {
    private val rootCols = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
    private val docCols = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    )

    override fun onCreate() = true

    private fun picked() = File(context!!.cacheDir, "picked")

    private fun bases() = (GameFiles.roots() + picked()).map { it.canonicalPath }

    /** Trust boundary: canonical path (no "..", no symlink escapes) must sit inside an allowed base. */
    private fun file(id: String): File {
        val f = File(id).canonicalFile
        val ok = bases().any { b -> f.path == b || f.path.startsWith("$b/") }
        if (!ok || !f.exists()) throw FileNotFoundException(id)
        return f
    }

    /** Like [file], but a base itself is never deleted or renamed. */
    private fun nonBase(id: String): File = file(id).also { if (it.path in bases()) throw SecurityException("root: $id") }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: rootCols).apply {
        for (r in GameFiles.roots()) newRow()
            .add(Root.COLUMN_ROOT_ID, r.path).add(Root.COLUMN_DOCUMENT_ID, r.path)
            .add(Root.COLUMN_TITLE, title(r))
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE)
    }

    private fun title(r: File) = if (r.path == "/storage/emulated/0") context!!.getString(R.string.pick_internal) else r.name

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: docCols).apply { val f = file(documentId); row(this, f, f.path in bases()) }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = mimeType == Document.MIME_TYPE_DIR
        val f = freeChild(file(parentDocumentId), displayName, dir) ?: throw IllegalArgumentException("bad name: $displayName")
        if (!(if (dir) f.mkdir() else f.createNewFile())) throw IllegalStateException("create failed: ${f.path}")
        return f.path
    }

    override fun deleteDocument(documentId: String) {
        val f = nonBase(documentId)
        if (!nuke(f)) throw IllegalStateException("delete failed: ${f.path}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = nonBase(documentId)
        val to = freeChild(f.parentFile!!, displayName, f.isDirectory) ?: throw IllegalArgumentException("bad name: $displayName")
        if (!f.renameTo(to)) throw IllegalStateException("rename failed: ${f.path}")
        return to.path
    }

    /** Recursive delete that removes symlinks themselves and never walks through them (they may point out of the roots). */
    private fun nuke(f: File): Boolean {
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { nuke(it) }
        return f.delete()
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: docCols).apply { file(parentDocumentId).listFiles()?.forEach { row(this, it) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        file(documentId).path.startsWith(file(parentDocumentId).path + "/")
    }.getOrDefault(false)

    private fun row(c: MatrixCursor, f: File, base: Boolean = false) {
        val mime = if (f.isDirectory) Document.MIME_TYPE_DIR
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        var flags = 0
        if (f.canWrite()) {
            flags = if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (!base) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, f.path).add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_SIZE, if (f.isFile) f.length() else null)
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    companion object {
        /** A free child of [dir] named like Android does ("a (1).txt"); null if [name] could leave [dir]. */
        fun freeChild(dir: File, name: String, isDir: Boolean): File? {
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) return null
            val dot = if (isDir) name.length else name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var f = File(dir, name)
            var n = 0
            while (f.exists()) f = File(dir, "${name.substring(0, dot)} (${++n})${name.substring(dot)}")
            return f
        }
    }
}
