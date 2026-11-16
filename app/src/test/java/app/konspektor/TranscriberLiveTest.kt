package app.konspektor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's real Groq request (verbose_json + prompt) on a recording with speech and a silent tail.
 * Run with: -Dkonspektor.groqKeyFile=<file with gsk_…> -Dkonspektor.audio=<speech + silence audio>
 */
class TranscriberLiveTest {
    private val keyFile = System.getProperty("konspektor.groqKeyFile").orEmpty()
    private val audio = File(System.getProperty("konspektor.audio").orEmpty())

    @Test
    fun transcribeAndFilter() {
        assumeTrue("no key/audio given", keyFile.isNotBlank() && File(keyFile).exists() && audio.exists())
        val key = Regex("gsk_[A-Za-z0-9]+").find(File(keyFile).readText())!!.value

        val prompt = WhisperFilter.composePrompt(Prefs.DEFAULT_HINT, "Алгебра", "Записываем число, классная работа.")
        val res = Transcriber.transcribe(audio, key, Prefs.DEFAULT_MODEL, prompt)
        assertTrue("segments", res.segments.isNotEmpty())
        val clean = WhisperFilter.clean(res.segments, prompt)
        println("raw:     ${res.text}")
        println("clean:   ${clean.text}")
        clean.removed.forEach { println("removed: ${it.reason}: ${it.segment.text.trim()}") }
        assertTrue(clean.text.contains("дискриминант"))
        assertFalse(clean.text.contains("Продолжение") || clean.text.contains("DimaTorzok"))

        // a prompt Groq rejects (over 896 bytes) must not cost the piece: the app retries without it
        val again = Transcriber.transcribe(audio, key, Prefs.DEFAULT_MODEL, "слишком длинная подсказка ".repeat(60))
        assertTrue(again.text.contains("дискриминант"))
        println("oversized prompt → retried without it: OK")
    }
}
