package dev.huenotes

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Dictation and transcription: text placement, audio conversion, the recogniser conversation, and the editor wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    }
    @After fun tearDown() = ShadowSpeechRecognizer.reset()

    private fun results(vararg s: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*s)) }
    private fun latest(): ShadowSpeechRecognizer { ShadowLooper.idleMainLooper(); return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) }

    // ---- where words go ------------------------------------------------------------------------

    @Test fun dictatedWordsFitIntoTheText() {
        assertEquals("Buy milk" to 8, DictationText.insert("", 0, "buy milk"))
        assertEquals("Call Ann. Then bake" to 19, DictationText.insert("Call Ann.", 9, "then bake"))
        assertEquals("Eggs and flour" to 14, DictationText.insert("Eggs", 4, "and flour"))
        assertEquals("a more b" to 6, DictationText.insert("a b", 1, "more"))
        assertEquals("first,\nSecond" to 13, DictationText.insert("first,\n", 7, "second"))
        assertEquals("Note: it works." to 14, DictationText.insert("Note: .", 6, "it works"))
        assertEquals("x" to 1, DictationText.insert("x", 5, "  "))
        assertEquals("Hello there. How are you", DictationText.join(listOf("hello there.", "how are you")))
    }

    // ---- audio conversion ----------------------------------------------------------------------

    @Test fun stereoIsAveragedToMono() =
        assertArrayEquals(shortArrayOf(150, -50), Pcm.downmix(shortArrayOf(100, 200, -100, 0), 2))

    private fun sine(n: Int, rate: Int, hz: Double, from: Int = 0) =
        ShortArray(n) { (10000 * sin(2 * PI * hz * (from + it) / rate)).toInt().toShort() }

    private fun crossings(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }

    @Test fun resamplingKeepsLengthAndPitch() {
        val src = sine(44_100, 44_100, 440.0)                     // one second of A4
        val out = Pcm.Resampler(44_100).process(src)
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        assertEquals("440 Hz has 880 zero crossings a second", 880.0, crossings(out).toDouble(), 4.0)
    }

    @Test fun resamplingInChunksMatchesOneGo() {
        val src = sine(10_000, 48_000, 300.0)
        val whole = Pcm.Resampler(48_000).process(src)
        val r = Pcm.Resampler(48_000)
        val pieces = listOf(0 until 1000, 1000 until 1001, 1001 until 4321, 4321 until 10_000).map { r.process(src.sliceArray(it)) }
        val joined = pieces.fold(ShortArray(0)) { a, b -> a + b }
        assertEquals(whole.size, joined.size)
        for (i in whole.indices) assertEquals("sample $i", whole[i].toDouble(), joined[i].toDouble(), 1.0)
    }

    @Test fun sixteenKilohertzPassesThrough() {
        val s = shortArrayOf(1, 2, 3)
        assertSame(s, Pcm.Resampler(16_000).process(s))
        assertArrayEquals(byteArrayOf(1, 0, -2, -1), Pcm.toBytes(shortArrayOf(1, -2)))
    }

    // ---- availability --------------------------------------------------------------------------

    @Test fun onDeviceRecognitionIsUsedWithoutAsking() {
        assertTrue(Speech.canDictate(app)); assertTrue(Speech.canTranscribe(app))
    }

    @Test fun onlineRecognitionOnlyWhenAllowed() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        shadowOf(app.packageManager).addServiceIfNotPresent(ComponentName("com.google.android.tts", "Svc"))
        shadowOf(app.packageManager).addIntentFilterForService(ComponentName("com.google.android.tts", "Svc"),
            android.content.IntentFilter("android.speech.RecognitionService"))
        assertFalse("off by default: voice would leave the phone", Speech.canDictate(app))
        Prefs.setOnlineSpeech(app, true)
        assertTrue(Speech.canDictate(app))
    }

    @Test fun recognitionLanguageFollowsTheAppWithTheRightRegion() {
        val br = java.util.Locale.forLanguageTag("pt-BR")
        assertEquals("pt-BR", Speech.language(app, br))
        Prefs.setLanguage(app, "pt"); assertEquals("pt-BR", Speech.language(app, br))
        Prefs.setLanguage(app, "de"); assertEquals("de", Speech.language(app, br))
    }

    // ---- dictation -----------------------------------------------------------------------------

    @Test fun aRecogniserThatNeverAnswersAfterStopDoesNotHang() {
        var ended: Pair<String, Int?>? = null
        val d = Dictation(app, { _, _ -> }, { t, e -> ended = t to e })
        d.start(); latest().triggerOnResults(results("kept")); ShadowLooper.idleMainLooper()
        d.stop(); assertNull(ended)
        ShadowLooper.idleMainLooper(Dictation.STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("Kept" to null, ended)
    }

    @Test fun dictationKeepsListeningUntilStopped() {
        val updates = ArrayList<Pair<String, String>>(); var ended: Pair<String, Int?>? = null
        val d = Dictation(app, { t, h -> updates += t to h }, { t, e -> ended = t to e })
        d.start()
        val intent = latest().lastRecognizerIntent
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(java.util.Locale.getDefault().toLanguageTag(), intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        latest().triggerOnPartialResults(results("buy"))
        assertEquals("" to "buy", updates.last())
        latest().triggerOnResults(results("buy milk", "by milk"))
        ShadowLooper.idleMainLooper()
        assertNull("still listening after the first phrase", ended)
        latest().triggerOnResults(results("and eggs"))
        ShadowLooper.idleMainLooper()
        d.stop()
        latest().triggerOnResults(results("thanks"))
        assertEquals("Buy milk and eggs thanks" to null, ended)
        assertTrue(latest().isDestroyed)
    }

    @Test fun silenceAfterSpeechEndsQuietlyButSilenceAloneIsReported() {
        var ended: Pair<String, Int?>? = null
        Dictation(app, { _, _ -> }, { t, e -> ended = t to e }).start()
        latest().triggerOnResults(results("hello")); ShadowLooper.idleMainLooper()
        latest().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals("Hello" to null, ended)

        Dictation(app, { _, _ -> }, { t, e -> ended = t to e }).start()
        latest().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals("" to SpeechRecognizer.ERROR_NO_MATCH, ended)
        assertEquals(R.string.speech_err_nothing, Speech.errorText(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(R.string.speech_err_language, Speech.errorText(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test fun cancelKeepsWhatWasRecognisedAndEndsOnce() {
        val ends = ArrayList<String>()
        val d = Dictation(app, { _, _ -> }, { t, _ -> ends += t })
        d.start(); latest().triggerOnResults(results("one")); ShadowLooper.idleMainLooper()
        d.cancel(); d.cancel(); d.stop()
        assertEquals(listOf("One"), ends)
    }

    // ---- transcription -------------------------------------------------------------------------

    @Test fun transcriptSegmentsAreJoined() {
        var ended: Pair<String, Int?>? = null; val seen = ArrayList<String>()
        val t = Transcriber(app, File("unused.m4a"), { text, _ -> seen += text }, { text, e -> ended = text to e })
        t.listener.onSegmentResults(results("first part."))
        t.listener.onSegmentResults(results(""))
        t.listener.onSegmentResults(results("second part"))
        assertEquals(listOf("First part.", "First part.", "First part. Second part"), seen)
        t.listener.onEndOfSegmentedSession()
        t.listener.onError(SpeechRecognizer.ERROR_CLIENT)          // late callbacks are ignored
        assertEquals("First part. Second part" to null, ended)
    }

    // ---- editor --------------------------------------------------------------------------------

    /** The phone's speech service answers the first-use check: the app's language is installed. */
    private fun speechInstalled() = latest().triggerSupportResult(android.speech.RecognitionSupport.Builder()
        .setInstalledOnDeviceLanguages(listOf(Speech.language(app))).build())

    @Test fun editorDictatesIntoTheTextAtTheCursor() {
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val id = NoteStore(app).save(Note(title = "Shopping", body = "Bread."))
        val c = Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup()
        val body = c.get().findViewById<EditText>(R.id.bodyEdit)
        body.requestFocus(); body.setSelection(body.text.length)
        c.get().findViewById<android.view.View>(R.id.dictateButton).performClick()
        speechInstalled()
        latest().triggerOnResults(results("milk and eggs")); ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        latest().triggerOnResults(results("")); ShadowLooper.idleMainLooper()
        assertEquals("Bread. Milk and eggs", body.text.toString())
        c.pause()
        assertEquals("Bread. Milk and eggs", NoteStore(app).get(id)!!.body)
    }

    @Test fun editorCancelThrowsAwayTheDictation() {
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val id = NoteStore(app).save(Note(title = "T", body = "Keep"))
        val c = Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup()
        c.get().findViewById<android.view.View>(R.id.dictateButton).performClick()
        speechInstalled()
        latest().triggerOnResults(results("oops")); ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("Keep", c.get().findViewById<EditText>(R.id.bodyEdit).text.toString())
    }

    @Test fun withoutRecognitionTheEditorExplainsInsteadOfListening() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        val c = Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java)).setup()
        c.get().findViewById<android.view.View>(R.id.dictateButton).performClick()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }
}
