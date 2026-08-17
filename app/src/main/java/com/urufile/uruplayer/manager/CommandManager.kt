package com.urufile.uruplayer.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.DataOutputStream

class CommandManager(private val context: Context) {

    private val tag = "CommandManager"

    /**
     * Executes a shell command.
     * @param command The command to execute.
     * @param useRoot Whether to attempt execution with root privileges.
     */
    fun execute(command: String, useRoot: Boolean = false): Boolean {
        return try {
            Log.i(tag, "Executing command: $command (root=$useRoot)")
            val process = if (useRoot) {
                Runtime.getRuntime().exec("su")
            } else {
                Runtime.getRuntime().exec("sh")
            }

            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }

            val result = process.waitFor()
            Log.d(tag, "Command result code: $result")
            result == 0
        } catch (e: Exception) {
            Log.e(tag, "Failed to execute command: ${e.message}")
            false
        }
    }

    /**
     * Reboots the device (requires root).
     */
    fun reboot() {
        if (!execute("reboot", true)) {
            Log.w(tag, "Reboot failed. Attempting alternative reboot...")
            execute("am broadcast -a android.intent.action.REBOOT", true)
        }
    }

    /**
     * Enables ADB over TCP/IP on port 5555 (requires root).
     */
    fun enableNetworkAdb() {
        Log.i(tag, "Attempting to enable ADB over TCP/IP...")
        execute("setprop service.adb.tcp.port 5555", true)
        execute("stop adbd", true)
        execute("start adbd", true)
    }

    /**
     * Installs an APK file.
     * If root is available, performs a silent installation.
     * Otherwise, triggers the standard Android package installer.
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return

        Log.i(tag, "Installing APK: ${apkFile.absolutePath}")

        // 1. Try silent install if root
        val silentSuccess = execute("pm install -r \"${apkFile.absolutePath}\"", true)
        
        if (silentSuccess) {
            Log.i(tag, "Silent installation successful. Rebooting app...")
            rebootApp()
            return
        }

        // 2. Fallback to standard installer
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch standard installer: ${e.message}")
        }
    }

    private fun rebootApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        System.exit(0)
    }
}
