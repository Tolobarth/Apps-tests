package dev.huenotes

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Google's ML Kit speech recognition, used to transcribe recordings. Unlike Android's SpeechRecognizer, it reads a recording
 * itself and streams it to Google's speech app (`com.google.android.tts`), so it also works on phones whose speech service
 * ignores recordings handed over by apps. Its speech model is downloaded once, on first use, after asking.
 */
object MlKitSpeech {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        object Done : Event
        data class Failed(val why: String) : Event
    }

    sealed interface Download {
        data class Started(val totalBytes: Long) : Download
        data class Progress(val bytes: Long) : Download
        object Completed : Download
        data class Failed(val why: String) : Download
    }

    enum class Status { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    /** What HueNotes needs from the engine; tests swap in a fake. */
    interface Engine {
        suspend fun status(): Status
        fun download(): Flow<Download>
        fun recognize(audio: ParcelFileDescriptor): Flow<Event>
        suspend fun stop()
        fun close()
    }

    var factory: (Locale) -> Engine = { Real(it) }

    /** Android 12+ with Google's speech app installed: worth asking ML Kit. */
    fun mayWork(c: Context) = Build.VERSION.SDK_INT >= 31 && try {
        c.packageManager.getPackageInfo("com.google.android.tts", 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** ML Kit wants a language with its region ("fr" → fr-FR). */
    fun locale(tag: String): Locale {
        val l = Locale.forLanguageTag(tag)
        return if (l.country.isNotEmpty()) l else Locale(l.language, Speech.REGION[l.language] ?: "")
    }

    private class Real(language: Locale) : Engine {
        private val r = SpeechRecognition.getClient(SpeechRecognizerOptions.Builder().apply {
            locale = language; preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }.build())

        override suspend fun status() = when (r.checkStatus()) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            else -> Status.UNAVAILABLE
        }

        override fun download(): Flow<Download> = r.download().map { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> Download.Started(s.bytesToDownload)
                is DownloadStatus.DownloadProgress -> Download.Progress(s.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> Download.Failed(s.e.message ?: "error ${s.e.errorCode}")
                else -> Download.Completed
            }
        }.catch { emit(Download.Failed(it.message ?: it.javaClass.simpleName)) }

        override fun recognize(audio: ParcelFileDescriptor): Flow<Event> =
            r.startRecognition(SpeechRecognizerRequest.Builder().apply { audioSource = AudioSource.fromPfd(audio) }.build()).map { x ->
                when (x) {
                    is SpeechRecognizerResponse.PartialTextResponse -> Event.Partial(x.text)
                    is SpeechRecognizerResponse.FinalTextResponse -> Event.Final(x.text)
                    is SpeechRecognizerResponse.ErrorResponse -> Event.Failed("${x.e.message ?: "error"} (${x.e.errorCode})")
                    else -> Event.Done
                }
            }.catch { emit(Event.Failed(it.message ?: it.javaClass.simpleName)) }

        override suspend fun stop() { runCatching { r.stopRecognition() } }
        override fun close() { runCatching { r.close() } }
    }
}

/**
 * Transcribes a recording with ML Kit: decodes it, feeds it at real-time speed through a pipe, and gathers the final pieces.
 * Ends with [ENGINE_FAILED] if ML Kit fails before producing anything (so the caller can try Android's recogniser instead),
 * or [Transcriber.NOT_ACCEPTED] if ML Kit stops reading the audio or never answers.
 */
class MlKitTranscriber(
    private val file: File,
    private val engine: MlKitSpeech.Engine,
    private val onUpdate: (text: String, positionMs: Long) -> Unit,
    private val onEnd: (text: String, error: Int?) -> Unit,
    private val decode: (File, OutputStream, () -> Boolean, (Long) -> Unit) -> Long = Pcm::decode,
    private val speed: Double = 1.0,
    private val scope: CoroutineScope = MainScope(),
) {
    private val parts = ArrayList<String>()
    private var partial = ""
    @Volatile private var stopFeed = false
    @Volatile private var fed = 0
    @Volatile private var lastWrite = SystemClock.elapsedRealtime()
    private var ended = false
    private var answered = false
    private var feedDoneAt = 0L
    private var read: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val log = ArrayList<String>()
    private val t0 = SystemClock.elapsedRealtime()
    val text get() = SpeechText.join(parts)
    val details get() = synchronized(log) { log.joinToString("\n") }

    private fun note(s: String) = synchronized(log) { if (log.size < 300) log += "+%.1fs ml-kit: %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, s) }
    private fun shown() = SpeechText.join(parts + listOfNotNull(partial.takeIf { it.isNotBlank() }))
    private fun position() = fed * 1000L / Pcm.BYTES_PER_SEC

    fun start() {
        note("file ${file.length() / 1024} KB")
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { Pcm.decodeAll(file, decode) { ended } }
            if (ended) return@launch
            if (pcm == null || pcm.size < Pcm.BYTES_PER_SEC / 10) { note("decode failed"); finish(android.speech.SpeechRecognizer.ERROR_AUDIO); return@launch }
            note("decoded %.1fs of audio".format(pcm.size / Pcm.BYTES_PER_SEC.toDouble()))
            val (r, w) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
            read = r
            job = scope.launch {
                engine.recognize(r).collect { ev -> if (!ended) handle(ev) }
                if (!ended) { note("stream ended"); finish(null) }
            }
            Thread({
                Pcm.feedRealTime(pcm, 0, w, speed, { stopFeed }) { pos -> fed = pos; lastWrite = SystemClock.elapsedRealtime() }
                scope.launch { if (!ended) { feedDoneAt = SystemClock.elapsedRealtime(); note("all audio handed over") } }
            }, "mlkit-feed").start()
            watch(pcm.size)
        }
    }

    private fun handle(ev: MlKitSpeech.Event) {
        when (ev) {
            is MlKitSpeech.Event.Partial -> { answered = true; partial = ev.text; onUpdate(shown(), position()) }
            is MlKitSpeech.Event.Final -> {
                answered = true; partial = ""
                note("final ${ev.text.length} chars")
                ev.text.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
                onUpdate(shown(), position())
            }
            MlKitSpeech.Event.Done -> { note("completed"); finish(null) }
            is MlKitSpeech.Event.Failed -> { note("error: ${ev.why}"); finish(if (parts.isEmpty()) ENGINE_FAILED else null) }
        }
    }

    /** Twice a second: is ML Kit still reading the audio, did it ever answer, has it finished after the last audio? */
    private suspend fun watch(total: Int) {
        val started = SystemClock.elapsedRealtime()
        while (!ended) {
            delay(500)
            if (ended) break
            val now = SystemClock.elapsedRealtime()
            if (feedDoneAt == 0L) onUpdate(shown(), position())
            when {
                feedDoneAt == 0L && fed < total && now - lastWrite > Transcriber.FEED_STALL_MS -> {
                    note("stopped reading the audio"); finish(if (answered) null else Transcriber.NOT_ACCEPTED)
                }
                !answered && feedDoneAt == 0L && now - started > Transcriber.NO_ANSWER_MS + total * 1000L / Pcm.BYTES_PER_SEC -> {
                    note("no answer"); finish(Transcriber.NOT_ACCEPTED)
                }
                feedDoneAt != 0L && now - feedDoneAt > Transcriber.END_GRACE_MS -> {
                    note("no end after the last audio"); engine.stop()
                    finish(if (parts.isEmpty() && partial.isBlank()) Transcriber.NOT_ACCEPTED else null)
                }
            }
        }
    }

    /** Stops, keeping what was transcribed so far. */
    fun cancel() { if (!ended) { note("stopped by the person"); finish(null) } }

    private fun finish(error: Int?) {
        if (ended) return
        ended = true; stopFeed = true
        if (partial.isNotBlank()) { parts += partial.trim(); partial = "" }      // keep the last words heard
        note("finished" + (error?.let { " with $it" } ?: ""))
        job?.cancel()
        runCatching { read?.close() }; read = null
        // Always release Google's speech app, even if it never confirms the stop: a session left open can keep it busy
        // and block it for other apps afterwards.
        scope.launch { try { kotlinx.coroutines.withTimeoutOrNull(2000) { engine.stop() } } finally { engine.close() } }
        onEnd(text, error)
    }

    companion object {
        /** ML Kit failed before producing any text: the caller may try Android's recogniser instead. */
        const val ENGINE_FAILED = -2
    }
}
