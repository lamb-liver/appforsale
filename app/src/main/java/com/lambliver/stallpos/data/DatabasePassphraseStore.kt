package com.lambliver.stallpos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object DatabasePassphraseStore {
    private const val PREFS = "stallpos_db_key"
    private const val KEY_ALIAS = "stallpos-db-wrap-v1"
    private const val IV = "iv"
    private const val WRAPPED = "wrapped"
    private val aad = "stallpos-db-passphrase-v1".toByteArray()

    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString(IV, null)
        val wrapped = prefs.getString(WRAPPED, null)
        check((iv == null) == (wrapped == null)) { "Database passphrase record is incomplete" }
        if (iv != null && wrapped != null) {
            val key = loadKey() ?: error("Database wrapping key is missing")
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                updateAAD(aad)
                doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
            }.also { check(it.size == 32) { "Database passphrase has invalid length" } }
        }

        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, loadKey() ?: createKey())
            updateAAD(aad)
        }
        val encrypted = cipher.doFinal(passphrase)
        check(
            prefs.edit()
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(WRAPPED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit(),
        ) { "Database passphrase could not be persisted" }
        return passphrase
    }

    private fun loadKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
