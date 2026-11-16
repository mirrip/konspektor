package app.konspektor

import android.content.Context

object Subjects {
    /**
     * Real subjects: ones the user added plus ones Codex assigned to lessons.
     * "Общий" is not a subject: those lessons wait for Codex to sort them.
     */
    fun list(context: Context, lessons: List<LessonInfo> = LessonStore.lessons(context)): List<String> {
        val saved = Prefs(context).subjects
        val fromLessons = lessons.map { it.subject }.distinct()
        return (saved + fromLessons.filter { it !in saved }).filter { it != LessonStore.GENERAL }
    }

    fun add(context: Context, name: String): String {
        val prefs = Prefs(context)
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotEmpty() && clean != LessonStore.GENERAL && clean !in prefs.subjects) prefs.subjects = prefs.subjects + clean
        return clean
    }

    /** Removes the subject together with its lessons and homework. */
    fun delete(context: Context, name: String) {
        val prefs = Prefs(context)
        prefs.subjects = prefs.subjects.filterNot { it == name }
        LessonStore.lessons(context).filter { it.subject == name }.forEach { LessonStore.deleteLesson(it.dir) }
        HomeworkStore.deleteSubject(context, name)
        if (prefs.currentSubject == name) prefs.currentSubject = LessonStore.GENERAL
    }
}
