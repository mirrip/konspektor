package app.konspektor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.konspektor.AppState
import app.konspektor.Homework
import app.konspektor.HomeworkStore
import app.konspektor.LessonInfo
import app.konspektor.LessonStore
import app.konspektor.R
import app.konspektor.Subjects

/** One subject: its homework and the numbered lessons ("3 · Законы Ньютона"). */
@Composable
fun SubjectScreen(subject: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    rememberAppTick()
    val sig = dataSignature()
    val lessons = remember(sig) { LessonStore.lessons(ctx).filter { it.subject == subject } }
    val homework = remember(sig) {
        HomeworkStore.forSubject(ctx, subject).sortedWith(compareBy<Homework>({ it.done }, { -it.createdMs }))
    }
    val general = subject == LessonStore.GENERAL
    var addHw by remember { mutableStateOf(false) }
    var deleteHw by remember { mutableStateOf<Homework?>(null) }
    var deleteSubject by remember { mutableStateOf(false) }

    Screen(
        title = if (general) "Неразобранные" else subject,
        subtitle = "предмет",
        onBack = { nav.back() },
        actions = {
            if (!general) IconButton(onClick = { deleteSubject = true }) {
                Icon(painterResource(R.drawable.ms_delete), "Удалить предмет", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        item(key = "info") {
            val total = lessons.sumOf { it.durationSec }
            val konspekts = lessons.count { it.hasKonspekt }
            Text(
                when {
                    lessons.isEmpty() -> "Уроков пока нет."
                    general -> "Эти уроки записаны без предмета. Codex прочитает их, разложит по предметам и даст темы."
                    else -> Fmt.plural(lessons.size, "урок", "урока", "уроков") + " · " + Fmt.duration(total) +
                        " записи · " + Fmt.plural(konspekts, "конспект", "конспекта", "конспектов")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        if (!general) {
            item(key = "hw-title") {
                SectionHeader("Домашка", homework.count { !it.done }.takeIf { it > 0 }?.toString(), "+ Задание") { addHw = true }
            }
            item(key = "hw") {
                if (homework.isEmpty()) EmptyNote("Заданий нет. Codex добавит их сам, когда услышит в уроке.")
                else KCard(padding = 12.dp) {
                    homework.forEachIndexed { i, hw ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        HomeworkRow(
                            hw, showSubject = false,
                            onToggle = { HomeworkStore.setDone(ctx, hw.id, !hw.done); UiSignals.edited() },
                            onLongPress = { deleteHw = hw },
                        )
                    }
                }
            }
        }

        item(key = "lessons-title") { SectionHeader("Уроки", lessons.size.takeIf { it > 0 }?.toString()) }
        if (lessons.isEmpty()) item(key = "lessons-empty") { EmptyNote("Здесь появятся уроки с номерами и темами.") }
        lessons.asReversed().forEach { l ->
            item(key = l.dir.path) { LessonCard(subject, l) { nav.go(Route.lesson(l.dir.path)) } }
        }
    }

    if (addHw) TextInputDialog("Задание: $subject", "Например: №12 и 15 к пятнице", "Добавить", { addHw = false }) {
        HomeworkStore.add(ctx, subject, it); UiSignals.edited()
    }
    deleteHw?.let { hw ->
        ConfirmDialog("Удалить задание?", hw.text, "Удалить", { deleteHw = null }) {
            HomeworkStore.delete(ctx, hw.id); UiSignals.edited()
        }
    }
    if (deleteSubject) {
        val busy = AppState.serviceRunning && lessons.any { it.dir == AppState.lessonDir }
        ConfirmDialog(
            "Удалить «$subject»?",
            if (busy) "Сейчас идёт запись или расшифровка урока этого предмета. Дождитесь окончания."
            else "Удалятся предмет, ${Fmt.plural(lessons.size, "урок", "урока", "уроков")} (аудио, тексты, конспекты) " +
                "и домашка по нему. Вернуть будет нельзя.",
            if (busy) "Понятно" else "Удалить",
            { deleteSubject = false },
        ) {
            if (!busy) {
                Subjects.delete(ctx, subject); UiSignals.edited(); nav.back()
            }
        }
    }
}

@Composable
private fun LessonCard(subject: String, l: LessonInfo, onClick: () -> Unit) {
    val c = Palette.subject(subject)
    val status = lessonStatus(l)
    KCard(modifier = Modifier.padding(bottom = 10.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(c.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Text("${l.number}", style = MaterialTheme.typography.titleLarge, color = c) }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    l.topic ?: "Урок ${l.number}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (l.topic != null) "Урок ${l.number} · " else "") + Fmt.dateTime(l.startedMs) + " · " + Fmt.duration(l.durationSec),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                StatusPill(status.label, status.color)
            }
            Chevron()
        }
    }
}
