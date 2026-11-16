package app.konspektor

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    var lastSubject: String
        get() = sp.getString("last_subject", "") ?: ""
        set(v) = sp.edit().putString("last_subject", v).apply()

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    }
}
