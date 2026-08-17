package com.urufile.uruplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import com.urufile.uruplayer.data.prefs.PrefsManager
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.UUID

class DeviceIdentity(private val context: Context) {

    private val prefs = PrefsManager(context)

    /**
     * Returns the persistent hardware key (UUID).
     * Generated once and NEVER regenerated unless user explicitly resets.
     */
    val hardwareKey: String
        get() {
            if (prefs.hardwareKey.isBlank()) {
                prefs.hardwareKey = UUID.randomUUID().toString()
            }
            return prefs.hardwareKey
        }

    /**
     * Returns the device MAC address.
     * Falls back to hardwareKey-derived string if unavailable.
     */
    val macAddress: String
        get() = try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.macAddress?.takeIf { it != "02:00:00:00:00:00" }
                ?: generateFakeMac()
        } catch (e: Exception) {
            generateFakeMac()
        }

    /**
     * Returns the XMR public key (PEM format).
     * Generates RSA 2048-bit keypair once and stores both keys.
     */
    val xmrPublicKey: String
        get() {
            if (prefs.xmrPublicKey.isBlank()) {
                generateRsaKeyPair()
            }
            return prefs.xmrPublicKey
        }

    /**
     * Returns the XMR private key (PEM format) for decryption.
     */
    val xmrPrivateKey: String
        get() {
            if (prefs.xmrPrivateKey.isBlank()) {
                generateRsaKeyPair()
            }
            return prefs.xmrPrivateKey
        }

    /**
     * Returns the XMR channel identifier.
     */
    val xmrChannel: String
        get() {
            if (prefs.xmrChannel.isBlank()) {
                prefs.xmrChannel = "xmr_${hardwareKey.replace("-", "").take(16)}"
            }
            return prefs.xmrChannel
        }

    private fun generateRsaKeyPair() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()

        val pubKey = keyPair.public as RSAPublicKey
        val privKey = keyPair.private

        // Use android.util.Base64 (available from API 1) instead of java.util.Base64 (API 26+)
        val pubB64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(privKey.encoded, Base64.NO_WRAP)

        prefs.xmrPublicKey = "-----BEGIN PUBLIC KEY-----\n$pubB64\n-----END PUBLIC KEY-----"
        prefs.xmrPrivateKey = "-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----"
    }

    private fun generateFakeMac(): String {
        val hw = hardwareKey.replace("-", "")
        return "${hw.substring(0, 2)}:${hw.substring(2, 4)}:${hw.substring(4, 6)}:" +
                "${hw.substring(6, 8)}:${hw.substring(8, 10)}:${hw.substring(10, 12)}"
    }
}
