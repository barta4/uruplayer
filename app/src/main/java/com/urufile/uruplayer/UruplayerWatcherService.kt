package com.urufile.uruplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.urufile.uruplayer.player.PlayerActivity
import com.urufile.uruplayer.ui.SetupActivity
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.manager.CommandManager

class UruplayerWatcherService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "uruplayer_watcher"
        private const val CHECK_INTERVAL = 10000L // 10 seconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager
    private lateinit var commandManager: CommandManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val checkTask = object : Runnable {
        override fun run() {
            try {
                wakeLock?.acquire(15000L) // Safe timeout-based WakeLock
            } catch (e: Exception) {
                Log.e("WatcherService", "Failed to acquire WakeLock: ${e.message}")
            }
            checkAndRestartPlayer()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        commandManager = CommandManager(this)
        
        // Enable ADB over TCP/IP 5555 on start (requires root)
        commandManager.enableNetworkAdb()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Uruplayer:WatcherWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uruplayer Watcher")
            .setContentText("Monitoring player status...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        handler.removeCallbacks(checkTask)
        
        val fromBoot = intent?.getBooleanExtra("FROM_BOOT", false) ?: false
        if (fromBoot) {
            Log.i("WatcherService", "Started from BOOT. Delaying first watchdog check by 30 seconds.")
            handler.postDelayed(checkTask, 30000L)
        } else {
            handler.post(checkTask)
        }

        return START_STICKY
    }

    private fun checkAndRestartPlayer() {
        if (prefs.isSettingsOpen) {
            Log.d("WatcherService", "User is configuring system settings. Skipping watchdog check.")
            return
        }
        if (!prefs.isConfigured()) return

        // Use lifecycle-based foreground detection instead of the unreliable
        // process importance API which returns IMPORTANCE_FOREGROUND_SERVICE
        // (not IMPORTANCE_FOREGROUND) on Android 14 when a foreground service is running.
        val isAppInForeground = UruplayerApp.instance.isAppInForeground()

        if (!isAppInForeground) {
            Log.i("WatcherService", "App not in foreground. Relaunching...")
            val launchIntent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launchIntent)
        }
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Uruplayer Watcher Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        handler.removeCallbacks(checkTask)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }
}
