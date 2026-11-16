package app.konspektor

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

/** One subject: record button, homework list and the numbered lessons. */
class SubjectActivity : BaseActivity() {

    private lateinit var subject: String
    private lateinit var homeworkBox: LinearLayout
    private lateinit var lessonsBox: LinearLayout
    private lateinit var infoText: android.widget.TextView
    private lateinit var recordButton: android.widget.Button
    private val listener: () -> Unit = { renderLessons() }
    private var signature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: run { finish(); return }
        val col = page(subject, null, action = "🗑" to { deleteSubject() })

        infoText = text("", 14f, Ui.MUTED).apply { setPadding(dp(2), dp(4), 0, 0) }
        col.addView(infoText)

        recordButton = primaryButton("●  Записать урок") { onRecord() }
        col.addView(full(recordButton, 54, 14))

        section(col, "Домашка")
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(android.graphics.Color.WHITE, 14, Ui.LINE)
            setPadding(dp(12), dp(2), dp(6), dp(2))
        }
        val input = EditText(this).apply {
            hint = "Новое задание: например, №12 и 15 к пятнице"
            background = null
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        fun add() {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return
            HomeworkStore.add(this, subject, t)
            input.setText("")
            renderHomework()
        }
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(pill("Добавить", Ui.CHIP_BG, Ui.PRIMARY) { add() })
        col.addView(full(addRow, null, 8))
        homeworkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(homeworkBox))

        section(col, "Уроки")
        lessonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(lessonsBox))
        col.addView(spacer(24))
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        signature = ""
        renderHomework()
        renderLessons()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    private fun onRecord() {
        if (AppState.recording) RecorderService.stop(this) else requestStart(subject)
    }

    private fun deleteSubject() {
        val recordingThis = AppState.recording && AppState.lessonDir?.let { LessonStore.subject(it) } == subject
        if (recordingThis) { toast("Сначала остановите запись"); return }
        val n = LessonStore.lessons(this).count { it.subject == subject }
        confirm(
            "Удалить «$subject»?",
            "Удалятся предмет, ${Ui.plural(n, "урок", "урока", "уроков")} (аудио и тексты) и домашка по нему. Вернуть будет нельзя.",
            "Удалить"
        ) {
            Subjects.delete(this, subject)
            finish()
        }
    }

    private fun renderHomework() {
        homeworkBox.removeAllViews()
        val items = HomeworkStore.forSubject(this, subject).sortedWith(compareBy({ it.done }, { it.createdMs }))
        if (items.isEmpty()) {
            homeworkBox.addView(text("Пока пусто. Задание можно удалить долгим нажатием.", 13f, Ui.MUTED).apply {
                setPadding(dp(4), dp(8), 0, 0)
            })
            return
        }
        val c = card()
        items.forEachIndexed { i, hw ->
            val box = CheckBox(this).apply {
                text = hw.text
                textSize = 15f
                isChecked = hw.done
                setTextColor(if (hw.done) Ui.MUTED else Ui.TEXT)
                if (hw.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.subjectColor(subject))
                setPadding(dp(4), if (i == 0) 0 else dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, checked ->
                    HomeworkStore.setDone(this@SubjectActivity, hw.id, checked)
                    renderHomework()
                }
                setOnLongClickListener {
                    confirm("Удалить задание?", hw.text, "Удалить") {
                        HomeworkStore.delete(this@SubjectActivity, hw.id)
                        renderHomework()
                    }
                    true
                }
            }
            c.addView(box)
        }
        homeworkBox.addCard(c, 8)
    }

    private fun renderLessons() {
        val recordingThis = AppState.recording && AppState.lessonDir?.let { LessonStore.subject(it) } == subject
        recordButton.text = if (recordingThis) "■  Остановить запись" else "●  Записать урок"
        recordButton.background = rounded(if (recordingThis) Ui.RED else Ui.PRIMARY, 14)
        recordButton.isEnabled = recordingThis || !AppState.recording

        val sig = "${AppState.recording}|${AppState.done}|${AppState.failed}|${AppState.queued}|${AppState.serviceRunning}"
        if (sig == signature) return
        signature = sig

        val lessons = LessonStore.lessons(this).filter { it.subject == subject }
        val total = lessons.sumOf { it.durationSec }
        infoText.text = if (lessons.isEmpty()) "Уроков пока нет" else
            Ui.plural(lessons.size, "урок", "урока", "уроков") + " · всего записано " + Ui.duration(total)

        lessonsBox.removeAllViews()
        if (lessons.isEmpty()) {
            lessonsBox.addView(text("Нажми «Записать урок» — он появится здесь под номером 1.", 13f, Ui.MUTED).apply {
                setPadding(dp(4), dp(8), 0, 0)
            })
            return
        }
        for (l in lessons.asReversed()) {
            val c = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    startActivity(Intent(this@SubjectActivity, LessonActivity::class.java).putExtra(LessonActivity.EXTRA_DIR, l.dir.path))
                }
            }
            val num = text("${l.number}", 18f, Ui.subjectColor(subject), bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Ui.CHIP_BG, 12)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }
            c.addView(num)
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            mid.addView(text("Урок ${l.number}", 16f, Ui.TEXT, bold = true))
            mid.addView(text(Ui.dateTime(l.startedMs) + " · " + Ui.duration(l.durationSec), 13f, Ui.MUTED))
            c.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val live = AppState.recording && AppState.lessonDir == l.dir
            val (label, bg, fg) = statusOf(l, live)
            c.addView(pill(label, bg, fg) {}.apply { setOnClickListener(null); isClickable = false; textSize = 12f })
            lessonsBox.addCard(c, 8)
        }
    }

    companion object {
        const val EXTRA_SUBJECT = "subject"

        fun statusOf(l: LessonInfo, live: Boolean): Triple<String, Int, Int> = when {
            live -> Triple("● запись", Ui.RED_BG, Ui.RED)
            l.pending > 0 -> Triple("расшифровка…", Ui.CHIP_BG, Ui.PRIMARY)
            l.failed > 0 -> Triple("ошибок: ${l.failed}", Ui.RED_BG, Ui.RED)
            l.hasKonspekt -> Triple("конспект ✓", Ui.GREEN_BG, Ui.GREEN)
            else -> Triple("текст готов", Ui.AMBER_BG, Ui.AMBER)
        }
    }
}
