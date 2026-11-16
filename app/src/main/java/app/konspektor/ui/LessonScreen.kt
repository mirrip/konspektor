package app.konspektor.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.konspektor.AppState
import app.konspektor.LessonStore
import app.konspektor.R
import java.io.File

/** One lesson: switch between the notes (konspekt.md from Codex) and the full transcript. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(path: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val recorder = LocalRecorder.current
    rememberAppTick()
    val dir = remember(path) { File(path) }
    val sig = dataSignature()
    val info = remember(sig) { if (dir.exists()) LessonStore.info(dir) else null }
    if (info == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val number = remember(sig) { LessonStore.number(ctx, dir) }
    val konspekt = remember(sig) { LessonStore.konspektText(dir) }
    val removed = remember(sig) { LessonStore.removedCount(dir) }
    val transcript = if (AppState.lessonDir == dir && AppState.transcript.isNotEmpty()) AppState.transcript
    else remember(sig) { LessonStore.transcriptText(dir) }
    var tab by rememberSaveable { mutableStateOf(if (konspekt != null) 0 else 1) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = AppState.lessonDir == dir && AppState.serviceRunning
    val status = lessonStatus(info)

    Screen(
        title = info.topic ?: "Урок $number",
        subtitle = if (info.subject == LessonStore.GENERAL) "неразобранный урок" else "${info.subject} · урок $number",
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = {
                val text = if (tab == 0 && konspekt != null) konspekt else transcript
                if (text.isNotBlank()) ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить")
                )
            }) { Icon(painterResource(R.drawable.ms_share), "Поделиться", tint = MaterialTheme.colorScheme.onSurface) }
        },
    ) {
        item(key = "meta") {
            Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
                Text(
                    Fmt.dateTime(info.startedMs) + "  ·  " + Fmt.duration(info.durationSec) + "  ·  " +
                        Fmt.plural(info.chunks, "часть", "части", "частей"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 10.dp)) { StatusPill(status.label, status.color) }
            }
        }
        if (info.failed > 0 && !busy) item(key = "retry") {
            OutlinedButton(onClick = { recorder.retry() }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Icon(painterResource(R.drawable.ms_refresh), null, modifier = Modifier.size(18.dp))
                Text("  Повторить нерасшифрованные части")
            }
        }
        item(key = "tabs") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                listOf("Конспект", "Расшифровка").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i, onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label) }
                }
            }
        }
        item(key = "body") {
            KCard(modifier = Modifier.padding(top = 12.dp), padding = 20.dp) {
                if (tab == 0) {
                    if (konspekt != null) SelectionContainer { MarkdownText(konspekt) }
                    else Text(
                        "Конспекта пока нет.\n\nОн появится здесь, когда Codex на компьютере разберёт уроки: " +
                            "определит предмет и тему и напишет конспект.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val body = transcript.lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
                    SelectionContainer {
                        Text(
                            body.ifEmpty { "Текст появится примерно через 5 минут после включения записи." },
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                            color = if (body.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (removed > 0) Text(
                        "Фильтр вырезал выдуманные Whisper фразы: $removed. Что именно — в папке урока, parts/*.removed.txt.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
        item(key = "delete") {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ms_delete), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text("  Удалить урок", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Удалить урок?",
            if (busy) "Урок ещё записывается или расшифровывается. Дождитесь окончания."
            else "Удалятся аудио, расшифровка и конспект этого урока. Вернуть будет нельзя.",
            if (busy) "Понятно" else "Удалить",
            { confirmDelete = false },
        ) {
            if (!busy) { LessonStore.deleteLesson(dir); UiSignals.edited(); nav.back() }
        }
    }
}

/** Light Markdown: headings, bullet/numbered lists, **bold**, `code`. Enough for Codex notes. */
@Composable
fun MarkdownText(md: String) {
    Column {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Column(Modifier.height(8.dp)) {}
                line.startsWith("### ") -> Text(
                    inline(line.drop(4)), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                line.startsWith("## ") -> Text(
                    inline(line.drop(3)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                line.startsWith("# ") -> Text(
                    inline(line.drop(2)), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp),
                )
                line == "---" -> Column(Modifier.height(12.dp)) {}
                line.trimStart().matches(Regex("^([-*•]|\\d+[.)])\\s+.*")) -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    val t = line.trimStart()
                    val marker = Regex("^([-*•]|\\d+[.)])\\s+").find(t)!!
                    val bullet = if (marker.value.trim().first().isDigit()) marker.value.trim() else "•"
                    Row(Modifier.padding(start = (indent * 16).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            bullet, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(inline(t.drop(marker.value.length)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                else -> Text(
                    inline(line), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun inline(s: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.tertiary
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                    val end = s.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                }
                s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                    val end = s.indexOf('`', i + 1)
                    withStyle(SpanStyle(color = accent)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}
