package dev.huenotes

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Records a voice note (AAC in an .m4a) to a temporary file; the editor moves it into attachment storage. */
class VoiceRecorder(private val c: Context, private val onMaxReached: () -> Unit) {
    private var rec: MediaRecorder? = null
    private var out: File? = null
    private var startedAt = 0L

    val elapsedMs get() = if (rec == null) 0L else SystemClock.elapsedRealtime() - startedAt

    fun start(): Boolean {
        val f = File(c.cacheDir, "rec-${System.nanoTime()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000); r.setAudioSamplingRate(44_100); r.setAudioChannels(1)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached() }
            r.prepare(); r.start()
            rec = r; out = f; startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) { f.delete(); false }
    }

    /** The recorded file and its length, or null when the recording failed or was too short to keep. */
    fun stop(): Pair<File, Long>? {
        val r = rec ?: return null
        val f = out!!; val ms = elapsedMs
        rec = null; out = null
        return try {
            r.stop(); r.release()
            if (f.length() > 0) f to ms else { f.delete(); null }
        } catch (e: RuntimeException) { r.release(); f.delete(); null }
    }

    fun cancel() {
        val r = rec; val f = out
        rec = null; out = null
        try { r?.stop() } catch (_: Exception) { }
        r?.release(); f?.delete()
    }

    companion object { const val MAX_MS = 10 * 60 * 1000 }
}

/** One voice note plays at a time; starting another stops the first. */
object Player {
    private var mp: MediaPlayer? = null
    private var owner: String? = null
    private var tick: ((playing: Boolean, pos: Int, dur: Int) -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val m = mp ?: return
            tick?.invoke(m.isPlaying, m.currentPosition, m.duration)
            if (m.isPlaying) h.postDelayed(this, 200)
        }
    }

    /** Play, or pause/resume if [id] is already the current one. False when the file can't be played. */
    fun toggle(id: String, file: File, onTick: (playing: Boolean, pos: Int, dur: Int) -> Unit): Boolean {
        val m = mp
        if (m != null && owner == id) {
            tick = onTick
            if (m.isPlaying) m.pause() else { m.start(); h.post(ticker) }
            onTick(m.isPlaying, m.currentPosition, m.duration)
            return true
        }
        stop()
        return try {
            val n = MediaPlayer().apply { setDataSource(file.path); prepare() }
            n.setOnCompletionListener { val t = tick; val d = n.duration; release(); t?.invoke(false, 0, d) }
            mp = n; owner = id; tick = onTick
            n.start(); h.post(ticker)
            true
        } catch (e: Exception) { release(); false }
    }

    private fun release() { h.removeCallbacks(ticker); mp?.release(); mp = null; owner = null; tick = null }

    fun stop() { val t = tick; release(); t?.invoke(false, 0, 0) }
}
