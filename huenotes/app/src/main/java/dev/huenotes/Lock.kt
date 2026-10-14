package dev.huenotes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
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
        val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(a).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
            .setCancelable(onCancel == null).show()
    }

    fun setupFlow(a: Activity, then: () -> Unit = {}) = prompt(a, "New password (min 4 chars)", { pw ->
        if (pw.length < 4) Toast.makeText(a, "Too short", Toast.LENGTH_SHORT).show()
        else { set(a, pw); Toast.makeText(a, "Password set", Toast.LENGTH_SHORT).show(); then() }
    })

    fun changeFlow(a: Activity) = prompt(a, "Current password", { cur ->
        if (verify(a, cur)) setupFlow(a) else Toast.makeText(a, "Wrong password", Toast.LENGTH_SHORT).show()
    })

    fun removeFlow(a: Activity) = prompt(a, "Current password", { cur ->
        if (verify(a, cur)) { clear(a); Widgets.refresh(a) } else Toast.makeText(a, "Wrong password", Toast.LENGTH_SHORT).show()
    })
}

/** Base class: hides content and demands the password whenever the app is locked. */
abstract class GatedActivity : Activity() {
    private var dialogUp = false

    override fun onResume() {
        super.onResume()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val content = findViewById<View>(android.R.id.content)
        if (Lock.needsUnlock(this)) {
            content.visibility = View.INVISIBLE
            if (!dialogUp) { dialogUp = true; ask(content) }
        } else onUnlocked()
    }

    private fun ask(content: View): Unit = Lock.prompt(this, "Password", { pw ->
        dialogUp = false
        if (Lock.verify(this, pw)) { Lock.markUnlocked(); content.visibility = View.VISIBLE; onUnlocked() }
        else { Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show(); dialogUp = true; ask(content) }
    }, onCancel = { dialogUp = false; finishAffinity() })

    override fun onPause() { super.onPause(); Lock.markLeft() }

    /** Called when content may be shown/refreshed. */
    protected open fun onUnlocked() {}
}
