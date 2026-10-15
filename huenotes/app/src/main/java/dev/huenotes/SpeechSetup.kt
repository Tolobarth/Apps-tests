package dev.huenotes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * First use of dictation or transcripts: makes sure the phone's speech service (normally Google's) has the language, and lets
 * the person download it if not. The download is done by that service, from its own servers; HueNotes ships no speech model.
 */
object SpeechSetup {
    sealed class State {
        /** Ready to listen in [tag] (the variant the service has, e.g. "fr-CA" when "fr-FR" was wanted). */
        data class Ready(val tag: String) : State()
        data class Downloading(val tag: String) : State()
        data class NeedsDownload(val tag: String) : State()
        /** The service has no offline speech for this language at all. */
        object NotOffered : State()
        /** The service didn't say (older Android, or no answer): just try. */
        object Unknown : State()
    }

    private fun norm(t: String) = t.replace('_', '-').lowercase(Locale.ROOT)
    private fun lang(t: String) = norm(t).substringBefore('-')

    /** Picks the best match for [want]: the exact language and region first, then the same language in another region. */
    fun pick(want: String, installed: List<String>, pending: List<String>, supported: List<String>): State {
        if (installed.isEmpty() && pending.isEmpty() && supported.isEmpty()) return State.Unknown
        val w = norm(want)
        // Some services write "pt_BR"; the recogniser is always given a proper tag ("pt-BR").
        val exact: (List<String>) -> String? = { l -> l.firstOrNull { norm(it) == w }?.replace('_', '-') }
        val same: (List<String>) -> String? = { l -> l.firstOrNull { lang(it) == lang(w) }?.replace('_', '-') }
        exact(installed)?.let { return State.Ready(it) }
        exact(pending)?.let { return State.Downloading(it) }
        exact(supported)?.let { return State.NeedsDownload(it) }
        same(installed)?.let { return State.Ready(it) }
        same(pending)?.let { return State.Downloading(it) }
        same(supported)?.let { return State.NeedsDownload(it) }
        return State.NotOffered
    }

    /** The language's own name for itself ("français", "English"), as written mid-sentence in that language. */
    fun displayName(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }

    /** Asks the on-device service which languages it has (Android 13+). Answers on the main thread; Unknown after [timeoutMs]. */
    fun check(c: Context, want: String = Speech.language(c), timeoutMs: Long = 3000, onResult: (State) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !Speech.onDevice(c)) { onResult(State.Unknown); return }
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull() ?: run { onResult(State.Unknown); return }
        var done = false
        val main = Handler(Looper.getMainLooper())
        fun finish(s: State) { if (done) return; done = true; runCatching { r.destroy() }; onResult(s) }
        main.postDelayed({ finish(State.Unknown) }, timeoutMs)
        try {
            r.checkRecognitionSupport(Speech.intent(c, want), ContextCompat.getMainExecutor(c), object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) =
                    finish(pick(want, s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages))
                override fun onError(error: Int) = finish(State.Unknown)
            })
        } catch (e: Exception) { finish(State.Unknown) }
    }

    /** Download progress as the editor shows it. */
    interface Progress {
        fun percent(p: Int)
        fun done()
        /** The service will fetch it later (e.g. on Wi-Fi). */
        fun scheduled()
        /** The service took the request but won't report progress, or refused it: [followable] tells which. */
        fun failed(followable: Boolean)
    }

    /** Asks the service to download [tag]. Android 14+ reports progress; Android 13 only starts it. */
    fun download(c: Context, tag: String, p: Progress) {
        if (Build.VERSION.SDK_INT < 33) { p.failed(false); return }
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull() ?: run { p.failed(false); return }
        if (Build.VERSION.SDK_INT >= 34) downloadWithProgress(c, r, tag, p)
        else try { r.triggerModelDownload(Speech.intent(c, tag)); p.scheduled() } catch (e: Exception) { p.failed(false) } finally { r.destroy() }
    }

    @RequiresApi(34)
    private fun downloadWithProgress(c: Context, r: SpeechRecognizer, tag: String, p: Progress) {
        try {
            r.triggerModelDownload(Speech.intent(c, tag), ContextCompat.getMainExecutor(c), object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = p.percent(completedPercent)
                override fun onSuccess() { r.destroy(); p.done() }
                override fun onScheduled() { r.destroy(); p.scheduled() }
                override fun onError(error: Int) {
                    r.destroy()
                    // The service accepted the download but can't tell us how it goes: it may still arrive.
                    p.failed(followable = error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
                }
            })
        } catch (e: Exception) { r.destroy(); p.failed(false) }
    }

    /** The package providing speech recognition, Google's preferred when there are several. */
    fun servicePackage(c: Context): String? {
        val pkgs = c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { it.serviceInfo.packageName }.distinct()
        return GOOGLE.firstOrNull { it in pkgs } ?: pkgs.firstOrNull()
    }

    private val GOOGLE = listOf("com.google.android.tts", "com.google.android.as", "com.google.android.googlequicksearchbox")

    /**
     * Where a person can download speech languages by hand: the phone's voice input settings (which lead to the speech
     * service's own language list), else the speech app's info page, else Settings.
     */
    fun openSettings(c: Context) {
        val tries = listOfNotNull(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            servicePackage(c)?.let { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)) },
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
    }
}
