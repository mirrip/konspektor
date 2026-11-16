package app.konspektor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Home: big square record button (+ small subject / settings squares), homework, subjects. */
class MainActivity : BaseActivity() {

    private lateinit var button: RecordButton
    private lateinit var subjectSquare: LinearLayout
    private lateinit var subjectIcon: TextView
    private lateinit var subjectName: TextView
    private lateinit var statusText: TextView
    private lateinit var liveCard: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var homeworkCol: LinearLayout
    private lateinit var subjectsCol: LinearLayout
    private lateinit var homeworkTitle: LinearLayout
    private lateinit var subjectsTitle: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var listSignature = ""
    private val listener: () -> Unit = { renderLive() }
    private val ticker = object : Runnable {
        override fun run() {
            renderLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeKeyFromIntent(intent)
        val col = page("Конспектор", Ui.today(), back = false)

        // --- record row: big square + two small squares
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        button = RecordButton(this).apply { setOnClickListener { onButton() } }
        row.addView(button, LinearLayout.LayoutParams(0, dp(212), 1f))
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), 0, dp(6))
        }
        subjectSquare = smallSquare("•", "Общий") { chooseSubject() }
        subjectIcon = subjectSquare.getChildAt(0) as TextView
        subjectName = subjectSquare.getChildAt(1) as TextView
        side.addView(subjectSquare, LinearLayout.LayoutParams(dp(84), 0, 1f))
        side.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        side.addView(smallSquare("⚙", "Настройки") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(84), 0, 1f))
        row.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(212)))
        col.addView(full(row, null, 14))

        statusText = text("", 13.5f, Ui.MUTED).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        col.addView(full(statusText))

        liveCard = card(clickable = true).apply {
            addView(label("СЕЙЧАС В ТЕКСТЕ", Ui.RED))
            liveText = text("", 14.5f, Ui.TEXT).apply { setPadding(0, dp(8), 0, dp(10)) }
            addView(liveText)
            addView(text("Открыть урок  ›", 13.5f, Ui.ACCENT, Ui.MEDIUM))
            setOnClickListener { AppState.lessonDir?.let { openLesson(it.path) } }
        }
        col.addCard(liveCard, 14)

        // --- homework
        homeworkTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(homeworkTitle)
        homeworkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(homeworkCol))

        // --- subjects
        subjectsTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(subjectsTitle)
        subjectsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(subjectsCol))
        col.addView(spacer(28))
    }

    private fun smallSquare(icon: String, name: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(Ui.SURFACE, 22)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(text(icon, 22f, Ui.TEXT, Ui.BOLD).apply { gravity = Gravity.CENTER })
        addView(text(name, 11.5f, Ui.MUTED, Ui.MEDIUM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { onClick() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeKeyFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        listSignature = ""
        renderLive()
        handler.post(ticker)
    }

    override fun onPause() {
        AppState.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Lets the key be set from a computer: adb shell am start -n app.konspektor/.MainActivity --es groq_key gsk_... */
    private fun takeKeyFromIntent(intent: Intent?) {
        val key = intent?.getStringExtra("groq_key")?.trim() ?: return
        if (key.startsWith("gsk_")) {
            prefs.groqKey = key
            toast("Ключ Groq сохранён")
        }
    }

    // ---------------------------------------------------------------- actions

    private fun onButton() {
        if (AppState.recording) RecorderService.stop(this) else requestStart(prefs.currentSubject)
    }

    /** Optional: by default lessons go to "Общий" and Codex sorts them. */
    private fun chooseSubject() {
        if (AppState.recording) { toast("Предмет меняется до начала записи"); return }
        val subjects = Subjects.list(this)
        val items = listOf("Общий — Codex разберёт сам") + subjects + "+ Новый предмет"
        dialog().setTitle("Предмет записи")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> prefs.currentSubject = LessonStore.GENERAL
                    items.size - 1 -> askText("Новый предмет", "Например: Физика", "Добавить") { name ->
                        prefs.currentSubject = Subjects.add(this, name)
                        listSignature = ""
                        renderLive()
                    }
                    else -> prefs.currentSubject = subjects[which - 1]
                }
                renderLive()
            }
            .show()
    }

    private fun addHomework() {
        askText("Новое задание", "Например: №12 и 15 к пятнице", "Дальше") { task ->
            val subjects = Subjects.list(this)
            if (subjects.isEmpty()) {
                HomeworkStore.add(this, LessonStore.GENERAL, task)
                listSignature = ""; renderLive()
                return@askText
            }
            val items = subjects + "Без предмета"
            dialog().setTitle("По какому предмету?")
                .setItems(items.toTypedArray()) { _, which ->
                    HomeworkStore.add(this, if (which < subjects.size) subjects[which] else LessonStore.GENERAL, task)
                    listSignature = ""; renderLive()
                }
                .show()
        }
    }

    private fun openLesson(path: String) =
        startActivity(Intent(this, LessonActivity::class.java).putExtra(LessonActivity.EXTRA_DIR, path))

    private fun openSubject(s: String) =
        startActivity(Intent(this, SubjectActivity::class.java).putExtra(SubjectActivity.EXTRA_SUBJECT, s))

    // ---------------------------------------------------------------- rendering

    /** Cheap updates, called often (timer, mic level, status). */
    private fun renderLive() {
        val rec = AppState.recording
        val subject = if (rec) AppState.lessonDir?.let { LessonStore.subject(it) } ?: "" else prefs.currentSubject
        button.recording = rec
        button.level = AppState.level
        button.subjectText = if (subject == LessonStore.GENERAL) "Общий · Codex разберёт" else subject
        if (rec) button.timerText = Ui.timer((System.currentTimeMillis() - AppState.startedAtMs) / 1000)

        val general = subject == LessonStore.GENERAL
        subjectIcon.text = if (general) "✦" else subject.first().uppercase()
        subjectIcon.setTextColor(if (general) Ui.TEXT else Ui.subjectColor(subject))
        subjectName.text = if (general) "Общий" else subject
        subjectSquare.alpha = if (rec) 0.45f else 1f

        val parts = mutableListOf<String>()
        when {
            rec -> parts += "Можно выключить экран — запись продолжится."
            AppState.serviceRunning -> parts += "Запись остановлена, дорасшифровываю…"
            else -> parts += "Положи телефон поближе к преподавателю."
        }
        if (rec || AppState.serviceRunning) {
            parts += "Расшифровано частей: ${AppState.done}" +
                (if (AppState.queued > 0) " · в очереди ${AppState.queued}" else "") +
                (if (AppState.failed > 0) " · ошибок ${AppState.failed}" else "")
        }
        AppState.lastError?.let { parts += "⚠ $it" }
        statusText.text = parts.joinToString("\n")
        statusText.setTextColor(if (AppState.lastError != null) Ui.AMBER else Ui.MUTED)

        val showLive = (rec || AppState.serviceRunning) && AppState.lessonDir != null
        liveCard.visibility = if (showLive) View.VISIBLE else View.GONE
        if (showLive) {
            val body = AppState.transcript.lines()
                .filterNot { it.startsWith("#") || it.contains("[ждёт расшифровки]") }
                .joinToString(" ").replace(Regex("\\[\\d+:\\d+]"), "").trim()
            liveText.text = if (body.isEmpty()) "Первый текст появится примерно через 5 минут после начала."
            else "…" + body.takeLast(260)
        }

        val sig = "$rec|${AppState.serviceRunning}|${AppState.done}|${AppState.failed}|${AppState.lessonDir}"
        if (sig != listSignature) {
            listSignature = sig
            renderLists()
        }
    }

    private fun renderLists() {
        val lessons = LessonStore.lessons(this)
        renderHomework()
        renderSubjects(lessons)
    }

    private fun renderHomework() {
        val open = HomeworkStore.all(this).filter { !it.done }.sortedBy { it.createdMs }
        homeworkTitle.removeAllViews()
        section(homeworkTitle, "Домашка", if (open.isEmpty()) null else "${open.size}", "+ Задание" to { addHomework() })
        homeworkCol.removeAllViews()
        if (open.isEmpty()) {
            homeworkCol.addView(full(emptyNote("Всё сделано ✓  Новые задания можно добавить кнопкой выше — позже их будет находить Codex."), null, 8))
            return
        }
        val c = card()
        open.forEachIndexed { i, hw ->
            if (i > 0) c.addView(divider())
            c.addView(homeworkRow(hw) { listSignature = ""; renderLive() })
        }
        homeworkCol.addCard(c, 8)
    }

    private fun renderSubjects(lessons: List<LessonInfo>) {
        val subjects = Subjects.list(this, lessons)
        val unsorted = lessons.filter { it.subject == LessonStore.GENERAL }
        subjectsTitle.removeAllViews()
        section(subjectsTitle, "Предметы", if (subjects.isEmpty()) null else "${subjects.size}", "+ Предмет" to {
            askText("Новый предмет", "Например: Физика", "Добавить") { Subjects.add(this, it); listSignature = ""; renderLive() }
        })
        subjectsCol.removeAllViews()

        if (unsorted.isNotEmpty()) {
            subjectsCol.addCard(subjectCard(
                LessonStore.GENERAL, "Неразобранные",
                Ui.plural(unsorted.size, "урок ждёт", "урока ждут", "уроков ждут") + " Codex",
                null
            ), 8)
        }
        if (subjects.isEmpty() && unsorted.isEmpty()) {
            subjectsCol.addView(full(emptyNote(
                "Пока пусто. Записывай уроки — Codex сам разложит их по предметам и даст каждому уроку номер и тему."
            ), null, 8))
            return
        }
        val homework = HomeworkStore.all(this)
        for (s in subjects) {
            val mine = lessons.filter { it.subject == s }
            val konspekts = mine.count { it.hasKonspekt }
            val sub = when {
                mine.isEmpty() -> "Пока нет уроков"
                konspekts > 0 -> Ui.plural(mine.size, "урок", "урока", "уроков") + " · " +
                    Ui.plural(konspekts, "конспект", "конспекта", "конспектов")
                else -> Ui.plural(mine.size, "урок", "урока", "уроков")
            }
            val openHw = homework.count { it.subject == s && !it.done }
            subjectsCol.addCard(subjectCard(s, s, sub, if (openHw > 0) "ДЗ $openHw" else null), 8)
        }
    }

    private fun subjectCard(subject: String, title: String, sub: String, badge: String?) = card(clickable = true).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(subjectBadge(subject))
        val mid = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        mid.addView(text(title, 16.5f, Ui.TEXT, Ui.MEDIUM))
        mid.addView(text(sub, 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (badge != null) addView(chip(badge, Ui.AMBER_SOFT, Ui.AMBER))
        addView(text("›", 24f, Ui.MUTED).apply { setPadding(dp(10), 0, 0, 0) })
        setOnClickListener { openSubject(subject) }
    }
}
