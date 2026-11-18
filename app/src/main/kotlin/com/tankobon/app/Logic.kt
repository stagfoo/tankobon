package com.tankobon.app

import android.view.KeyEvent

/** Pure helpers (no Android state), so they can be unit-tested. */
object Logic {

    val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
    val ARCHIVES = setOf("cbz", "zip")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String) = ext(name) in IMAGES && !name.substringAfterLast('/').startsWith(".")
    fun isArchive(name: String) = ext(name) in ARCHIVES

    private fun bare(n: String) = if (ext(n) in IMAGES || ext(n) in ARCHIVES) n.substringBeforeLast('.') else n

    /** "Chapter 2" before "Chapter 10": digit runs compare as numbers. */
    val natural = Comparator<String> { a, b ->
        // Extensions off, so "Chapter 2.cbz" sorts before "Chapter 2.5.cbz".
        val x = bare(a).lowercase(); val y = bare(b).lowercase()
        var i = 0; var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                val si = i; while (i < x.length && x[i].isDigit()) i++
                val sj = j; while (j < y.length && y[j].isDigit()) j++
                val ni = x.substring(si, i).trimStart('0'); val nj = y.substring(sj, j).trimStart('0')
                if (ni.length != nj.length) return@Comparator ni.length - nj.length
                val c = ni.compareTo(nj); if (c != 0) return@Comparator c
            } else {
                if (x[i] != y[j]) return@Comparator x[i] - y[j]
                i++; j++
            }
        }
        (x.length - i) - (y.length - j)
    }

    /** "[Group] Chainsaw Man (Digital) v01.cbz" -> "Chainsaw Man v01"; folder and file names to show. */
    fun clean(name: String): String = name
        .let { if (isArchive(it)) it.substringBeforeLast('.') else it }
        .replace(Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}"""), " ")
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ").trim()
        .ifEmpty { name }

    /**
     * Degrees (clockwise) to turn the small screen's content so it stands the same way up as the
     * big screen. A display at rotation R draws its content turned R×90° clockwise on the panel;
     * both Thor panels are mounted the same way, so matching the two angles lines them up.
     */
    fun controlsRotation(bigRotation: Int, smallRotation: Int, flip: Boolean): Int {
        var d = ((bigRotation - smallRotation) * 90 + (if (flip) 180 else 0)) % 360
        if (d < 0) d += 360
        return if (d == 270) -90 else d
    }

    enum class Dir { UP, RIGHT, DOWN, LEFT }

    /**
     * The d-pad doesn't turn with the picture, so in book mode "up" on the pad points sideways
     * on screen. Clamshell is rotation 1 (landscape); every quarter turn away from it moves the
     * pad's directions a quarter turn the other way on screen.
     */
    fun onScreen(key: Int, bigRotation: Int): Dir? {
        val pad = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_DOWN -> 2; KeyEvent.KEYCODE_DPAD_LEFT -> 3
            else -> return null
        }
        return Dir.entries[((pad + (1 - bigRotation)) % 4 + 4) % 4]
    }

    /** Page step for a key: +1 next, -1 previous, null for keys that don't turn pages. */
    fun step(key: Int, bigRotation: Int, rightToLeft: Boolean): Int? {
        when (key) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_DOWN -> return 1
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP -> return -1
        }
        return when (onScreen(key, bigRotation)) {
            Dir.LEFT -> if (rightToLeft) 1 else -1
            Dir.RIGHT -> if (rightToLeft) -1 else 1
            Dir.DOWN -> 1
            Dir.UP -> -1
            null -> null
        }
    }

    /** Largest power-of-two shrink that still covers the target size. */
    fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var s = 1
        while (w / (s * 2) >= maxW && h / (s * 2) >= maxH) s *= 2
        return s
    }
}
