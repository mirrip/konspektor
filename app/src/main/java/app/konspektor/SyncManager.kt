package app.konspektor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-way sync with the private GitHub repository (text only, audio stays on the phone):
 *  - up:   finished lessons → inbox/<lessonId>/{transcript.txt, meta.json}; homework changes → homework/<id>.json
 *  - down: only what changed since the last sync (one compare request): index.json → subject, topic and
 *          number of this phone's lessons (info.json) and their konspekt.md; homework/ files.
 * A sync costs the same however big the repository grows: branch head + compare + the changed files.
 * Full pass (first sync, or 300+ changed files) lists only the root and homework/ directories.
 */
object SyncManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean(false)

    fun isConfigured(context: Context) = Prefs(context).githubToken.isNotBlank()

    /** Runs a sync soon; calls made while one is waiting are merged. */
    fun requestSync(context: Context) {
        val app = context.applicationContext
        if (!isConfigured(app)) return
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            runSync(app)
        }
    }

    private fun runSync(context: Context) {
        val prefs = Prefs(context)
        AppState.syncing = true
        AppState.changed()
        try {
            val repo = prefs.githubRepo
            val api = GitHubApi(prefs.githubToken, repo)
            val branch = prefs.githubBranch.ifBlank { api.checkRepo().also { prefs.githubBranch = it } }

            val sent = uploadLessons(context, api, repo)
            val hwSent = pushHomework(context, api)

            val head = try {
                api.headSha(branch)
            } catch (e: GitHubApi.Failure) {
                if (e.code == 404 || e.code == 422) prefs.githubBranch = "" // branch renamed: look it up again
                throw e
            }
            val last = prefs.lastHead
            val received = when {
                head == last -> 0
                else -> {
                    val changes = if (last.isBlank()) null else api.compare(last, head)
                    if (changes == null) fullPull(context, api, branch) else pullChanges(context, api, changes)
                }
            }
            prefs.lastHead = head
            prefs.lastSyncMs = System.currentTimeMillis()
            AppState.syncError = false
            AppState.syncMessage = buildList {
                if (sent > 0) add("отправлено уроков: $sent")
                if (received > 0) add("получено от Codex: $received")
                if (hwSent > 0) add("домашка: $hwSent")
            }.joinToString(" · ").ifEmpty { "всё актуально" }
            if (sent > 0 || received > 0 || hwSent > 0) AppState.syncVersion++
        } catch (e: GitHubApi.Failure) {
            AppState.syncError = true
            AppState.syncMessage = e.message
        } catch (e: Exception) {
            AppState.syncError = true
            AppState.syncMessage = "Ошибка синхронизации: ${e.message}"
        } finally {
            AppState.syncing = false
            AppState.changed()
        }
    }

    // ---------------------------------------------------------------- lessons up

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * Sends lessons whose transcription is complete and not yet in [repo] with this text. A lesson
     * Codex already sorted goes again (with "resent": true) if its parts were re-transcribed later.
     */
    private fun uploadLessons(context: Context, api: GitHubApi, repo: String): Int {
        var count = 0
        val device = Prefs(context).deviceId
        for (dir in LessonStore.lessonDirs(context)) {
            if (!File(dir, "done.txt").exists()) continue          // still recording / transcribing
            val transcript = LessonStore.transcriptText(dir)
            if (transcript.isBlank()) continue
            val hash = sha1(transcript)
            val marker = File(dir, "synced.txt")
            val was = marker.takeIf { it.exists() }?.readText()?.trim()
            if (was == "$repo:$hash" || was == hash) continue      // 0.6 wrote the bare hash
            val id = LessonStore.lessonId(context, dir)
            val info = LessonStore.info(dir)
            val meta = JSONObject().apply {
                put("lessonId", id)
                put("complete", true)
                put("started", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.startedMs)))
                put("started_ms", info.startedMs)
                put("duration_sec", info.durationSec)
                put("chunks", info.chunks)
                put("failed_chunks", info.failed)
                put("device", device)
                val hint = LessonStore.subject(dir)
                if (hint != LessonStore.GENERAL) put("subject_hint", hint)
                if (File(dir, "info.json").exists()) put("resent", true)
            }
            val base = "inbox/$id"
            api.writeText("$base/transcript.txt", transcript, "Урок $id", null)
            api.writeText("$base/meta.json", meta.toString(2), "Урок $id: meta", null)
            marker.writeText("$repo:$hash")
            count++
        }
        return count
    }

    // ---------------------------------------------------------------- homework up

    private fun pushHomework(context: Context, api: GitHubApi): Int {
        val prefs = Prefs(context)
        var count = 0
        for (id in prefs.deletedHomework) {                        // deletions made on the phone
            val path = "homework/$id.json"
            api.delete(path, prefs.remoteShas[path], "Домашка: удалено задание $id")
            prefs.remoteShas = prefs.remoteShas - path
            prefs.deletedHomework = prefs.deletedHomework - id
            count++
        }
        for (hw in HomeworkStore.all(context).filter { it.dirty }) { // new tasks and "done" ticks
            val path = "homework/${hw.id}.json"
            val sha = api.writeText(path, hw.toRepoJson().toString(2), "Домашка: ${hw.text.take(40)}", prefs.remoteShas[path])
            prefs.remoteShas = prefs.remoteShas + (path to sha)
            synchronized(HomeworkStore) {
                HomeworkStore.save(context, HomeworkStore.all(context).map {
                    if (it.id == hw.id && it.done == hw.done && it.text == hw.text) it.copy(dirty = false, synced = true) else it
                })
            }
            count++
        }
        return count
    }

    // ---------------------------------------------------------------- results down

    private fun pullChanges(context: Context, api: GitHubApi, changes: List<GitHubApi.Change>): Int {
        var n = 0
        val index = changes.firstOrNull { it.path == "index.json" }
        val konspekts = changes.filter { it.path.endsWith("/konspekt.md") && it.status != "removed" }.map { it.path }.toSet()
        if (index != null || konspekts.isNotEmpty()) {
            val lessons = if (index != null && index.status != "removed") downloadIndex(context, api, index.sha)
            else cachedIndex(context) ?: downloadIndex(context, api, null)
            if (lessons != null) n += applyLessons(context, api, lessons, konspekts)
        }
        val hw = changes.filter { isHomework(it.path) || isHomework(it.previousPath) }
        if (hw.isNotEmpty()) {
            val upserts = hw.filter { it.status != "removed" && isHomework(it.path) }.associate { it.path to it.sha }
            val gone = hw.filter { it.status == "removed" }.map { it.path } +
                hw.filter { it.status == "renamed" }.mapNotNull { it.previousPath }.filter(::isHomework)
            n += applyHomework(context, api, upserts, gone.toSet(), full = false)
        }
        return n
    }

    private fun fullPull(context: Context, api: GitHubApi, branch: String): Int {
        var n = 0
        val root = api.listDir(branch)
        root["index.json"]?.let { entry ->
            val lessons = if (Prefs(context).remoteShas["index.json"] == entry.sha) cachedIndex(context) ?: downloadIndex(context, api, entry.sha)
            else downloadIndex(context, api, entry.sha)
            if (lessons != null) n += applyLessons(context, api, lessons, emptySet())
        }
        val dir = root["homework"]
        val remote = if (dir?.isDir != true) emptyMap() else api.listDir(dir.sha)
            .filter { (name, e) -> !e.isDir && name.endsWith(".json") }
            .map { (name, e) -> "homework/$name" to e.sha }.toMap()
        n += applyHomework(context, api, remote, emptySet(), full = true)
        return n
    }

    private fun indexFile(context: Context) = File(LessonStore.root(context), ".index.json")

    private fun lessonsOf(text: String): JSONObject? =
        try { JSONObject(text).optJSONObject("lessons") } catch (_: Exception) { null }

    private fun cachedIndex(context: Context): JSONObject? =
        indexFile(context).takeIf { it.exists() }?.readText()?.let(::lessonsOf)

    private fun downloadIndex(context: Context, api: GitHubApi, sha: String?): JSONObject? {
        val text = api.readText("index.json") ?: return null
        indexFile(context).writeText(text)
        if (sha != null) Prefs(context).let { it.remoteShas = it.remoteShas + ("index.json" to sha) }
        return lessonsOf(text)
    }

    /** index.json → info.json of each lesson recorded on this phone, and its konspekt.md when new or changed. */
    private fun applyLessons(context: Context, api: GitHubApi, lessons: JSONObject, changedKonspekts: Set<String>): Int {
        val prefs = Prefs(context)
        val fetched = prefs.konspektPaths.toMutableMap()
        var n = 0
        for (dir in LessonStore.lessonDirs(context)) {
            val id = LessonStore.lessonId(context, dir)
            val entry = lessons.optJSONObject(id) ?: continue
            val info = JSONObject().apply {
                put("subject", entry.optString("subject"))
                put("topic", entry.optString("topic"))
                put("number", entry.optInt("number"))
            }.toString(2)
            val infoFile = File(dir, "info.json")
            if (!infoFile.exists() || infoFile.readText() != info) { infoFile.writeText(info); n++ }

            val path = entry.optString("path").trim('/')
            if (path.isEmpty()) continue
            val kPath = "$path/konspekt.md"
            if (kPath !in changedKonspekts && fetched[id] == kPath) continue
            val text = api.readText(kPath)
            val kFile = File(dir, "konspekt.md")
            if (text != null && (!kFile.exists() || kFile.readText() != text)) { kFile.writeText(text); n++ }
            fetched[id] = kPath
            prefs.konspektPaths = fetched // per lesson: an interrupted sync doesn't download it again
        }
        return n
    }

    private fun isHomework(path: String?) =
        path != null && path.startsWith("homework/") && path.endsWith(".json") && path.count { it == '/' } == 1

    private fun homeworkId(path: String) = path.removePrefix("homework/").removeSuffix(".json").toLongOrNull()

    /**
     * Applies homework files from GitHub (Codex's tasks and the other phone's ticks). Downloads happen
     * outside the lock so the UI never waits for the network; a task ticked meanwhile keeps the tick.
     * [full]: [upserts] is the whole homework/ directory, anything synced but missing there was removed.
     */
    private fun applyHomework(context: Context, api: GitHubApi, upserts: Map<String, String>, gone: Set<String>, full: Boolean): Int {
        val prefs = Prefs(context)
        val shas = prefs.remoteShas.toMutableMap()
        val snapshot = HomeworkStore.all(context).associateBy { it.id }
        val deleted = prefs.deletedHomework
        val downloaded = HashMap<Long, Pair<String, Homework>>()
        for ((path, sha) in upserts) {
            val id = homeworkId(path) ?: continue
            if (id in deleted) continue
            val mine = snapshot[id]
            if (mine?.dirty == true) continue                      // our change wins until it is sent
            if (mine != null && shas[path] == sha) continue          // nothing new
            val text = api.readText(path) ?: continue
            val hw = try { Homework.fromJson(JSONObject(text)) } catch (_: Exception) { continue }
            downloaded[id] = sha to hw.copy(id = id)
        }
        var n = 0
        synchronized(HomeworkStore) {
            val local = HomeworkStore.all(context).associateBy { it.id }.toMutableMap()
            for ((id, pair) in downloaded) {
                if (local[id]?.dirty == true) continue
                local[id] = pair.second.copy(dirty = false, synced = true)
                shas["homework/$id.json"] = pair.first
                n++
            }
            val removedIds = if (full) local.keys.filter { "homework/$it.json" !in upserts } else gone.mapNotNull(::homeworkId)
            for (id in removedIds) {
                val h = local[id] ?: continue
                if (h.synced && !h.dirty) { local.remove(id); shas.remove("homework/$id.json"); n++ }
            }
            HomeworkStore.save(context, local.values.sortedBy { it.createdMs })
        }
        prefs.remoteShas = shas
        return n
    }
}
