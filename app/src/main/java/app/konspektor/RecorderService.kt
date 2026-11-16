package app.konspektor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foreground service: records the microphone into overlapping 5-minute AAC pieces and
 * sends each finished piece to Groq for transcription. Keeps running after "Stop" until
 * the queue is empty.
 */
class RecorderService : Service() {

    companion object {
        private const val ACTION_START = "app.konspektor.START"
        private const val ACTION_STOP = "app.konspektor.STOP"
        private const val ACTION_RETRY = "app.konspektor.RETRY"
        private const val EXTRA_SUBJECT = "subject"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, subject: String) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_SUBJECT, subject)
        )

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun retry(ctx: Context) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_RETRY)
        )

        /** Whisper's typical inventions on silence (Russian YouTube subtitles credits etc.). */
        private val JUNK = listOf(
            "продолжение следует", "субтитр", "спасибо за просмотр", "подписывайтесь",
            "редактор", "корректор", "dimatorzok"
        )
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingDeque<File>()
    private val queuedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var keepRecording = false
    @Volatile private var busy = false
    private var recorderThread: Thread? = null
    private var uploader: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Запись урока", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) return START_NOT_STICKY
                if (!AppState.recording) startRecording(intent.getStringExtra(EXTRA_SUBJECT).orEmpty())
            }
            ACTION_STOP -> {
                keepRecording = false
                if (!AppState.recording) maybeStop()
            }
            ACTION_RETRY -> {
                if (!goForeground()) return START_NOT_STICKY
                requeueFailed()
                enqueueLeftovers()
                ensureUploader()
            }
            else -> if (!AppState.recording) maybeStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepRecording = false
        releaseWakeLock()
        AppState.serviceRunning = false
        AppState.changed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground & notification

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            AppState.serviceRunning = true
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "konspektor:recording")
                    .apply { setReferenceCounted(false); acquire() }
            }
            AppState.changed()
            true
        } catch (e: Exception) {
            AppState.lastError = "Не удалось запустить службу: ${e.message}"
            AppState.changed()
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setContentTitle(if (AppState.recording) "Идёт запись урока" else "Дорасшифровываю урок")
            .setContentText("Расшифровано: ${AppState.done} · в очереди: ${AppState.queued}" +
                (if (AppState.failed > 0) " · ошибок: ${AppState.failed}" else ""))
        if (AppState.recording) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Стоп", stop).build())
        }
        return b.build()
    }

    private fun updateNotification() {
        if (!AppState.serviceRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /** Ends the service once nothing is recorded and nothing is waiting. Main thread. */
    private fun maybeStop() {
        if (AppState.recording || queue.isNotEmpty() || busy) return
        AppState.lessonDir?.let { LessonStore.finishIfComplete(it) }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppState.serviceRunning = false
        AppState.changed()
        stopSelf()
    }

    // ---------------------------------------------------------------- recording

    private fun startRecording(subject: String) {
        enqueueLeftovers()
        val lesson = LessonStore.newLesson(this, subject)
        AppState.lessonDir = lesson
        AppState.startedAtMs = System.currentTimeMillis()
        AppState.done = 0
        AppState.failed = 0
        AppState.lastError = null
        AppState.transcript = LessonStore.rebuildTranscript(lesson)
        AppState.recording = true
        keepRecording = true
        recorderThread = thread(name = "recorder") { recordLoop(lesson) }
        ensureUploader()
        updateNotification()
        AppState.changed()
    }

    private fun recordLoop(lesson: File) {
        val sr = AacChunkWriter.SAMPLE_RATE
        val chunkSamples = LessonStore.CHUNK_SEC.toLong() * sr
        val overlapSamples = LessonStore.OVERLAP_SEC.toLong() * sr
        var rec: AudioRecord? = null
        var current: AacChunkWriter? = null
        var next: AacChunkWriter? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sr) * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон занят или недоступен")
            val buf = ShortArray(sr / 10)               // 100 ms
            var index = 1
            current = AacChunkWriter(LessonStore.chunkFile(lesson, index))
            rec.startRecording()
            var tick = 0
            while (keepRecording) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
                if (n == 0) continue
                current!!.write(buf, n)
                next?.write(buf, n)

                if (tick++ % 3 == 0) {
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    AppState.level = min(1f, (sqrt(sum / n) / 32768.0 * 8).toFloat())
                    AppState.changed()
                }

                if (next == null && current.samples >= chunkSamples - overlapSamples) {
                    next = AacChunkWriter(LessonStore.chunkFile(lesson, index + 1))
                }
                if (current.samples >= chunkSamples) {
                    current.finish()
                    enqueue(current.file)
                    current = next
                    next = null
                    index++
                }
            }
        } catch (e: Exception) {
            AppState.lastError = "Запись остановлена: ${e.message}"
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            current?.let {
                it.finish()
                if (it.durationSec >= 1.0) enqueue(it.file) else it.file.delete()
            }
            next?.let { it.finish(); it.file.delete() }   // only holds the overlap, already in `current`
            LessonStore.markStopped(lesson)
            AppState.transcript = LessonStore.rebuildTranscript(lesson)
            AppState.recording = false
            AppState.level = 0f
            AppState.changed()
            ui.post {
                updateNotification()
                maybeStop()
            }
        }
    }

    // ---------------------------------------------------------------- transcription queue

    private fun enqueue(chunk: File) {
        if (queuedPaths.add(chunk.path)) {
            queue.add(chunk)
            AppState.queued = queue.size
            AppState.changed()
            ui.post { updateNotification() }
        }
        ensureUploader()
    }

    /** Pieces left untranscribed by an earlier run (app killed, phone off, no network). */
    private fun enqueueLeftovers() {
        for (lesson in LessonStore.lessons(this)) {
            if (lesson == AppState.lessonDir && AppState.recording) continue
            if (!LessonStore.isStopped(lesson)) LessonStore.markStopped(lesson)
            LessonStore.chunks(lesson).filter { LessonStore.isPending(it) }.forEach { enqueue(it) }
        }
    }

    private fun requeueFailed() {
        for (lesson in LessonStore.lessons(this)) {
            var any = false
            for (chunk in LessonStore.chunks(lesson)) {
                val f = LessonStore.failedFile(chunk)
                if (f.exists()) { f.delete(); any = true }
            }
            if (any) File(lesson, "done.txt").delete()
        }
        AppState.failed = 0
        AppState.lastError = null
    }

    @Synchronized
    private fun ensureUploader() {
        if (uploader?.isAlive == true) return
        uploader = thread(name = "uploader") {
            while (true) {
                val chunk = queue.poll(2, TimeUnit.SECONDS)
                if (chunk == null) {
                    if (!AppState.recording && queue.isEmpty()) break
                    continue
                }
                busy = true
                try {
                    process(chunk)
                } finally {
                    queuedPaths.remove(chunk.path)
                    AppState.queued = queue.size
                    busy = false
                    AppState.changed()
                    ui.post { updateNotification() }
                }
            }
            ui.post { maybeStop() }
        }
    }

    private fun process(chunk: File) {
        if (!chunk.exists() || !LessonStore.isPending(chunk)) return
        val lesson = LessonStore.lessonOf(chunk)
        val isCurrent = lesson == AppState.lessonDir
        var attempt = 0
        while (true) {
            val key = prefs.groqKey
            try {
                if (key.isBlank()) throw Transcriber.Failure("Не задан ключ Groq (настройки внизу экрана)", retryable = false)
                val text = Transcriber.transcribe(chunk, key, prefs.model, previousText(chunk))
                LessonStore.textFile(chunk).writeText(clean(text))
                if (isCurrent) AppState.done++
                AppState.lastError = null
                break
            } catch (e: Transcriber.Failure) {
                attempt++
                AppState.lastError = e.message
                AppState.changed()
                // While recording, network errors are retried forever; after Stop give up after 5 tries.
                if (!e.retryable || (!AppState.recording && attempt >= 5)) {
                    LessonStore.failedFile(chunk).writeText(e.message.orEmpty())
                    if (isCurrent) AppState.failed++
                    break
                }
                val waitSec = e.retryAfterSec ?: min(120L, 10L shl min(attempt - 1, 4))
                Thread.sleep(waitSec * 1000)
            } catch (e: Exception) {
                LessonStore.failedFile(chunk).writeText(e.toString())
                if (isCurrent) AppState.failed++
                AppState.lastError = "Ошибка обработки ${chunk.name}: ${e.message}"
                break
            }
        }
        val text = LessonStore.rebuildTranscript(lesson)
        if (isCurrent) AppState.transcript = text
        LessonStore.finishIfComplete(lesson)
        AppState.changed()
    }

    private fun previousText(chunk: File): String? {
        val idx = chunk.nameWithoutExtension.removePrefix("chunk_").toIntOrNull() ?: return null
        if (idx <= 1) return null
        val prev = LessonStore.textFile(LessonStore.chunkFile(LessonStore.lessonOf(chunk), idx - 1))
        return prev.takeIf { it.exists() }?.readText()?.takeLast(200)
    }

    private fun clean(text: String): String {
        val t = text.trim()
        val low = t.lowercase()
        return if (t.length < 120 && JUNK.any { low.contains(it) }) "" else t
    }
}
