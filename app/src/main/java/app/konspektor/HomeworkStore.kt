package app.konspektor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Homework(
    val id: Long,
    val subject: String,
    val text: String,
    val done: Boolean,
    val createdMs: Long,
    /** When it is due, as the teacher said it ("к пятнице", "20 ноября"). Filled by Codex. */
    val due: String? = null,
    /** Folder name of the lesson where the teacher gave it. Filled by Codex. */
    val lesson: String? = null,
    /** Added by Codex from the recording (true) or typed by hand (false). */
    val fromCodex: Boolean = false,
)

/**
 * Homework list, kept as Documents/Konspektor/homework.json next to the lessons.
 * Codex adds tasks it hears in the lesson recordings; the app shows them and stores "done".
 */
object HomeworkStore {
    private fun file(context: Context) = File(LessonStore.root(context), "homework.json")

    @Synchronized
    fun all(context: Context): List<Homework> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Homework(
                    id = o.optLong("id"),
                    subject = o.optString("subject").ifBlank { LessonStore.GENERAL },
                    text = o.optString("text"),
                    done = o.optBoolean("done"),
                    createdMs = o.optLong("created"),
                    due = o.optString("due").takeIf { it.isNotBlank() },
                    lesson = o.optString("lesson").takeIf { it.isNotBlank() },
                    fromCodex = o.optString("source") == "codex",
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(context: Context, items: List<Homework>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("subject", it.subject)
                put("text", it.text)
                put("done", it.done)
                put("created", it.createdMs)
                it.due?.let { d -> put("due", d) }
                it.lesson?.let { l -> put("lesson", l) }
                put("source", if (it.fromCodex) "codex" else "manual")
            })
        }
        file(context).writeText(arr.toString(2))
    }

    fun forSubject(context: Context, subject: String) = all(context).filter { it.subject == subject }

    fun add(context: Context, subject: String, text: String) {
        val now = System.currentTimeMillis()
        save(context, all(context) + Homework(now, subject, text.trim(), false, now))
    }

    fun setDone(context: Context, id: Long, done: Boolean) =
        save(context, all(context).map { if (it.id == id) it.copy(done = done) else it })

    fun delete(context: Context, id: Long) = save(context, all(context).filterNot { it.id == id })

    fun deleteSubject(context: Context, subject: String) =
        save(context, all(context).filterNot { it.subject == subject })
}
