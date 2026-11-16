package app.konspektor

import android.content.Context

object Subjects {
    /** The school subjects; Codex sorts lessons into exactly these (see AGENTS.md in konspektor-data). */
    val KNOWN = listOf(
        "Английский язык", "Русский язык", "Литературное чтение", "История", "Обществознание",
        "Химия", "Биология", "География", "Информатика", "Алгебра", "Геометрия",
        "Вероятность и статистика", "Физика",
    )

    /** For the manual subject picker: the known subjects plus any added by hand. */
    fun choices(context: Context): List<String> = KNOWN + list(context).filter { it !in KNOWN }

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
