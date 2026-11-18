package com.tankobon.app

import android.content.Context
import android.os.Build
import android.os.Environment
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

    /**
     * Copies anyone can open: Documents/Tankobon on internal storage holds progress.json (where
     * every series is up to) and reading-log.csv (one line per reading session or finished
     * chapter). The copy also brings progress back after a reinstall.
     */
    val shared = File(Environment.getExternalStorageDirectory(), "Documents/Tankobon")
    val logFile = File(shared, "reading-log.csv")
    private val sharedProgress = File(shared, "progress.json")

    private val marks: MutableMap<String, Mark> = load()

    private fun load(): MutableMap<String, Mark> = try {
        val src = if (file.exists()) file else sharedProgress
        val o = JSONObject(src.readText())
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
        // Readable copy, with dates people can read, sorted by most recent.
        try {
            val out = JSONObject()
            marks.entries.sortedByDescending { it.value.last }.forEach { (k, m) ->
                out.put(k, JSONObject().put("chapter", m.chapter).put("page", m.page + 1).put("last_read", stamp(m.last))
                    .put("read", JSONArray(m.read.sortedWith(Logic.reading))).put("last", m.last))
            }
            shared.mkdirs()
            val t = File(sharedProgress.path + ".tmp"); t.writeText(out.toString(2)); t.renameTo(sharedProgress)
        } catch (_: Exception) {}
    }

    private fun stamp(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    /**
     * One line in reading-log.csv. [event] is "read" (a sitting with a chapter: pages from–to and
     * minutes) or "finished" (reached the chapter's last page).
     */
    fun log(event: String, s: Series, ch: Chapter, from: Int, to: Int, pages: Int, minutes: Int) = synchronized(LOCK) {
        try {
            shared.mkdirs()
            val fresh = !logFile.exists()
            logFile.appendText(buildString {
                if (fresh) append("time,event,series,chapter,from_page,to_page,pages,minutes\n")
                append(listOf(stamp(System.currentTimeMillis()), event, s.title, ch.title, "${from + 1}", "${to + 1}", "$pages", "$minutes")
                    .joinToString(",") { csv(it) }).append('\n')
            })
        } catch (_: Exception) {}
    }

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** The last [n] log lines, newest first, for the settings screen. */
    fun recent(n: Int): List<String> = try {
        logFile.readLines().drop(1).takeLast(n).reversed()
    } catch (_: Exception) { emptyList() }

    fun of(s: Series): Mark? = marks[s.name]

    /** Remember the page; reaching a chapter's last page marks it read (and logs it, once). */
    fun at(s: Series, ch: Chapter, page: Int, pages: Int) = synchronized(LOCK) {
        val m = marks.getOrPut(s.name) { Mark(ch.name, 0, mutableSetOf(), 0) }
        m.chapter = ch.name; m.page = page; m.last = System.currentTimeMillis()
        if (pages > 0 && page >= pages - 1 && m.read.add(ch.name)) log("finished", s, ch, page, page, pages, 0)
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
    fun push(library: List<Series>, force: Boolean = false): String = push0(library, force).also {
        prefs.edit().putString("sent", "$it · ${stamp(System.currentTimeMillis())}").apply()
    }

    private fun push0(library: List<Series>, force: Boolean): String {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) return "No Next Up key"
        val byName = library.associateBy { it.name }
        val items = JSONArray()
        synchronized(LOCK) {
            marks.forEach { (name, m) ->
                val s = byName[name]
                val total = s?.chapters?.size ?: 0
                val done = s?.chapters?.count { it.name in m.read } ?: m.read.size
                // Volumes and loose chapters counted apart, so Next Up says "3 vols + 2 ch".
                val files = s?.chapters?.map { it.name } ?: m.read.toList()
                val (vols, chs) = files.partition(Logic::isVolume)
                val item = JSONObject().put("title", s?.title ?: Logic.clean(name))
                if (vols.isNotEmpty()) item.put("volumes_read", vols.count { it in m.read })
                if (chs.isNotEmpty() || vols.isEmpty()) item.put("chapters_read", chs.count { it in m.read })
                items.put(item
                    .put("status", if (total > 0 && done >= total) "completed" else if (System.currentTimeMillis() - m.last < 30L * 86_400_000L) "active" else "paused")
                    .put("last_read", java.time.Instant.ofEpochMilli(m.last).toString()))
            }
        }
        if (items.length() == 0) return "Nothing read yet"
        val body = JSONObject().put("device", Build.MODEL ?: "android").put("origin", "reader").put("items", items).toString()
        val hash = body.hashCode()
        if (!force && hash == prefs.getInt("sentHash", 0)) return "Up to date"
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
