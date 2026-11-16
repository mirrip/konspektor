package app.konspektor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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

/** Shared dark look (cards, headers, buttons) and the "start recording" flow used by several screens. */
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
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        if (back) {
            header.addView(iconButton("‹") { finish() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(14) })
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        subtitle?.let { titles.addView(label(it.uppercase(), Ui.MUTED).apply { letterSpacing = 0.12f }) }
        titles.addView(text(title, if (back) 24f else 28f, Ui.TEXT, Ui.BOLD).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { (lbl, onClick) ->
            header.addView(iconButton(lbl, onClick), LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        col.addView(header)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
        }
        setContentView(scroll)
        applyInsets(scroll, col, pad)
        return col
    }

    private fun applyInsets(root: View, content: View, pad: Int) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                content.setPadding(pad + i.left, pad / 2 + i.top, pad + i.right, pad + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(
                    pad + insets.systemWindowInsetLeft, pad / 2 + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    // ---------------------------------------------------------------- building blocks

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun text(s: CharSequence, size: Float, color: Int, face: Typeface = Ui.REGULAR) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = face
        setLineSpacing(0f, 1.15f)
        includeFontPadding = false
    }

    /** Small caps-like caption. */
    fun label(s: CharSequence, color: Int = Ui.MUTED) = text(s, 11.5f, color, Ui.MEDIUM)

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(1), it) }
    }

    /** Background with a touch ripple. */
    fun pressable(color: Int, radiusDp: Int, strokeColor: Int? = Ui.LINE) =
        RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), rounded(color, radiusDp, strokeColor), rounded(Color.WHITE, radiusDp))

    fun card(clickable: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (clickable) pressable(Ui.SURFACE, 20) else rounded(Ui.SURFACE, 20, Ui.LINE)
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }

    fun LinearLayout.addCard(card: View, topMarginDp: Int = 10) =
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topMarginDp)
        })

    fun section(parent: LinearLayout, title: String, count: String? = null, action: Pair<String, () -> Unit>? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(30), 0, dp(4))
        }
        row.addView(text(title, 19f, Ui.TEXT, Ui.BOLD))
        count?.let { row.addView(text("  $it", 15f, Ui.MUTED, Ui.MEDIUM)) }
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        action?.let { (lbl, onClick) -> row.addView(chip(lbl, Ui.ACCENT_SOFT, Ui.ACCENT, onClick)) }
        parent.addView(row)
    }

    fun chip(lbl: String, bg: Int, fg: Int, onClick: (() -> Unit)? = null) = text(lbl, 13f, fg, Ui.MEDIUM).apply {
        background = if (onClick != null) pressable(bg, 20, null) else rounded(bg, 20)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        gravity = Gravity.CENTER
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun primaryButton(lbl: String, color: Int = Ui.ACCENT, onClick: () -> Unit) = text(lbl, 15f, Color.WHITE, Ui.MEDIUM).apply {
        gravity = Gravity.CENTER
        background = pressable(color, 16, null)
        setOnClickListener { onClick() }
    }

    fun outlineButton(lbl: String, color: Int = Ui.TEXT, onClick: () -> Unit) = text(lbl, 15f, color, Ui.MEDIUM).apply {
        gravity = Gravity.CENTER
        background = pressable(Ui.SURFACE, 16)
        setOnClickListener { onClick() }
    }

    fun iconButton(lbl: String, onClick: () -> Unit) = text(lbl, 20f, Ui.TEXT, Ui.MEDIUM).apply {
        gravity = Gravity.CENTER
        background = pressable(Ui.SURFACE, 14)
        setOnClickListener { onClick() }
    }

    /** Rounded square with the subject's first letter. */
    fun subjectBadge(subject: String, sizeDp: Int = 44): TextView {
        val color = Ui.subjectColor(subject)
        return TextView(this).apply {
            text = if (subject == LessonStore.GENERAL) "•" else subject.trim().firstOrNull()?.uppercase() ?: "?"
            textSize = sizeDp / 2.5f
            gravity = Gravity.CENTER
            setTextColor(color)
            typeface = Ui.BOLD
            background = rounded(Ui.alpha(color, 0.16f), 14)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    fun divider() = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        }
    }

    /** Homework line with a round check. Tap the circle to mark done, long-press to delete. */
    fun homeworkRow(hw: Homework, showSubject: Boolean = true, onChanged: () -> Unit): View {
        val color = Ui.subjectColor(hw.subject)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val check = text(if (hw.done) "✓" else "", 13f, Ui.BG, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (hw.done) setColor(color) else { setColor(Color.TRANSPARENT); setStroke(dp(2), color) }
            }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        row.addView(check)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val main = text(hw.text, 15f, if (hw.done) Ui.MUTED else Ui.TEXT)
        if (hw.done) main.paintFlags = main.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        mid.addView(main)
        if (showSubject && hw.subject != LessonStore.GENERAL) {
            mid.addView(label(hw.subject, color).apply { setPadding(0, dp(4), 0, 0) })
        }
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            HomeworkStore.setDone(this, hw.id, !hw.done)
            row.animate().alpha(0.4f).setDuration(160).withEndAction { onChanged() }.start()
        }
        row.setOnLongClickListener {
            confirm("Удалить задание?", hw.text, "Удалить") {
                HomeworkStore.delete(this, hw.id)
                onChanged()
            }
            true
        }
        return row
    }

    fun spacer(hDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

    fun full(view: View, heightDp: Int? = null, topDp: Int = 0) = view.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightDp?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    fun emptyNote(s: String) = text(s, 14f, Ui.MUTED).apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Ui.BG, 18, Ui.LINE)
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun dialog() = AlertDialog.Builder(this, R.style.AppDialog)

    fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        dialog().setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun askText(title: String, hint: String, ok: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            backgroundTintList = ColorStateList.valueOf(Ui.ACCENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        dialog().setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Отмена", null)
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- recording flow

    /** Checks key and microphone permission, then starts recording a lesson of [subject]. */
    fun requestStart(subject: String) {
        if (AppState.recording) return
        if (prefs.groqKey.isBlank()) {
            confirm("Нужен ключ Groq", "Без ключа запись не расшифруется. Вставьте его в настройках.", "Открыть настройки") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        // Only the microphone is required. Notifications are NOT requested: recording works without them.
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
        if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
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
    val BG = Color.parseColor("#0B0E13")
    val SURFACE = Color.parseColor("#151A23")
    val SURFACE2 = Color.parseColor("#1C2330")
    val LINE = Color.parseColor("#232B38")
    val RIPPLE = Color.parseColor("#33FFFFFF")
    val TEXT = Color.parseColor("#EEF1F6")
    val MUTED = Color.parseColor("#8A94A6")
    val ACCENT = Color.parseColor("#8B7CFF")
    val ACCENT2 = Color.parseColor("#4C8DFF")
    val ACCENT_SOFT = Color.parseColor("#221F3D")
    val RED = Color.parseColor("#FF5468")
    val RED2 = Color.parseColor("#FF8A4C")
    val RED_SOFT = Color.parseColor("#33161D")
    val GREEN = Color.parseColor("#3DD68C")
    val GREEN_SOFT = Color.parseColor("#11291F")
    val AMBER = Color.parseColor("#F5B84B")
    val AMBER_SOFT = Color.parseColor("#2E2410")

    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val SUBJECT_COLORS = listOf(
        "#8B7CFF", "#4C8DFF", "#3DD68C", "#F5B84B", "#FF7A90", "#38C7E8", "#FF9F5A", "#C084FC", "#5EEAD4"
    ).map { Color.parseColor(it) }

    fun subjectColor(subject: String) =
        if (subject == LessonStore.GENERAL) MUTED else SUBJECT_COLORS[abs(subject.hashCode()) % SUBJECT_COLORS.size]

    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private val RU = Locale.forLanguageTag("ru")
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", RU).format(Date(ms))
    fun today(): String = SimpleDateFormat("EEEE, d MMMM", RU).format(Date())

    fun duration(sec: Long): String = when {
        sec < 60 -> "< 1 мин"
        sec < 3600 -> "${sec / 60} мин"
        else -> "${sec / 3600} ч ${sec / 60 % 60} мин"
    }

    fun timer(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    else "%02d:%02d".format(sec / 60, sec % 60)

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
