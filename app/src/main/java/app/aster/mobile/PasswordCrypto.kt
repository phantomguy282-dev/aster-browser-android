package app.aster.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore implementation of [PasswordSealer].
 * AES-256-GCM with a non-exportable keystore key (TEE/StrongBox-backed where
 * available). IV is prepended to the sealed blob. No plaintext ever touches
 * disk or logs.
 */
object PasswordCrypto : PasswordSealer {
    private const val ALIAS = "aster_password_vault"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: CharArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES)
        val sealed = cipher.doFinal(String(plain).toByteArray(Charsets.UTF_8))
        return iv + sealed
    }

    override fun decrypt(sealed: ByteArray): CharArray {
        require(sealed.size > GCM_IV_BYTES) { "Corrupt credential blob" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES), Charsets.UTF_8).toCharArray()
    }
}
