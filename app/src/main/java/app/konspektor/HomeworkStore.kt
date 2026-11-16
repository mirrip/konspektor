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
    /** Lesson id where the teacher gave it. Filled by Codex. */
    val lesson: String? = null,
    /** Added by Codex from the recording (true) or typed by hand (false). */
    val fromCodex: Boolean = false,
    /** Changed on the phone and not yet sent to GitHub. */
    val dirty: Boolean = false,
    /** Exists on GitHub (homework/<id>.json). */
    val synced: Boolean = false,
) {
    /** The file stored in the repository (no phone-only fields). */
    fun toRepoJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("subject", subject)
        put("text", text)
        due?.let { put("due", it) }
        lesson?.let { put("lesson", it) }
        put("source", if (fromCodex) "codex" else "manual")
        put("done", done)
        put("created", createdMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = Homework(
            id = o.optLong("id"),
            subject = o.optString("subject").ifBlank { LessonStore.GENERAL },
            text = o.optString("text"),
            done = o.optBoolean("done"),
            createdMs = o.optLong("created"),
            due = o.optString("due").takeIf { it.isNotBlank() },
            lesson = o.optString("lesson").takeIf { it.isNotBlank() },
            fromCodex = o.optString("source") == "codex",
            dirty = o.optBoolean("dirty"),
            synced = o.optBoolean("synced"),
        )
    }
}

/**
 * Homework list on the phone (Documents/Konspektor/homework.json).
 * Codex adds tasks it hears in the recordings (via GitHub); the phone stores "done" and sends it back.
 */
object HomeworkStore {
    private fun file(context: Context) = File(LessonStore.root(context), "homework.json")

    @Synchronized
    fun all(context: Context): List<Homework> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Homework.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, items: List<Homework>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toRepoJson().put("dirty", it.dirty).put("synced", it.synced)) }
        file(context).writeText(arr.toString(2))
    }

    fun forSubject(context: Context, subject: String) = all(context).filter { it.subject == subject }

    @Synchronized
    fun add(context: Context, subject: String, text: String) {
        val now = System.currentTimeMillis()
        save(context, all(context) + Homework(now, subject, text.trim(), false, now, dirty = true))
        SyncManager.requestSync(context)
    }

    @Synchronized
    fun setDone(context: Context, id: Long, done: Boolean) {
        save(context, all(context).map { if (it.id == id) it.copy(done = done, dirty = true) else it })
        SyncManager.requestSync(context)
    }

    @Synchronized
    fun delete(context: Context, id: Long) {
        val items = all(context)
        if (items.firstOrNull { it.id == id }?.synced == true) {
            val p = Prefs(context)
            p.deletedHomework = p.deletedHomework + id
        }
        save(context, items.filterNot { it.id == id })
        SyncManager.requestSync(context)
    }

    @Synchronized
    fun deleteSubject(context: Context, subject: String) {
        all(context).filter { it.subject == subject }.forEach { delete(context, it.id) }
    }
}
