package app.konspektor

import android.content.Context
import org.json.JSONArray

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

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    }
}
