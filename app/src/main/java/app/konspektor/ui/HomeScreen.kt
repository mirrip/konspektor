package app.konspektor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import app.konspektor.HomeworkStore
import app.konspektor.LessonStore
import app.konspektor.Prefs
import app.konspektor.R
import app.konspektor.Subjects

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val recorder = LocalRecorder.current
    val prefs = remember { Prefs(ctx) }
    @Suppress("UNUSED_VARIABLE") val tick = rememberAppTick()   // live status

    val sig = dataSignature()
    val lessons = remember(sig) { LessonStore.lessons(ctx) }
    val homework = remember(sig) { HomeworkStore.all(ctx) }
    val subjects = remember(sig) { Subjects.list(ctx, lessons) }
    var current by remember(sig) { mutableStateOf(prefs.currentSubject) }

    var chooser by remember { mutableStateOf(false) }
    var newSubject by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val rec = AppState.recording
    val recSubject = if (rec) AppState.lessonDir?.let { LessonStore.subject(it) } ?: "" else current

    Screen(title = "Конспектор", subtitle = Fmt.today()) {
        // --- small controls above the button
        item(key = "controls") {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { if (!rec) chooser = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.height(52.dp),
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 16.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        SubjectBadge(recSubject.ifEmpty { LessonStore.GENERAL }, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            recSubject.ifEmpty { LessonStore.GENERAL }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { nav.go(Route.SETTINGS) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ms_settings), "Настройки", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // --- the switch
        item(key = "power") {
            PowerButton(
                recording = rec,
                startedAtMs = AppState.startedAtMs,
                subjectLabel = if (recSubject == LessonStore.GENERAL) "" else recSubject,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(250.dp),
                onClick = { if (AppState.recording) recorder.stop() else recorder.start(current) },
            )
        }

        // --- status line
        item(key = "status") {
            val lines = buildList {
                when {
                    rec -> add("Можно выключить экран — запись продолжится.")
                    AppState.serviceRunning -> add("Запись выключена, дорасшифровываю…")
                    else -> add("Положи телефон поближе к преподавателю.")
                }
                if (rec || AppState.serviceRunning) {
                    add(
                        "Расшифровано частей: ${AppState.done}" +
                            (if (AppState.queued > 0) " · в очереди ${AppState.queued}" else "") +
                            (if (AppState.failed > 0) " · ошибок ${AppState.failed}" else "")
                    )
                }
                AppState.lastError?.let { add("⚠ $it") }
            }
            Text(
                lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                color = if (AppState.lastError != null) Palette.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp),
            )
        }

        // --- live text while recording / transcribing
        item(key = "live") {
            val show = AppState.serviceRunning && AppState.lessonDir != null
            AnimatedVisibility(show, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val body = AppState.transcript.lines()
                    .filterNot { it.startsWith("#") || it.contains("[ждёт расшифровки]") }
                    .joinToString(" ").replace(Regex("\\[\\d+:\\d+]"), "").trim()
                KCard(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = { AppState.lessonDir?.let { nav.go(Route.lesson(it.path)) } },
                ) {
                    Text("СЕЙЧАС В ТЕКСТЕ", style = MaterialTheme.typography.labelSmall, color = Palette.red)
                    Gap(8.dp)
                    Text(
                        if (body.isEmpty()) "Первый текст появится примерно через 5 минут после включения."
                        else "…" + body.takeLast(260),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Gap(10.dp)
                    Text("Открыть урок", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // --- homework section card
        item(key = "homework") {
            val open = homework.filter { !it.done }.sortedBy { it.createdMs }
            KCard(modifier = Modifier.padding(top = 26.dp), onClick = { nav.go(Route.HOMEWORK) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.amber.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ms_assignment), null, tint = Palette.amber) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("Домашка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (open.isEmpty()) "Заданий нет" else Fmt.plural(open.size, "задание", "задания", "заданий") + " не сделано",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (open.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Palette.amber,
                        )
                    }
                    Chevron()
                }
                open.take(2).forEach { hw ->
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.subject(hw.subject)))
                        Text(
                            (if (hw.subject != LessonStore.GENERAL) hw.subject + ":  " else "") + hw.text,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }

        // --- subjects
        item(key = "subjects-title") {
            SectionHeader(
                "Предметы", if (subjects.isEmpty()) null else "${subjects.size}", "+ Предмет",
            ) { newSubject = { Subjects.add(ctx, it); UiSignals.edited() } }
        }
        val unsorted = lessons.filter { it.subject == LessonStore.GENERAL }
        if (unsorted.isNotEmpty()) item(key = "unsorted") {
            SubjectCard(
                LessonStore.GENERAL, "Неразобранные",
                Fmt.plural(unsorted.size, "урок ждёт", "урока ждут", "уроков ждут") + " Codex", null,
            ) { nav.go(Route.subject(LessonStore.GENERAL)) }
        }
        if (subjects.isEmpty() && unsorted.isEmpty()) item(key = "subjects-empty") {
            EmptyNote("Пока пусто. Записывай уроки — Codex сам разложит их по предметам и даст каждому номер и тему.")
        }
        subjects.forEach { s ->
            item(key = "s-$s") {
                val mine = lessons.filter { it.subject == s }
                val konspekts = mine.count { it.hasKonspekt }
                val sub = when {
                    mine.isEmpty() -> "Пока нет уроков"
                    konspekts > 0 -> Fmt.plural(mine.size, "урок", "урока", "уроков") + " · " +
                        Fmt.plural(konspekts, "конспект", "конспекта", "конспектов")
                    else -> Fmt.plural(mine.size, "урок", "урока", "уроков")
                }
                val openHw = homework.count { it.subject == s && !it.done }
                SubjectCard(s, s, sub, if (openHw > 0) "ДЗ $openHw" else null) { nav.go(Route.subject(s)) }
            }
        }
    }

    if (chooser) {
        ModalBottomSheet(
            onDismissRequest = { chooser = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                "Предмет записи", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            val choose = { s: String -> prefs.currentSubject = s; current = s; chooser = false }
            (listOf(LessonStore.GENERAL) + Subjects.choices(ctx)).forEach { s ->
                ListItem(
                    headlineContent = { Text(s) },
                    leadingContent = { SubjectBadge(s, 36.dp) },
                    trailingContent = {
                        if (s == current) Icon(painterResource(R.drawable.ms_check), null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color0),
                    modifier = Modifier.clickable { choose(s) },
                )
            }
            ListItem(
                headlineContent = { Text("Новый предмет", color = MaterialTheme.colorScheme.primary) },
                leadingContent = {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ms_add), null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color0),
                modifier = Modifier.clickable {
                    chooser = false
                    newSubject = { name -> choose(Subjects.add(ctx, name)); UiSignals.edited() }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    newSubject?.let { onDone ->
        TextInputDialog("Новый предмет", "Например: Физика", "Добавить", onDismiss = { newSubject = null }) { onDone(it) }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun SubjectCard(subject: String, title: String, sub: String, badge: String?, onClick: () -> Unit) {
    KCard(modifier = Modifier.padding(bottom = 10.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubjectBadge(subject)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge?.let { StatusPill(it, Palette.amber); Spacer(Modifier.width(8.dp)) }
            Chevron()
        }
    }
}
