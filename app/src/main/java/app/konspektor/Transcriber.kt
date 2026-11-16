package app.konspektor

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Speech-to-text through Groq's Whisper API (OpenAI-compatible endpoint). */
object Transcriber {

    class Failure(message: String, val retryable: Boolean, val retryAfterSec: Long? = null) : Exception(message)

    /** Whole text plus Whisper's segments with timings (used to cut out invented phrases). */
    data class Result(val text: String, val segments: List<WhisperFilter.Segment>)

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    /**
     * @param prompt text Whisper reads as if spoken right before the audio (see [WhisperFilter.composePrompt]);
     * it shapes style and spelling of terms, it is not an instruction.
     */
    fun transcribe(audio: File, apiKey: String, model: String, prompt: String?): Result {
        val boundary = "----konspektor" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model)
        field("language", "ru")
        field("response_format", "verbose_json")
        field("temperature", "0")
        if (!prompt.isNullOrBlank()) field("prompt", prompt)
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n").toByteArray()
        )
        body.write(audio.readBytes())
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(body.size())
        }
        try {
            conn.outputStream.use { body.writeTo(it) }
            val code = conn.responseCode
            if (code == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = json.optJSONArray("segments")
                val segments = if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    WhisperFilter.Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), s.optString("text"))
                }
                return Result(json.optString("text", "").trim(), segments)
            }
            val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = try { JSONObject(err).getJSONObject("error").optString("message", err) } catch (_: Exception) { err }
            // a prompt Groq doesn't accept must never cost the recording: try once more without it
            if (code == 400 && !prompt.isNullOrBlank() && err.contains("prompt")) {
                conn.disconnect()
                return transcribe(audio, apiKey, model, null)
            }
            throw when (code) {
                401, 403 -> Failure("Ключ Groq не принят ($code): $msg", retryable = false)
                400, 413, 415 -> Failure("Groq отклонил файл ($code): $msg", retryable = false)
                429 -> Failure(
                    "Лимит Groq ($code), жду: $msg", retryable = true,
                    retryAfterSec = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.toLong()
                )
                else -> Failure("Ошибка Groq ($code): $msg", retryable = true)
            }
        } catch (e: Failure) {
            throw e
        } catch (e: IOException) {
            throw Failure("Нет связи: ${e.message}", retryable = true)
        } finally {
            conn.disconnect()
        }
    }
}
