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

        // --- small controls above the big button: subject on the left, settings on the right
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subjectSquare = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(Ui.SURFACE, 16)
            setPadding(dp(12), 0, dp(16), 0)
            setOnClickListener { chooseSubject() }
        }
        subjectIcon = text("✦", 16f, Ui.TEXT, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.SURFACE2, 10)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        subjectName = text("Общий", 14.5f, Ui.TEXT, Ui.MEDIUM).apply {
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subjectSquare.addView(subjectIcon)
        subjectSquare.addView(subjectName)
        controls.addView(subjectSquare, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(iconButton("⚙") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.apply { background = pressable(Ui.SURFACE, 16) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        col.addView(full(controls, null, 18))

        // --- the big full-width on/off button
        button = RecordButton(this).apply { setOnClickListener { onButton() } }
        col.addView(full(button, 236, 12))

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
        val items = listOf("Общий") + subjects + "+ Новый предмет"
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
        button.subjectText = if (subject == LessonStore.GENERAL) "" else subject
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

    /** Homework is its own section: one card here, the full list opens on tap. */
    private fun renderHomework() {
        val open = HomeworkStore.all(this).filter { !it.done }.sortedBy { it.createdMs }
        homeworkTitle.removeAllViews()
        homeworkCol.removeAllViews()
        val c = card(clickable = true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, HomeworkActivity::class.java)) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("✎", 18f, Ui.AMBER, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.AMBER_SOFT, 14)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        mid.addView(text("Домашка", 17f, Ui.TEXT, Ui.BOLD))
        mid.addView(text(
            if (open.isEmpty()) "Заданий нет" else Ui.plural(open.size, "задание", "задания", "заданий") + " не сделано",
            13f, if (open.isEmpty()) Ui.MUTED else Ui.AMBER
        ).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("›", 24f, Ui.MUTED))
        c.addView(head)
        // short preview of the nearest tasks
        open.take(2).forEach { hw ->
            c.addView(divider())
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Ui.subjectColor(hw.subject))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                })
                addView(text(
                    (if (hw.subject != LessonStore.GENERAL) hw.subject + ":  " else "") + hw.text, 14f, Ui.TEXT
                ).apply {
                    setPadding(dp(12), 0, 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            })
        }
        homeworkCol.addCard(c, 26)
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
