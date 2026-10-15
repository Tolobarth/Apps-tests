package dev.huenotes

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Speech to text through Android's own recogniser. On-device recognition (Android 12+) is used whenever the phone has it, so
 * audio never leaves the phone; the phone's regular, possibly online, recogniser is used only if the person allowed it.
 */
object Speech {
    fun onDevice(c: Context) = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(c) }.getOrDefault(false)
    fun online(c: Context) = Prefs.onlineSpeech(c) && SpeechRecognizer.isRecognitionAvailable(c)
    fun canDictate(c: Context) = onDevice(c) || online(c)
    /** Feeding a recording to the recogniser needs Android 13. */
    fun canTranscribe(c: Context) = (Build.VERSION.SDK_INT >= 33 && canDictate(c)) || MlKitSpeech.mayWork(c)

    /** [online] picks the phone's regular recogniser (only when the person allowed it); otherwise on-device when available. */
    fun create(c: Context, online: Boolean = false): SpeechRecognizer =
        if (!online && onDevice(c)) SpeechRecognizer.createOnDeviceSpeechRecognizer(c) else SpeechRecognizer.createSpeechRecognizer(c)

    /**
     * Speech is recognised in the app's language (the in-app choice if any, otherwise the phone's). A bare language such as
     * "pt" borrows the phone's region when the phone speaks that language too ("pt-BR"), since recognisers are per region.
     */
    fun language(c: Context, phone: Locale = Locale.getDefault()): String {
        val chosen = Prefs.language(c)
        if (chosen.isEmpty()) return phone.toLanguageTag()
        val l = Locale.forLanguageTag(chosen)
        return if (l.country.isEmpty() && l.language == phone.language && phone.country.isNotEmpty()) phone.toLanguageTag() else chosen
    }

    fun intent(c: Context, language: String = language(c)): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)

    /** "Nothing heard" endings: not worth an error message when something was already recognised. */
    fun isSilence(error: Int) = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    fun isMissingLanguage(error: Int) = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    @StringRes fun errorText(error: Int): Int = when {
        isSilence(error) -> R.string.speech_err_nothing
        isMissingLanguage(error) -> R.string.speech_err_language
        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.rec_permission
        error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER -> R.string.speech_err_network
        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> R.string.speech_err_busy
        else -> R.string.speech_err_other
    }

    fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}

/** Where dictated words go in text that is already there. */
object DictationText {
    private fun startsSentence(before: String): Boolean {
        val t = before.trimEnd(' ', '\t')
        return t.isEmpty() || t.last() == '\n' || t.last() in ".!?…"
    }

    /** Inserts [phrase] at [cursor], adding the spaces it needs and a capital at the start of a sentence. Returns text and new cursor. */
    fun insert(text: String, cursor: Int, phrase: String): Pair<String, Int> {
        val p = phrase.trim()
        val at = cursor.coerceIn(0, text.length)
        if (p.isEmpty()) return text to at
        val before = text.substring(0, at); val after = text.substring(at)
        val word = if (startsSentence(before)) p.replaceFirstChar { it.titlecase(Locale.getDefault()) } else p
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,;:!?)") " " else ""
        val ins = lead + word + trail
        return before + ins + after to at + ins.length - trail.length
    }

    /** Joins recognised pieces into one text. */
    fun join(parts: List<String>): String = parts.fold("") { acc, s -> insert(acc, acc.length, s).first }
}

/**
 * Continuous dictation: listens phrase after phrase until [stop] (or silence after at least one phrase). [onUpdate] gets the
 * text so far plus the phrase being heard; [onEnd] gets the final text and, if it ended badly, an error code.
 */
class Dictation(
    private val c: Context,
    private val onUpdate: (text: String, hearing: String) -> Unit,
    private val onEnd: (text: String, error: Int?) -> Unit,
    private val language: String = Speech.language(c),
    private val recognizer: SpeechRecognizer = Speech.create(c),
) {
    private val parts = ArrayList<String>()
    private var stopping = false
    private var ended = false
    private var events = 0
    private val main = Handler(Looper.getMainLooper())
    private val log = ArrayList<String>()
    private val t0 = android.os.SystemClock.elapsedRealtime()
    val text get() = DictationText.join(parts)
    /** What happened, for a shared report: recogniser events and timings, no words. */
    val details get() = log.joinToString("\n")

    private fun note(s: String) { if (log.size < 200) log += "+%.1fs %s".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000.0, s) }
    private fun ev(name: String) { events++; if (events <= 30 || !name.startsWith("rms")) note(name) }

    internal val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) { ev("partial ${Speech.best(b).length} chars"); onUpdate(text, Speech.best(b)) }
        override fun onResults(b: Bundle?) {
            ev("results ${Speech.best(b).length} chars")
            Speech.best(b).takeIf { it.isNotEmpty() }?.let { parts += it }
            onUpdate(text, "")
            if (stopping) finish(null) else main.post { if (!ended) listen() }    // keep going until the person stops
        }
        override fun onError(error: Int) { ev("error $error"); finish(if (Speech.isSilence(error) && (parts.isNotEmpty() || stopping)) null else error) }
        override fun onReadyForSpeech(p: Bundle?) = ev("ready")
        override fun onBeginningOfSpeech() = ev("speech")
        override fun onRmsChanged(v: Float) = ev("rms")
        override fun onBufferReceived(b: ByteArray?) = ev("buffer")
        override fun onEndOfSpeech() = ev("end-of-speech")
        override fun onEvent(t: Int, p: Bundle?) = ev("event $t")
    }

    fun start() {
        note("start $language")
        recognizer.setRecognitionListener(listener); listen()
        // A speech service that never answers at all (not even "ready") would leave "Speak now" up forever.
        main.postDelayed({ if (!ended && events == 0) { note("no answer from the speech service"); recognizer.cancel(); finish(NO_ANSWER) } }, NO_ANSWER_MS)
    }

    private fun listen() { note("listen"); recognizer.startListening(Speech.intent(c, language)) }

    /** Stops listening; what is being said right now is still recognised and kept. */
    fun stop() {
        if (ended) return
        stopping = true; recognizer.stopListening()
        main.postDelayed({ cancel() }, STOP_GRACE_MS)    // never hang if the recogniser doesn't answer
    }

    /** Ends at once, keeping only what was already recognised. */
    fun cancel() { if (!ended) { recognizer.cancel(); finish(null) } }

    private fun finish(error: Int?) {
        if (ended) return
        ended = true
        note("finished" + (error?.let { " with $it" } ?: "") + ", ${parts.size} phrases")
        recognizer.destroy()
        onEnd(text, error)
    }

    companion object {
        const val STOP_GRACE_MS = 4000L
        const val NO_ANSWER_MS = 8000L
        /** The speech service gave no sign of life at all. */
        const val NO_ANSWER = -3
    }
}

/** Turning a recording into the 16 kHz mono 16-bit PCM the recogniser reads. */
object Pcm {
    const val RATE = 16_000
    private const val STALL_MS = 3000L

    /** Averages interleaved channels into one. */
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val out = ShortArray(interleaved.size / channels)
        for (i in out.indices) {
            var sum = 0
            for (ch in 0 until channels) sum += interleaved[i * channels + ch]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** Streaming linear-interpolation resampler: chunks can be fed one after another without clicks at the seams. */
    class Resampler(private val from: Int, private val to: Int = RATE) {
        private val step = from.toDouble() / to
        private var pos = 0.0               // position of the next output sample, relative to the current chunk
        private var last: Short? = null     // final sample of the previous chunk, for interpolating across the seam

        fun process(input: ShortArray): ShortArray {
            if (from == to) return input
            val src = last?.let { shortArrayOf(it) + input } ?: input
            val base = if (last == null) 0.0 else 1.0
            val out = ArrayList<Short>()
            var p = pos + base
            while (p <= src.size - 1) {
                val i = p.toInt(); val f = p - i
                val a = src[i]; val b = src[minOf(i + 1, src.size - 1)]
                out += (a + (b - a) * f).toInt().toShort()
                p += step
            }
            if (src.isNotEmpty()) { last = src.last(); pos = p - (src.size - 1) - 1 }
            return out.toShortArray()
        }
    }

    const val BYTES_PER_SEC = RATE * 2
    private const val CHUNK = BYTES_PER_SEC / 10
    private const val LEAD_MS = 500L

    /**
     * Writes [pcm] from [from] into [w] like a microphone would: 100 ms chunks, never more than half a second ahead of real
     * time ([speed] 1). Closes [w] at the end. Blocking: run on a worker thread.
     */
    fun feedRealTime(pcm: ByteArray, from: Int, w: ParcelFileDescriptor, speed: Double, stop: () -> Boolean, wrote: (Int) -> Unit) {
        val begin = SystemClock.elapsedRealtime()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(w).use { out ->
                var pos = from
                while (!stop() && pos < pcm.size) {
                    val n = minOf(CHUNK, pcm.size - pos)
                    out.write(pcm, pos, n); pos += n
                    wrote(pos)
                    val ahead = ((pos - from) * 1000.0 / BYTES_PER_SEC / speed).toLong() - (SystemClock.elapsedRealtime() - begin)
                    if (ahead > LEAD_MS) Thread.sleep(ahead - LEAD_MS)
                }
            }
        } catch (e: Exception) { runCatching { w.close() } }
    }

    /** Decodes a whole recording to 16 kHz mono PCM bytes (even length), or null if it can't be read. */
    fun decodeAll(file: File, decode: (File, OutputStream, () -> Boolean, (Long) -> Unit) -> Long, cancelled: () -> Boolean): ByteArray? = try {
        java.io.ByteArrayOutputStream().also { decode(file, it, cancelled) { } }.toByteArray().let { if (it.size % 2 == 0) it else it.copyOf(it.size - 1) }
    } catch (e: Exception) { null }

    fun toBytes(s: ShortArray): ByteArray = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(s) }.array()

    /**
     * Decodes the audio track of [file] and writes it to [out] as 16 kHz mono PCM. [onProgress] gets the position in ms;
     * [cancelled] is checked between chunks. Returns the duration in ms.
     */
    fun decode(file: File, out: OutputStream, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("No audio in ${file.name}")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(fmt, null, null, 0); codec.start()
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var resampler = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var lastOutput = System.currentTimeMillis()
                while (!cancelled()) {
                    // Some decoders never flag the end of the stream: give up waiting once nothing has come out for a while.
                    if (inputDone && System.currentTimeMillis() - lastOutput > STALL_MS) break
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); resampler = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    } else if (o >= 0) {
                        lastOutput = System.currentTimeMillis()
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = ShortArray(info.size / 2)
                        buf.position(info.offset); buf.asShortBuffer().get(shorts)
                        codec.releaseOutputBuffer(o, false)
                        out.write(toBytes(resampler.process(downmix(shorts, channels))))
                        onProgress(info.presentationTimeUs / 1000)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            return durationMs
        } finally { ex.release() }
    }
}

/**
 * Transcribes a voice note (Android 13+). The recording is decoded to 16 kHz mono PCM, then fed to the recogniser through a
 * pipe **at real-time speed**, like a microphone (Google's engine expects that). Two ways of listening are tried:
 *  - [Mode.SEGMENTED]: one long session that reports a piece of text at each pause (Android 13's way for recordings);
 *  - [Mode.PHRASES]: the classic one-phrase session, restarted after each phrase from where the audio had got to.
 * If the speech service stops reading the audio, or never answers, it falls back from the first to the second, and if that
 * fails too it ends with [NOT_ACCEPTED] and a log of what happened ([details]) that can be shared.
 * [onUpdate] gets the text so far and the position reached in ms; [onEnd] the result.
 */
class Transcriber(
    private val c: Context,
    private val file: File,
    private val onUpdate: (text: String, positionMs: Long) -> Unit,
    private val onEnd: (text: String, error: Int?) -> Unit,
    private val language: String = Speech.language(c),
    private val newRecognizer: () -> SpeechRecognizer = { Speech.create(c) },
    /** Writes the recording as 16 kHz mono PCM; tests replace it. */
    private val decode: (File, OutputStream, () -> Boolean, (Long) -> Unit) -> Long = Pcm::decode,
    /** How fast the audio is fed: 1 = real time; tests go faster. Waiting limits don't change with it. */
    private val speed: Double = 1.0,
) {
    enum class Mode { SEGMENTED, PHRASES }

    private val parts = ArrayList<String>()
    @Volatile private var cancelled = false
    private var ended = false
    private val main = Handler(Looper.getMainLooper())
    private var pcm = ByteArray(0)
    private val log = ArrayList<String>()
    private val t0 = SystemClock.elapsedRealtime()
    val text get() = DictationText.join(parts)
    /** What happened, for a shared report: modes, recogniser events, timings. No transcript text. */
    val details get() = synchronized(log) { log.joinToString("\n") }
    var mode = Mode.SEGMENTED; private set

    private fun note(s: String) = synchronized(log) { if (log.size < 300) log += "+%.1fs %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, s) }

    // ---- one listening session -----------------------------------------------------------------

    private inner class Session(val from: Int, val mode: Mode) {
        val rec: SpeechRecognizer = newRecognizer()
        lateinit var read: ParcelFileDescriptor     // kept open until the session ends: Android hands it over later
        @Volatile var fed = from
        @Volatile var lastWrite = SystemClock.elapsedRealtime()
        @Volatile var stop = false
        /** True once this session is being replaced or ended: whatever it still reports is ignored. */
        fun live() = !stop && session === this
        var feedDone = false; var feedDoneAt = 0L
        var events = 0
        val startedAt = SystemClock.elapsedRealtime()

        val listener = object : RecognitionListener {
            private fun ev(name: String) { if (live()) { events++; if (events <= 40 || name != "rms") note(name) } }
            override fun onReadyForSpeech(p: Bundle?) = ev("ready")
            override fun onBeginningOfSpeech() = ev("speech")
            override fun onRmsChanged(v: Float) = ev("rms")
            override fun onBufferReceived(b: ByteArray?) = ev("buffer")
            override fun onEndOfSpeech() = ev("end-of-speech")
            override fun onPartialResults(b: Bundle?) = ev("partial")
            override fun onEvent(t: Int, p: Bundle?) = ev("event $t")
            override fun onSegmentResults(b: Bundle) {
                ev("segment ${Speech.best(b).length} chars")
                if (live()) { add(b); onUpdate(text, position()) }
            }
            override fun onEndOfSegmentedSession() { ev("end-of-session"); if (live()) finish(null) }
            override fun onResults(b: Bundle?) {
                ev("results ${Speech.best(b).length} chars")
                if (!live()) return
                add(b)
                if (this@Session.mode == Mode.SEGMENTED || atEnd()) finish(null) else next()
            }
            override fun onError(error: Int) {
                ev("error $error")
                if (!live()) return
                when {
                    this@Session.mode == Mode.PHRASES && Speech.isSilence(error) && !atEnd() -> next()     // a quiet stretch
                    Speech.isSilence(error) && parts.isNotEmpty() -> finish(null)
                    else -> finish(error)
                }
            }
        }

        fun atEnd() = feedDone || fed >= pcm.size

        fun start() {
            val (r, w) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
            read = r
            note("start ${mode.name.lowercase()} at %.1fs".format(from / BYTES_PER_SEC.toDouble()))
            rec.setRecognitionListener(listener)
            val i = Speech.intent(c, language)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, r)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.RATE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (mode == Mode.SEGMENTED) i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            rec.startListening(i)
            Thread({ feed(w) }, "transcribe-feed").start()
        }

        private fun feed(w: ParcelFileDescriptor) {
            Pcm.feedRealTime(pcm, from, w, speed, { stop }) { pos -> fed = pos; lastWrite = SystemClock.elapsedRealtime() }
            if (!stop) main.post { if (live()) { feedDone = true; feedDoneAt = SystemClock.elapsedRealtime(); note("all audio handed over") } }
        }

        fun end() {
            stop = true
            runCatching { rec.cancel() }; runCatching { rec.destroy() }
            runCatching { read.close() }        // also unblocks a writer the service stopped reading
        }
    }

    private var session: Session? = null

    private fun add(b: Bundle?) { Speech.best(b).takeIf { it.isNotEmpty() }?.let { parts += it } }
    private fun position() = (session?.fed ?: 0) * 1000L / BYTES_PER_SEC

    /** Phrases mode: the next phrase starts where the audio had got to (a little earlier, so no word is cut). */
    private fun next() {
        val s = session ?: return
        val from = maxOf(s.from, s.fed - REWIND_BYTES)
        s.end()
        open(from, Mode.PHRASES)
    }

    private fun open(from: Int, m: Mode) {
        mode = m
        val s = Session(from, m)
        session = s
        try { s.start() } catch (e: Exception) { note("start failed: ${e.javaClass.simpleName}"); finish(SpeechRecognizer.ERROR_CLIENT) }
    }

    // ---- the whole job -------------------------------------------------------------------------

    fun start() {
        note("file ${file.length() / 1024} KB, $language")
        Thread({
            val bytes = Pcm.decodeAll(file, decode) { cancelled }
            if (bytes == null) note("decode failed")
            main.post {
                if (ended) return@post
                if (bytes == null || bytes.size < BYTES_PER_SEC / 10) { finish(SpeechRecognizer.ERROR_AUDIO); return@post }
                pcm = bytes
                note("decoded %.1fs of audio".format(pcm.size / BYTES_PER_SEC.toDouble()))
                open(0, Mode.SEGMENTED)
                main.post(watchdog)
            }
        }, "transcribe-decode").start()
    }

    /** Checks twice a second that the speech service is still taking the audio and answering. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (ended) return
            val s = session ?: return
            val now = SystemClock.elapsedRealtime()
            if (!s.atEnd()) onUpdate(text, position())
            val notReading = !s.atEnd() && now - s.lastWrite > FEED_STALL_MS
            val silent = s.events == 0 && now - s.startedAt > NO_ANSWER_MS
            when {
                notReading || silent -> {
                    note(if (notReading) "service stopped reading the audio" else "no answer from the service")
                    if (s.mode == Mode.SEGMENTED && parts.isEmpty()) { s.end(); open(0, Mode.PHRASES) }
                    else finish(if (parts.isEmpty()) NOT_ACCEPTED else null)
                }
                s.feedDone && now - s.feedDoneAt > END_GRACE_MS -> {
                    note("no final answer after the end of the audio")
                    finish(if (parts.isEmpty()) NOT_ACCEPTED else null)
                }
            }
            if (!ended) main.postDelayed(this, 500)
        }
    }

    /** Stops, keeping what was already transcribed. */
    fun cancel() { cancelled = true; if (!ended) { note("stopped by the person"); finish(null) } }

    private fun finish(error: Int?) {
        if (ended) return
        ended = true; cancelled = true
        note("finished" + (error?.let { " with error $it" } ?: ""))
        session?.end(); session = null
        main.removeCallbacks(watchdog)
        onEnd(text, error)
    }

    /** For tests: the recogniser conversation of the current session. */
    internal val listener: RecognitionListener? get() = session?.listener

    companion object {
        /** The speech service took no audio or never answered, in either mode. */
        const val NOT_ACCEPTED = -1
        const val BYTES_PER_SEC = Pcm.BYTES_PER_SEC
        private const val REWIND_BYTES = BYTES_PER_SEC / 2
        const val FEED_STALL_MS = 6_000L
        const val NO_ANSWER_MS = 8_000L
        const val END_GRACE_MS = 15_000L
    }
}
