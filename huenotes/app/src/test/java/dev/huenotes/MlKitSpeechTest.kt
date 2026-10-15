package dev.huenotes

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
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
import java.util.Locale

/** Transcription through ML Kit (with a fake engine: ML Kit itself needs a real phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitSpeechTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Fake(var status: MlKitSpeech.Status = MlKitSpeech.Status.AVAILABLE) : MlKitSpeech.Engine {
        val events = Channel<MlKitSpeech.Event>(Channel.UNLIMITED)
        var downloads: List<MlKitSpeech.Download> = listOf(MlKitSpeech.Download.Started(100), MlKitSpeech.Download.Progress(50), MlKitSpeech.Download.Completed)
        var audio: ParcelFileDescriptor? = null; var closed = false; var stopped = false
        override suspend fun status() = status
        override fun download(): Flow<MlKitSpeech.Download> = flowOf(*downloads.toTypedArray())
        override fun recognize(audio: ParcelFileDescriptor): Flow<MlKitSpeech.Event> { this.audio = audio; return events.receiveAsFlow() }
        override suspend fun stop() { stopped = true; events.close() }
        override fun close() { closed = true }
    }

    private lateinit var fake: Fake
    private var ended: Pair<String, Int?>? = null

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        fake = Fake(); MlKitSpeech.factory = { fake }
        ShadowSpeechRecognizer.reset()
    }
    @After fun tearDown() { MlKitSpeech.factory = { error("not in tests") }; ShadowSpeechRecognizer.reset() }

    private fun settle(advanceMs: Long = 0) {
        repeat(3) { Thread.sleep(80); ShadowLooper.idleMainLooper() }
        if (advanceMs > 0) ShadowLooper.idleMainLooper(advanceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        repeat(2) { Thread.sleep(50); ShadowLooper.idleMainLooper() }
    }

    private fun transcriber(seconds: Double = 1.0) = MlKitTranscriber(File("x.m4a"), fake, { _, _ -> }, { t, e -> ended = t to e },
        decode = { _, out, _, _ -> out.write(ByteArray((seconds * Pcm.BYTES_PER_SEC).toInt())); 0L }, speed = 1e6)

    @Test fun languagesGetTheRegionMlKitExpects() {
        assertEquals(Locale("fr", "FR"), MlKitSpeech.locale("fr"))
        assertEquals(Locale("pt", "BR"), MlKitSpeech.locale("pt"))
        assertEquals(Locale.forLanguageTag("en-GB"), MlKitSpeech.locale("en-GB"))
    }

    @Test fun finalPiecesAreJoinedAndTheRecordingIsFedThroughAPipe() {
        ended = null
        val t = transcriber(); t.start(); settle()
        assertNotNull("ML Kit reads the recording from a pipe", fake.audio)
        fake.events.trySend(MlKitSpeech.Event.Partial("bonjour"))
        fake.events.trySend(MlKitSpeech.Event.Final("bonjour à tous."))
        fake.events.trySend(MlKitSpeech.Event.Final("à demain"))
        fake.events.trySend(MlKitSpeech.Event.Done)
        settle()
        assertEquals("Bonjour à tous. À demain" to null, ended)
        assertTrue(fake.closed)
    }

    @Test fun anEarlyFailureLetsTheCallerTryAndroidsRecogniser() {
        ended = null
        val t = transcriber(); t.start(); settle()
        fake.events.trySend(MlKitSpeech.Event.Failed("locale not supported"))
        settle()
        assertEquals("" to MlKitTranscriber.ENGINE_FAILED, ended)
        assertTrue(t.details, t.details.contains("locale not supported"))
    }

    @Test fun stoppingKeepsTheWordsBeingHeard() {
        ended = null
        val t = transcriber(); t.start(); settle()
        fake.events.trySend(MlKitSpeech.Event.Final("un"))
        fake.events.trySend(MlKitSpeech.Event.Partial("deux trois"))
        settle()
        t.cancel()
        assertEquals("Un deux trois" to null, ended)
    }

    @Test fun googlesSpeechAppIsReleasedEvenIfItNeverConfirmsTheStop() {
        val stuck = object : MlKitSpeech.Engine by fake {
            override suspend fun stop() { kotlinx.coroutines.awaitCancellation() }
        }
        ended = null
        val t = MlKitTranscriber(File("x.m4a"), stuck, { _, _ -> }, { x, e -> ended = x to e },
            decode = { _, out, _, _ -> out.write(ByteArray(Pcm.BYTES_PER_SEC)); 0L }, speed = 1e6)
        t.start(); settle()
        t.cancel(); settle(3000)
        assertTrue("closed despite a stop that never returns", fake.closed)
    }

    @Test fun silenceAfterTheLastAudioEndsWithWhatWasHeard() {
        ended = null
        val t = transcriber(); t.start(); settle()
        fake.events.trySend(MlKitSpeech.Event.Final("presque"))
        settle(Transcriber.END_GRACE_MS + 1000)
        assertEquals("Presque" to null, ended)
        assertTrue(fake.stopped)
    }

    // ---- the editor ----------------------------------------------------------------------------

    private fun withGoogleSpeechApp() =
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.google.android.tts" })

    private fun noteWithVoice(): Pair<org.robolectric.android.controller.ActivityController<EditorActivity>, Long> {
        val s = NoteStore(app); val id = s.save(Note(title = "V")); val uid = s.get(id)!!.uid
        s.addAttachment(Attachment(noteUid = uid, kind = AttachmentKind.AUDIO, durationMs = 1000).also { s.storage.file(it).writeBytes(byteArrayOf(1)) })
        return Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup() to id
    }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test fun firstTranscriptionAsksThenDownloadsTheSpeechModelThenTranscribes() {
        withGoogleSpeechApp(); fake.status = MlKitSpeech.Status.DOWNLOADABLE
        // The real decoder needs a phone; feed the fake engine directly once transcription starts.
        val (c, _) = noteWithVoice()
        val button = c.get().findViewById<View>(R.id.transcribe)
        assertEquals("shown when Google's speech app is there", View.VISIBLE, button.visibility)
        button.performClick(); settle()
        val name = SpeechSetup.displayName(MlKitSpeech.locale(Speech.language(app)).toLanguageTag())
        assertTrue("asks first", dialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains(name))
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); settle()
        // Downloaded, then transcription started (and, here, stopped at once: the test recording isn't real audio).
        assertTrue("model ready", org.robolectric.shadows.ShadowToast.showedToast(app.getString(R.string.speech_ready, name)))
        assertTrue("transcription ran", org.robolectric.shadows.ShadowToast.showedToast(app.getString(Speech.errorText(android.speech.SpeechRecognizer.ERROR_AUDIO))))
    }

    private fun message(d: AlertDialog) = d.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()

    @Test fun withoutAnyRecogniserThePersonIsToldToUpdateGooglesSpeechApp() {
        withGoogleSpeechApp(); fake.status = MlKitSpeech.Status.UNAVAILABLE
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val (c, _) = noteWithVoice()
        c.get().findViewById<View>(R.id.transcribe).performClick(); settle()
        val d = dialog()
        assertEquals(app.getString(R.string.transcribe_unavailable), message(d))
        assertEquals(app.getString(R.string.transcribe_share_details), d.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper()
        val store = shadowOf(c.get()).nextStartedActivity
        assertEquals("market://details?id=com.google.android.tts", store.dataString)
    }

    @Test fun whenOnlyOnlineRecognitionExistsItIsOfferedWithConsent() {
        withGoogleSpeechApp(); fake.status = MlKitSpeech.Status.UNAVAILABLE
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        shadowOf(app.packageManager).addServiceIfNotPresent(android.content.ComponentName("com.google.android.tts", "Svc"))
        shadowOf(app.packageManager).addIntentFilterForService(android.content.ComponentName("com.google.android.tts", "Svc"),
            android.content.IntentFilter("android.speech.RecognitionService"))
        val (c, _) = noteWithVoice()
        c.get().findViewById<View>(R.id.transcribe).performClick(); settle()
        assertEquals(app.getString(R.string.transcribe_unavailable_online), message(dialog()))
        assertFalse("nothing sent before agreeing", Prefs.onlineSpeech(app))
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); settle()
        assertTrue(Prefs.onlineSpeech(app))
        assertTrue("transcription ran", org.robolectric.shadows.ShadowToast.showedToast(app.getString(Speech.errorText(android.speech.SpeechRecognizer.ERROR_AUDIO))))
    }
}
