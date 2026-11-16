package app.konspektor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The big rounded-square start/stop button.
 * Idle: violet gradient with a microphone. Recording: warm red gradient, glowing edge,
 * timer and a live equalizer driven by the microphone level.
 */
class RecordButton(context: Context) : View(context) {

    var recording = false
        set(v) {
            if (field != v) history.fill(0f)
            field = v
            contentDescription = if (v) "Остановить запись" else "Начать запись"
            invalidate()
        }
    var level = 0f
    var timerText = "00:00"
    var subjectText = ""

    private val d = resources.displayMetrics.density
    private val sd = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Ui.BOLD
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Ui.MEDIUM
    }
    private val mic = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val rect = RectF()
    private val tmp = RectF()
    private val history = FloatArray(21)
    private var phase = 0f
    private var shown = 0f
    private var frame = 0

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Начать запись"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 6 * d
        rect.set(inset, inset, width - inset, height - inset)
        val corner = 30 * d
        val c1 = if (recording) Ui.RED else Ui.ACCENT
        val c2 = if (recording) Ui.RED2 else Ui.ACCENT2

        if (recording) {
            phase += 0.05f
            val breathe = (sin(phase) + 1f) / 2f
            for (i in 1..2) {
                val grow = (i * 2.5f + breathe * 2f + shown * 3f) * d
                tmp.set(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow)
                glow.strokeWidth = 2f * d
                glow.color = Ui.alpha(c1, (0.35f - i * 0.12f) * (0.6f + breathe * 0.4f))
                canvas.drawRoundRect(tmp, corner + grow, corner + grow, glow)
            }
        }

        fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, corner, corner, fill)
        // soft highlight in the upper half
        soft.shader = LinearGradient(0f, rect.top, 0f, rect.centerY(),
            Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, corner, corner, soft)

        val cx = rect.centerX()
        if (recording) drawRecording(canvas, cx) else drawIdle(canvas, cx)
    }

    private fun drawIdle(canvas: Canvas, cx: Float) {
        val h = rect.height()
        val circleR = min(30 * d, h * 0.17f)
        val cy = rect.top + h * 0.33f
        soft.shader = null
        soft.color = Color.argb(42, 255, 255, 255)
        canvas.drawCircle(cx, cy, circleR, soft)
        val s = (circleR * 1.05f).toInt()
        mic.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        mic.draw(canvas)

        title.textSize = 21 * sd
        canvas.drawText("Начать запись", cx, rect.top + h * 0.66f, title)
        caption.textSize = 13 * sd
        canvas.drawText(subjectText, cx, rect.top + h * 0.80f, caption)
    }

    private fun drawRecording(canvas: Canvas, cx: Float) {
        val h = rect.height()
        shown += (level - shown) * 0.3f
        if (frame++ % 3 == 0) {
            System.arraycopy(history, 1, history, 0, history.size - 1)
            history[history.size - 1] = shown
        }

        // "● REC" mark
        caption.textSize = 12 * sd
        caption.textAlign = Paint.Align.LEFT
        val blink = if ((phase / 1.5f).toInt() % 2 == 0) 255 else 90
        bar.alpha = blink
        canvas.drawCircle(rect.left + 22 * d, rect.top + 22 * d, 4 * d, bar)
        bar.alpha = 255
        canvas.drawText("ЗАПИСЬ · $subjectText", rect.left + 32 * d, rect.top + 26 * d, caption)
        caption.textAlign = Paint.Align.CENTER

        title.textSize = 40 * sd
        canvas.drawText(timerText, cx, rect.top + h * 0.46f, title)

        // equalizer
        val n = history.size
        val gap = 4 * d
        val bw = min(5 * d, (rect.width() * 0.62f - gap * (n - 1)) / n)
        val total = n * bw + (n - 1) * gap
        val baseY = rect.top + h * 0.68f
        val maxH = h * 0.16f
        for (i in 0 until n) {
            val v = max(0.06f, min(1f, history[i] * (0.7f + 0.3f * sin(phase * 3 + i))))
            val bh = maxH * v
            val x = cx - total / 2 + i * (bw + gap)
            bar.alpha = (110 + 145 * (i.toFloat() / n)).toInt()
            tmp.set(x, baseY - bh / 2, x + bw, baseY + bh / 2)
            canvas.drawRoundRect(tmp, bw / 2, bw / 2, bar)
        }
        bar.alpha = 255

        caption.textSize = 13 * sd
        canvas.drawText("нажми, чтобы остановить", cx, rect.top + h * 0.88f, caption)
        postInvalidateOnAnimation()
    }
}
