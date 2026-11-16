package app.konspektor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class SettingsActivity : BaseActivity() {

    private lateinit var keyStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = page("Настройки")

        section(col, "Ключ Groq")
        val keyCard = card()
        keyStatus = text("", 15f, Ui.TEXT, bold = true)
        keyCard.addView(keyStatus)
        keyCard.addView(text("Нужен для расшифровки. Хранится только на этом телефоне.", 13f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        val keyEdit = EditText(this).apply {
            hint = "Вставьте ключ (gsk_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        keyCard.addView(full(keyEdit))
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
        }, 48, 8))
        col.addCard(keyCard, 8)

        section(col, "Работа в фоне")
        val bat = card()
        batteryStatus = text("", 15f, Ui.TEXT, bold = true)
        bat.addView(batteryStatus)
        bat.addView(text(
            "Чтобы телефон не останавливал запись при выключенном экране. На Xiaomi, Huawei и Samsung " +
                "ещё включите «Автозапуск» и снимите ограничения батареи в настройках приложения.",
            13f, Ui.MUTED
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        batteryButton = outlineButton("") { onBattery() }
        bat.addView(full(batteryButton, 48))
        bat.addView(full(outlineButton("Настройки приложения") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 48, 8))
        col.addCard(bat, 8)

        section(col, "Расшифровка")
        val tr = card()
        tr.addView(text("Модель: ${prefs.model}", 15f, Ui.TEXT))
        tr.addView(text(
            "Запись режется на части по ${LessonStore.CHUNK_SEC / 60} мин с перекрытием ${LessonStore.OVERLAP_SEC} с. " +
                "Если связи нет, части ждут и отправляются позже.",
            13f, Ui.MUTED
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        tr.addView(full(outlineButton("Повторить все нерасшифрованные части") { requestRetry() }, 48))
        col.addCard(tr, 8)

        section(col, "Файлы")
        val files = card()
        files.addView(text("Папка с уроками", 15f, Ui.TEXT, bold = true))
        files.addView(text(LessonStore.root(this).path, 13f, Ui.MUTED).apply { setTextIsSelectable(true) })
        files.addView(text(
            "Внутри: папка на каждый урок (аудио, расшифровка, конспект) и homework.json с домашкой. " +
                "Эту папку будем синхронизировать с компьютером.",
            13f, Ui.MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        col.addCard(files, 8)

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(text("Конспектор $version", 12f, Ui.MUTED).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }.let { full(it) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val key = prefs.groqKey
        keyStatus.text = if (key.isBlank()) "Ключ не задан" else "Ключ сохранён ✓  (…${key.takeLast(4)})"
        keyStatus.setTextColor(if (key.isBlank()) Ui.RED else Ui.GREEN)
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (ignoring) "Экономия батареи отключена ✓" else "Телефон может прервать запись"
        batteryStatus.setTextColor(if (ignoring) Ui.GREEN else Ui.AMBER)
        batteryButton.text = if (ignoring) "Открыть настройки батареи" else "Разрешить работу в фоне"
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
