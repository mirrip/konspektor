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

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    /**
     * @param prompt tail of the previous chunk's text; helps Whisper keep terms and spelling consistent.
     */
    fun transcribe(audio: File, apiKey: String, model: String, prompt: String?): String {
        val boundary = "----konspektor" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model)
        field("language", "ru")
        field("response_format", "json")
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
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(json).optString("text", "").trim()
            }
            val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = try { JSONObject(err).getJSONObject("error").optString("message", err) } catch (_: Exception) { err }
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
