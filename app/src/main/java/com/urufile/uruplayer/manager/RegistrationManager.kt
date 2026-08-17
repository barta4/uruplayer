package com.urufile.uruplayer.manager

import android.content.Context
import android.util.Log
import com.urufile.uruplayer.DeviceIdentity
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.xmds.XmdsClient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

enum class RegistrationStatus {
    READY,
    WAITING_APPROVAL,
    PLAYER_UPGRADE_REQUIRED,
    ERROR
}

data class RegistrationResult(
    val status: RegistrationStatus,
    val message: String = "",
    val screenshotRequested: Boolean = false,
    val logLevel: String = "error"
)

class RegistrationManager(private val context: Context) {

    private val tag = "RegistrationManager"
    private val prefs = PrefsManager(context)
    private val client = XmdsClient(context)
    private val identity = DeviceIdentity(context)

    suspend fun register(): RegistrationResult {
        return try {
            val responseXml = client.registerDisplay(
                displayName = prefs.displayName,
                macAddress = identity.macAddress,
                xmrChannel = identity.xmrChannel,
                xmrPubKey = identity.xmrPublicKey
            )
            Log.d(tag, "RegisterDisplay response: $responseXml")
            parseActivationMessage(responseXml)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            Log.e(tag, "Registration failed: ${e.message}", e)
            RegistrationResult(RegistrationStatus.ERROR, e.message ?: "Unknown error")
        }
    }

    /**
     * Parse the XML ActivationMessage returned by RegisterDisplay.
     * Expected format:
     * <display code="READY" message="..." version_instructions=""
     *          screenshot="0" log_level="error" />
     *
     * CMS may also return the display status attribute instead of code:
     * <display status="0" .../>  (0 = WAITING, 1+ = READY)
     */
    private fun parseActivationMessage(xml: String): RegistrationResult {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("display", ignoreCase = true)) {
                    val code = parser.getAttributeValue(null, "code") ?: ""
                    val statusAttr = parser.getAttributeValue(null, "status")
                    val message = parser.getAttributeValue(null, "message") ?: ""
                    val screenshot = parser.getAttributeValue(null, "screenshot") == "1"
                    val logLevel = parser.getAttributeValue(null, "log_level") ?: "error"

                    val status = when {
                        code.uppercase() == "READY" -> RegistrationStatus.READY
                        code.uppercase() in listOf("WAITING_APPROVAL", "PENDING") -> RegistrationStatus.WAITING_APPROVAL
                        code.uppercase() == "PLAYER_UPGRADE_REQUIRED" -> RegistrationStatus.PLAYER_UPGRADE_REQUIRED
                        // Fallback: some CMS versions use status="1" for READY
                        statusAttr != null -> {
                            val statusInt = statusAttr.toIntOrNull() ?: 0
                            if (statusInt > 0) RegistrationStatus.READY else RegistrationStatus.WAITING_APPROVAL
                        }
                        else -> RegistrationStatus.ERROR
                    }
                    return RegistrationResult(status, message, screenshot, logLevel)
                }
                event = parser.next()
            }
            RegistrationResult(RegistrationStatus.ERROR, "Could not parse activation message")
        } catch (e: Exception) {
            Log.e(tag, "Parse error: ${e.message}", e)
            RegistrationResult(RegistrationStatus.ERROR, e.message ?: "Parse error")
        }
    }
}
