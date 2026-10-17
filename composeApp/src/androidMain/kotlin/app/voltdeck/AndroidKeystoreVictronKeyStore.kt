package app.voltdeck

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.voltdeck.core.DeviceId
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronKeyStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clés d'annonce Victron chiffrées par une clé AES-GCM non exportable du Keystore Android. Seul le chiffré (IV + texte
 * chiffré, en Base64) est écrit dans des SharedPreferences : ni SQLite, ni journal. Si le Keystore a perdu sa clé
 * (restauration, réinitialisation), la clé Victron est considérée comme absente et l'utilisateur la ressaisit.
 */
class AndroidKeystoreVictronKeyStore(context: Context) : VictronKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun get(device: DeviceId): VictronKey? {
        val stored = prefs.getString(device.value, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            try {
                VictronKey.fromBytes(plain)
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            prefs.edit().remove(device.value).commit()
            null
        }
    }

    override fun put(device: DeviceId, key: VictronKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val plain = key.exportBytes()
        val blob = try {
            cipher.iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        prefs.edit().putString(device.value, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    override fun remove(device: DeviceId) {
        prefs.edit().remove(device.value).commit()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "voltdeck.victron.keys"
        const val PREFS_FILE = "victron_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
