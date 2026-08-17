package com.urufile.uruplayer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.urufile.uruplayer.R
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.player.PlayerActivity
import com.urufile.uruplayer.worker.CollectionCycleWorker
import java.util.concurrent.TimeUnit
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private val PERMISSION_REQUEST_CODE = 101
    private var isAskingOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            if (!isAskingOverlay) {
                isAskingOverlay = true
                Toast.makeText(this, "Permiso Requerido: Mostrar sobre otras apps", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 102)
                return
            }
        }

        proceedToPlayer()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAndRequestPermissions()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            checkAndRequestPermissions()
        }
    }

    private fun proceedToPlayer() {
        // Auto-configuración (Zero-Touch) la primera vez
        if (!prefs.isConfigured()) {
            prefs.cmsUrl = "http://urufile.online"
            prefs.serverKey = "mNlUBpO4"
            prefs.displayName = "Uruplayer-" + java.util.UUID.randomUUID().toString().substring(0, 5).uppercase()
            prefs.collectionInterval = 60
            
            startCollectionWorker(60)
        }

        startPlayerAndFinish()
    }

    override fun onResume() {
        super.onResume()
        prefs.isSettingsOpen = false
    }

    private fun startCollectionWorker(intervalSeconds: Int) {
        val intervalMin = (intervalSeconds / 60L).coerceAtLeast(15L)
        val periodicRequest = PeriodicWorkRequestBuilder<CollectionCycleWorker>(
            intervalMin, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UruplayerCollectionCycle",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    private fun startPlayerAndFinish() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }
}
