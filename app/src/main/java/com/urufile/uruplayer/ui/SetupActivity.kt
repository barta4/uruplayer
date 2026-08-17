package com.urufile.uruplayer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.player.PlayerActivity
import com.urufile.uruplayer.util.PermissionHelper
import com.urufile.uruplayer.worker.CollectionCycleWorker
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private val tag = "SetupActivity"
    private lateinit var prefs: PrefsManager

    private val RC_RUNTIME_PERMISSIONS = 101
    private val RC_OVERLAY = 102
    private val RC_BATTERY = 103
    private val RC_ROLE_HOME = 104

    private var askedOverlay = false
    private var askedBattery = false
    private var askedLauncher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        startPermissionFlow()
    }

    private fun startPermissionFlow() {
        // 1. Permisos normales de ejecución (Storage / Media / Notifications según versión Android)
        val missing = PermissionHelper.getMissingRuntimePermissions(this)
        if (missing.isNotEmpty()) {
            Log.i(tag, "Solicitando permisos de ejecución: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), RC_RUNTIME_PERMISSIONS)
            return
        }

        // 2. Permiso de Superposición (Overlay / SYSTEM_ALERT_WINDOW)
        if (!PermissionHelper.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true
            showOverlayPermissionDialog()
            return
        }

        // 3. Desactivar optimización de batería (24/7 Digital Signage)
        if (!PermissionHelper.isIgnoringBatteryOptimizations(this) && !askedBattery) {
            askedBattery = true
            showBatteryOptimizationDialog()
            return
        }

        // 4. Solicitar ser Launcher Principal si no lo es
        if (!PermissionHelper.isDefaultLauncher(this) && !askedLauncher) {
            askedLauncher = true
            showDefaultLauncherDialog()
            return
        }

        // Todos los pasos completados
        proceedToPlayer()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("📱 Permiso de Superposición")
            .setMessage("Para que UruPlayer pueda autorecuperarse y volver a primer plano si otra app se abre o ocurre un error, se requiere el permiso de mostrar sobre otras apps.")
            .setCancelable(false)
            .setPositiveButton("Conceder") { _, _ ->
                PermissionHelper.requestOverlayPermission(this, RC_OVERLAY)
            }
            .setNegativeButton("Omitir") { _, _ ->
                startPermissionFlow()
            }
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚡ Modo Señalización 24/7")
            .setMessage("Para evitar que Android cierre el reproductor en segundo plano durante la noche o ciclos de sincronización, desactiva la optimización de batería.")
            .setCancelable(false)
            .setPositiveButton("Configurar") { _, _ ->
                PermissionHelper.requestIgnoreBatteryOptimizations(this, RC_BATTERY)
            }
            .setNegativeButton("Omitir") { _, _ ->
                startPermissionFlow()
            }
            .show()
    }

    private fun showDefaultLauncherDialog() {
        AlertDialog.Builder(this)
            .setTitle("🏠 Configurar como Launcher Principal")
            .setMessage("¿Deseas que UruPlayer sea la pantalla de inicio predeterminada de este dispositivo?\n\nAl configurarlo como Launcher, el reproductor iniciará automáticamente al encender el equipo o presionar el botón Inicio/Home.")
            .setCancelable(false)
            .setPositiveButton("Establecer como Launcher") { _, _ ->
                PermissionHelper.requestDefaultLauncher(this, RC_ROLE_HOME)
            }
            .setNegativeButton("Continuar") { _, _ ->
                proceedToPlayer()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_RUNTIME_PERMISSIONS) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, "Algunos permisos fueron denegados. La app continuará.", Toast.LENGTH_SHORT).show()
            }
            startPermissionFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_OVERLAY, RC_BATTERY, RC_ROLE_HOME -> {
                startPermissionFlow()
            }
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
