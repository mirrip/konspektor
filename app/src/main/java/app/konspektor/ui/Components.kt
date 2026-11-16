package app.konspektor.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.konspektor.Homework
import app.konspektor.LessonStore
import app.konspektor.R
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Page skeleton: scrolling content under a frosted-glass top bar (chrisbanes/haze).
 * The big title scrolls away; the bar then shows a compact title.
 */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showBigTitle: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val haze = rememberHazeState()
    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90 }
    }
    val bars = WindowInsets.systemBars.asPaddingValues()
    val bg = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(haze).imePadding(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = bars.calculateTopPadding() + 60.dp,
                bottom = bars.calculateBottomPadding() + 36.dp,
            ),
        ) {
            if (showBigTitle) item(key = "title") {
                Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                    subtitle?.let {
                        Text(
                            it.uppercase(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        title, style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            content()
        }

        Row(
            Modifier
                .fillMaxWidth()
                .hazeEffect(haze) {
                    blurRadius = 28.dp
                    backgroundColor = bg
                    tints = listOf(HazeTint(bg.copy(alpha = if (scrolled) 0.72f else 0.0f)))
                    noiseFactor = 0.04f
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ms_arrow_back), "Назад", tint = MaterialTheme.colorScheme.onSurface)
                }
            } else Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            actions()
        }
    }
}

/** Rounded dark card; optional tap / long-press. */
@Composable
fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    onClick = { onClick?.invoke() }, onLongClick = onLongClick,
                ) else Modifier
            ),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionHeader(title: String, count: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        count?.let {
            Text(
                "  $it", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) Pill(action, MaterialTheme.colorScheme.primary, onClick = onAction)
    }
}

/** Small rounded label. */
@Composable
fun Pill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Text(
            text, color = color, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Rounded square with the subject's first letter in its color. */
@Composable
fun SubjectBadge(subject: String, size: Dp = 44.dp) {
    val c = Palette.subject(subject)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (subject == LessonStore.GENERAL) {
            Icon(painterResource(R.drawable.ms_auto_awesome), null, tint = c, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                subject.trim().take(1).uppercase(), color = c,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun Chevron() = Icon(
    painterResource(R.drawable.ms_chevron_right), null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
)

@Composable
fun EmptyNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Homework line: round check (tap = done), long-press = delete. */
@Composable
fun HomeworkRow(hw: Homework, showSubject: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val c = Palette.subject(hw.subject)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (hw.done) R.drawable.ms_check_circle else R.drawable.ms_radio_button_unchecked),
            if (hw.done) "Сделано" else "Не сделано", tint = c, modifier = Modifier.size(26.dp),
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                hw.text, style = MaterialTheme.typography.bodyLarge,
                color = if (hw.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (hw.done) TextDecoration.LineThrough else null,
            )
            val meta = buildList {
                if (showSubject && hw.subject != LessonStore.GENERAL) add(hw.subject)
                hw.due?.let { add("срок: $it") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = if (showSubject) c else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                placeholder = { Text(hint) }, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { value.trim().takeIf { it.isNotEmpty() }?.let(onConfirm); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
