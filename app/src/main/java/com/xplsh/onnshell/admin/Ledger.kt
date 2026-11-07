package com.xplsh.onnshell.admin

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every system change OnnShell makes, each with the shell command that undoes it.
 * Lives in the app's external files dir so "adb shell" can read it:
 *   ledger.json   open changes
 *   restore.sh    their restore commands, newest first (run with: adb shell sh <dir>/restore.sh)
 *   history.log   append only record of every add and undo
 */
class Ledger(private val dir: File) {
    data class Entry(val time: Long, val kind: String, val target: String, val restore: String)

    private val json = File(dir, "ledger.json")

    @Synchronized
    fun entries(): List<Entry> {
        if (!json.exists()) return emptyList()
        val a = JSONArray(json.readText())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(o.getLong("time"), o.getString("kind"), o.getString("target"), o.getString("restore"))
        }
    }

    /** Records a change before it is made. Same kind and target twice is a no-op. */
    @Synchronized
    fun add(kind: String, target: String, restore: String, now: Long = System.currentTimeMillis()): Entry {
        entries().firstOrNull { it.kind == kind && it.target == target }?.let { return it }
        val e = Entry(now, kind, target, restore)
        save(entries() + e)
        log("add", e)
        return e
    }

    @Synchronized
    fun remove(e: Entry) {
        save(entries() - e)
        log("undo", e)
    }

    private fun save(list: List<Entry>) {
        dir.mkdirs()
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("time", it.time).put("kind", it.kind).put("target", it.target).put("restore", it.restore))
        }
        // Write then rename, so a crash never leaves a half written ledger
        val tmp = File(dir, "ledger.json.tmp")
        tmp.writeText(a.toString(2))
        if (!tmp.renameTo(json)) { json.delete(); tmp.renameTo(json) }
        File(dir, "restore.sh").writeText(
            "# OnnShell restore commands, newest change first. Run: adb shell sh <this file>\n" +
                list.reversed().joinToString("") { it.restore + "\n" }
        )
    }

    private fun log(action: String, e: Entry) {
        File(dir, "history.log").appendText("${System.currentTimeMillis()} $action ${e.kind} ${e.target}\n")
    }
}
