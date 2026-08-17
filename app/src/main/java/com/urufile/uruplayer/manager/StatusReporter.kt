package com.urufile.uruplayer.manager

import android.content.Context
import android.util.Log
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.xmds.XmdsClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusReporter(private val context: Context) {

    private val tag = "StatusReporter"
    private val client = XmdsClient(context)
    private val prefs = PrefsManager(context)

    // ── NotifyStatus ─────────────────────────────────────────────────────────

    suspend fun notifyStatus() {
        try {
            val statusJson = buildStatusJson()
            val success = client.notifyStatus(statusJson)
            Log.i(tag, "NotifyStatus sent. Success=$success")
        } catch (e: Exception) {
            Log.e(tag, "NotifyStatus failed: ${e.message}", e)
        }
    }

    private fun buildStatusJson(): String {
        val availableSpace = context.filesDir.freeSpace
        val totalSpace = context.filesDir.totalSpace
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        // Xibo CMS checks these exact fields to mark display as Connected/Online
        return JSONObject().apply {
            put("currentLayoutId", prefs.currentLayoutId.takeIf { it > 0 } ?: 1)
            put("availableSpace", availableSpace)
            put("totalSpace", totalSpace)
            put("lastActivity", now)
            put("lastStatusHelper", now)
            put("deviceName", prefs.displayName)
            put("latitude", 0.0)
            put("longitude", 0.0)
        }.toString()
    }

    // ── SubmitStats ──────────────────────────────────────────────────────────

    suspend fun submitStats(entries: List<StatEntry>) {
        if (entries.isEmpty()) return
        try {
            val xml = buildStatXml(entries)
            val success = client.submitStats(xml)
            Log.i(tag, "SubmitStats sent ${entries.size} entries. Success=$success")
        } catch (e: Exception) {
            Log.e(tag, "SubmitStats failed: ${e.message}", e)
        }
    }

    private fun buildStatXml(entries: List<StatEntry>): String {
        val sb = StringBuilder("<stats>")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        entries.forEach { e ->
            sb.append(
                "<stat type=\"${e.type}\" fromdt=\"${fmt.format(e.fromDt)}\" " +
                        "todt=\"${fmt.format(e.toDt)}\" layoutid=\"${e.layoutId}\" " +
                        "mediaid=\"${e.mediaId}\" scheduleid=\"${e.scheduleId}\"/>"
            )
        }
        sb.append("</stats>")
        return sb.toString()
    }

    // ── SubmitScreenShot ─────────────────────────────────────────────────────

    suspend fun submitScreenshot(screenshotBase64: String) {
        try {
            val success = client.submitScreenShot(screenshotBase64)
            Log.i(tag, "SubmitScreenShot. Success=$success")
        } catch (e: Exception) {
            Log.e(tag, "SubmitScreenShot failed: ${e.message}", e)
        }
    }

    // ── SubmitLog ────────────────────────────────────────────────────────────

    suspend fun submitLog(message: String, level: String = "error") {
        try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val xml = "<log><entry date=\"$now\" category=\"$level\" message=\"${escapeXml(message)}\"/></log>"
            client.submitLog(xml)
        } catch (e: Exception) {
            Log.e(tag, "SubmitLog failed: ${e.message}", e)
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

data class StatEntry(
    val type: String = "media",
    val layoutId: Int,
    val mediaId: String,
    val scheduleId: String,
    val fromDt: Date,
    val toDt: Date
)
