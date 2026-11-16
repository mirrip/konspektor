package app.konspektor

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screens need to know about one recorded lesson. */
data class LessonInfo(
    val dir: File,
    /** Subject from Codex (info.json) if it has sorted the lesson, otherwise the one chosen when recording. */
    val subject: String,
    /** What the lesson was about, written by Codex. */
    val topic: String?,
    /** Codex has looked at this lesson (info.json exists). */
    val sorted: Boolean,
    val number: Int,
    val startedMs: Long,
    val durationSec: Long,
    val chunks: Int,
    val pending: Int,
    val failed: Int,
    val stopped: Boolean,
    val hasKonspekt: Boolean,
) {
    val isDone get() = stopped && pending == 0
}

/**
 * Lesson folder layout (Documents/Konspektor/<lesson>/):
 *   meta.txt             subject, lesson number, start time
 *   audio/chunk_001.m4a  recorded pieces (5 min, overlapping by a few seconds)
 *   parts/chunk_001.txt  transcription of each piece (or chunk_001.failed)
 *   stopped              recording finished (holds the end time)
 *   transcript.txt       merged text, rebuilt after every piece
 *   done.txt             everything is transcribed: the PC side can pick the lesson up
 *   konspekt.md          notes written later (by Codex on the PC); shown in the app if present
 *   info.json            written by Codex: {"subject": "Физика", "topic": "Законы Ньютона"}
 */
object LessonStore {
    const val CHUNK_SEC = 300
    const val OVERLAP_SEC = 3
    /** Recorded without choosing a subject; Codex decides later. */
    const val GENERAL = "Общий"
    const val NO_SUBJECT = GENERAL

    @Volatile private var cachedRoot: File? = null

    fun root(context: Context): File {
        cachedRoot?.let { if (it.exists()) return it }
        @Suppress("DEPRECATION")
        val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Konspektor")
        val dir = if (isWritable(shared)) shared else
            File(context.getExternalFilesDir(null) ?: context.filesDir, "Konspektor").apply { mkdirs() }
        cachedRoot = dir
        return dir
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

    // ---------------------------------------------------------------- lessons

    fun newLesson(context: Context, subject: String): File {
        val subj = subject.trim().ifEmpty { GENERAL }
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(now)
        val clean = subj.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).trim('_')
        val name = "${stamp}_${clean}"
        var dir = File(root(context), name)
        var i = 2
        while (dir.exists()) dir = File(root(context), "${name}_${i++}")
        File(dir, "audio").mkdirs()
        File(dir, "parts").mkdirs()
        val started = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)
        File(dir, "meta.txt").writeText("subject=$subj\nstarted=$started\nstarted_ms=${now.time}\n")
        return dir
    }

    fun lessonDirs(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory && File(f, "meta.txt").exists() }?.sortedBy { it.name } ?: emptyList()

    /** All lessons, oldest first, numbered 1, 2, 3… inside their (final) subject. */
    fun lessons(context: Context): List<LessonInfo> {
        val counters = HashMap<String, Int>()
        return lessonDirs(context).map { info(it) }.sortedBy { it.startedMs }.map { l ->
            val n = (counters[l.subject] ?: 0) + 1
            counters[l.subject] = n
            l.copy(number = n)
        }
    }

    fun number(context: Context, dir: File): Int = lessons(context).firstOrNull { it.dir == dir }?.number ?: 0

    private fun codexInfo(dir: File): JSONObject? = File(dir, "info.json").takeIf { it.exists() }?.let {
        try { JSONObject(it.readText()) } catch (_: Exception) { null }
    }

    fun info(dir: File): LessonInfo {
        val meta = readMeta(dir)
        val all = chunks(dir)
        val started = meta["started_ms"]?.toLongOrNull()
            ?: meta["started"]?.let { runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(it)?.time }.getOrNull() }
            ?: dir.lastModified()
        val stoppedFile = File(dir, "stopped")
        val ended = stoppedFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        val duration = when {
            ended != null && ended > started -> (ended - started) / 1000
            all.isNotEmpty() -> (all.size - 1L) * (CHUNK_SEC - OVERLAP_SEC) + CHUNK_SEC / 2
            else -> 0L
        }
        val codex = codexInfo(dir)
        val codexSubject = codex?.optString("subject")?.trim().orEmpty()
        val topic = codex?.optString("topic")?.trim()?.takeIf { it.isNotEmpty() }
        return LessonInfo(
            dir = dir,
            subject = codexSubject.ifEmpty { meta["subject"].orEmpty().ifEmpty { GENERAL } },
            topic = topic,
            sorted = codex != null,
            number = 0,
            startedMs = started,
            durationSec = duration,
            chunks = all.size,
            pending = all.count { isPending(it) },
            failed = all.count { failedFile(it).exists() },
            stopped = stoppedFile.exists(),
            hasKonspekt = File(dir, "konspekt.md").exists(),
        )
    }

    private fun readMeta(dir: File): Map<String, String> =
        File(dir, "meta.txt").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            ?.toMap() ?: emptyMap()

    /** Subject chosen when recording (before Codex sorts the lesson). */
    fun subject(lesson: File): String = readMeta(lesson)["subject"].orEmpty().ifEmpty { GENERAL }

    fun deleteLesson(dir: File) = dir.deleteRecursively()

    fun transcriptText(dir: File): String = File(dir, "transcript.txt").takeIf { it.exists() }?.readText().orEmpty()
    fun konspektText(dir: File): String? = File(dir, "konspekt.md").takeIf { it.exists() }?.readText()

    // ---------------------------------------------------------------- chunks

    fun chunkFile(lesson: File, index: Int) = File(File(lesson, "audio"), "chunk_%03d.m4a".format(index))
    fun lessonOf(chunk: File): File = chunk.parentFile!!.parentFile!!
    private fun partBase(chunk: File) = File(File(lessonOf(chunk), "parts"), chunk.nameWithoutExtension)
    fun textFile(chunk: File) = File(partBase(chunk).path + ".txt")
    fun failedFile(chunk: File) = File(partBase(chunk).path + ".failed")

    fun isPending(chunk: File) = !textFile(chunk).exists() && !failedFile(chunk).exists()

    fun chunks(lesson: File): List<File> =
        File(lesson, "audio").listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.name } ?: emptyList()

    fun markStopped(lesson: File) {
        val f = File(lesson, "stopped")
        if (!f.exists()) f.writeText(System.currentTimeMillis().toString())
    }

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
        val meta = readMeta(lesson)
        val sb = StringBuilder()
        sb.append("# ").append(subject(lesson)).append('\n')
        sb.append("# Начало: ").append(meta["started"].orEmpty()).append('\n')
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
