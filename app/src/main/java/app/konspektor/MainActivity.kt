package app.konspektor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var subjectEdit: EditText
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var transcriptText: TextView
    private lateinit var keyStatus: TextView
    private lateinit var keyEdit: EditText
    private lateinit var folderText: TextView
    private lateinit var batteryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        takeKeyFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeKeyFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        if (!AppState.serviceRunning && AppState.transcript.isEmpty()) loadLastTranscript()
        folderText.text = "Папка с уроками: " + LessonStore.root(this).path
        handler.post(ticker)
    }

    override fun onPause() {
        AppState.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Lets the key be set from a computer: adb shell am start -n app.konspektor/.MainActivity --es groq_key gsk_... */
    private fun takeKeyFromIntent(intent: Intent?) {
        val key = intent?.getStringExtra("groq_key")?.trim() ?: return
        if (key.startsWith("gsk_")) {
            prefs.groqKey = key
            Toast.makeText(this, "Ключ Groq сохранён", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    // ---------------------------------------------------------------- actions

    private fun onStartStop() {
        if (AppState.recording) {
            RecorderService.stop(this)
            return
        }
        if (prefs.groqKey.isBlank()) {
            Toast.makeText(this, "Сначала вставьте ключ Groq внизу экрана", Toast.LENGTH_LONG).show()
            keyEdit.requestFocus()
            return
        }
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_START)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val subject = subjectEdit.text.toString().trim()
        prefs.lastSubject = subject
        AppState.transcript = ""
        RecorderService.start(this, subject)
    }

    private fun neededPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Без доступа к микрофону запись невозможна", Toast.LENGTH_LONG).show()
            return
        }
        when (requestCode) {
            REQ_START -> startRecording()
            REQ_RETRY -> RecorderService.retry(this)
        }
    }

    private fun onRetry() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(neededPermissions().toTypedArray(), REQ_RETRY)
            return
        }
        RecorderService.retry(this)
        Toast.makeText(this, "Отправляю нерасшифрованные части заново", Toast.LENGTH_SHORT).show()
    }

    private fun onShare() {
        val text = AppState.transcript
        if (text.isBlank()) {
            Toast.makeText(this, "Текста пока нет", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Отправить расшифровку"))
    }

    private fun onSaveKey() {
        val key = keyEdit.text.toString().trim()
        if (!key.startsWith("gsk_") || key.length < 20) {
            Toast.makeText(this, "Ключ Groq должен начинаться с gsk_", Toast.LENGTH_LONG).show()
            return
        }
        prefs.groqKey = key
        keyEdit.setText("")
        Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
        render()
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

    private fun loadLastTranscript() {
        val last = LessonStore.lessons(this).lastOrNull() ?: return
        val f = File(last, "transcript.txt")
        AppState.transcript = if (f.exists()) f.readText() else ""
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        if (!::startButton.isInitialized) return
        val rec = AppState.recording
        startButton.text = if (rec) "■  Остановить запись" else "●  Начать запись"
        (startButton.background as? GradientDrawable)?.setColor(if (rec) RED else BLUE)
        subjectEdit.isEnabled = !rec

        val lines = mutableListOf<String>()
        if (rec) {
            val sec = (System.currentTimeMillis() - AppState.startedAtMs) / 1000
            lines += "Идёт запись: %d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
        } else if (AppState.serviceRunning) {
            lines += "Запись остановлена, дорасшифровываю…"
        }
        if (rec || AppState.serviceRunning || AppState.done > 0) {
            lines += "Расшифровано частей: ${AppState.done} · в очереди: ${AppState.queued}" +
                (if (AppState.failed > 0) " · ошибок: ${AppState.failed}" else "")
        }
        AppState.lastError?.let { lines += "⚠ $it" }
        if (lines.isEmpty()) lines += "Готово к записи. Положите телефон микрофоном к преподавателю."
        statusText.text = lines.joinToString("\n")

        levelBar.visibility = if (rec) View.VISIBLE else View.GONE
        levelBar.progress = (AppState.level * 100).toInt()

        val t = AppState.transcript
        if (transcriptText.text.toString() != t) {
            transcriptText.text = t.ifEmpty { "Здесь появится текст урока — по частям, примерно раз в 5 минут." }
        }

        val key = prefs.groqKey
        keyStatus.text = if (key.isBlank()) "Ключ Groq не задан" else "Ключ Groq сохранён (…${key.takeLast(4)})"

        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryButton.text = if (ignoring) "Экономия батареи отключена ✓" else "Разрешить работу в фоне (батарея)"
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "Конспектор"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F2A44"))
        })
        col.addView(TextView(this).apply {
            text = "Записывает урок, расшифровывает через Groq и складывает текст в папку для конспекта."
            textSize = 14f
            setTextColor(Color.parseColor("#5A6478"))
            setPadding(0, dp(4), 0, dp(12))
        })

        subjectEdit = EditText(this).apply {
            hint = "Предмет или тема (например: Матан, лекция 5)"
            setText(prefs.lastSubject)
            setSingleLine()
        }
        col.addView(subjectEdit, matchWrap())

        startButton = Button(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(BLUE) }
            setOnClickListener { onStartStop() }
        }
        col.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(12)
        })

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(levelBar, matchWrap().apply { topMargin = dp(8) })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#1F2A44"))
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(statusText, matchWrap())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("Поделиться текстом") { onShare() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(smallButton("Повторить ошибки") { onRetry() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(8)
        })
        col.addView(row, matchWrap())

        col.addView(sectionTitle("Текст урока"))
        transcriptText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#F3F5F9"))
            }
        }
        col.addView(transcriptText, matchWrap())

        col.addView(sectionTitle("Настройки"))
        keyStatus = TextView(this).apply { textSize = 14f }
        col.addView(keyStatus)
        keyEdit = EditText(this).apply {
            hint = "Вставьте ключ Groq (gsk_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        col.addView(keyEdit, matchWrap())
        col.addView(smallButton("Сохранить ключ") { onSaveKey() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        batteryButton = smallButton("") { onBattery() }
        col.addView(batteryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
        folderText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5A6478"))
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(folderText)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col)
            isFillViewport = true
        }
        applyInsets(scroll, col, pad)
        render()
        return scroll
    }

    /** Android 15+ draws apps edge-to-edge; keep content clear of the status/navigation bars and keyboard. */
    private fun applyInsets(root: View, content: View, pad: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.post {
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                content.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1F2A44"))
        setPadding(0, dp(20), 0, dp(8))
        gravity = Gravity.START
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_START = 1
        private const val REQ_RETRY = 2
        private val BLUE = Color.parseColor("#2E5BBA")
        private val RED = Color.parseColor("#C62828")
    }
}
