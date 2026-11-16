package app.konspektor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.konspektor.AppState
import app.konspektor.Prefs
import app.konspektor.RecorderService

/** Screens are plain strings so the back stack survives rotation. */
object Route {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HOMEWORK = "homework"
    fun subject(name: String) = "subject:$name"
    fun lesson(path: String) = "lesson:$path"
}

class Nav(private val push: (String) -> Unit, private val pop: () -> Unit) {
    fun go(route: String) = push(route)
    fun back() = pop()
}

/** Start/stop/retry, with the microphone permission and key checks in one place. */
class Recorder(val start: (String) -> Unit, val stop: () -> Unit, val retry: () -> Unit)

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }
val LocalRecorder = staticCompositionLocalOf<Recorder> { error("no recorder") }

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var stack by rememberSaveable { mutableStateOf(listOf(Route.HOME)) }
    var forward by remember { mutableStateOf(true) }
    val nav = remember {
        Nav(
            push = { r -> forward = true; stack = stack + r },
            pop = { if (stack.size > 1) { forward = false; stack = stack.dropLast(1) } },
        )
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }

    // --- recording: only the microphone is required (notifications are not)
    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askKey by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (perms.all { res[it] == true || ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            pending?.invoke()
        } else {
            Toast.makeText(ctx, "Без доступа к микрофону запись невозможна", Toast.LENGTH_LONG).show()
        }
        pending = null
    }
    fun withPerms(block: () -> Unit) {
        if (perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) block()
        else { pending = block; launcher.launch(perms) }
    }
    val recorder = remember {
        Recorder(
            start = { subject ->
                if (prefs.groqKey.isBlank()) askKey = true
                else withPerms {
                    prefs.currentSubject = subject
                    AppState.transcript = ""
                    RecorderService.start(ctx, subject)
                }
            },
            stop = { RecorderService.stop(ctx) },
            retry = {
                withPerms {
                    RecorderService.retry(ctx)
                    Toast.makeText(ctx, "Отправляю нерасшифрованные части заново", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    CompositionLocalProvider(LocalNav provides nav, LocalRecorder provides recorder) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { it / 6 * dir } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(320)) { -it / 10 * dir } + fadeOut(tween(180)))
            },
            label = "screens",
        ) { route ->
            when {
                route == Route.HOME -> HomeScreen()
                route == Route.SETTINGS -> SettingsScreen()
                route == Route.HOMEWORK -> HomeworkScreen()
                route.startsWith("subject:") -> SubjectScreen(route.substringAfter(':'))
                route.startsWith("lesson:") -> LessonScreen(route.substringAfter(':'))
                else -> HomeScreen()
            }
        }
    }

    if (askKey) {
        AlertDialog(
            onDismissRequest = { askKey = false },
            title = { Text("Нужен ключ Groq") },
            text = { Text("Без ключа запись не расшифруется. Вставьте его в настройках.") },
            confirmButton = {
                TextButton(onClick = { askKey = false; nav.go(Route.SETTINGS) }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = { askKey = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
