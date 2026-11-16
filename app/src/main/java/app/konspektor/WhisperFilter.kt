package app.konspektor

import kotlin.math.roundToInt

/**
 * Removes what Whisper invents before the text becomes part of a lesson (and reaches Codex).
 *
 * Whisper does not follow instructions such as "do not make things up": the prompt is read as text
 * spoken before the recording. On silence it "remembers" YouTube subtitle credits ("Продолжение
 * следует…", "Субтитры сделал DimaTorzok") and loops. Groq's whisper-large-v3-turbo reports
 * no_speech_prob = 0 even for those (checked 2026-11-16), so they are recognised by what they look
 * like: a few words stretched over many seconds, known credit lines, repeats, echoes of the prompt.
 */
object WhisperFilter {

    data class Segment(val start: Double, val end: Double, val text: String)
    data class Removed(val segment: Segment, val reason: String)
    data class Result(val text: String, val removed: List<Removed>)

    /** Groq rejects prompts longer than 896 bytes of UTF-8 ("invalid_prompt"). */
    const val PROMPT_MAX_BYTES = 880

    /** Real speech is 1.5–3 words per second; credits invented on silence are 2 words over 10–30 s. */
    private const val SILENCE_MIN_SEC = 6.0
    private const val SILENCE_MAX_WORDS_PER_SEC = 0.4

    // declared before CREDITS: object fields initialise top to bottom and CREDITS uses normalize()
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val SOUND_TAGS = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    private val CREDITS = listOf(
        "продолжение следует", "субтитры сделал", "субтитры создавал", "субтитры делал",
        "субтитры подогнал", "субтитры подготовил", "субтитры сделаны", "субтитры предоставлены",
        "редактор субтитров", "корректор субтитров", "автор субтитров", "dimatorzok", "dima torzok",
        "amara.org", "спасибо за просмотр", "благодарю за просмотр", "подписывайтесь на канал",
        "подписывайтесь на наш канал", "подпишитесь на канал", "ставьте лайк",
    ).map(::normalize)

    fun normalize(s: String): String = NON_WORD.replace(s.lowercase().replace('ё', 'е'), " ").trim()

    private fun words(norm: String) = norm.split(' ').count { it.isNotEmpty() }

    fun clean(segments: List<Segment>, prompt: String?): Result {
        val kept = ArrayList<String>()
        val removed = ArrayList<Removed>()
        val promptNorm = normalize(prompt.orEmpty())
        var lastNorm = ""
        for (s in segments) {
            val text = s.text.trim()
            if (text.isEmpty()) continue
            val norm = normalize(text)
            val n = words(norm)
            val dur = s.end - s.start
            val reason = when {
                words(normalize(SOUND_TAGS.replace(text, " "))) == 0 -> "нет речи"
                n <= 12 && CREDITS.any { norm.contains(it) } -> "титры YouTube"
                dur >= SILENCE_MIN_SEC && n / dur < SILENCE_MAX_WORDS_PER_SEC ->
                    "тишина: $n сл. за ${dur.roundToInt()} с"
                norm == lastNorm -> "повтор"
                n >= 4 && promptNorm.contains(norm) -> "повтор подсказки"
                else -> null
            }
            if (reason != null) {
                removed += Removed(s, reason)
                continue
            }
            kept += collapseRepeats(text)
            lastNorm = norm
        }
        return Result(kept.joinToString(" ").trim(), removed)
    }

    /** "Я не знаю. Я не знаю. Я не знаю." → at most two identical sentences in a row. */
    fun collapseRepeats(text: String): String {
        val parts = SENTENCE_END.split(text)
        if (parts.size < 3) return text
        val out = ArrayList<String>()
        var run = 0
        for (p in parts) {
            val same = out.isNotEmpty() && normalize(p).isNotEmpty() && normalize(out.last()) == normalize(p)
            run = if (same) run + 1 else 0
            if (run < 2) out += p
        }
        return out.joinToString(" ")
    }

    /**
     * Prompt for one piece: the user's hint, the lesson's subject and the end of the previous piece
     * (most recent text last: Whisper keeps the end of a long prompt). Fits Groq's byte limit.
     */
    fun composePrompt(hint: String?, subject: String?, previous: String?): String? {
        val head = prefixWithin(
            listOfNotNull(
                hint?.trim()?.takeIf { it.isNotEmpty() },
                subject?.trim()?.takeIf { it.isNotEmpty() }?.let { "Предмет: $it." },
            ).joinToString(" "),
            PROMPT_MAX_BYTES,
        )
        val budget = PROMPT_MAX_BYTES - utf8(head) - (if (head.isEmpty()) 0 else 1)
        val tail = if (budget <= 0) "" else suffixWithin(previous?.trim().orEmpty(), budget)
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun suffixWithin(s: String, maxBytes: Int): String {
        if (utf8(s) <= maxBytes) return s
        var bytes = 0
        var i = s.length
        while (i > 0) {
            val cp = s.codePointBefore(i)
            val n = utf8(String(Character.toChars(cp)))
            if (bytes + n > maxBytes) break
            bytes += n
            i -= Character.charCount(cp)
        }
        val cut = s.substring(i)
        // start at a word boundary
        val space = cut.indexOf(' ')
        return if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
    }

    private fun prefixWithin(s: String, maxBytes: Int): String {
        if (utf8(s) <= maxBytes) return s
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = utf8(String(Character.toChars(cp)))
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i).trimEnd()
    }
}
