package app.konspektor

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/**
 * The homework section. Tasks come from Codex: the teacher says the homework out loud,
 * Codex finds it in the lesson text and adds it to homework.json. Adding by hand is secondary.
 */
class HomeworkActivity : BaseActivity() {

    private lateinit var listCol: LinearLayout
    private var showDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = page("Домашка", "задания", action = "+" to { addManual() })
        col.addView(text(
            "Задания находит Codex в записи урока — учитель произносит их вслух. " +
                "Отметь выполненное нажатием, долгое нажатие удаляет.",
            13.5f, Ui.MUTED
        ).apply { setPadding(dp(2), dp(10), 0, 0) })
        listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(full(listCol))
        col.addView(spacer(28))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listCol.removeAllViews()
        val all = HomeworkStore.all(this)
        val open = all.filter { !it.done }
        val done = all.filter { it.done }.sortedByDescending { it.createdMs }

        if (open.isEmpty()) {
            listCol.addView(full(emptyNote(
                if (all.isEmpty()) "Пока заданий нет. Как только Codex разберёт урок, где задали домашку, она появится здесь."
                else "Всё сделано ✓"
            ), null, 18))
        } else {
            // group open tasks by subject, subjects in a stable order
            val bySubject = open.groupBy { it.subject }.toSortedMap(compareBy({ it == LessonStore.GENERAL }, { it }))
            for ((subject, items) in bySubject) {
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(24), 0, dp(2))
                }
                head.addView(subjectBadge(subject, 28))
                head.addView(text(
                    if (subject == LessonStore.GENERAL) "Без предмета" else subject, 16.5f, Ui.TEXT, Ui.BOLD
                ).apply { setPadding(dp(10), 0, 0, 0) })
                head.addView(text("  ${items.size}", 14f, Ui.MUTED, Ui.MEDIUM))
                listCol.addView(head)
                val c = card()
                items.sortedBy { it.createdMs }.forEachIndexed { i, hw ->
                    if (i > 0) c.addView(divider())
                    c.addView(homeworkRow(hw, showSubject = false) { render() })
                    hwMeta(hw)?.let { c.addView(text(it, 12f, Ui.MUTED).apply { setPadding(dp(38), dp(4), 0, 0) }) }
                }
                listCol.addCard(c, 8)
            }
        }

        if (done.isNotEmpty()) {
            val toggle = text(
                (if (showDone) "Скрыть выполненные" else "Выполненные") + "  ·  ${done.size}",
                14f, Ui.ACCENT, Ui.MEDIUM
            ).apply {
                setPadding(dp(2), dp(28), 0, dp(4))
                setOnClickListener { showDone = !showDone; render() }
            }
            listCol.addView(toggle)
            if (showDone) {
                val c = card()
                done.forEachIndexed { i, hw ->
                    if (i > 0) c.addView(divider())
                    c.addView(homeworkRow(hw) { render() })
                }
                listCol.addCard(c, 8)
            }
        }
    }

    private fun hwMeta(hw: Homework): String? {
        val parts = mutableListOf<String>()
        hw.due?.let { parts += "срок: $it" }
        if (hw.fromCodex) parts += "из урока" else parts += "добавлено вручную"
        return parts.joinToString(" · ")
    }

    private fun addManual() {
        askText("Новое задание", "Например: №12 и 15 к пятнице", "Дальше") { task ->
            val subjects = Subjects.list(this)
            if (subjects.isEmpty()) {
                HomeworkStore.add(this, LessonStore.GENERAL, task)
                render()
                return@askText
            }
            val items = subjects + "Без предмета"
            dialog().setTitle("По какому предмету?")
                .setItems(items.toTypedArray()) { _, which ->
                    HomeworkStore.add(this, if (which < subjects.size) subjects[which] else LessonStore.GENERAL, task)
                    render()
                }
                .show()
        }
    }
}
