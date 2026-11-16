package app.konspektor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : BaseActivity() {

    private lateinit var keyStatus: TextView
    private lateinit var batteryButton: TextView
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = page("Настройки", "конспектор")

        section(col, "Ключ Groq")
        val keyCard = card()
        keyStatus = text("", 15f, Ui.TEXT, Ui.MEDIUM)
        keyCard.addView(keyStatus)
        keyCard.addView(note("Нужен только для расшифровки речи в текст. Хранится на этом телефоне."))
        val keyEdit = EditText(this).apply {
            hint = "Вставьте ключ (gsk_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            textSize = 15f
            background = rounded(Ui.BG, 14, Ui.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            backgroundTintList = null
        }
        keyCard.addView(full(keyEdit, null, 12))
        keyCard.addView(full(primaryButton("Сохранить ключ") {
            val key = keyEdit.text.toString().trim()
            if (!key.startsWith("gsk_") || key.length < 20) {
                toast("Ключ Groq начинается с gsk_")
            } else {
                prefs.groqKey = key
                keyEdit.setText("")
                toast("Ключ сохранён")
                render()
            }
        }, 48, 10))
        col.addCard(keyCard, 8)

        section(col, "Работа в фоне")
        val bat = card()
        batteryStatus = text("", 15f, Ui.TEXT, Ui.MEDIUM)
        bat.addView(batteryStatus)
        bat.addView(note(
            "Уведомления приложению не нужны — запись идёт и без них. Важно другое: чтобы телефон не усыплял " +
                "приложение. На Xiaomi, Huawei и Samsung ещё включите «Автозапуск» и уберите ограничения батареи."
        ))
        batteryButton = primaryButton("", Ui.SURFACE2) { onBattery() }
        bat.addView(full(batteryButton, 48, 12))
        bat.addView(full(outlineButton("Настройки приложения") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 48, 8))
        col.addCard(bat, 8)

        section(col, "Расшифровка")
        val tr = card()
        tr.addView(row("Модель", prefs.model))
        tr.addView(divider())
        tr.addView(row("Части записи", "${LessonStore.CHUNK_SEC / 60} мин, перекрытие ${LessonStore.OVERLAP_SEC} с"))
        tr.addView(note("Если связи нет, части ждут и отправляются позже. Аудио всегда остаётся на телефоне."))
        tr.addView(full(outlineButton("Повторить нерасшифрованные части") { requestRetry() }, 48, 12))
        col.addCard(tr, 8)

        section(col, "Файлы")
        val files = card()
        files.addView(text("Папка с уроками", 15f, Ui.TEXT, Ui.MEDIUM))
        files.addView(text(LessonStore.root(this).path, 13f, Ui.ACCENT).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, 0)
        })
        files.addView(note(
            "Внутри: папка на каждый урок (аудио, текст, конспект) и homework.json с домашкой. " +
                "Codex на компьютере читает эту папку, раскладывает уроки по предметам и пишет конспекты."
        ))
        col.addCard(files, 8)

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(full(text("Конспектор $version", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(12))
        }))
    }

    private fun note(s: String) = text(s, 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }

    private fun row(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text(k, 14.5f, Ui.MUTED), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(v, 14.5f, Ui.TEXT, Ui.MEDIUM))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val key = prefs.groqKey
        keyStatus.text = if (key.isBlank()) "Ключ не задан" else "Ключ сохранён  ·  …${key.takeLast(4)}"
        keyStatus.setTextColor(if (key.isBlank()) Ui.RED else Ui.GREEN)
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (ignoring) "Работа в фоне разрешена" else "Телефон может прервать запись"
        batteryStatus.setTextColor(if (ignoring) Ui.GREEN else Ui.AMBER)
        batteryButton.text = if (ignoring) "Настройки батареи" else "Разрешить работу в фоне"
        batteryButton.background = pressable(if (ignoring) Ui.SURFACE2 else Ui.ACCENT, 16, null)
    }

    private fun onBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
