package app.konspektor

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lesson folder layout (Documents/Konspektor/<lesson>/):
 *   meta.txt            subject and start time
 *   audio/chunk_001.m4a  recorded pieces (5 min, overlapping by a few seconds)
 *   parts/chunk_001.txt  transcription of each piece (or chunk_001.failed)
 *   stopped             recording finished
 *   transcript.txt      merged text, rebuilt after every piece
 *   done.txt            everything is transcribed: the PC side can pick the lesson up
 */
object LessonStore {
    const val CHUNK_SEC = 300
    const val OVERLAP_SEC = 3

    fun root(context: Context): File {
        @Suppress("DEPRECATION")
        val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Konspektor")
        if (isWritable(shared)) return shared
        val private = File(context.getExternalFilesDir(null) ?: context.filesDir, "Konspektor")
        private.mkdirs()
        return private
    }

    private fun isWritable(dir: File): Boolean = try {
        dir.mkdirs()
        val probe = File(dir, ".probe")
        probe.writeText("ok")
        probe.delete()
        true
    } catch (_: Exception) {
        false
    }

    fun newLesson(context: Context, subject: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val clean = subject.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).trim('_')
        val name = if (clean.isEmpty()) stamp else "${stamp}_$clean"
        var dir = File(root(context), name)
        var i = 2
        while (dir.exists()) dir = File(root(context), "${name}_${i++}")
        File(dir, "audio").mkdirs()
        File(dir, "parts").mkdirs()
        val started = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(dir, "meta.txt").writeText("subject=${subject.trim()}\nstarted=$started\n")
        return dir
    }

    fun chunkFile(lesson: File, index: Int) = File(File(lesson, "audio"), "chunk_%03d.m4a".format(index))
    fun lessonOf(chunk: File): File = chunk.parentFile!!.parentFile!!
    private fun partBase(chunk: File) = File(File(lessonOf(chunk), "parts"), chunk.nameWithoutExtension)
    fun textFile(chunk: File) = File(partBase(chunk).path + ".txt")
    fun failedFile(chunk: File) = File(partBase(chunk).path + ".failed")

    fun isPending(chunk: File) = !textFile(chunk).exists() && !failedFile(chunk).exists()

    fun chunks(lesson: File): List<File> =
        File(lesson, "audio").listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.name } ?: emptyList()

    fun lessons(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory && File(f, "meta.txt").exists() }?.sortedBy { it.name } ?: emptyList()

    fun subject(lesson: File): String =
        File(lesson, "meta.txt").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("subject=") }?.removePrefix("subject=").orEmpty()

    fun markStopped(lesson: File) = File(lesson, "stopped").writeText("1")
    fun isStopped(lesson: File) = File(lesson, "stopped").exists()

    /** Writes done.txt once a stopped lesson has nothing left to transcribe. */
    fun finishIfComplete(lesson: File): Boolean {
        if (!isStopped(lesson) || File(lesson, "done.txt").exists()) return false
        val all = chunks(lesson)
        if (all.any { isPending(it) }) return false
        val failed = all.count { failedFile(it).exists() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(lesson, "done.txt").writeText("finished=$stamp\nchunks=${all.size}\nfailed=$failed\n")
        return true
    }

    /** Rebuilds transcript.txt from the parts, removing text repeated in the overlaps. */
    fun rebuildTranscript(lesson: File): String {
        val all = chunks(lesson)
        val meta = File(lesson, "meta.txt").takeIf { it.exists() }?.readLines().orEmpty()
        val started = meta.firstOrNull { it.startsWith("started=") }?.removePrefix("started=").orEmpty()
        val sb = StringBuilder()
        sb.append("# ").append(subject(lesson).ifEmpty { "Без темы" }).append('\n')
        sb.append("# Начало: ").append(started).append('\n')
        sb.append("# Частей: ").append(all.size)
            .append(", не расшифровано: ").append(all.count { failedFile(it).exists() }).append("\n\n")

        var prev = ""
        all.forEachIndexed { i, chunk ->
            val offsetSec = i * (CHUNK_SEC - OVERLAP_SEC)
            val stamp = "[%02d:%02d]".format(offsetSec / 60, offsetSec % 60)
            val txt = textFile(chunk)
            when {
                txt.exists() -> {
                    val raw = txt.readText().trim()
                    val body = if (prev.isEmpty()) raw else removeOverlap(prev, raw)
                    sb.append(stamp).append(' ').append(body).append("\n\n")
                    prev = raw
                }
                failedFile(chunk).exists() -> {
                    sb.append(stamp).append(" [не расшифровано, аудио: audio/").append(chunk.name).append("]\n\n")
                    prev = ""
                }
                else -> {
                    sb.append(stamp).append(" [ждёт расшифровки]\n\n")
                    prev = ""
                }
            }
        }
        val text = sb.toString()
        File(lesson, "transcript.txt").writeText(text)
        return text
    }

    /** Drops the first words of [next] that repeat the last words of [prev]. */
    fun removeOverlap(prev: String, next: String): String {
        val a = prev.split(Regex("\\s+")).filter { it.isNotBlank() }
        val b = next.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun norm(w: String) = w.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        for (skip in 0..2) {                       // the first word of a chunk is often cut in half
            val maxK = minOf(25, a.size, b.size - skip)
            for (k in maxK downTo 2) {
                val match = (0 until k).all { norm(a[a.size - k + it]) == norm(b[skip + it]) }
                if (match) return b.drop(skip + k).joinToString(" ")
            }
        }
        return next
    }
}
