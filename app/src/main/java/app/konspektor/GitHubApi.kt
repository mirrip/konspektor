package app.konspektor

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Minimal GitHub REST client for one repository. Every request stays small however big the
 * repository grows: the phone asks for the branch head, then only for what changed since the commit
 * it saw last (compare), and lists single directories, never the whole tree.
 */
class GitHubApi(private val token: String, private val repo: String) {

    class Failure(message: String, val code: Int) : Exception(message)

    data class Response(val code: Int, val body: String)

    /** One changed file between two commits; [status] is added / modified / removed / renamed / … */
    data class Change(val path: String, val status: String, val sha: String, val previousPath: String?)

    /** Directory entry: blob or tree with its SHA. */
    data class Entry(val sha: String, val isDir: Boolean)

    private val api = "https://api.github.com/repos/$repo"

    private fun encodePath(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun call(method: String, url: String, body: JSONObject? = null, accept: String = JSON): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
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
                403 -> if (msg.contains("rate limit", true)) "GitHub временно ограничил запросы, повторю позже"
                else "У ключа нет прав на запись в репозиторий"
                404 -> "Репозиторий $repo не найден или ключ не даёт к нему доступа"
                else -> "$what: GitHub ответил ${r.code} $msg"
            },
            r.code,
        )
    }

    /** Checks access; returns the default branch. */
    fun checkRepo(): String {
        val r = call("GET", api)
        if (r.code != 200) fail(r, "Проверка репозитория")
        val o = JSONObject(r.body)
        val push = o.optJSONObject("permissions")?.optBoolean("push", true) ?: true
        if (!push) throw Failure("У ключа нет права записи в $repo", 403)
        return o.optString("default_branch", "main")
    }

    /** SHA of the commit the branch points to: a 40-byte answer, the cheap "anything new?" check. */
    fun headSha(branch: String): String {
        val r = call("GET", "$api/commits/${encodePath(branch)}", accept = "application/vnd.github.sha")
        if (r.code != 200) fail(r, "Проверка изменений")
        return r.body.trim()
    }

    /**
     * Files changed between two commits, or null when GitHub can't say exactly: unknown [base]
     * (history rewritten), 300+ files (GitHub lists at most 300), a diff too big to compute —
     * then the caller syncs in full. Only key problems are errors.
     */
    fun compare(base: String, head: String): List<Change>? {
        val r = call("GET", "$api/compare/$base...$head?per_page=1")
        if (r.code == 401 || r.code == 403) fail(r, "Список изменений")
        if (r.code != 200) return null
        val o = JSONObject(r.body)
        val files = o.optJSONArray("files") ?: return if (o.optInt("total_commits", -1) == 0) emptyList() else null
        if (files.length() >= 300) return null
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            Change(f.getString("filename"), f.optString("status"), f.optString("sha"),
                f.optString("previous_filename").ifEmpty { null })
        }
    }

    /** Entries of one directory (not recursive). [treeish] is a branch name for the root or a tree SHA. */
    fun listDir(treeish: String): Map<String, Entry> {
        val r = call("GET", "$api/git/trees/${encodePath(treeish)}")
        if (r.code == 404 || r.code == 409) return emptyMap() // empty repository
        if (r.code != 200) fail(r, "Список файлов")
        val arr = JSONObject(r.body).getJSONArray("tree")
        return (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            o.getString("path") to Entry(o.getString("sha"), o.optString("type") == "tree")
        }
    }

    fun readText(path: String): String? {
        val r = call("GET", "$api/contents/${encodePath(path)}", accept = RAW)
        if (r.code == 404) return null
        if (r.code != 200) fail(r, "Чтение $path")
        return r.body
    }

    /** Current blob SHA of a file, or null if there is no such file. */
    fun fileSha(path: String): String? {
        val r = call("GET", "$api/contents/${encodePath(path)}")
        if (r.code == 404) return null
        if (r.code != 200) fail(r, "Чтение $path")
        return JSONObject(r.body).optString("sha").ifEmpty { null }
    }

    /** Creates or updates a file; returns the new blob SHA. A stale or missing [sha] is recovered once. */
    fun writeText(path: String, text: String, message: String, sha: String?): String {
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            if (sha != null) put("sha", sha)
        }
        var r = call("PUT", "$api/contents/${encodePath(path)}", body)
        if (r.code == 409 || r.code == 422) {
            val current = fileSha(path)
            if (current != null && current != sha) {
                body.put("sha", current)
                r = call("PUT", "$api/contents/${encodePath(path)}", body)
            }
        }
        if (r.code !in 200..201) fail(r, "Запись $path")
        return JSONObject(r.body).getJSONObject("content").getString("sha")
    }

    /** Deletes a file; already gone is fine. A stale [sha] is recovered once. */
    fun delete(path: String, sha: String?, message: String) {
        val known = sha ?: fileSha(path) ?: return
        val body = JSONObject().put("message", message).put("sha", known)
        var r = call("DELETE", "$api/contents/${encodePath(path)}", body)
        if (r.code == 409 || r.code == 422) {
            val current = fileSha(path) ?: return
            body.put("sha", current)
            r = call("DELETE", "$api/contents/${encodePath(path)}", body)
        }
        if (r.code != 200 && r.code != 404) fail(r, "Удаление $path")
    }

    private companion object {
        const val JSON = "application/vnd.github+json"
        const val RAW = "application/vnd.github.raw+json"
    }
}
