package com.tankobon.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.ZipFile

/** A chapter or volume: a .cbz/.zip, or a folder of images. */
class Chapter(val name: String, val path: String) {
    val title get() = Logic.clean(name)
    val isArchive get() = Logic.isArchive(name)
}

/** A series folder and its chapters in reading order. */
class Series(val name: String, val path: String, val chapters: List<Chapter>) {
    val title get() = Logic.clean(name)
}

/**
 * Where the manga lives: a "Manga" (or "Comics") folder at the top of internal storage or
 * the SD card. Each folder in it is a series; each .cbz/.zip or image folder in that is a
 * chapter. Loose .cbz files at the top level are one-book series of their own.
 */
object Library {

    private val FOLDERS = listOf("Manga", "manga", "MANGA", "Comics", "comics", "Books/Manga")

    fun roots(): List<File> {
        val vols = listOf(File("/storage/emulated/0")) +
            (File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" } ?: emptyList())
        return vols.flatMap { v -> FOLDERS.map { File(v, it) } }.filter { it.isDirectory }
            .distinctBy { it.canonicalPath.lowercase() }
    }

    fun scan(): List<Series> {
        val out = mutableListOf<Series>()
        for (root in roots()) {
            for (f in root.listFiles() ?: emptyArray()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) series(f)?.let(out::add)
                else if (Logic.isArchive(f.name)) out += Series(f.name, f.path, listOf(Chapter(f.name, f.path)))
            }
        }
        // The same series on internal storage and the SD card shows once.
        return out.distinctBy { it.name.lowercase() }.sortedWith(compareBy(Logic.natural) { it.title })
    }

    private fun series(dir: File): Series? {
        val files = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return null
        val chapters = mutableListOf<Chapter>()
        for (f in files) {
            if (f.isFile && Logic.isArchive(f.name)) chapters += Chapter(f.name, f.path)
            else if (f.isDirectory && f.listFiles()?.any { Logic.isImage(it.name) } == true) chapters += Chapter(f.name, f.path)
        }
        // Images straight in the series folder: one chapter.
        if (files.any { it.isFile && Logic.isImage(it.name) }) chapters += Chapter(dir.name, dir.path)
        if (chapters.isEmpty()) return null
        return Series(dir.name, dir.path, chapters.sortedWith(compareBy(Logic.natural) { it.name }))
    }
}

/** An open chapter: its page list, and a decoder that keeps the archive open while reading. */
class Book(val chapter: Chapter) : AutoCloseable {
    private val zip: ZipFile? = if (chapter.isArchive) ZipFile(chapter.path) else null
    val pages: List<String> = if (zip != null) {
        zip.entries().asSequence().filter { !it.isDirectory && Logic.isImage(it.name) && !it.name.contains("__MACOSX") }
            .map { it.name }.sortedWith(Logic.natural).toList()
    } else {
        (File(chapter.path).listFiles()?.filter { it.isFile && Logic.isImage(it.name) } ?: emptyList())
            .map { it.path }.sortedWith(Logic.natural)
    }

    /** Page [i] shrunk to about [maxW]×[maxH], or null if it can't be read. */
    @Synchronized
    fun decode(i: Int, maxW: Int, maxH: Int): Bitmap? = try {
        val bytes = bytes(i) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = Logic.sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun bytes(i: Int): ByteArray? {
        val name = pages.getOrNull(i) ?: return null
        return if (zip != null) zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        else File(name).readBytes()
    }

    override fun close() { try { zip?.close() } catch (_: Exception) {} }
}
