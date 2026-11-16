package app.konspektor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Shared look (colors, cards, headers) and the "start recording" flow used by several screens. */
abstract class BaseActivity : Activity() {

    protected val prefs by lazy { Prefs(this) }
    private var pendingSubject: String? = null

    // ---------------------------------------------------------------- page skeleton

    /** Builds a scrollable page with a header and returns the column to fill. */
    protected fun page(
        title: String,
        subtitle: String? = null,
        back: Boolean = true,
        action: Pair<String, () -> Unit>? = null,
    ): LinearLayout {
        val pad = dp(18)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (back) {
            header.addView(iconButton("←") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(title, 24f, Ui.TEXT, bold = true))
        subtitle?.let { titles.addView(text(it, 14f, Ui.MUTED)) }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { (label, onClick) ->
            header.addView(iconButton(label, onClick), LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        col.addView(header)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)
        applyInsets(scroll, col, pad)
        return col
    }

    private fun applyInsets(root: View, content: View, pad: Int) {
        if (Build.VERSION.SDK_INT < 35) {         // Android 15+ draws behind the bars, colors come from the page
            @Suppress("DEPRECATION")
            window.statusBarColor = Ui.BG
            @Suppress("DEPRECATION")
            window.navigationBarColor = Ui.BG
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.post {
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(light, light)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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

    // ---------------------------------------------------------------- building blocks

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(1), it) }
    }

    fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 18, Ui.LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        elevation = dp(1).toFloat()
    }

    fun LinearLayout.addCard(card: View, topMarginDp: Int = 10) =
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topMarginDp)
        })

    fun section(parent: LinearLayout, title: String, action: Pair<String, () -> Unit>? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(26), 0, dp(2))
        }
        row.addView(text(title, 19f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { (label, onClick) -> row.addView(pill(label, Ui.CHIP_BG, Ui.PRIMARY, onClick)) }
        parent.addView(row)
    }

    fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 14f, fg, bold = true).apply {
        background = rounded(bg, 20)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    fun primaryButton(label: String, color: Int = Ui.PRIMARY, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun outlineButton(label: String, color: Int = Ui.PRIMARY, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(color)
        background = rounded(Color.WHITE, 14, Ui.LINE)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun iconButton(label: String, onClick: () -> Unit) = text(label, 20f, Ui.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(Color.WHITE, 22, Ui.LINE)
        setOnClickListener { onClick() }
    }

    /** Colored circle with the subject's first letter. */
    fun subjectBadge(subject: String, sizeDp: Int = 44) = TextView(this).apply {
        text = subject.trim().firstOrNull()?.uppercase() ?: "?"
        textSize = sizeDp / 2.6f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.subjectColor(subject)) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun spacer(hDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

    fun full(view: View, heightDp: Int? = null, topDp: Int = 0) = view.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightDp?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun askText(title: String, hint: String, ok: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Отмена", null)
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- recording flow

    /** Checks key and permissions, then starts recording a lesson of [subject]. */
    fun requestStart(subject: String) {
        if (AppState.recording) return
        if (prefs.groqKey.isBlank()) {
            confirm("Нужен ключ Groq", "Без ключа запись не расшифруется. Вставьте его в настройках.", "Открыть настройки") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingSubject = subject
            requestPermissions(missing.toTypedArray(), REQ_START)
            return
        }
        prefs.currentSubject = subject
        AppState.transcript = ""
        RecorderService.start(this, subject)
    }

    fun requestRetry() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(neededPermissions().toTypedArray(), REQ_RETRY)
            return
        }
        RecorderService.retry(this)
        toast("Отправляю нерасшифрованные части заново")
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
            REQ_START -> pendingSubject?.let { requestStart(it) }
            REQ_RETRY -> requestRetry()
        }
    }

    companion object {
        private const val REQ_START = 1
        private const val REQ_RETRY = 2
    }
}

object Ui {
    val BG = Color.parseColor("#F4F6FB")
    val TEXT = Color.parseColor("#1C2333")
    val MUTED = Color.parseColor("#6B7385")
    val LINE = Color.parseColor("#E3E7F0")
    val PRIMARY = Color.parseColor("#3B5BDB")
    val CHIP_BG = Color.parseColor("#E7ECFF")
    val RED = Color.parseColor("#E5484D")
    val RED_BG = Color.parseColor("#FDECEC")
    val GREEN = Color.parseColor("#2F9E44")
    val GREEN_BG = Color.parseColor("#E6F6EA")
    val AMBER = Color.parseColor("#C77700")
    val AMBER_BG = Color.parseColor("#FFF4E0")

    private val SUBJECT_COLORS = listOf(
        "#5B7FFF", "#F2994A", "#27AE60", "#9B51E0", "#EB5757", "#2D9CDB", "#E2B93B", "#16A085", "#D35FA0"
    ).map { Color.parseColor(it) }

    fun subjectColor(subject: String) = SUBJECT_COLORS[abs(subject.hashCode()) % SUBJECT_COLORS.size]

    private val RU = Locale.forLanguageTag("ru")
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", RU).format(Date(ms))
    fun today(): String = SimpleDateFormat("EEEE, d MMMM", RU).format(Date()).replaceFirstChar { it.uppercase() }

    fun duration(sec: Long): String = when {
        sec < 60 -> "меньше минуты"
        sec < 3600 -> "${sec / 60} мин"
        else -> "${sec / 3600} ч ${sec / 60 % 60} мин"
    }

    fun timer(sec: Long) = "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }
}
