package app.konspektor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.konspektor.Homework
import app.konspektor.HomeworkStore
import app.konspektor.LessonStore
import app.konspektor.R
import app.konspektor.Subjects

/**
 * Homework section. Tasks come from Codex: the teacher says the homework out loud,
 * Codex finds it in the lesson text and adds it to homework.json. Adding by hand is secondary.
 */
@Composable
fun HomeworkScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val sig = dataSignature()
    val all = remember(sig) { HomeworkStore.all(ctx) }
    val open = all.filter { !it.done }
    val done = all.filter { it.done }.sortedByDescending { it.createdMs }
    var showDone by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf(false) }
    var pickSubjectFor by remember { mutableStateOf<String?>(null) }
    var deleteHw by remember { mutableStateOf<Homework?>(null) }

    val toggle = { hw: Homework -> HomeworkStore.setDone(ctx, hw.id, !hw.done); UiSignals.edited() }

    Screen(
        title = "Домашка",
        subtitle = if (open.isEmpty()) "всё сделано" else Fmt.plural(open.size, "задание", "задания", "заданий"),
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { newTask = true }) {
                Icon(painterResource(R.drawable.ms_add), "Добавить вручную", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
    ) {
        item(key = "about") {
            Text(
                "Задания находит Codex в записи урока — учитель произносит их вслух. " +
                    "Нажми на кружок, чтобы отметить выполненное; долгое нажатие удаляет.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
            )
        }
        if (open.isEmpty()) item(key = "empty") {
            EmptyNote(
                if (all.isEmpty()) "Пока заданий нет. Как только Codex разберёт урок, где задали домашку, она появится здесь."
                else "Всё сделано ✓"
            )
        }
        val groups = open.groupBy { it.subject }.toSortedMap(compareBy<String>({ it == LessonStore.GENERAL }, { it }))
        groups.forEach { (subject, items) ->
            item(key = "g-$subject") {
                Row(Modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SubjectBadge(subject, 28.dp)
                    Text(
                        "  " + (if (subject == LessonStore.GENERAL) "Без предмета" else subject),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text("  ${items.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                KCard(padding = 12.dp) {
                    items.sortedBy { it.createdMs }.forEachIndexed { i, hw ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        HomeworkRow(hw, showSubject = false, onToggle = { toggle(hw) }, onLongPress = { deleteHw = hw })
                        Text(
                            if (hw.fromCodex) "из урока" else "добавлено вручную",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 40.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done-toggle") {
                TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(top = 18.dp)) {
                    Text((if (showDone) "Скрыть выполненные" else "Выполненные") + "  ·  ${done.size}")
                }
            }
            if (showDone) item(key = "done") {
                KCard(padding = 12.dp) {
                    done.forEachIndexed { i, hw ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        HomeworkRow(hw, showSubject = true, onToggle = { toggle(hw) }, onLongPress = { deleteHw = hw })
                    }
                }
            }
        }
    }

    if (newTask) TextInputDialog("Новое задание", "Например: №12 и 15 к пятнице", "Дальше", { newTask = false }) { task ->
        pickSubjectFor = task
    }
    pickSubjectFor?.let { task ->
        val subjects = Subjects.choices(ctx)
        AlertDialog(
            onDismissRequest = { pickSubjectFor = null },
            title = { Text("По какому предмету?") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    (subjects + LessonStore.GENERAL).forEach { s ->
                        ListItem(
                            headlineContent = { Text(if (s == LessonStore.GENERAL) "Без предмета" else s) },
                            leadingContent = { SubjectBadge(s, 32.dp) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                HomeworkStore.add(ctx, s, task); UiSignals.edited(); pickSubjectFor = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickSubjectFor = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    deleteHw?.let { hw ->
        ConfirmDialog("Удалить задание?", hw.text, "Удалить", { deleteHw = null }) {
            HomeworkStore.delete(ctx, hw.id); UiSignals.edited()
        }
    }
}
