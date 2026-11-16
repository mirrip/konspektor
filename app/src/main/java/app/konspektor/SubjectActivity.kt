package app.konspektor

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** One subject: its homework and numbered lessons ("Урок 3 · тема"). */
class SubjectActivity : BaseActivity() {

    private lateinit var subject: String
    private lateinit var infoText: TextView
    private lateinit var homeworkTitle: LinearLayout
    private lateinit var homeworkCol: LinearLayout
    private lateinit var lessonsTitle: LinearLayout
    private lateinit var lessonsCol: LinearLayout
    private val listener: () -> Unit = { renderLessons() }
    private var signature = ""

    private val isGeneral get() = subject == LessonStore.GENERAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: run { finish(); return }
        val col = page(
            if (isGeneral) "Неразобранные" else subject,
            "предмет",
            action = if (isGeneral) null else "⋯" to { deleteSubject() }
        )
        infoText = text("", 14f, Ui.MUTED).apply { setPadding(dp(2), dp(10), 0, 0) }
        col.addView(infoText)

        if (!isGeneral) {
            homeworkTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(homeworkTitle)
            homeworkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(full(homeworkCol))
        }

        lessonsTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(lessonsTitle)
        lessonsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(lessonsCol))
        col.addView(spacer(28))
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        signature = ""
        if (!isGeneral) renderHomework()
        renderLessons()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    private fun deleteSubject() {
        val recordingThis = AppState.serviceRunning && AppState.lessonDir?.let { LessonStore.info(it).subject } == subject
        if (recordingThis) { toast("Сначала дождитесь конца записи"); return }
        val n = LessonStore.lessons(this).count { it.subject == subject }
        confirm(
            "Удалить «$subject»?",
            "Удалятся предмет, ${Ui.plural(n, "урок", "урока", "уроков")} (аудио, тексты, конспекты) и домашка по нему. Вернуть будет нельзя.",
            "Удалить"
        ) {
            Subjects.delete(this, subject)
            finish()
        }
    }

    private fun renderHomework() {
        val items = HomeworkStore.forSubject(this, subject).sortedWith(compareBy({ it.done }, { -it.createdMs }))
        val open = items.count { !it.done }
        homeworkTitle.removeAllViews()
        section(homeworkTitle, "Домашка", if (open > 0) "$open" else null, "+ Задание" to {
            askText("Задание по предмету «$subject»", "Например: №12 и 15 к пятнице", "Добавить") {
                HomeworkStore.add(this, subject, it)
                renderHomework()
            }
        })
        homeworkCol.removeAllViews()
        if (items.isEmpty()) {
            homeworkCol.addView(full(emptyNote("Заданий нет. Долгое нажатие на задание удаляет его."), null, 8))
            return
        }
        val c = card()
        items.forEachIndexed { i, hw ->
            if (i > 0) c.addView(divider())
            c.addView(homeworkRow(hw, showSubject = false) { renderHomework() })
        }
        homeworkCol.addCard(c, 8)
    }

    private fun renderLessons() {
        val sig = "${AppState.recording}|${AppState.done}|${AppState.failed}|${AppState.queued}|${AppState.serviceRunning}"
        if (sig == signature) return
        signature = sig

        val lessons = LessonStore.lessons(this).filter { it.subject == subject }
        val total = lessons.sumOf { it.durationSec }
        val konspekts = lessons.count { it.hasKonspekt }
        infoText.text = when {
            lessons.isEmpty() -> "Уроков пока нет."
            isGeneral -> "Эти уроки записаны без предмета. Codex прочитает их, разложит по предметам и даст темы."
            else -> Ui.plural(lessons.size, "урок", "урока", "уроков") + " · " + Ui.duration(total) + " записи · " +
                Ui.plural(konspekts, "конспект", "конспекта", "конспектов")
        }

        lessonsTitle.removeAllViews()
        section(lessonsTitle, "Уроки", if (lessons.isEmpty()) null else "${lessons.size}")
        lessonsCol.removeAllViews()
        if (lessons.isEmpty()) {
            lessonsCol.addView(full(emptyNote("Здесь появятся уроки с номерами и темами."), null, 8))
            return
        }
        for (l in lessons.asReversed()) lessonsCol.addCard(lessonCard(l), 8)
    }

    private fun lessonCard(l: LessonInfo) = card(clickable = true).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val color = Ui.subjectColor(subject)
        addView(text("${l.number}", 18f, color, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.alpha(color, 0.14f), 14)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        })
        val mid = LinearLayout(this@SubjectActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        mid.addView(text(l.topic ?: "Урок ${l.number}", 16f, Ui.TEXT, Ui.MEDIUM).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = (if (l.topic != null) "Урок ${l.number} · " else "") + Ui.dateTime(l.startedMs) + " · " + Ui.duration(l.durationSec)
        mid.addView(text(sub, 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        val live = AppState.recording && AppState.lessonDir == l.dir
        val (lbl, bg, fg) = statusOf(l, live)
        mid.addView(LinearLayout(this@SubjectActivity).apply {
            setPadding(0, dp(8), 0, 0)
            addView(chip(lbl, bg, fg).apply { textSize = 11.5f; setPadding(dp(10), dp(4), dp(10), dp(4)) })
        })
        addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 24f, Ui.MUTED))
        setOnClickListener {
            startActivity(Intent(this@SubjectActivity, LessonActivity::class.java).putExtra(LessonActivity.EXTRA_DIR, l.dir.path))
        }
    }

    companion object {
        const val EXTRA_SUBJECT = "subject"

        fun statusOf(l: LessonInfo, live: Boolean): Triple<String, Int, Int> = when {
            live -> Triple("● идёт запись", Ui.RED_SOFT, Ui.RED)
            l.pending > 0 -> Triple("расшифровка…", Ui.ACCENT_SOFT, Ui.ACCENT)
            l.failed > 0 -> Triple("ошибок: ${l.failed}", Ui.RED_SOFT, Ui.RED)
            l.hasKonspekt -> Triple("конспект готов", Ui.GREEN_SOFT, Ui.GREEN)
            else -> Triple("текст готов · ждёт Codex", Ui.AMBER_SOFT, Ui.AMBER)
        }
    }
}
