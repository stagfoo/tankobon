package com.tankobon.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where each series is up to, and which chapters have been read to the end.
 * Keyed by series folder name, so moving the SD card between devices keeps it.
 */
class Progress(private val ctx: Context) {

    class Mark(var chapter: String, var page: Int, val read: MutableSet<String>, var last: Long)

    companion object {
        val DEFAULT_URL: String = BuildConfig.NEXT_UP_URL
        private val LOCK = Any()
    }

    val prefs = ctx.getSharedPreferences("tankobon", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "progress.json")
    private val marks: MutableMap<String, Mark> = load()

    private fun load(): MutableMap<String, Mark> = try {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val m = o.getJSONObject(k)
            val r = m.optJSONArray("read") ?: JSONArray()
            Mark(m.getString("chapter"), m.optInt("page"), MutableList(r.length()) { r.getString(it) }.toMutableSet(), m.optLong("last"))
        }.toMutableMap()
    } catch (e: Exception) { mutableMapOf() }

    private fun save() {
        val o = JSONObject()
        marks.forEach { (k, m) ->
            o.put(k, JSONObject().put("chapter", m.chapter).put("page", m.page).put("read", JSONArray(m.read.toList())).put("last", m.last))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun of(s: Series): Mark? = marks[s.name]

    /** Remember the page; reaching a chapter's last page marks it read. */
    fun at(s: Series, ch: Chapter, page: Int, pages: Int) = synchronized(LOCK) {
        val m = marks.getOrPut(s.name) { Mark(ch.name, 0, mutableSetOf(), 0) }
        m.chapter = ch.name; m.page = page; m.last = System.currentTimeMillis()
        if (pages > 0 && page >= pages - 1) m.read += ch.name
        save()
    }

    fun setRead(s: Series, ch: Chapter, read: Boolean) = synchronized(LOCK) {
        val m = marks.getOrPut(s.name) { Mark(ch.name, 0, mutableSetOf(), System.currentTimeMillis()) }
        if (read) m.read += ch.name else m.read -= ch.name
        save()
    }

    /**
     * Send every series' progress to Next Up's Manga tab. Runs off the UI thread; quietly does
     * nothing without a key (set over adb: `am start -n com.tankobon.app/.MainActivity --es key <key>`).
     */
    fun push(library: List<Series>): String {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) return "No Next Up key"
        val byName = library.associateBy { it.name }
        val items = JSONArray()
        synchronized(LOCK) {
            marks.forEach { (name, m) ->
                val s = byName[name]
                val total = s?.chapters?.size ?: 0
                val done = s?.chapters?.count { it.name in m.read } ?: m.read.size
                items.put(JSONObject().put("title", s?.title ?: Logic.clean(name))
                    .put("chapters_read", done)
                    .put("status", if (total > 0 && done >= total) "completed" else if (System.currentTimeMillis() - m.last < 30L * 86_400_000L) "active" else "paused")
                    .put("last_read", java.time.Instant.ofEpochMilli(m.last).toString()))
            }
        }
        if (items.length() == 0) return "Nothing read yet"
        val body = JSONObject().put("device", Build.MODEL ?: "android").put("origin", "reader").put("items", items).toString()
        val hash = body.hashCode()
        if (hash == prefs.getInt("sentHash", 0)) return "Up to date"
        val url = prefs.getString("url", DEFAULT_URL).orEmpty().trimEnd('/')
        if (url.isBlank()) return "No Next Up address"
        return try {
            val c = URL("$url/api/ingest/manga").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 60_000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code < 400) { prefs.edit().putInt("sentHash", hash).apply(); "Sent ${items.length()} series" }
            else "Next Up said HTTP $code"
        } catch (e: Exception) { "Not sent: ${e.javaClass.simpleName}" }
    }
}
