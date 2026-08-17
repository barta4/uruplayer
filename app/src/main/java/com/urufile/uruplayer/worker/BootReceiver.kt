package com.urufile.uruplayer.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.urufile.uruplayer.UruplayerWatcherService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device booted. Launching UruplayerWatcherService.")
            
            val serviceIntent = Intent(context, UruplayerWatcherService::class.java).apply {
                putExtra("FROM_BOOT", true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start UruplayerWatcherService: ${e.message}", e)
            }
        }
    }
}
