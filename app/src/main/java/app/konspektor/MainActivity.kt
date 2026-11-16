package app.konspektor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** Home: big round button on top, then open homework and the list of subjects. */
class MainActivity : BaseActivity() {

    private lateinit var button: RecordButton
    private lateinit var subjectChip: TextView
    private lateinit var statusText: TextView
    private lateinit var liveCard: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var homeworkBox: LinearLayout
    private lateinit var subjectsBox: LinearLayout

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
        val col = page("Конспектор", Ui.today(), back = false, action = "⚙" to {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        button = RecordButton(this).apply { setOnClickListener { onButton() } }
        val buttonWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(buttonWrap)

        subjectChip = pill("", Ui.CHIP_BG, Ui.PRIMARY) { chooseSubject(startAfter = false) }.apply { textSize = 16f }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(subjectChip)
        })

        statusText = text("", 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
        }
        col.addView(full(statusText))

        liveCard = card().apply {
            addView(text("Сейчас в тексте", 13f, Ui.MUTED, bold = true))
            liveText = text("", 15f, Ui.TEXT).apply { setPadding(0, dp(6), 0, dp(6)) }
            addView(liveText)
            addView(text("Открыть урок  →", 14f, Ui.PRIMARY, bold = true))
            setOnClickListener { AppState.lessonDir?.let { openLesson(it.path) } }
        }
        col.addCard(liveCard, 16)

        section(col, "Домашка")
        homeworkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(homeworkBox))

        section(col, "Предметы", "+ Добавить" to { addSubject(startAfter = false) })
        subjectsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(subjectsBox))
        col.addView(spacer(24))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeKeyFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        listSignature = ""
        renderLists()
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
        if (AppState.recording) {
            RecorderService.stop(this)
            return
        }
        val subjects = Subjects.list(this)
        val current = prefs.currentSubject
        when {
            subjects.isEmpty() -> addSubject(startAfter = true)
            current.isBlank() || current !in subjects -> chooseSubject(startAfter = true)
            else -> requestStart(current)
        }
    }

    private fun chooseSubject(startAfter: Boolean) {
        if (AppState.recording) return
        val subjects = Subjects.list(this)
        if (subjects.isEmpty()) { addSubject(startAfter); return }
        val items = (subjects + "➕  Новый предмет").toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(if (startAfter) "Какой сейчас урок?" else "Предмет для записи")
            .setItems(items) { _, which ->
                if (which == subjects.size) addSubject(startAfter)
                else {
                    prefs.currentSubject = subjects[which]
                    renderLive()
                    if (startAfter) requestStart(subjects[which])
                }
            }
            .show()
    }

    private fun addSubject(startAfter: Boolean) {
        askText("Новый предмет", "Например: Физика", "Добавить") { name ->
            val s = Subjects.add(this, name)
            prefs.currentSubject = s
            listSignature = ""
            renderLists()
            if (startAfter) requestStart(s)
        }
    }

    private fun openLesson(path: String) =
        startActivity(Intent(this, LessonActivity::class.java).putExtra(LessonActivity.EXTRA_DIR, path))

    // ---------------------------------------------------------------- rendering

    /** Cheap updates, called often (timer, mic level, status). */
    private fun renderLive() {
        val rec = AppState.recording
        button.recording = rec
        button.level = AppState.level
        if (rec) button.timerText = Ui.timer((System.currentTimeMillis() - AppState.startedAtMs) / 1000)

        val subject = if (rec) AppState.lessonDir?.let { LessonStore.subject(it) } ?: "" else prefs.currentSubject
        subjectChip.text = when {
            rec -> "●  $subject"
            subject.isBlank() -> "Выбрать предмет  ▾"
            else -> "$subject  ▾"
        }
        subjectChip.setTextColor(if (rec) Ui.RED else Ui.PRIMARY)
        subjectChip.background = rounded(if (rec) Ui.RED_BG else Ui.CHIP_BG, 20)

        val lines = mutableListOf<String>()
        when {
            rec -> lines += "Идёт запись. Можно выключить экран."
            AppState.serviceRunning -> lines += "Запись остановлена, дорасшифровываю…"
        }
        if (rec || AppState.serviceRunning) {
            lines += "Расшифровано частей: ${AppState.done}" +
                (if (AppState.queued > 0) " · в очереди: ${AppState.queued}" else "") +
                (if (AppState.failed > 0) " · ошибок: ${AppState.failed}" else "")
        }
        AppState.lastError?.let { lines += "⚠ $it" }
        if (lines.isEmpty()) lines += "Положи телефон поближе к преподавателю"
        statusText.text = lines.joinToString("\n")

        val showLive = (rec || AppState.serviceRunning) && AppState.lessonDir != null
        liveCard.visibility = if (showLive) View.VISIBLE else View.GONE
        if (showLive) {
            val body = AppState.transcript.lines().filterNot { it.startsWith("#") }.joinToString(" ").trim()
            liveText.text = if (body.isEmpty() || body.contains("[ждёт расшифровки]") && body.length < 40)
                "Первый текст появится примерно через 5 минут после начала."
            else "…" + body.takeLast(280)
        }

        // Rebuild lists only when something structural changed.
        val sig = "${AppState.recording}|${AppState.serviceRunning}|${AppState.done}|${AppState.failed}|${AppState.lessonDir}"
        if (sig != listSignature) renderLists()
    }

    private fun renderLists() {
        listSignature = "${AppState.recording}|${AppState.serviceRunning}|${AppState.done}|${AppState.failed}|${AppState.lessonDir}"
        renderHomework()
        renderSubjects()
    }

    private fun renderHomework() {
        homeworkBox.removeAllViews()
        val open = HomeworkStore.all(this).filter { !it.done }.sortedBy { it.createdMs }
        if (open.isEmpty()) {
            homeworkBox.addView(full(text("Заданий нет 🎉  Добавляй их на странице предмета.", 14f, Ui.MUTED).apply {
                setPadding(dp(4), dp(8), 0, 0)
            }))
            return
        }
        val c = card()
        open.take(8).forEachIndexed { i, hw ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (i == 0) 0 else dp(6), 0, 0)
            }
            val box = CheckBox(this).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.subjectColor(hw.subject))
            }
            val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val main = text(hw.text, 15f, Ui.TEXT)
            label.addView(main)
            label.addView(text(hw.subject, 12f, Ui.subjectColor(hw.subject), bold = true))
            box.setOnCheckedChangeListener { _, checked ->
                HomeworkStore.setDone(this, hw.id, checked)
                main.paintFlags = if (checked) main.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else main.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                main.setTextColor(if (checked) Ui.MUTED else Ui.TEXT)
            }
            row.addView(box)
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(row)
        }
        if (open.size > 8) c.addView(text("и ещё ${open.size - 8}…", 13f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        homeworkBox.addCard(c, 8)
    }

    private fun renderSubjects() {
        subjectsBox.removeAllViews()
        val subjects = Subjects.list(this)
        if (subjects.isEmpty()) {
            val c = card()
            c.addView(text("Добавь первый предмет", 16f, Ui.TEXT, bold = true))
            c.addView(text("Например «Физика» или «Матан». Уроки будут складываться внутрь предмета и нумероваться.", 14f, Ui.MUTED).apply {
                setPadding(0, dp(4), 0, dp(10))
            })
            c.addView(full(primaryButton("+ Добавить предмет") { addSubject(startAfter = false) }, 48))
            subjectsBox.addCard(c, 8)
            return
        }
        val lessons = LessonStore.lessons(this)
        val homework = HomeworkStore.all(this)
        for (s in subjects) {
            val mine = lessons.filter { it.subject == s }
            val openHw = homework.count { it.subject == s && !it.done }
            val c = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SubjectActivity::class.java).putExtra(SubjectActivity.EXTRA_SUBJECT, s))
                }
            }
            c.addView(subjectBadge(s))
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            mid.addView(text(s, 17f, Ui.TEXT, bold = true))
            val sub = if (mine.isEmpty()) "Пока нет уроков" else
                Ui.plural(mine.size, "урок", "урока", "уроков") + " · последний " + Ui.dateTime(mine.last().startedMs)
            mid.addView(text(sub, 13f, Ui.MUTED))
            c.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val recordingThis = AppState.recording && AppState.lessonDir?.let { LessonStore.subject(it) } == s
            val tail = when {
                recordingThis -> pill("● запись", Ui.RED_BG, Ui.RED) {}
                openHw > 0 -> pill("ДЗ $openHw", Ui.AMBER_BG, Ui.AMBER) {}
                else -> text("›", 26f, Ui.MUTED)
            }
            tail.setOnClickListener(null)
            tail.isClickable = false
            c.addView(tail)
            subjectsBox.addCard(c, 8)
        }
    }
}
