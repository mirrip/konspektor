package app.konspektor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** One lesson: notes (konspekt.md, when Codex has written it) and the full transcript. */
class LessonActivity : BaseActivity() {

    private lateinit var dir: File
    private lateinit var metaText: TextView
    private lateinit var statusPill: TextView
    private lateinit var retryButton: Button
    private lateinit var konspektText: TextView
    private lateinit var transcriptText: TextView
    private val listener: () -> Unit = { render() }
    private var signature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        if (!dir.exists()) { finish(); return }
        val info = LessonStore.info(dir)
        val number = LessonStore.lessons(this).firstOrNull { it.dir == dir }?.number ?: info.number
        val col = page("Урок $number", info.subject, action = "↗" to { share() })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, 0)
        }
        metaText = text("", 14f, Ui.MUTED)
        top.addView(metaText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = pill("", Ui.CHIP_BG, Ui.PRIMARY) {}.apply { setOnClickListener(null); isClickable = false; textSize = 12f }
        top.addView(statusPill)
        col.addView(top)

        retryButton = outlineButton("Повторить нерасшифрованные части") { requestRetry() }
        col.addView(full(retryButton, 48, 12))

        section(col, "Конспект")
        konspektText = text("", 15f, Ui.TEXT).apply { setTextIsSelectable(true) }
        col.addCard(card().apply { addView(konspektText) }, 8)

        section(col, "Расшифровка")
        transcriptText = text("", 15f, Ui.TEXT).apply { setTextIsSelectable(true) }
        col.addCard(card().apply { addView(transcriptText) }, 8)

        col.addView(full(outlineButton("Удалить урок", Ui.RED) { deleteLesson() }, 48, 28))
        col.addView(spacer(24))
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        signature = ""
        render()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        val live = AppState.lessonDir == dir && (AppState.recording || AppState.serviceRunning)
        val sig = "$live|${AppState.done}|${AppState.failed}|${AppState.queued}|${AppState.recording}"
        if (sig == signature) return
        signature = sig
        if (!dir.exists()) { finish(); return }

        val info = LessonStore.info(dir)
        metaText.text = Ui.dateTime(info.startedMs) + " · " + Ui.duration(info.durationSec) + " · " +
            Ui.plural(info.chunks, "часть", "части", "частей")
        val (label, bg, fg) = SubjectActivity.statusOf(info, AppState.recording && AppState.lessonDir == dir)
        statusPill.text = label
        statusPill.setTextColor(fg)
        statusPill.background = rounded(bg, 20)
        retryButton.visibility = if (info.failed > 0) View.VISIBLE else View.GONE

        konspektText.text = LessonStore.konspektText(dir)
            ?: "Конспекта пока нет. Он появится здесь, когда его сделает Codex на компьютере " +
                "(файл konspekt.md в папке урока)."
        konspektText.setTextColor(if (info.hasKonspekt) Ui.TEXT else Ui.MUTED)

        val transcript = if (AppState.lessonDir == dir && AppState.transcript.isNotEmpty()) AppState.transcript
        else LessonStore.transcriptText(dir)
        val body = transcript.lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
        transcriptText.text = body.ifEmpty { "Текст появится примерно через 5 минут после начала записи." }
    }

    private fun share() {
        val k = LessonStore.konspektText(dir)
        val text = k ?: LessonStore.transcriptText(dir)
        if (text.isBlank()) { toast("Текста пока нет"); return }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, if (k != null) "Отправить конспект" else "Отправить расшифровку"))
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
