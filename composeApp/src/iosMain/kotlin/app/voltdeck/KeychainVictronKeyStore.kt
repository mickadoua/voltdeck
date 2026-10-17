@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronKeyStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Clés d'annonce Victron dans le Keychain iOS (classe « mot de passe générique », accessible appareil déverrouillé,
 * jamais sauvegardée vers un autre appareil). Rien n'est écrit en SQLite ni dans un journal.
 * Security.framework n'est pas compilable sous Linux : ce fichier n'est vérifié que par le job CI iOS (workflow_dispatch).
 */
class KeychainVictronKeyStore : VictronKeyStore {
    override fun get(device: DeviceId): VictronKey? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(device, extra = mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) {
            SecItemCopyMatching(it, result.ptr)
        }
        if (status != errSecSuccess) return null
        val data: CFDataRef = result.value?.reinterpret() ?: return null
        try {
            val length = CFDataGetLength(data).toInt()
            if (length != VictronKey.SIZE_BYTES) return null
            val bytes = CFDataGetBytePtr(data)!!.reinterpret<ByteVar>().readBytes(length)
            try {
                VictronKey.fromBytes(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            CFRelease(data)
        }
    }

    override fun put(device: DeviceId, key: VictronKey) {
        remove(device)
        val bytes = key.exportBytes()
        try {
            val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
            val retained = CFBridgingRetain(data)
            try {
                val status = withQuery(
                    device,
                    extra = mapOf(kSecValueData to retained, kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly),
                ) { SecItemAdd(it, null) }
                check(status == errSecSuccess) { "Keychain : écriture refusée" }
            } finally {
                CFRelease(retained)
            }
        } finally {
            bytes.fill(0)
        }
    }

    override fun remove(device: DeviceId) {
        withQuery(device, extra = emptyMap()) { SecItemDelete(it) }
    }

    private fun <R> withQuery(
        device: DeviceId,
        extra: Map<CFStringRef?, CFTypeRef?>,
        call: (CFDictionaryRef?) -> R,
    ): R {
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(device.value)
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            extra.forEach { (k, v) -> CFDictionaryAddValue(query, k, v) }
            return call(query)
        } finally {
            CFRelease(query)
            CFRelease(service)
            CFRelease(account)
        }
    }

    private companion object {
        const val SERVICE = "app.voltdeck.victron.keys"
    }
}
