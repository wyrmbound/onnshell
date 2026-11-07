package com.xplsh.onnshell.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app touchpad profile. touch = the pad is a touchscreen in that app (TOUCH mode), otherwise
 * Android's own pointer (POINTER mode). rect = the part of the screen the pad maps onto, as
 * x, y, w, h in screen pixels; null means the default (full width, aspect corrected).
 */
data class Profile(val touch: Boolean, val rect: List<Int>? = null)

object Profiles {
    /** Stored as JSON by package name: {"pkg": {"touch": true, "rect": [x, y, w, h]}}. */
    fun parse(json: String?): Map<String, Profile> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { pkg ->
            val p = o.getJSONObject(pkg)
            val r = p.optJSONArray("rect")?.let { a -> (0 until a.length()).map { a.getInt(it) } }?.takeIf { it.size == 4 }
            Profile(p.optBoolean("touch"), r)
        }
    }

    fun format(map: Map<String, Profile>): String {
        val o = JSONObject()
        map.forEach { (pkg, p) ->
            val j = JSONObject().put("touch", p.touch)
            p.rect?.let { r -> j.put("rect", JSONArray(r)) }
            o.put(pkg, j)
        }
        return o.toString()
    }

    /**
     * The pad (about 2:1) mapped onto the screen without stretching: the widest rectangle with the
     * pad's aspect ratio, centered. On a 16:9 screen that is the full width with bands above and below.
     */
    fun defaultRect(screenW: Int, screenH: Int, padW: Int, padH: Int): List<Int> {
        if (padW <= 0 || padH <= 0) return listOf(0, 0, screenW, screenH)
        return if (padW.toLong() * screenH >= padH.toLong() * screenW) {
            val h = (screenW.toLong() * padH / padW).toInt()
            listOf(0, (screenH - h) / 2, screenW, h)
        } else {
            val w = (screenH.toLong() * padW / padH).toInt()
            listOf((screenW - w) / 2, 0, w, screenH)
        }
    }

    /** A rect is usable when it lies inside the screen and is not empty. */
    fun valid(r: List<Int>, screenW: Int, screenH: Int) =
        r.size == 4 && r[0] >= 0 && r[1] >= 0 && r[2] > 0 && r[3] > 0 && r[0] + r[2] <= screenW && r[1] + r[3] <= screenH
}
