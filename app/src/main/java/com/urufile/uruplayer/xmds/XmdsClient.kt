package com.urufile.uruplayer.xmds

import android.content.Context
import android.util.Base64
import android.util.Log
import com.urufile.uruplayer.data.prefs.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ksoap2.SoapEnvelope
import org.ksoap2.serialization.SoapObject
import org.ksoap2.serialization.SoapSerializationEnvelope
import org.ksoap2.transport.HttpTransportSE

class XmdsClient(private val context: Context) {

    private val prefs = PrefsManager(context)
    private val tag = "XmdsClient"

    companion object {
        private const val NAMESPACE = "urn:xmds"
        private const val XMDS_VERSION = "5"
        private const val CHUNK_SIZE = 524288          // 512 KB
        private const val CONNECT_TIMEOUT = 30_000     // 30 s
        private const val READ_TIMEOUT = 60_000        // 60 s
        private const val MAX_RETRIES = 3
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds the XMDS endpoint URL.
     * IMPORTANT: the ?method= param is REQUIRED to avoid CMS rate-limiting.
     */
    private fun xmdsUrl(method: String): String {
        val base = prefs.cmsUrl.trimEnd('/')
        // If the URL already contains /xmds.php, use it directly
        return if (base.contains("/xmds.php", ignoreCase = true)) {
            "$base?v=$XMDS_VERSION&method=$method"
        } else {
            "$base/xmds.php?v=$XMDS_VERSION&method=$method"
        }
    }

    private suspend fun call(
        method: String,
        params: Map<String, Any>,
        includeHardwareKey: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val envelope = SoapSerializationEnvelope(SoapEnvelope.VER11).apply {
                    encodingStyle = "http://schemas.xmlsoap.org/soap/encoding/"
                    dotNet = false
                    implicitTypes = false
                }

                val request = SoapObject(NAMESPACE, method).apply {
                    // serverKey is ALWAYS first per the XMDS spec
                    addProperty("serverKey", prefs.serverKey)
                    // hardwareKey is second for all calls except when already included in params
                    if (includeHardwareKey) {
                        addProperty("hardwareKey", prefs.hardwareKey)
                    }
                    params.forEach { (key, value) -> addProperty(key, value) }
                }
                envelope.setOutputSoapObject(request)

                val url = xmdsUrl(method)
                Log.d(tag, "XMDS [$method] -> $url")

                val transport = HttpTransportSE(url, CONNECT_TIMEOUT)
                transport.debug = true   // Enable to capture raw SOAP for debugging

                // SOAPAction header: XMDS expects "urn:xmds/MethodName"
                transport.call("$NAMESPACE/$method", envelope)

                // Log the raw response for debugging
                Log.d(tag, "XMDS [$method] responseDump: ${transport.responseDump}")

                val result = envelope.response
                return@withContext when (result) {
                    is ByteArray -> Base64.encodeToString(result, Base64.NO_WRAP)
                    else -> result?.toString() ?: ""
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                lastException = e
                Log.w(tag, "XMDS [$method] attempt ${attempt + 1}/${MAX_RETRIES} failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) kotlinx.coroutines.delay(2000L * (attempt + 1))
            }
        }
        throw lastException ?: Exception("Unknown XMDS error for $method")
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Public XMDS methods
    // ────────────────────────────────────────────────────────────────────────

    /**
     * RegisterDisplay – XMDS v5 field order (must match WSDL exactly):
     * serverKey, hardwareKey, displayName, clientType, clientVersion,
     * clientCode, operatingSystem, macAddress, xmrChannel, xmrPubKey
     *
     * The hardwareKey is sent via the base call() helper (second param, always).
     * The operatingSystem field carries the exact Android version string.
     */
    suspend fun registerDisplay(
        displayName: String,
        clientType: String = "android",
        clientVersion: String = "4",
        clientCode: Int = 400,
        operatingSystem: String = "Android ${android.os.Build.VERSION.RELEASE}",
        macAddress: String,
        xmrChannel: String,
        xmrPubKey: String
    ): String = call(
        "RegisterDisplay",
        mapOf(
            "displayName"   to displayName,
            "clientType"    to clientType,
            "clientVersion" to clientVersion,
            "clientCode"    to clientCode,
            "operatingSystem" to operatingSystem,
            "macAddress"    to macAddress,
            "xmrChannel"   to xmrChannel,
            "xmrPubKey"    to xmrPubKey
        )
    )

    suspend fun requiredFiles(): String = call("RequiredFiles", emptyMap())

    suspend fun schedule(): String = call("Schedule", emptyMap())

    suspend fun getFile(
        fileId: Int,
        fileType: String,
        chunkOffset: Long,
        chunkSize: Long = CHUNK_SIZE.toLong()
    ): String = call(
        "GetFile", mapOf(
            "fileId" to fileId,
            "fileType" to fileType,
            "chunkOffset" to chunkOffset,
            "chunkSize" to chunkSize
        )
    )

    suspend fun mediaInventory(inventoryXml: String): Boolean =
        call("MediaInventory", mapOf("mediaInventory" to inventoryXml)).equals("true", ignoreCase = true)

    suspend fun notifyStatus(statusJson: String): Boolean =
        call("NotifyStatus", mapOf("status" to statusJson)).equals("true", ignoreCase = true)

    suspend fun submitLog(logXml: String): Boolean =
        call("SubmitLog", mapOf("logXml" to logXml)).equals("true", ignoreCase = true)

    suspend fun submitStats(statXml: String): Boolean =
        call("SubmitStats", mapOf("statXml" to statXml)).equals("true", ignoreCase = true)

    suspend fun submitScreenShot(screenshotBase64: String): Boolean =
        call("SubmitScreenShot", mapOf("screenShot" to screenshotBase64)).equals("true", ignoreCase = true)

    suspend fun getResource(layoutId: Int, regionId: String, mediaId: String): String =
        call("GetResource", mapOf("layoutId" to layoutId, "regionId" to regionId, "mediaId" to mediaId))

    suspend fun blackList(mediaId: String, type: String, reason: String): Boolean =
        call("BlackList", mapOf("mediaId" to mediaId, "type" to type, "reason" to reason))
            .equals("true", ignoreCase = true)
}
