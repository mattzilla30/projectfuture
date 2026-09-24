package com.projectfuture.browser.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.projectfuture.browser.net.Url
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A saved login, scoped to the origin (scheme://host:port) it was captured on. */
data class SavedCredential(val origin: String, val username: String, val password: String)

/** `scheme://host:port` - the same granularity [com.projectfuture.browser.net.isSameOrigin] uses, so a saved login only ever autofills the exact site it was saved on, never a different host or scheme. */
fun Url.credentialOrigin(): String = "$scheme://$host:$port"

/**
 * Encrypted-at-rest credential store for saved logins - the in-browser
 * password manager described in the project roadmap, as an alternative to
 * (not a replacement for) the platform Autofill framework.
 *
 * Design: a single AES-256/GCM key is generated once inside the Android
 * Keystore (`AndroidKeyStore` provider, alias [KEY_ALIAS]) and never leaves
 * it - `getKey()` returns an opaque handle backed by hardware/TEE storage
 * where available, not exportable key material. Every saved
 * username/password pair is JSON-encoded, encrypted with that key via
 * [CredentialCipher] (fresh random IV per entry), base64'd, and only that
 * ciphertext blob is written to SharedPreferences. This is a from-scratch
 * equivalent of `androidx.security`'s `EncryptedSharedPreferences` (which
 * this project doesn't depend on, matching its policy of hand-writing
 * things instead of pulling in a library for them) built directly on
 * Keystore + `javax.crypto`.
 *
 * Real limitations, stated plainly: usernames (the map keys/index) are
 * stored in plaintext, only passwords+usernames-inside-the-blob are
 * encrypted twice-over (the JSON blob holds both, but the origin they're
 * filed under is a plaintext SharedPreferences key) - matching how most
 * real password managers treat the site/username as metadata rather than
 * secret. There's no master passphrase gate on top of Keystore (no
 * `setUserAuthenticationRequired`), so any code running as this app's UID
 * can ask Keystore to decrypt - the boundary this defends is disk-level
 * (a backup, a rooted device's `/data` pull, another app), not
 * "another part of this app".
 */
class CredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = CredentialCipher(::keystoreKey)

    fun credentialsForOrigin(origin: String): List<SavedCredential> =
        readEntries(origin).mapNotNull { decodeEntry(origin, it) }

    fun hasCredentials(origin: String): Boolean = readEntries(origin).isNotEmpty()

    /** Every saved credential across every origin, for a settings-style management list. */
    fun allCredentials(): List<SavedCredential> =
        readAll().entries.flatMap { (origin, entries) -> entries.mapNotNull { decodeEntry(origin, it) } }

    /** Saves (or overwrites, matched by username) a login for [origin]. */
    fun save(origin: String, username: String, password: String) {
        val all = readAll()
        val list = all.getOrPut(origin) { ArrayList() }
        list.removeAll { entryUsername(it) == username }
        val plaintext = JSONObject().put("u", username).put("p", password).toString().toByteArray(Charsets.UTF_8)
        val blob = Base64.encodeToString(cipher.encrypt(plaintext), Base64.NO_WRAP)
        list.add(JSONObject().put("u", username).put("blob", blob).toString())
        writeAll(all)
    }

    fun delete(origin: String, username: String) {
        val all = readAll()
        val list = all[origin] ?: return
        if (list.removeAll { entryUsername(it) == username }) writeAll(all)
    }

    private fun entryUsername(raw: String): String? = try { JSONObject(raw).optString("u", null) } catch (_: Exception) { null }

    private fun decodeEntry(origin: String, raw: String): SavedCredential? = try {
        val obj = JSONObject(raw)
        val blob = Base64.decode(obj.getString("blob"), Base64.NO_WRAP)
        val plaintext = JSONObject(String(cipher.decrypt(blob), Charsets.UTF_8))
        SavedCredential(origin, plaintext.getString("u"), plaintext.getString("p"))
    } catch (_: Exception) {
        null // Corrupt entry, or decrypted with a key that's since been invalidated - skip it rather than crash.
    }

    private fun readEntries(origin: String): List<String> = readAll()[origin] ?: emptyList()

    private fun readAll(): MutableMap<String, MutableList<String>> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return LinkedHashMap()
        val root = try { JSONObject(json) } catch (_: Exception) { return LinkedHashMap() }
        val map = LinkedHashMap<String, MutableList<String>>()
        root.keys().forEach { origin ->
            val arr = root.optJSONArray(origin) ?: JSONArray()
            map[origin] = MutableList(arr.length()) { arr.getString(it) }
        }
        return map
    }

    private fun writeAll(entries: Map<String, List<String>>) {
        val root = JSONObject()
        for ((origin, list) in entries) root.put(origin, JSONArray(list))
        prefs.edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    /** Gets (or, on first use, generates) this app's Keystore-resident AES key. Never returns/handles raw key bytes. */
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "credentials_store"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_ALIAS = "projectfuture_credential_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
