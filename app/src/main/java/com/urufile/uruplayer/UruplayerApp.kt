package com.urufile.uruplayer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.work.Configuration
import com.urufile.uruplayer.data.db.AppDatabase
import com.urufile.uruplayer.data.prefs.PrefsManager
import java.util.concurrent.atomic.AtomicInteger

class UruplayerApp : Application(), Configuration.Provider {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    // Counts activities that are between onStart and onStop (i.e., visible to the user).
    // An AtomicInteger is used for thread safety since the watchdog runs on a Handler thread.
    private val startedActivityCount = AtomicInteger(0)

    /**
     * Returns true if at least one Activity is currently started (visible).
     * This is reliable on Android 14+ where process importance can be misleading
     * due to foreground services raising the importance to IMPORTANCE_FOREGROUND_SERVICE.
     */
    fun isAppInForeground(): Boolean = startedActivityCount.get() > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread.setDefaultUncaughtExceptionHandler(UruplayerCrashHandler(this))
        
        // Reset settings open flag on startup to prevent watchdog block
        PrefsManager(this).isSettingsOpen = false

        // Register lifecycle callbacks to track foreground state accurately
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount.incrementAndGet()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount.decrementAndGet()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        
        // Start foreground watcher service
        val serviceIntent = Intent(this, UruplayerWatcherService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        lateinit var instance: UruplayerApp
            private set
    }
}
