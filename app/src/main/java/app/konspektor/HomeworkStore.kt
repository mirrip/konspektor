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
)

/**
 * Homework list, kept as Documents/Konspektor/homework.json next to the lessons,
 * so it travels to the PC together with them (and Codex can add tasks there later).
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
                    subject = o.optString("subject"),
                    text = o.optString("text"),
                    done = o.optBoolean("done"),
                    createdMs = o.optLong("created"),
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
            })
        }
        file(context).writeText(arr.toString(2))
    }

    fun forSubject(context: Context, subject: String) = all(context).filter { it.subject == subject }

    fun openCount(context: Context, subject: String) = forSubject(context, subject).count { !it.done }

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
