package com.tankobon.app

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A manga reader for a two-screen handheld held sideways like a book: the page fills the big
 * screen, and the small screen beside it holds the controls (page, chapter, bookshelf), the way
 * DS training games put the text on one screen and the buttons on the other.
 *
 * Without a second screen the controls sit under the page instead.
 */
class MainActivity : Activity() {

    private enum class Mode { SHELF, READER }

    private lateinit var progress: Progress
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val covers = Executors.newSingleThreadExecutor()

    private var mode = Mode.SHELF
    private var library: List<Series> = emptyList()
    private var selected = 0

    private var series: Series? = null
    private var chapterIndex = 0
    private var book: Book? = null
    private var page = 0
    private val pages = LruCache<String, Bitmap>(6)

    private lateinit var big: FrameLayout        // what the big screen shows
    private lateinit var root: Turned            // turns it to the way the Thor is held
    /**
     * The rotation the big screen should have for the way the Thor is held. The Thor's system
     * keeps its screens fixed in landscape and ignores apps asking for portrait, so Tankobon
     * turns its own picture: by the difference between this and the display's real rotation.
     */
    private var held = 0
    private lateinit var pageView: ImageView
    private var controls: Presentation? = null
    private var embedded: Turned? = null          // controls under the page when there's no second screen
    private var turned: Turned? = null            // whichever of the two is live

    private var settings = false                  // the small screen shows settings instead of controls

    // The current sitting with one chapter, for the reading log.
    private var sitStart = 0L
    private var sitFrom = 0

    private val prefs get() = progress.prefs
    private val rightToLeft get() = prefs.getBoolean("rtl", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = Progress(applicationContext)
        intent?.getStringExtra("key")?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("key", it).apply() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        big = FrameLayout(this)
        pageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root = Turned(this).apply { addView(big) }
        setContentView(root)
        // Book-style (small screen on the left) until the sensor says otherwise.
        held = prefs.getInt("held", 0)
        turnPicture()
        immersive(window)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displays, main)
    }

    override fun onResume() {
        super.onResume()
        if (tilt.canDetectOrientation()) tilt.enable()
        showControls()
        if (!Environment.isExternalStorageManager()) { askForFiles(); return }
        if (mode == Mode.READER && book != null) { startSitting(); showReader() } else rescan()
    }

    override fun onPause() {
        super.onPause()
        endSitting()
        tilt.disable()
        controls?.dismiss(); controls = null
        val lib = library
        Thread { progress.push(lib) }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displays)
        book?.close()
        io.shutdownNow(); covers.shutdownNow()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        turnPicture()
        if (mode == Mode.READER) showPage() else showShelf()
    }

    // ---- the small screen ----------------------------------------------------------------

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { if (controls == null && embedded == null) showControls() }
        override fun onDisplayRemoved(id: Int) { if (controls?.display?.displayId == id) { controls?.dismiss(); controls = null; showControls() } }
        override fun onDisplayChanged(id: Int) { turnPicture() }
    }

    private fun secondScreen(): Display? {
        val mine = display?.displayId ?: Display.DEFAULT_DISPLAY
        return getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != mine }
    }

    private fun showControls() {
        val other = secondScreen()
        if (other != null && controls == null) {
            embedded?.let { (it.parent as? FrameLayout)?.removeView(it) }; embedded = null
            val p = object : Presentation(this, other) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val t = Turned(context)
                    setContentView(t)
                    // Keys stay with the big screen; touches still reach the buttons.
                    window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window?.let(::immersive)
                    turned = t
                }
            }
            try { p.show(); controls = p } catch (e: Exception) { controls = null }
        }
        if (controls == null && embedded == null) {
            embedded = Turned(this); turned = embedded
        }
        turnControls()
        renderControls()
    }

    private val tilt by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (prefs.getBoolean("lockTurn", false)) return
                val r = Logic.rotationFor(o, held)
                if (r != held) { held = r; prefs.edit().putInt("held", r).apply(); turnPicture() }
            }
        }
    }

    private fun turnPicture() {
        root.degrees = Logic.controlsRotation(held, display?.rotation ?: 0, false)
        turnControls()
    }

    private fun turnControls() {
        val c = controls ?: return
        turned?.degrees = Logic.controlsRotation(held, c.display.rotation, prefs.getBoolean("flip", false))
    }

    /** Views for the controls screen are made with its own context (its own display). */
    private val cctx: Context get() = controls?.context ?: this

    private fun renderControls() {
        val t = turned ?: return
        val panel = when {
            !Environment.isExternalStorageManager() -> panel(Ui.band(cctx, "Tankobon", "Waiting for file access"))
            settings -> settingsControls()
            mode == Mode.READER -> readerControls()
            else -> shelfControls()
        }
        t.removeAllViews()
        t.addView(panel)
    }

    private fun panel(vararg views: View): View {
        val col = LinearLayout(cctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(cctx, 14), Ui.dp(cctx, 12), Ui.dp(cctx, 14), Ui.dp(cctx, 12))
            // Keep a view's own size if it has one: a plain spacer told to wrap its content
            // fills the whole screen instead.
            views.forEach { addView(it, it.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp()) }
        }
        return FrameLayout(cctx).apply { background = Ui.paper(cctx); addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
    }

    private fun shelfControls(): View {
        val s = library.getOrNull(selected)
            ?: return panel(Ui.band(cctx, "Bookshelf", "No manga yet"), Ui.gap(cctx, 12),
                Ui.text(cctx, "Copy manga into a folder called Manga on the SD card: one folder per series, " +
                    "with .cbz files or folders of pages inside.", 17f),
                Ui.gap(cctx, 16), Ui.button(cctx, "Look again") { rescan() })
        val mark = progress.of(s)
        val read = s.chapters.count { mark?.read?.contains(it.name) == true }
        val at = mark?.let { m -> s.chapters.indexOfFirst { it.name == m.chapter } }?.takeIf { it >= 0 }
        val list = LinearLayout(cctx).apply { orientation = LinearLayout.VERTICAL }
        s.chapters.forEachIndexed { i, ch ->
            val done = mark?.read?.contains(ch.name) == true
            list.addView(TextView(cctx).apply {
                text = (if (done) "✓  " else if (i == at) "▶  " else "     ") + ch.title
                textSize = 17f; setTextColor(if (done) Ui.SOFT else Ui.INK)
                if (i == at) typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(Ui.dp(cctx, 10), Ui.dp(cctx, 12), Ui.dp(cctx, 10), Ui.dp(cctx, 12))
                setOnClickListener { open(s, i, 0) }
                setOnLongClickListener { progress.setRead(s, ch, !done); renderControls(); true }
            }, Ui.lp())
        }
        val scroll = ScrollView(cctx).apply { addView(list); background = Ui.card(cctx) }
        val go = if (at != null) Ui.button(cctx, "Continue · ${s.chapters[at].title}, p. ${mark!!.page + 1}", Ui.Kind.ORANGE, 19f) {
            open(s, at, mark.page)
        } else Ui.button(cctx, "Start reading", Ui.Kind.ORANGE, 20f) { open(s, 0, 0) }
        val col = panel(
            Ui.band(cctx, s.title, "${s.chapters.size} chapter${if (s.chapters.size == 1) "" else "s"} · $read read"),
            Ui.gap(cctx, 10), go, Ui.gap(cctx, 10))
        val inner = (col as FrameLayout).getChildAt(0) as LinearLayout
        inner.addView(scroll, Ui.lp(h = 0, weight = 1f))
        inner.addView(Ui.gap(cctx, 10))
        inner.addView(Ui.button(cctx, "Settings", size = 16f) { openSettings() }, Ui.lp())
        return col
    }

    private fun readerControls(): View {
        val s = series ?: return panel()
        val b = book
        val total = b?.pages?.size ?: 0
        val ch = s.chapters[chapterIndex]
        val counter = Ui.text(cctx, if (total > 0) "${page + 1} / $total" else "No pages", 40f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }
        val seek = SeekBar(cctx).apply {
            max = (total - 1).coerceAtLeast(0); progress = page
            // Right-to-left books scrub right to left, like the pages.
            layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.BLUE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ORANGE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) counter.text = "${p + 1} / $total" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { go(sb.progress) }
            })
        }
        // Buttons point the way the page turns: in a right-to-left book, "next" is on the left.
        val left = Ui.button(cctx, if (rightToLeft) "◀  Next" else "◀  Back", Ui.Kind.BLUE, 22f) { turn(if (rightToLeft) 1 else -1) }
        val right = Ui.button(cctx, if (rightToLeft) "Back  ▶" else "Next  ▶", Ui.Kind.BLUE, 22f) { turn(if (rightToLeft) -1 else 1) }
        listOf(left, right).forEach { it.minHeight = Ui.dp(cctx, 96) }
        return panel(
            Ui.band(cctx, s.title, ch.title),
            Ui.gap(cctx, 6), counter, seek, Ui.gap(cctx, 10),
            Ui.row(cctx, 12, left, right), Ui.gap(cctx, 10),
            Ui.row(cctx, 10,
                Ui.button(cctx, "Prev chapter", size = 16f) { chapter(-1) },
                Ui.button(cctx, "Next chapter", size = 16f) { chapter(1) }),
            Ui.gap(cctx, 10),
            Ui.row(cctx, 10,
                Ui.button(cctx, "Bookshelf", size = 16f) { closeBook() },
                Ui.button(cctx, if (rightToLeft) "Read: right→left" else "Read: left→right", size = 15f) {
                    prefs.edit().putBoolean("rtl", !rightToLeft).apply(); renderControls()
                },
                Ui.button(cctx, "Settings", size = 15f) { openSettings() }),
        )
    }

    private fun startSitting() { sitStart = System.currentTimeMillis(); sitFrom = page }

    /** Log the sitting with the open chapter, if anything happened in it. */
    private fun endSitting() {
        val s = series; val b = book
        if (sitStart == 0L || s == null || b == null || mode != Mode.READER) { sitStart = 0L; return }
        val minutes = ((System.currentTimeMillis() - sitStart) / 60_000).toInt()
        if (page != sitFrom || minutes > 0) {
            val ch = s.chapters[chapterIndex]; val from = sitFrom; val to = page; val n = b.pages.size
            io.execute { progress.log("read", s, ch, from, to, n, minutes) }
        }
        sitStart = 0L
    }

    // ---- settings (small screen) ---------------------------------------------------------

    private fun openSettings() { settings = true; renderControls() }

    private fun setting(label: String, value: String, onClick: () -> Unit): View =
        Ui.row(cctx, 10, Ui.text(cctx, label, 16f, Ui.INK, bold = true), Ui.button(cctx, value, size = 15f, onClick = onClick))

    private fun toggle(key: String, default: Boolean) { prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply() }

    private fun settingsControls(): View {
        val c = cctx
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View) { list.addView(v, Ui.lp()); list.addView(Ui.gap(c, 8)) }
        fun heading(t: String) { list.addView(Ui.text(c, t, 14f, Ui.SOFT, bold = true).apply { setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4)) }, Ui.lp()) }

        heading("READING")
        add(setting("Page order", if (rightToLeft) "Right → left (manga)" else "Left → right") { toggle("rtl", true); renderControls() })
        add(setting("Tap page sides to turn", if (prefs.getBoolean("taps", true)) "On" else "Off (swipe only)") { toggle("taps", true); renderControls() })

        heading("SCREENS")
        add(setting("Turn with the Thor", if (prefs.getBoolean("lockTurn", false)) "Locked as it is now" else "On") { toggle("lockTurn", false); renderControls() })
        add(setting("This screen", if (prefs.getBoolean("flip", false)) "Flipped" else "Normal") { toggle("flip", false); turnControls(); renderControls() })

        heading("NEXT UP")
        val hasKey = prefs.getString("key", "").orEmpty().isNotBlank()
        val status = Ui.text(c, if (!hasKey) "No key set, so progress stays on the Thor." else "Last: " + prefs.getString("sent", "nothing sent yet"), 15f, Ui.SOFT)
        add(status)
        if (hasKey) add(Ui.button(c, "Send progress now", Ui.Kind.BLUE, 16f) {
            status.text = "Sending…"
            val lib = library
            Thread { val r = progress.push(lib, force = true); main.post { status.text = "Last: " + prefs.getString("sent", r) } }.start()
        })

        heading("READING LOG")
        add(Ui.text(c, "Saved on the Thor in Documents/Tankobon: reading-log.csv (every sitting and finished chapter) and progress.json (where each series is up to).", 15f))
        val recent = progress.recent(6)
        add(Ui.text(c, if (recent.isEmpty()) "Nothing logged yet." else recent.joinToString("\n") { pretty(it) }, 14f, Ui.INK).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        })

        heading("LIBRARY")
        add(Ui.button(c, "Look for new manga", size = 16f) { settings = false; if (mode == Mode.READER) closeBook(); rescan() })

        val scroll = ScrollView(c).apply { addView(list); isVerticalScrollBarEnabled = true }
        val col = panel(Ui.band(c, "Settings"), Ui.gap(c, 6))
        val inner = (col as FrameLayout).getChildAt(0) as LinearLayout
        inner.addView(scroll, Ui.lp(h = 0, weight = 1f))
        inner.addView(Ui.gap(c, 10))
        inner.addView(Ui.button(c, "Done", Ui.Kind.ORANGE, 18f) { settings = false; renderControls() }, Ui.lp())
        return col
    }

    /** A log line, shortened for the small screen: "11-19 21:04  Pluto v02  p.6→24  12 min". */
    private fun pretty(line: String): String {
        val f = Regex("""(?:^|,)("(?:[^"]|"")*"|[^,]*)""").findAll(line).map { it.groupValues[1].trim('"').replace("\"\"", "\"") }.toList()
        if (f.size < 8) return line
        val time = f[0].drop(5)
        return if (f[1] == "finished") "$time  ${f[3]}  finished"
        else "$time  ${f[3]}  p.${f[4]}→${f[5]}  ${f[7]} min"
    }

    // ---- the big screen: bookshelf -------------------------------------------------------

    private fun rescan() {
        Thread {
            val lib = Library.scan()
            main.post {
                library = lib
                if (selected >= lib.size) selected = 0
                // Start on the series read most recently.
                if (mode == Mode.SHELF && !prefs.getBoolean("picked", false)) {
                    lib.withIndex().maxByOrNull { progress.of(it.value)?.last ?: 0 }?.let { selected = it.index }
                }
                mode = Mode.SHELF
                showShelf(); renderControls()
            }
        }.start()
    }

    private val columns = 3
    private var shelfScroll: ScrollView? = null
    private val cards = mutableListOf<View>()

    private fun showShelf() {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 24))
        }
        grid.addView(Ui.band(this, "Bookshelf", if (library.isEmpty()) "Nothing here yet" else "${library.size} series"), Ui.lp())
        grid.addView(Ui.gap(this, 12))
        cards.clear()
        library.chunked(columns).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until columns) {
                val i = r * columns + c
                val s = chunk.getOrNull(c)
                val v = if (s != null) card(s, i) else View(this)
                row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    val m = Ui.dp(this@MainActivity, 6); setMargins(m, m, m, m)
                })
                if (s != null) cards += v
            }
            grid.addView(row, Ui.lp())
        }
        if (library.isEmpty()) grid.addView(Ui.text(this, "Put manga in a folder called Manga on the SD card or internal storage:\n\n" +
            "Manga/\n   Series name/\n      Chapter 1.cbz\n      Chapter 2.cbz\n\nFolders of images work too.", 18f), Ui.lp())
        val scroll = ScrollView(this).apply { addView(grid); background = Ui.paper(this@MainActivity, margin = true) }
        shelfScroll = scroll
        big.removeAllViews()
        big.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        embedded?.let { addEmbedded(it) }
        scroll.post { cards.getOrNull(selected)?.let { scroll.smoothScrollTo(0, (it.parent as View).top - Ui.dp(this, 80)) } }
    }

    private fun card(s: Series, i: Int): View {
        val mark = progress.of(s)
        val read = s.chapters.count { mark?.read?.contains(it.name) == true }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#E9EDF2")) }
        val box = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY))
            }
        }.apply { addView(cover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)); clipToOutline = true }
        loadCover(s, cover)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(this@MainActivity, 6); setPadding(pad, pad, pad, pad)
            background = Ui.card(this@MainActivity, i == selected)
            addView(box, Ui.lp())
            addView(Ui.text(this@MainActivity, s.title, 14f, Ui.INK, bold = true).apply { maxLines = 2; setPadding(0, pad, 0, 0) })
            addView(Ui.text(this@MainActivity, if (read > 0) "$read / ${s.chapters.size} read" else "${s.chapters.size} ch", 12f, Ui.SOFT))
            setOnClickListener { if (selected == i) openSelected() else select(i) }
        }
    }

    private fun select(i: Int) {
        if (library.isEmpty()) return
        selected = i.coerceIn(0, library.size - 1)
        prefs.edit().putBoolean("picked", true).apply()
        cards.forEachIndexed { j, v -> v.background = Ui.card(this, j == selected) }
        cards.getOrNull(selected)?.let { v ->
            val y = (v.parent as View).top
            shelfScroll?.let { s -> if (y < s.scrollY || y + v.height > s.scrollY + s.height) s.smoothScrollTo(0, y - Ui.dp(this, 80)) }
        }
        renderControls()
    }

    private fun openSelected() {
        val s = library.getOrNull(selected) ?: return
        val m = progress.of(s)
        val at = m?.let { mk -> s.chapters.indexOfFirst { it.name == mk.chapter } }?.takeIf { it >= 0 }
        open(s, at ?: 0, if (at != null) m.page else 0)
    }

    private fun loadCover(s: Series, into: ImageView) {
        val f = File(cacheDir, "covers/${(s.path + s.chapters.first().name).hashCode()}.jpg")
        covers.execute {
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else try {
                Book(s.chapters.first()).use { it.decode(0, 360, 480) }?.also { b ->
                    f.parentFile?.mkdirs(); f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
            } catch (e: Exception) { null }
            if (bmp != null) main.post { into.setImageBitmap(bmp) }
        }
    }

    // ---- the big screen: reading ---------------------------------------------------------

    private fun open(s: Series, chapter: Int, atPage: Int) {
        if (series !== s || chapterIndex != chapter || book == null) {
            endSitting()
            book?.close(); book = null; pages.evictAll()
            series = s; chapterIndex = chapter
            book = try { Book(s.chapters[chapter]) } catch (e: Exception) { null }
        }
        page = atPage.coerceIn(0, ((book?.pages?.size ?: 1) - 1).coerceAtLeast(0))
        if (sitStart == 0L) startSitting()
        mode = Mode.READER
        showReader()
    }

    private val taps by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!prefs.getBoolean("taps", true)) return true
                val third = big.width / 3f
                when {
                    e.x < third -> turn(if (rightToLeft) 1 else -1)
                    e.x > big.width - third -> turn(if (rightToLeft) -1 else 1)
                }
                return true
            }
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                // Swipe the page away: a swipe to the right uncovers what's on the left.
                turn(if ((vx > 0) == rightToLeft) 1 else -1)
                return true
            }
        })
    }

    private fun showReader() {
        big.removeAllViews()
        big.setBackgroundColor(Color.WHITE)
        (pageView.parent as? FrameLayout)?.removeView(pageView)
        big.addView(pageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        big.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        embedded?.let { addEmbedded(it) }
        showPage()
    }

    private fun showPage() {
        val b = book ?: run { pageView.setImageBitmap(null); renderControls(); return }
        val key = "$chapterIndex:$page"
        pages.get(key)?.let { pageView.setImageBitmap(it) } ?: run {
            pageView.setImageBitmap(null)
            load(b, page) { if (book === b && this.page == it) pages.get("$chapterIndex:$it")?.let(pageView::setImageBitmap) }
        }
        // Read ahead in the reading direction, and keep the page behind.
        listOf(page + 1, page + 2, page - 1).filter { it in b.pages.indices }.forEach { load(b, it) {} }
        series?.let { s -> progress.at(s, s.chapters[chapterIndex], page, b.pages.size) }
        renderControls()
    }

    private fun load(b: Book, i: Int, done: (Int) -> Unit) {
        val key = "$chapterIndex:$i"
        if (pages.get(key) != null) { done(i); return }
        val w = big.width.takeIf { it > 0 } ?: 1080
        val h = big.height.takeIf { it > 0 } ?: 1920
        io.execute {
            if (book !== b) return@execute
            val bmp = b.decode(i, w, h) ?: return@execute
            main.post { if (book === b) { pages.put(key, bmp); done(i) } }
        }
    }

    private fun go(p: Int) {
        val b = book ?: return
        page = p.coerceIn(0, (b.pages.size - 1).coerceAtLeast(0))
        showPage()
    }

    private fun turn(step: Int) {
        val b = book ?: return
        val next = page + step
        when {
            next in b.pages.indices -> { page = next; showPage() }
            step > 0 -> chapter(1)
            else -> chapter(-1, fromEnd = true)
        }
    }

    private fun chapter(step: Int, fromEnd: Boolean = false) {
        val s = series ?: return
        val i = chapterIndex + step
        if (i !in s.chapters.indices) return
        // Leaving a chapter forwards counts it as read.
        if (step > 0) book?.let { progress.at(s, s.chapters[chapterIndex], it.pages.size - 1, it.pages.size) }
        open(s, i, 0)
        if (fromEnd) go((book?.pages?.size ?: 1) - 1)
    }

    private fun closeBook() {
        endSitting()
        mode = Mode.SHELF
        big.setOnTouchListener(null)
        val lib = library
        Thread { progress.push(lib) }.start()
        showShelf(); renderControls()
    }

    // ---- keys ----------------------------------------------------------------------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val k = e.keyCode
        val rot = held
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (settings) { settings = false; renderControls(); return true }
            if (mode == Mode.READER) { closeBook(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (mode == Mode.READER) {
            Logic.step(k, rot, rightToLeft)?.let { turn(it); return true }
            return super.dispatchKeyEvent(e)
        }
        when (k) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_START -> {
                openSelected(); return true
            }
        }
        when (Logic.onScreen(k, rot)) {
            Logic.Dir.LEFT -> select(selected - 1)
            Logic.Dir.RIGHT -> select(selected + 1)
            Logic.Dir.UP -> select(selected - columns)
            Logic.Dir.DOWN -> select(selected + columns)
            null -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun addEmbedded(t: Turned) {
        (t.parent as? FrameLayout)?.removeView(t)
        big.addView(t, FrameLayout.LayoutParams(MATCH_PARENT, Ui.dp(this, 420), Gravity.BOTTOM))
    }

    private fun askForFiles() {
        big.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 40), Ui.dp(this@MainActivity, 20), 0)
            background = Ui.paper(this@MainActivity, margin = true)
            addView(Ui.band(this@MainActivity, "Tankobon", "Hold the Thor sideways, like a book"), Ui.lp())
            addView(Ui.gap(this@MainActivity, 20))
            addView(Ui.text(this@MainActivity, "To find your manga, Tankobon needs to read the Manga folder on your storage.", 19f), Ui.lp())
            addView(Ui.gap(this@MainActivity, 20))
            addView(Ui.button(this@MainActivity, "Allow file access", Ui.Kind.ORANGE, 20f) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }, Ui.lp())
        }
        big.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        renderControls()
    }

    private fun immersive(w: android.view.Window) {
        w.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
