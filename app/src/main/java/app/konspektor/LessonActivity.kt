package app.konspektor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** One lesson: switch between the notes (konspekt.md from Codex) and the full transcript. */
class LessonActivity : BaseActivity() {

    private lateinit var dir: File
    private lateinit var metaText: TextView
    private lateinit var statusChip: TextView
    private lateinit var retryButton: TextView
    private lateinit var tabKonspekt: TextView
    private lateinit var tabText: TextView
    private lateinit var bodyText: TextView
    private var showKonspekt = true
    private val listener: () -> Unit = { render(force = false) }
    private var signature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        if (!dir.exists()) { finish(); return }
        val info = LessonStore.info(dir)
        val number = LessonStore.number(this, dir)
        val subjectLabel = if (info.subject == LessonStore.GENERAL) "неразобранный урок" else "${info.subject} · урок $number"
        val col = page(info.topic ?: "Урок $number", subjectLabel, action = "↗" to { share() })
        showKonspekt = info.hasKonspekt

        metaText = text("", 13.5f, Ui.MUTED).apply { setPadding(dp(2), dp(10), 0, 0) }
        col.addView(metaText)
        statusChip = chip("", Ui.ACCENT_SOFT, Ui.ACCENT)
        col.addView(LinearLayout(this).apply { setPadding(0, dp(10), 0, 0); addView(statusChip) })

        retryButton = outlineButton("Повторить нерасшифрованные части", Ui.AMBER) { requestRetry() }
        col.addView(full(retryButton, 48, 14))

        // segmented switch
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Ui.SURFACE, 16, Ui.LINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        tabKonspekt = segment("Конспект") { showKonspekt = true; render(force = true) }
        tabText = segment("Расшифровка") { showKonspekt = false; render(force = true) }
        seg.addView(tabKonspekt, LinearLayout.LayoutParams(0, dp(40), 1f))
        seg.addView(tabText, LinearLayout.LayoutParams(0, dp(40), 1f))
        col.addView(full(seg, null, 22))

        bodyText = text("", 15.5f, Ui.TEXT).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.3f)
        }
        col.addCard(card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(bodyText) }, 12)

        col.addView(full(outlineButton("Удалить урок", Ui.RED) { deleteLesson() }, 48, 28))
        col.addView(spacer(28))
    }

    private fun segment(lbl: String, onClick: () -> Unit) = text(lbl, 14f, Ui.MUTED, Ui.MEDIUM).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        render(force = true)
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    private fun render(force: Boolean) {
        val live = AppState.lessonDir == dir && AppState.serviceRunning
        val sig = "$live|${AppState.done}|${AppState.failed}|${AppState.queued}|${AppState.recording}|$showKonspekt"
        if (!force && sig == signature) return
        signature = sig
        if (!dir.exists()) { finish(); return }

        val info = LessonStore.info(dir)
        metaText.text = Ui.dateTime(info.startedMs) + "  ·  " + Ui.duration(info.durationSec) + "  ·  " +
            Ui.plural(info.chunks, "часть", "части", "частей")
        val (lbl, bg, fg) = SubjectActivity.statusOf(info, AppState.recording && AppState.lessonDir == dir)
        statusChip.text = lbl
        statusChip.setTextColor(fg)
        statusChip.background = rounded(bg, 20)
        retryButton.visibility = if (info.failed > 0 && !live) View.VISIBLE else View.GONE

        for ((tab, active) in listOf(tabKonspekt to showKonspekt, tabText to !showKonspekt)) {
            tab.setTextColor(if (active) Ui.TEXT else Ui.MUTED)
            tab.background = if (active) rounded(Ui.SURFACE2, 12) else null
        }

        if (showKonspekt) {
            val k = LessonStore.konspektText(dir)
            bodyText.text = k ?: "Конспекта пока нет.\n\nCodex сделает его на компьютере после урока: разберёт текст, " +
                "определит предмет и тему и положит сюда конспект."
            bodyText.setTextColor(if (k != null) Ui.TEXT else Ui.MUTED)
        } else {
            val transcript = if (AppState.lessonDir == dir && AppState.transcript.isNotEmpty()) AppState.transcript
            else LessonStore.transcriptText(dir)
            val body = transcript.lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
            bodyText.text = body.ifEmpty { "Текст появится примерно через 5 минут после начала записи." }
            bodyText.setTextColor(if (body.isEmpty()) Ui.MUTED else Ui.TEXT)
        }
    }

    private fun share() {
        val k = LessonStore.konspektText(dir)
        val text = if (showKonspekt && k != null) k else LessonStore.transcriptText(dir)
        if (text.isBlank()) { toast("Текста пока нет"); return }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Отправить"))
    }

    private fun deleteLesson() {
        if (AppState.lessonDir == dir && AppState.serviceRunning) {
            toast("Урок ещё записывается или расшифровывается")
            return
        }
        confirm("Удалить урок?", "Удалятся аудио, расшифровка и конспект этого урока. Вернуть будет нельзя.", "Удалить") {
            LessonStore.deleteLesson(dir)
            finish()
        }
    }

    companion object {
        const val EXTRA_DIR = "dir"
    }
}
