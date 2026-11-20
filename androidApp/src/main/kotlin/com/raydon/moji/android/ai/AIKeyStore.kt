package com.raydon.moji.android.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where API keys are kept. The app uses the Android Keystore; tests and
 * screenshot scenarios pass an in-memory store so nothing real is read or written.
 */
interface AIKeyStoring {
    /** The saved key, or "" when there is none. Throws when the store cannot be read. */
    fun read(account: String): String

    /** Saves a trimmed key; an empty one deletes it. */
    fun write(key: String, account: String)
}

/**
 * API keys encrypted with an AES key that never leaves the Android Keystore.
 * Only the ciphertext is written, to a private preferences file of its own,
 * which the manifest's backup and transfer rules leave out; nothing here goes
 * into the plan database, a backup file or the widget.
 */
class AIKeystoreStore(context: Context) : AIKeyStoring {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun read(account: String): String = synchronized(lock) {
        val stored = prefs.getString(entry(account), null)
        if (stored == null) "" else decrypt(stored)
    }

    private fun decrypt(stored: String): String {
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) throw AIRequestError.credentialUnavailable
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false) ?: throw AIRequestError.credentialUnavailable,
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (error: AIRequestError) {
            throw error
        } catch (error: Exception) {
            // Same as a keychain read failing: the caller treats it as "no key"
            // and saving a key again overwrites the unreadable entry.
            throw AIRequestError.credentialUnavailable
        }
    }

    override fun write(key: String, account: String) {
        synchronized(lock) { store(key, account) }
    }

    private fun store(key: String, account: String) {
        val clean = key.trim()
        if (clean.contains('\n') || clean.contains('\r')) throw AIRequestError.credentialUnavailable
        if (clean.isEmpty()) {
            if (!prefs.edit().remove(entry(account)).commit()) throw AIRequestError.credentialUnavailable
            return
        }
        val encoded = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true) ?: throw AIRequestError.credentialUnavailable)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw AIRequestError.credentialUnavailable
            Base64.encodeToString(iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (error: AIRequestError) {
            throw error
        } catch (error: Exception) {
            throw AIRequestError.credentialUnavailable
        }
        // commit, not apply: a save must fail visibly rather than lose the key later.
        if (!prefs.edit().putString(entry(account), encoded).commit()) throw AIRequestError.credentialUnavailable
    }

    private fun entry(account: String) = "key.$account"

    private fun secretKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "moji.ai.keys"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "moji.ai.keys.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Keys held in memory only. */
class AIMemoryKeyStore(values: Map<String, String> = emptyMap()) : AIKeyStoring {
    private val values = values.toMutableMap()

    override fun read(account: String): String = synchronized(values) { values[account] ?: "" }

    override fun write(key: String, account: String) {
        val clean = key.trim()
        synchronized(values) {
            if (clean.isEmpty()) values.remove(account) else values[account] = clean
            Unit
        }
    }
}
