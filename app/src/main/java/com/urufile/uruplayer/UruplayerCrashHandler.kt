package com.urufile.uruplayer

import android.content.Context
import android.content.Intent
import android.util.Log
import com.urufile.uruplayer.ui.SetupActivity
import kotlin.system.exitProcess

class UruplayerCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Log.e("UruplayerCrash", "CRASH DETECTED: ${ex.message}", ex)

        // Create a pending intent to restart the app
        val intent = Intent(context, SetupActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        // Kill the process
        exitProcess(1)
    }
}
