package app.konspektor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** The big round start/stop button. Breathes and reacts to the microphone level while recording. */
class RecordButton(context: Context) : View(context) {

    var recording = false
        set(v) { field = v; contentDescription = if (v) "Остановить запись" else "Начать запись"; invalidate() }
    var level = 0f
    var timerText = ""
    var hint = "Начать запись"

    private val density = resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    private val mic = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val rect = RectF()
    private var phase = 0f
    private var shownLevel = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Начать запись"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (270 * density).toInt()
        val w = min(MeasureSpec.getSize(widthMeasureSpec), max)
        setMeasuredDimension(w, w)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.70f
        val base = if (recording) Ui.RED else Ui.PRIMARY

        shownLevel += (level - shownLevel) * 0.25f
        if (recording) {
            phase += 0.045f
            val breathe = (sin(phase) + 1f) / 2f
            haloPaint.color = base
            haloPaint.alpha = 26
            canvas.drawCircle(cx, cy, r * (1.30f + 0.10f * breathe + 0.12f * shownLevel), haloPaint)
            haloPaint.alpha = 48
            canvas.drawCircle(cx, cy, r * (1.12f + 0.05f * breathe + 0.10f * shownLevel), haloPaint)
        } else {
            haloPaint.color = base
            haloPaint.alpha = 18
            canvas.drawCircle(cx, cy, r * 1.30f, haloPaint)
            haloPaint.alpha = 36
            canvas.drawCircle(cx, cy, r * 1.13f, haloPaint)
        }

        circlePaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
            lighten(base, 0.28f), base, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, circlePaint)

        if (recording) {
            val s = r * 0.17f
            rect.set(cx - s, cy - r * 0.38f - s, cx + s, cy - r * 0.38f + s)
            canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, whitePaint)
            bigText.textSize = r * 0.24f
            canvas.drawText(timerText, cx, cy + r * 0.16f, bigText)
            smallText.textSize = r * 0.12f
            canvas.drawText("нажми, чтобы остановить", cx, cy + r * 0.42f, smallText)
            postInvalidateOnAnimation()
        } else {
            val s = (r * 0.36f).toInt()
            mic.setBounds((cx - s / 2).toInt(), (cy - r * 0.45f).toInt(), (cx + s / 2).toInt(), (cy - r * 0.45f + s).toInt())
            mic.draw(canvas)
            bigText.textSize = r * 0.17f
            canvas.drawText(hint, cx, cy + r * 0.24f, bigText)
            smallText.textSize = r * 0.115f
            canvas.drawText("одно нажатие", cx, cy + r * 0.46f, smallText)
        }
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
    )
}
