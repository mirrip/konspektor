package app.konspektor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    /** Subject the big button records into; "Общий" means Codex sorts it later. */
    var currentSubject: String
        get() = (sp.getString("current_subject", null) ?: LessonStore.GENERAL).ifBlank { LessonStore.GENERAL }
        set(v) = sp.edit().putString("current_subject", v).apply()

    var subjects: List<String>
        get() = try {
            val arr = JSONArray(sp.getString("subjects", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("subjects", JSONArray(v).toString()).apply()

    // ---------------------------------------------------------------- GitHub sync

    /** Fine-grained token with Contents read/write on [githubRepo] only. */
    var githubToken: String
        get() = sp.getString("github_token", "") ?: ""
        set(v) = sp.edit().putString("github_token", v.trim()).apply()

    /** "owner/name" of the data repository. */
    var githubRepo: String
        get() = (sp.getString("github_repo", null) ?: DEFAULT_REPO).ifBlank { DEFAULT_REPO }
        set(v) = sp.edit().putString("github_repo", v.trim().trim('/')).apply()

    var lastSyncMs: Long
        get() = sp.getLong("last_sync_ms", 0L)
        set(v) = sp.edit().putLong("last_sync_ms", v).apply()

    /** Short random id of this phone, so lessons from two phones never collide. */
    val deviceId: String
        get() {
            sp.getString("device_id", null)?.let { return it }
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val id = (1..4).map { chars.random() }.joinToString("")
            sp.edit().putString("device_id", id).apply()
            return id
        }

    /** Blob SHAs of files already downloaded from GitHub (path → sha). */
    var remoteShas: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString("remote_shas", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
        set(v) = sp.edit().putString("remote_shas", JSONObject(v).toString()).apply()

    /** Homework ids deleted on the phone that still have to be deleted on GitHub. */
    var deletedHomework: Set<Long>
        get() = (sp.getString("deleted_homework", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putString("deleted_homework", v.joinToString(",")).apply()

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_REPO = "mirrip/konspektor-data"
    }
}
