package app.konspektor

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Shared in-process state between [RecorderService] and [MainActivity]. */
object AppState {
    @Volatile var recording = false
    @Volatile var serviceRunning = false
    @Volatile var lessonDir: File? = null
    @Volatile var startedAtMs = 0L
    @Volatile var level = 0f          // 0..1 microphone loudness
    @Volatile var done = 0            // transcribed chunks (current lesson)
    @Volatile var queued = 0          // chunks waiting for transcription (all lessons)
    @Volatile var failed = 0          // chunks given up on (current lesson)
    @Volatile var lastError: String? = null
    @Volatile var transcript: String = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }
}
