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
    fun canTranscribe(c: Context) = Build.VERSION.SDK_INT >= 33 && canDictate(c)

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
    private val main = Handler(Looper.getMainLooper())
    val text get() = DictationText.join(parts)

    internal val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) { onUpdate(text, Speech.best(b)) }
        override fun onResults(b: Bundle?) {
            Speech.best(b).takeIf { it.isNotEmpty() }?.let { parts += it }
            onUpdate(text, "")
            if (stopping) finish(null) else main.post { if (!ended) listen() }    // keep going until the person stops
        }
        override fun onError(error: Int) = finish(if (Speech.isSilence(error) && (parts.isNotEmpty() || stopping)) null else error)
        override fun onReadyForSpeech(p: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(v: Float) = Unit
        override fun onBufferReceived(b: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(t: Int, p: Bundle?) = Unit
    }

    fun start() { recognizer.setRecognitionListener(listener); listen() }

    private fun listen() = recognizer.startListening(Speech.intent(c, language))

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
        recognizer.destroy()
        onEnd(text, error)
    }

    companion object { const val STOP_GRACE_MS = 4000L }
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
 * Transcribes a voice note (Android 13+): the recording is decoded on a worker thread and streamed to the recogniser through
 * a pipe as one long, segmented session. [onUpdate] gets the text so far and the position reached in ms; [onEnd] the result.
 */
class Transcriber(
    private val c: Context,
    private val file: File,
    private val onUpdate: (text: String, positionMs: Long) -> Unit,
    private val onEnd: (text: String, error: Int?) -> Unit,
    private val language: String = Speech.language(c),
    private val recognizer: SpeechRecognizer = Speech.create(c),
    /** Writes the recording as 16 kHz mono PCM; tests replace it. */
    private val decode: (File, OutputStream, () -> Boolean, (Long) -> Unit) -> Long = Pcm::decode,
) {
    private val parts = ArrayList<String>()
    @Volatile private var cancelled = false
    private var ended = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var position = 0L
    /** Our end of the pipe the recogniser reads. Kept open for the whole session: Android hands the request to the speech
     *  service a moment after startListening(), and a closed descriptor at that point crashes the app. */
    private var source: ParcelFileDescriptor? = null
    val text get() = DictationText.join(parts)

    internal val listener = object : RecognitionListener {
        override fun onSegmentResults(b: Bundle) { Speech.best(b).takeIf { it.isNotEmpty() }?.let { parts += it }; onUpdate(text, position) }
        override fun onEndOfSegmentedSession() = finish(null)
        override fun onResults(b: Bundle?) { Speech.best(b).takeIf { it.isNotEmpty() }?.let { parts += it }; finish(null) }
        override fun onError(error: Int) = finish(if (Speech.isSilence(error) && parts.isNotEmpty()) null else error)
        override fun onPartialResults(b: Bundle?) = Unit
        override fun onReadyForSpeech(p: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(v: Float) = Unit
        override fun onBufferReceived(b: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(t: Int, p: Bundle?) = Unit
    }

    @RequiresApi(33)
    fun start() {
        val (read, write) = try { ParcelFileDescriptor.createPipe().let { it[0] to it[1] } } catch (e: IOException) { finish(SpeechRecognizer.ERROR_CLIENT); return }
        source = read
        try {
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(Speech.intent(c, language)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.RATE)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        } catch (e: Exception) { runCatching { write.close() }; finish(SpeechRecognizer.ERROR_CLIENT); return }
        Thread {
            var ok = false
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    decode(file, out, { cancelled }) { ms -> position = ms; main.post { if (!ended) onUpdate(text, ms) } }
                }
                ok = true
            } catch (e: Exception) { runCatching { write.close() } }
            main.post {
                if (ended || cancelled) return@post
                if (!ok) { recognizer.cancel(); finish(if (parts.isEmpty()) SpeechRecognizer.ERROR_AUDIO else null) }
                // The whole recording has been handed over; if the service never says it's done, stop waiting.
                else main.postDelayed({ if (!ended) { recognizer.cancel(); finish(null) } }, END_GRACE_MS)
            }
        }.start()
    }

    /** Stops, keeping what was already transcribed. */
    fun cancel() { cancelled = true; if (!ended) { runCatching { recognizer.cancel() }; finish(null) } }

    private fun finish(error: Int?) {
        if (ended) return
        ended = true; cancelled = true
        runCatching { recognizer.destroy() }
        source?.let { runCatching { it.close() } }; source = null
        onEnd(text, error)
    }

    companion object { const val END_GRACE_MS = 30_000L }
}
