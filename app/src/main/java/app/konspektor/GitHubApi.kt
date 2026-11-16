package app.konspektor

import org.json.JSONObject
import java.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal GitHub REST client for one repository (Contents + Git trees). */
class GitHubApi(private val token: String, private val repo: String) {

    class Failure(message: String, val code: Int) : Exception(message)

    data class Response(val code: Int, val body: String)

    private fun encodePath(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun call(method: String, url: String, body: JSONObject? = null, raw: Boolean = false): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Konspektor-Android")
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } catch (e: IOException) {
            throw Failure("Нет связи с GitHub: ${e.message}", 0)
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(r: Response, what: String): Nothing {
        val msg = try { JSONObject(r.body).optString("message") } catch (_: Exception) { "" }
        throw Failure(
            when (r.code) {
                401 -> "GitHub не принял ключ (истёк или неверный)"
                403 -> "У ключа нет прав на запись в репозиторий"
                404 -> "Репозиторий $repo не найден или ключ не даёт к нему доступа"
                else -> "$what: GitHub ответил ${r.code} $msg"
            },
            r.code,
        )
    }

    /** Checks access; returns the default branch. */
    fun checkRepo(): String {
        val r = call("GET", "https://api.github.com/repos/$repo")
        if (r.code != 200) fail(r, "Проверка репозитория")
        val o = JSONObject(r.body)
        val push = o.optJSONObject("permissions")?.optBoolean("push", true) ?: true
        if (!push) throw Failure("У ключа нет права записи в $repo", 403)
        return o.optString("default_branch", "main")
    }

    /** All files of the branch: path → blob sha. */
    fun tree(branch: String): Map<String, String> {
        val r = call("GET", "https://api.github.com/repos/$repo/git/trees/${encodePath(branch)}?recursive=1")
        if (r.code == 409) return emptyMap() // empty repository
        if (r.code != 200) fail(r, "Список файлов")
        val arr = JSONObject(r.body).getJSONArray("tree")
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "blob") out[o.getString("path")] = o.getString("sha")
        }
        return out
    }

    fun readText(path: String): String? {
        val r = call("GET", "https://api.github.com/repos/$repo/contents/${encodePath(path)}", raw = true)
        if (r.code == 404) return null
        if (r.code != 200) fail(r, "Чтение $path")
        return r.body
    }

    /** Creates or updates a file; returns the new blob sha. [sha] is required when the file exists. */
    fun writeText(path: String, text: String, message: String, sha: String?): String {
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            if (sha != null) put("sha", sha)
        }
        var r = call("PUT", "https://api.github.com/repos/$repo/contents/${encodePath(path)}", body)
        if ((r.code == 409 || r.code == 422) ) {
            // our sha was stale (or missing): fetch the current one and retry once
            val cur = call("GET", "https://api.github.com/repos/$repo/contents/${encodePath(path)}")
            if (cur.code == 200) {
                body.put("sha", JSONObject(cur.body).getString("sha"))
                r = call("PUT", "https://api.github.com/repos/$repo/contents/${encodePath(path)}", body)
            }
        }
        if (r.code !in 200..201) fail(r, "Запись $path")
        return JSONObject(r.body).getJSONObject("content").getString("sha")
    }

    fun delete(path: String, sha: String, message: String) {
        val body = JSONObject().put("message", message).put("sha", sha)
        val r = call("DELETE", "https://api.github.com/repos/$repo/contents/${encodePath(path)}", body)
        if (r.code != 200 && r.code != 404) fail(r, "Удаление $path")
    }
}
