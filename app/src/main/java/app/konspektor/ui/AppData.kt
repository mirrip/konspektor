package app.konspektor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.konspektor.AppState
import app.konspektor.LessonInfo
import app.konspektor.LessonStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Signals that make screens reload data from disk. */
object UiSignals {
    /** Bumped in Activity.onResume: files may have changed (sync, Codex). */
    val resumes = mutableIntStateOf(0)
    /** Bumped after local edits (homework ticked, subject added…). */
    val edits = mutableIntStateOf(0)
    fun edited() { edits.intValue++ }
}

/** Recomposes the caller whenever the recorder service reports anything (incl. mic level). */
@Composable
fun rememberAppTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        AppState.addListener(l)
        onDispose { AppState.removeListener(l) }
    }
    return tick
}

/** Changes only when lessons, queue or files change — not on every mic level update. */
fun dataSignature(): String =
    "${AppState.recording}|${AppState.serviceRunning}|${AppState.done}|${AppState.failed}|" +
        "${AppState.queued}|${AppState.lessonDir}|${UiSignals.resumes.intValue}|${UiSignals.edits.intValue}|" +
        "${AppState.syncVersion}"

object Palette {
    val idleStart = Color(0xFF7C6CFF)
    val idleEnd = Color(0xFF4C8DFF)
    val recStart = Color(0xFFFF4D62)
    val recEnd = Color(0xFFFF8A4C)
    val red = Color(0xFFFF6B7A)
    val green = Color(0xFF4ADE80)
    val amber = Color(0xFFF5B84B)
    val violet = Color(0xFFB4A9FF)

    private val subjects = listOf(
        0xFF9B8CFF, 0xFF5B9BFF, 0xFF4ADE80, 0xFFF5B84B, 0xFFFF7A90,
        0xFF38C7E8, 0xFFFF9F5A, 0xFFC084FC, 0xFF5EEAD4,
    ).map { Color(it) }

    fun subject(name: String): Color =
        if (name == LessonStore.GENERAL) Color(0xFFA8A3B3) else subjects[abs(name.hashCode()) % subjects.size]
}

object Fmt {
    private val RU = Locale.forLanguageTag("ru")
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", RU).format(Date(ms))
    fun today(): String = SimpleDateFormat("EEEE, d MMMM", RU).format(Date())

    fun duration(sec: Long): String = when {
        sec < 60 -> "< 1 мин"
        sec < 3600 -> "${sec / 60} мин"
        else -> "${sec / 3600} ч ${sec / 60 % 60} мин"
    }

    fun timer(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    else "%02d:%02d".format(sec / 60, sec % 60)

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }
}

/** Short label + color for a lesson's state. */
data class LessonStatus(val label: String, val color: Color)

fun lessonStatus(l: LessonInfo): LessonStatus = when {
    AppState.recording && AppState.lessonDir == l.dir -> LessonStatus("● идёт запись", Palette.red)
    l.pending > 0 -> LessonStatus("расшифровка…", Palette.violet)
    l.failed > 0 -> LessonStatus("ошибок: ${l.failed}", Palette.red)
    l.hasKonspekt -> LessonStatus("конспект готов", Palette.green)
    else -> LessonStatus("текст готов · ждёт Codex", Palette.amber)
}
