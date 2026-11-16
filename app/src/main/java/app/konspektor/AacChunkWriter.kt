package app.konspektor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Encodes 16-bit mono PCM into an AAC .m4a file.
 * ~32 kbit/s → about 14 MB per hour, small enough for mobile data.
 */
class AacChunkWriter(
    val file: File,
    private val sampleRate: Int = SAMPLE_RATE,
    bitRate: Int = 32_000,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var wroteSamples = false

    /** PCM samples fed to the encoder so far. */
    var samples = 0L
        private set

    val durationSec: Double get() = samples.toDouble() / sampleRate

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(false)
                continue
            }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val n = min(buf.remaining() / 2, count - offset)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(idx, 0, n * 2, ptsUs(), 0)
            samples += n
            offset += n
            drain(false)
        }
    }

    /** Flushes the encoder and closes the file. Safe to call once. */
    fun finish() {
        try {
            var idx = -1
            var tries = 0
            while (idx < 0 && tries++ < 200) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) drain(false)
            }
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } catch (_: Exception) {
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxerStarted && wroteSamples) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }

    private fun ptsUs() = samples * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || idle++ > 300) return
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        wroteSamples = true
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
