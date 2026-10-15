package dev.huenotes

import android.Manifest
import android.app.Application
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
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
import java.util.concurrent.TimeUnit
import dev.huenotes.SpeechSetup.State

/** First use of speech: is the language on the phone, and getting it there when it isn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechSetupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.reset(); ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    }
    @After fun tearDown() { ShadowSpeechRecognizer.reset(); MlKitSpeech.factory = { error("not in tests") } }

    // ---- choosing the language -----------------------------------------------------------------

    @Test fun exactLanguageAndRegionWinsThenTheSameLanguageElsewhere() {
        assertEquals(State.Ready("fr-FR"), SpeechSetup.pick("fr-FR", listOf("en-US", "fr-FR"), emptyList(), listOf("fr-CA")))
        assertEquals("the region the person speaks beats an installed other region",
            State.NeedsDownload("fr-FR"), SpeechSetup.pick("fr-FR", listOf("fr-CA"), emptyList(), listOf("fr-FR")))
        assertEquals(State.Ready("fr-CA"), SpeechSetup.pick("fr-FR", listOf("fr-CA"), emptyList(), listOf("de-DE")))
        assertEquals(State.Downloading("fr-FR"), SpeechSetup.pick("fr-FR", emptyList(), listOf("fr-FR"), listOf("fr-FR")))
        assertEquals(State.Ready("pt-BR"), SpeechSetup.pick("pt", listOf("pt_BR"), emptyList(), emptyList()))
        assertEquals(State.NotOffered, SpeechSetup.pick("ga-IE", listOf("en-US"), emptyList(), listOf("fr-FR")))
        assertEquals("a service that lists nothing gets a try", State.Unknown, SpeechSetup.pick("fr-FR", emptyList(), emptyList(), emptyList()))
    }

    @Test fun languagesAreNamedTheWayTheyAreWrittenMidSentence() {
        assertEquals("français", SpeechSetup.displayName("fr-FR")); assertEquals("Deutsch", SpeechSetup.displayName("de"))
    }

    // ---- asking the service --------------------------------------------------------------------

    private fun support(installed: List<String> = emptyList(), supported: List<String> = emptyList()) =
        RecognitionSupport.Builder().setInstalledOnDeviceLanguages(installed).setSupportedOnDeviceLanguages(supported).build()
    private fun latest(): ShadowSpeechRecognizer { ShadowLooper.idleMainLooper(); return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) }

    @Test fun checkReportsWhatTheServiceSays() {
        var got: State? = null
        SpeechSetup.check(app, "fr-FR") { got = it }
        latest().triggerSupportResult(support(supported = listOf("fr-FR"))); ShadowLooper.idleMainLooper()
        assertEquals(State.NeedsDownload("fr-FR"), got)
    }

    @Test fun aServiceThatNeverAnswersDoesNotBlock() {
        var got: State? = null
        SpeechSetup.check(app, "fr-FR", timeoutMs = 3000) { got = it }
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertEquals(State.Unknown, got)
    }

    // ---- the editor's first use (transcribing with Android's recogniser) ------------------------------

    /** A note with a voice note, ML Kit out of the way so Android's recogniser path is used. */
    private fun editor(): org.robolectric.android.controller.ActivityController<EditorActivity> {
        MlKitSpeech.factory = { throw IllegalStateException("ML Kit not in this test") }
        val s = NoteStore(app); val id = s.save(Note(title = "V")); val uid = s.get(id)!!.uid
        s.addAttachment(Attachment(noteUid = uid, kind = AttachmentKind.AUDIO, durationMs = 1000).also { s.storage.file(it).writeBytes(byteArrayOf(1)) })
        return Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup()
    }
    private fun transcribe(c: org.robolectric.android.controller.ActivityController<EditorActivity>) {
        c.get().findViewById<View>(R.id.transcribe).performClick(); ShadowLooper.idleMainLooper()
    }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun message(d: AlertDialog) = d.findViewById<android.widget.TextView>(android.R.id.message)?.text.toString()
    /** The transcription itself ran (it then stops at once: the test recording isn't real audio). */
    private fun transcriptionRan(): Boolean {
        repeat(5) { Thread.sleep(60); ShadowLooper.idleMainLooper() }
        return org.robolectric.shadows.ShadowToast.showedToast(app.getString(Speech.errorText(SpeechRecognizer.ERROR_AUDIO)))
    }

    @Test fun anInstalledLanguageGoesStraightToTranscribing() {
        Prefs.setLanguage(app, "fr")
        val c = editor(); transcribe(c)
        latest().triggerSupportResult(support(installed = listOf("fr-CA")))
        assertTrue(transcriptionRan())
    }

    @Test fun aMissingLanguageIsOfferedForDownloadBeforeTranscribing() {
        val c = editor(); transcribe(c)
        val checker = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        latest().triggerSupportResult(support(supported = listOf(Speech.language(app)))); ShadowLooper.idleMainLooper()
        assertSame("nothing listens yet", checker, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val offer = dialog()
        assertTrue(message(offer).contains(SpeechSetup.displayName(Speech.language(app))))
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        // The service asked to download (whatever it answers, the person is never left without a next step).
        assertTrue(dialog().isShowing)
    }

    @Test @Config(sdk = [33]) fun onAndroid13TheDownloadIsRequestedAndThePersonToldToComeBack() {
        val c = editor(); transcribe(c)
        latest().triggerSupportResult(support(supported = listOf(Speech.language(app)))); ShadowLooper.idleMainLooper()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(Speech.language(app), latest().latestModelDownloadIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        val after = dialog()
        assertTrue(message(after).contains(SpeechSetup.displayName(Speech.language(app))))
        assertNotNull("a way to the phone's speech settings", after.getButton(AlertDialog.BUTTON_NEUTRAL).takeIf { it.visibility == View.VISIBLE })
    }

    @Test fun whenOfflineSpeechLacksTheLanguageOnlineIsOfferedNotForced() {
        shadowOf(app.packageManager).addServiceIfNotPresent(android.content.ComponentName("com.google.android.tts", "Svc"))
        shadowOf(app.packageManager).addIntentFilterForService(android.content.ComponentName("com.google.android.tts", "Svc"),
            android.content.IntentFilter("android.speech.RecognitionService"))
        val c = editor(); transcribe(c)
        val checker = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        latest().triggerSupportResult(support(installed = listOf("xx-XX"))); ShadowLooper.idleMainLooper()
        assertFalse(Prefs.onlineSpeech(app))
        assertSame(checker, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertTrue(Prefs.onlineSpeech(app))
        assertTrue(transcriptionRan())
        assertEquals("com.google.android.tts", SpeechSetup.servicePackage(app))
    }
}
