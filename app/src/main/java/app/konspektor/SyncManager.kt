package app.konspektor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-way sync with the private GitHub repository (text only, audio stays on the phone):
 *  - up:   finished lessons → inbox/<lessonId>/{transcript.txt, meta.json}; homework changes → homework/<id>.json
 *  - down: index.json → subject/topic of each lesson (info.json) and its konspekt.md; homework/ files
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
            val api = GitHubApi(prefs.githubToken, prefs.githubRepo)
            val branch = api.checkRepo()
            var tree = api.tree(branch)
            val sent = uploadLessons(context, api, tree)
            if (sent > 0) tree = api.tree(branch)
            val hwSent = pushHomework(context, api, tree)
            if (hwSent > 0) tree = api.tree(branch)
            val received = pullResults(context, api, tree)
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

    /** Sends lessons whose transcription is complete and not yet sent (or changed after a retry). */
    private fun uploadLessons(context: Context, api: GitHubApi, tree: Map<String, String>): Int {
        var count = 0
        for (dir in LessonStore.lessonDirs(context)) {
            if (!File(dir, "done.txt").exists()) continue          // still recording / transcribing
            if (File(dir, "info.json").exists()) continue          // Codex already sorted it
            val transcript = LessonStore.transcriptText(dir)
            if (transcript.isBlank()) continue
            val id = LessonStore.lessonId(context, dir)
            val hash = sha1(transcript)
            val marker = File(dir, "synced.txt")
            if (marker.exists() && marker.readText().trim() == hash) continue

            val info = LessonStore.info(dir)
            val meta = JSONObject().apply {
                put("lessonId", id)
                put("complete", true)
                put("started", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(info.startedMs)))
                put("started_ms", info.startedMs)
                put("duration_sec", info.durationSec)
                put("chunks", info.chunks)
                put("failed_chunks", info.failed)
                put("device", Prefs(context).deviceId)
                val hint = LessonStore.subject(dir)
                if (hint != LessonStore.GENERAL) put("subject_hint", hint)
            }
            val base = "inbox/$id"
            api.writeText("$base/transcript.txt", transcript, "Урок $id", tree["$base/transcript.txt"])
            api.writeText("$base/meta.json", meta.toString(2), "Урок $id: meta", tree["$base/meta.json"])
            marker.writeText(hash)
            count++
        }
        return count
    }

    // ---------------------------------------------------------------- homework up

    private fun pushHomework(context: Context, api: GitHubApi, tree: Map<String, String>): Int {
        val prefs = Prefs(context)
        var count = 0
        // deletions made on the phone
        val deleted = prefs.deletedHomework
        for (id in deleted) {
            val path = "homework/$id.json"
            tree[path]?.let { api.delete(path, it, "Домашка: удалено задание $id") }
            prefs.deletedHomework = prefs.deletedHomework - id
            count++
        }
        // new tasks and "done" ticks
        val shas = prefs.remoteShas.toMutableMap()
        for (hw in HomeworkStore.all(context).filter { it.dirty }) {
            val path = "homework/${hw.id}.json"
            val newSha = api.writeText(path, hw.toRepoJson().toString(2), "Домашка: ${hw.text.take(40)}", tree[path])
            shas[path] = newSha
            synchronized(HomeworkStore) {
                HomeworkStore.save(context, HomeworkStore.all(context).map {
                    if (it.id == hw.id && it.done == hw.done && it.text == hw.text) it.copy(dirty = false, synced = true) else it
                })
            }
            count++
        }
        prefs.remoteShas = shas
        return count
    }

    // ---------------------------------------------------------------- results down

    private fun pullResults(context: Context, api: GitHubApi, tree: Map<String, String>): Int {
        val prefs = Prefs(context)
        val shas = prefs.remoteShas.toMutableMap()
        var changed = 0

        // lessons: index.json tells where Codex put each lesson
        val indexSha = tree["index.json"]
        val indexText = when {
            indexSha == null -> null
            shas["index.json"] == indexSha -> File(LessonStore.root(context), ".index.json").takeIf { it.exists() }?.readText()
                ?: api.readText("index.json")
            else -> api.readText("index.json")
        }
        if (indexText != null && indexSha != null) {
            File(LessonStore.root(context), ".index.json").writeText(indexText)
            shas["index.json"] = indexSha
            val lessons = try { JSONObject(indexText).optJSONObject("lessons") } catch (_: Exception) { null }
            if (lessons != null) {
                for (dir in LessonStore.lessonDirs(context)) {
                    val entry = lessons.optJSONObject(LessonStore.lessonId(context, dir)) ?: continue
                    // info.json: subject + topic
                    val info = JSONObject().apply {
                        put("subject", entry.optString("subject"))
                        put("topic", entry.optString("topic"))
                        put("number", entry.optInt("number"))
                    }.toString(2)
                    val infoFile = File(dir, "info.json")
                    if (!infoFile.exists() || infoFile.readText() != info) { infoFile.writeText(info); changed++ }
                    // konspekt.md
                    val path = entry.optString("path").trim('/')
                    val kPath = "$path/konspekt.md"
                    val kSha = tree[kPath] ?: continue
                    val kFile = File(dir, "konspekt.md")
                    if (shas[kPath] != kSha || !kFile.exists()) {
                        api.readText(kPath)?.let { kFile.writeText(it); changed++ }
                        shas[kPath] = kSha
                    }
                }
            }
        }

        // homework from Codex (and from the other phone)
        val remote = tree.filterKeys { it.startsWith("homework/") && it.endsWith(".json") }
        synchronized(HomeworkStore) {
            val local = HomeworkStore.all(context).associateBy { it.id }.toMutableMap()
            val deleted = prefs.deletedHomework
            for ((path, sha) in remote) {
                val id = path.removePrefix("homework/").removeSuffix(".json").toLongOrNull() ?: continue
                if (id in deleted) continue
                val mine = local[id]
                if (mine?.dirty == true) continue                    // our change wins until it is sent
                if (mine != null && shas[path] == sha) continue        // nothing new
                val text = api.readText(path) ?: continue
                val hw = try { Homework.fromJson(JSONObject(text)) } catch (_: Exception) { continue }
                local[id] = hw.copy(dirty = false, synced = true)
                shas[path] = sha
                changed++
            }
            // removed on GitHub (e.g. Codex dropped a duplicate) → remove here too
            val remoteIds = remote.keys.mapNotNull { it.removePrefix("homework/").removeSuffix(".json").toLongOrNull() }.toSet()
            local.values.filter { it.synced && !it.dirty && it.id !in remoteIds }.forEach { local.remove(it.id); changed++ }
            HomeworkStore.save(context, local.values.sortedBy { it.createdMs })
        }
        prefs.remoteShas = shas
        return changed
    }
}
