package app.konspektor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segments below are real Groq whisper-large-v3-turbo answers (2026-11-16) unless marked otherwise. */
class WhisperFilterTest {
    private fun seg(start: Double, end: Double, text: String) = WhisperFilter.Segment(start, end, text)

    /** Windows TTS reading a short algebra lesson, then 40 s of quiet room noise. */
    private val speechThenNoise = listOf(
        seg(0.0, 4.5, " Итак, тема сегодняшнего урока – квадратные уравнения."),
        seg(5.5, 13.2, " Квадратным называется уравнение вида x в квадрате плюс bx плюс c равно 0, где а не равно 0."),
        seg(14.1, 21.1, " Чтобы его решить, сначала находим дискриминант d равно b в квадрате минус 4ac."),
        seg(22.1, 26.7, " Если дискриминант больше 0, уравнение имеет два корня."),
        seg(26.7, 34.2, " Домашнее задание, параграф 21, номера 512 и 515"),
        seg(56.7, 75.2, " Продолжение следует..."),
    )

    @Test
    fun keepsTheLessonAndDropsTheCreditInventedOnSilence() {
        val r = WhisperFilter.clean(speechThenNoise, null)
        assertTrue(r.text.startsWith("Итак, тема сегодняшнего урока"))
        assertTrue(r.text.endsWith("номера 512 и 515"))
        assertFalse(r.text.contains("Продолжение"))
        assertEquals(listOf("титры YouTube"), r.removed.map { it.reason })
    }

    @Test
    fun roomNoiseAloneGivesNoText() {
        val r = WhisperFilter.clean(listOf(seg(0.0, 30.0, " Продолжение следует..."), seg(30.0, 40.0, " Субтитры создавал DimaTorzok")), null)
        assertEquals("", r.text)
        assertEquals(2, r.removed.size)
    }

    @Test
    fun fewWordsStretchedOverSilenceAreDropped() { // constructed: no known credit, just 3 words over 30 s
        val r = WhisperFilter.clean(listOf(seg(40.0, 70.0, " Ну что ж.")), null)
        assertEquals("", r.text)
        assertTrue(r.removed.single().reason.startsWith("тишина"))
    }

    @Test
    fun realSpeechStaysEvenWhenItLooksLikeTheFilter() { // constructed
        val segments = listOf(
            seg(0.0, 1.2, " Записываем."),
            seg(1.5, 9.5, " Итак, записываем тему урока: причастный оборот."),
            seg(10.0, 15.0, " Сегодня посмотрим фильм на английском с субтитрами и обсудим его."),
        )
        val r = WhisperFilter.clean(segments, null)
        assertEquals(segments.joinToString(" ") { it.text.trim() }, r.text)
        assertTrue(r.removed.isEmpty())
    }

    @Test
    fun soundTagsRepeatsLoopsAndPromptEcho() { // constructed
        val prompt = "Урок в школе. Учитель объясняет новую тему, ученики отвечают на вопросы."
        val r = WhisperFilter.clean(
            listOf(
                seg(0.0, 2.0, " [МУЗЫКА]"),
                seg(2.0, 5.0, " Открываем тетради."),
                seg(5.0, 7.0, " Открываем тетради."),
                seg(7.0, 11.0, " Учитель объясняет новую тему, ученики отвечают на вопросы."),
                seg(11.0, 16.0, " Я не знаю. Я не знаю. Я не знаю. Я не знаю."),
            ),
            prompt,
        )
        assertEquals("Открываем тетради. Я не знаю. Я не знаю.", r.text)
        assertEquals(listOf("нет речи", "повтор", "повтор подсказки"), r.removed.map { it.reason })
    }

    @Test
    fun promptFitsGroqLimitAndKeepsTheMostRecentText() {
        val hint = "Термины: дискриминант, биссектриса, фотосинтез."
        val tail = "Начало длинного куска. " + "слово ".repeat(400) + "номера 512 и 515"
        val p = WhisperFilter.composePrompt(hint, "Алгебра", tail)!!
        assertTrue(WhisperFilter.utf8(p) <= WhisperFilter.PROMPT_MAX_BYTES)
        assertTrue(p.startsWith("$hint Предмет: Алгебра."))
        assertTrue(p.endsWith("номера 512 и 515"))

        val huge = WhisperFilter.composePrompt("очень длинная подсказка ".repeat(100), null, "хвост")!!
        assertTrue(WhisperFilter.utf8(huge) <= WhisperFilter.PROMPT_MAX_BYTES)

        assertNull(WhisperFilter.composePrompt(null, null, null))
        assertEquals("текст", WhisperFilter.composePrompt("  ", null, "текст"))
    }
}
