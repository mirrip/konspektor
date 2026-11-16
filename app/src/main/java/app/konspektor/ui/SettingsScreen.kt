package app.konspektor.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.konspektor.LessonStore
import app.konspektor.Prefs
import app.konspektor.R

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val recorder = LocalRecorder.current
    val prefs = remember { Prefs(ctx) }
    val sig = dataSignature()
    var key by remember(sig) { mutableStateOf(prefs.groqKey) }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    val ignoring = remember(sig) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }

    Screen(title = "Настройки", subtitle = "конспектор", onBack = { nav.back() }) {
        item(key = "key") {
            SettingsCard(R.drawable.ms_key, "Ключ Groq", Palette.violet) {
                Text(
                    if (key.isBlank()) "Ключ не задан" else "Ключ сохранён  ·  …${key.takeLast(4)}",
                    style = MaterialTheme.typography.titleSmall, color = if (key.isBlank()) Palette.red else Palette.green,
                )
                Note("Нужен только для расшифровки речи в текст. Хранится на этом телефоне.")
                OutlinedTextField(
                    value = input, onValueChange = { input = it; inputError = false },
                    placeholder = { Text("Вставьте ключ (gsk_…)") }, singleLine = true, isError = inputError,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(
                    onClick = {
                        val k = input.trim()
                        if (!k.startsWith("gsk_") || k.length < 20) inputError = true
                        else { prefs.groqKey = k; key = k; input = ""; UiSignals.edited() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                ) { Text("Сохранить ключ") }
            }
        }

        item(key = "battery") {
            SettingsCard(R.drawable.ms_battery_charging_full, "Работа в фоне", if (ignoring) Palette.green else Palette.amber) {
                Text(
                    if (ignoring) "Работа в фоне разрешена" else "Телефон может прервать запись",
                    style = MaterialTheme.typography.titleSmall, color = if (ignoring) Palette.green else Palette.amber,
                )
                Note(
                    "Уведомления приложению не нужны — запись идёт и без них. Важно, чтобы телефон не усыплял приложение. " +
                        "На Xiaomi, Huawei и Samsung ещё включите «Автозапуск» и уберите ограничения батареи."
                )
                Button(
                    onClick = {
                        val intent = if (!ignoring) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        try { ctx.startActivity(intent) } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                ) { Text(if (ignoring) "Настройки батареи" else "Разрешить работу в фоне") }
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                ) { Text("Настройки приложения") }
            }
        }

        item(key = "transcribe") {
            SettingsCard(R.drawable.ms_graphic_eq, "Расшифровка", Palette.subject("Физика")) {
                KeyValue("Модель", prefs.model)
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                KeyValue("Части записи", "${LessonStore.CHUNK_SEC / 60} мин · перекрытие ${LessonStore.OVERLAP_SEC} с")
                Note("Если связи нет, части ждут и отправляются позже. Аудио всегда остаётся на телефоне.")
                OutlinedButton(
                    onClick = { recorder.retry() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                ) { Text("Повторить нерасшифрованные части") }
            }
        }

        item(key = "files") {
            SettingsCard(R.drawable.ms_folder, "Файлы", Palette.amber) {
                Text("Папка с уроками", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    remember { LessonStore.root(ctx).path }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
                )
                Note(
                    "Внутри: папка на каждый урок (аудио, текст, конспект) и homework.json с домашкой. " +
                        "Codex на компьютере раскладывает уроки по предметам, пишет конспекты и находит домашку."
                )
            }
        }

        item(key = "version") {
            Text(
                "Конспектор $version", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingsCard(icon: Int, title: String, tint: Color, content: @Composable () -> Unit) {
    KCard(modifier = Modifier.padding(top = 14.dp), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(20.dp)) }
            Text("  $title", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column { content() }
    }
}

@Composable
private fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 6.dp),
)

@Composable
private fun KeyValue(k: String, v: String) {
    Row {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
