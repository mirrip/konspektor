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

    /** Text Whisper reads as if spoken before each piece: style, punctuation, spelling of terms. */
    var transcribeHint: String
        get() = sp.getString("transcribe_hint", null) ?: DEFAULT_HINT
        set(v) = sp.edit().putString("transcribe_hint", v.take(HINT_MAX)).apply()

    /** Cut out phrases Whisper invents on silence (YouTube credits, repeats). */
    var filterHallucinations: Boolean
        get() = sp.getBoolean("filter_hallucinations", true)
        set(v) = sp.edit().putBoolean("filter_hallucinations", v).apply()

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
        get() = readMap("remote_shas")
        set(v) = sp.edit().putString("remote_shas", JSONObject(v).toString()).apply()

    /** Homework ids deleted on the phone that still have to be deleted on GitHub. */
    var deletedHomework: Set<Long>
        get() = (sp.getString("deleted_homework", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putString("deleted_homework", v.joinToString(",")).apply()

    /** Default branch of [githubRepo], remembered after the check in settings. */
    var githubBranch: String
        get() = sp.getString("github_branch", "") ?: ""
        set(v) = sp.edit().putString("github_branch", v).apply()

    /** Commit the phone has already taken changes from; the next sync asks GitHub only for what changed after it. */
    var lastHead: String
        get() = sp.getString("last_head", "") ?: ""
        set(v) = sp.edit().putString("last_head", v).apply()

    /** lessonId → path of the konspekt.md already fetched for it. */
    var konspektPaths: Map<String, String>
        get() = readMap("konspekt_paths")
        set(v) = sp.edit().putString("konspekt_paths", JSONObject(v).toString()).apply()

    /** Another repository or key: start sync from scratch. */
    fun resetSync() = sp.edit()
        .remove("github_branch").remove("last_head").remove("remote_shas").remove("konspekt_paths")
        .apply()

    private fun readMap(key: String): Map<String, String> = try {
        val o = JSONObject(sp.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_REPO = "mirrip/konspektor-data"
        const val DEFAULT_HINT = "Урок в школе. Учитель объясняет новую тему, ученики отвечают на вопросы."
        const val HINT_MAX = 250
    }
}
