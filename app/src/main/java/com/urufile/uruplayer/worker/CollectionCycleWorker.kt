package com.urufile.uruplayer.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.manager.CommandManager
import com.urufile.uruplayer.manager.FileManager
import com.urufile.uruplayer.manager.RegistrationManager
import com.urufile.uruplayer.manager.RegistrationStatus
import com.urufile.uruplayer.manager.StatusReporter
import com.urufile.uruplayer.player.PlayerActivity
import com.urufile.uruplayer.xmds.XmdsClient
import java.io.File

class CollectionCycleWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tag = "CollectionWorker"
    private val prefs = PrefsManager(context)

    override suspend fun doWork(): Result {
        Log.i(tag, "Starting Collection Cycle")

        if (!prefs.isConfigured()) {
            Log.w(tag, "Player not configured. Aborting cycle.")
            return Result.failure()
        }

        try {
            // 1. Register / Check Status
            val regManager = RegistrationManager(context)
            val regResult = regManager.register()

            if (regResult.status != RegistrationStatus.READY) {
                Log.w(tag, "Display is not READY. Status: ${regResult.status}. Message: ${regResult.message}")
                prefs.isAuthorized = false

                // Save the CMS error/status message so the player can show it on screen
                prefs.lastSyncError = when (regResult.status) {
                    RegistrationStatus.WAITING_APPROVAL ->
                        "⏳ Pantalla pendiente de autorización.\n\nIngresa al panel Xibo CMS y autoriza este dispositivo."
                    RegistrationStatus.PLAYER_UPGRADE_REQUIRED ->
                        "⚠️ El CMS requiere una versión más nueva del reproductor.\n\nContacta al administrador."
                    RegistrationStatus.ERROR ->
                        "❌ Error del CMS:\n${regResult.message}"
                    else ->
                        regResult.message
                }

                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(PlayerActivity.ACTION_SCHEDULE_UPDATED))
                return Result.success()
            }
            prefs.isAuthorized = true

            val client = XmdsClient(context)
            val fileManager = FileManager(context)
            val statusReporter = StatusReporter(context)
            val commandManager = CommandManager(context)

            // 2. RequiredFiles
            val requiredFilesXml = client.requiredFiles()
            val oldRequiredFiles = prefs.lastRequiredFilesXml
            prefs.lastRequiredFilesXml = requiredFilesXml
            val syncedFiles = fileManager.parseAndSyncRequiredFiles(requiredFilesXml)

            // 3. Schedule
            val scheduleXml = client.schedule()
            val oldSchedule = prefs.lastScheduleXml
            prefs.lastScheduleXml = scheduleXml

            // 4. Download missing files
            fileManager.downloadPendingFiles()

            // 5. Check for updates (APK files)
            syncedFiles.filter { it.path.endsWith(".apk", ignoreCase = true) }.forEach { apkMedia ->
                val apkFile = File(apkMedia.path)
                if (apkFile.exists()) {
                    try {
                        val pm = context.packageManager
                        val apkInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        val currentInfo = pm.getPackageInfo(context.packageName, 0)
                        
                        if (apkInfo != null && currentInfo != null) {
                            val apkVersion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) apkInfo.longVersionCode else apkInfo.versionCode.toLong()
                            val currentVersion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) currentInfo.longVersionCode else currentInfo.versionCode.toLong()
                            
                            if (apkVersion > currentVersion) {
                                Log.i(tag, "Found update APK: ${apkFile.name} (v$apkVersion > v$currentVersion). Triggering installation.")
                                commandManager.installApk(apkFile)
                            } else {
                                Log.d(tag, "APK ${apkFile.name} (v$apkVersion) is not newer than current (v$currentVersion). Skipping.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to check APK version: ${e.message}")
                    }
                }
            }

            // 6. MediaInventory
            val inventoryXml = fileManager.buildMediaInventoryXml()
            val inventorySuccess = client.mediaInventory(inventoryXml)
            Log.i(tag, "MediaInventory sent. Success=$inventorySuccess. XML: $inventoryXml")

            // 7. NotifyStatus
            statusReporter.notifyStatus()

            // 8. Notify PlayerActivity to refresh its layout ONLY IF something changed
            val oldError = prefs.lastSyncError
            prefs.lastSyncError = "" // Reset on success

            if (scheduleXml != oldSchedule || requiredFilesXml != oldRequiredFiles || oldError.isNotBlank()) {
                Log.i(tag, "Content changed or error cleared. Broadcasting layout update.")
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(PlayerActivity.ACTION_SCHEDULE_UPDATED))
            } else {
                Log.d(tag, "No content changes. Skipping layout reload.")
            }

            Log.i(tag, "Collection Cycle completed successfully.")
            return Result.success()

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            Log.e(tag, "Error in Collection Cycle: ${e.message}", e)
            prefs.lastSyncError = e.message ?: "Error de conexión desconocido"
            // Notify PlayerActivity immediately to show the error
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent(PlayerActivity.ACTION_SCHEDULE_UPDATED))
            
            return Result.retry()
        }
    }

}
