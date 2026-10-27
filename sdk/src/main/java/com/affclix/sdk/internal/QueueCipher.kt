package com.affclix.sdk.internal

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts offline-queue entries (request URLs and bodies carry the advertising
 * ID, IP-linked data and user IDs) with an AES-256-GCM key held in the Android
 * Keystore, so it never leaves secure hardware and needs no SDK init — the
 * WorkManager worker decrypts on its own.
 *
 * Stored form: "enc1:" + base64(iv ‖ ciphertext). Values without the prefix are
 * plaintext: rows queued before encryption, or written on API < 23 (no Keystore
 * AES) or when the Keystore fails.
 */
internal object QueueCipher {

    private const val TAG       = "QueueCipher"
    private const val KEYSTORE  = "AndroidKeyStore"
    private const val ALIAS     = "affclix_event_queue"
    private const val PREFIX    = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES  = 12
    private const val TAG_BITS  = 128

    fun encrypt(plain: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Delivering the event matters more than at-rest encryption on a
            // device whose Keystore is broken.
            Logger.w(TAG, "Keystore encryption failed, storing plaintext: ${e.message}")
            plain
        }
    }

    /** The plaintext, or null when an encrypted value cannot be decrypted (key lost). */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.w(TAG, "Queue entry decryption failed: ${e.message}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
