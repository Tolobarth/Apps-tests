package dev.huenotes

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** App-level password. Stores only a salted PBKDF2 hash; it gates the UI, it does not encrypt the database. */
object Lock {
    private const val PREFS = "lock"
    private const val RELOCK_AFTER_MS = 30_000L
    private var unlockedUntilLeft = false
    private var leftAt = 0L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(c: Context) = prefs(c).contains("hash")

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 100_000, 256)).encoded

    fun set(c: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit().putString("salt", Base64.encodeToString(salt, 0))
            .putString("hash", Base64.encodeToString(hash(pw, salt), 0)).apply()
        unlockedUntilLeft = true
    }

    fun clear(c: Context) { prefs(c).edit().clear().apply() }

    fun verify(c: Context, pw: String): Boolean {
        val p = prefs(c)
        val salt = Base64.decode(p.getString("salt", null) ?: return false, 0)
        val want = Base64.decode(p.getString("hash", null) ?: return false, 0)
        return MessageDigest.isEqual(want, hash(pw, salt))
    }

    fun needsUnlock(c: Context): Boolean {
        if (!isSet(c)) return false
        if (unlockedUntilLeft && System.currentTimeMillis() - leftAt < RELOCK_AFTER_MS) return false
        unlockedUntilLeft = false
        return true
    }

    fun markUnlocked() { unlockedUntilLeft = true }
    fun markLeft() { leftAt = System.currentTimeMillis() }

    fun prompt(a: Activity, title: String, onOk: (String) -> Unit, onCancel: (() -> Unit)? = null) {
        val til = TextInputLayout(a).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            val pad = Palette.dp(a, 20); setPadding(pad, Palette.dp(a, 8), pad, 0)
        }
        val input = TextInputEditText(til.context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        til.addView(input)
        MaterialAlertDialogBuilder(a).setTitle(title).setView(til)
            .setPositiveButton(R.string.btn_ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel?.invoke() }
            .setCancelable(onCancel == null).show()
    }

    fun setupFlow(a: Activity, then: () -> Unit = {}) = prompt(a, a.getString(R.string.lock_new), { pw ->
        if (pw.length < 4) Toast.makeText(a, R.string.lock_short, Toast.LENGTH_SHORT).show()
        else { set(a, pw); Toast.makeText(a, R.string.lock_set, Toast.LENGTH_SHORT).show(); then() }
    })

    fun changeFlow(a: Activity) = prompt(a, a.getString(R.string.lock_current), { cur ->
        if (verify(a, cur)) setupFlow(a) else Toast.makeText(a, R.string.lock_wrong, Toast.LENGTH_SHORT).show()
    })

    fun removeFlow(a: Activity, then: () -> Unit = {}) = prompt(a, a.getString(R.string.lock_current), { cur ->
        if (verify(a, cur)) { clear(a); Widgets.refresh(a); Toast.makeText(a, R.string.lock_removed, Toast.LENGTH_SHORT).show(); then() }
        else Toast.makeText(a, R.string.lock_wrong, Toast.LENGTH_SHORT).show()
    })
}

/** Base class: hides content and demands the password whenever the app is locked. */
abstract class GatedActivity : AppCompatActivity() {
    private var dialogUp = false

    override fun onResume() {
        super.onResume()
        // Block screenshots / recents thumbnails only while a password protects the notes.
        if (Lock.isSet(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = findViewById<View>(android.R.id.content)
        if (Lock.needsUnlock(this)) {
            content.visibility = View.INVISIBLE
            if (!dialogUp) { dialogUp = true; ask(content) }
        } else onUnlocked()
    }

    private fun ask(content: View): Unit = Lock.prompt(this, getString(R.string.lock_enter), { pw ->
        dialogUp = false
        if (Lock.verify(this, pw)) { Lock.markUnlocked(); content.visibility = View.VISIBLE; onUnlocked() }
        else { Toast.makeText(this, R.string.lock_wrong, Toast.LENGTH_SHORT).show(); dialogUp = true; ask(content) }
    }, onCancel = { dialogUp = false; finishAffinity() })

    override fun onPause() { super.onPause(); Lock.markLeft() }

    /** Called when content may be shown/refreshed. */
    protected open fun onUnlocked() {}
}
