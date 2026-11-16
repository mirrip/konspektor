package app.konspektor

import android.content.Context

object Subjects {
    /** Subjects the user added, plus any that only exist in recorded lessons (nothing gets hidden). */
    fun list(context: Context): List<String> {
        val prefs = Prefs(context)
        val saved = prefs.subjects
        val fromLessons = LessonStore.lessons(context).map { it.subject }.distinct()
        return saved + fromLessons.filter { it !in saved }
    }

    fun add(context: Context, name: String): String {
        val prefs = Prefs(context)
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotEmpty() && clean !in prefs.subjects) prefs.subjects = prefs.subjects + clean
        return clean
    }

    /** Removes the subject together with its lessons and homework. */
    fun delete(context: Context, name: String) {
        val prefs = Prefs(context)
        prefs.subjects = prefs.subjects.filterNot { it == name }
        LessonStore.lessons(context).filter { it.subject == name }.forEach { LessonStore.deleteLesson(it.dir) }
        HomeworkStore.deleteSubject(context, name)
        if (prefs.currentSubject == name) prefs.currentSubject = ""
    }
}
