package com.urufile.uruplayer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.player.PlayerActivity
import com.urufile.uruplayer.worker.CollectionCycleWorker
import java.util.concurrent.TimeUnit
import com.urufile.uruplayer.UruplayerWatcherService

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            setBackgroundColor(0xFF1A1A2E.toInt())
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 48, 64, 48)
        }
        scrollView.addView(layout)

        // ── Title ──
        val title = TextView(this).apply {
            text = "Uruplayer Settings"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        // ── CMS URL ──
        val labelCms = makeLabel("CMS URL")
        val etCmsUrl = makeEditText(prefs.cmsUrl, android.text.InputType.TYPE_TEXT_VARIATION_URI)
        layout.addView(labelCms)
        layout.addView(etCmsUrl)

        // ── Server Key ──
        val labelKey = makeLabel("Server Key")
        val etServerKey = makeEditText(prefs.serverKey, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        layout.addView(labelKey)
        layout.addView(etServerKey)

        // ── Display Name ──
        val labelName = makeLabel("Display Name")
        val etDisplayName = makeEditText(prefs.displayName, android.text.InputType.TYPE_CLASS_TEXT)
        layout.addView(labelName)
        layout.addView(etDisplayName)

        // ── Collection Interval ──
        val labelInterval = makeLabel("Collection Interval (seconds)")
        val etInterval = makeEditText(prefs.collectionInterval.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(labelInterval)
        layout.addView(etInterval)

        // ── Overscan Padding ──
        val labelOverscan = makeLabel("Overscan Padding (%) - Ajuste bordes TV")
        val etOverscan = makeEditText(prefs.overscanPadding.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(labelOverscan)
        layout.addView(etOverscan)

        // ── Save Button ──
        val btnSave = Button(this).apply {
            text = "Save & Restart Worker"
            setOnClickListener {
                // Normalize backslashes to forward slashes
                var cmsUrl = etCmsUrl.text.toString().trim().replace('\\', '/')

                // Correct malformed http:/ or https:/ (e.g. http:/192.168.1.5 -> http://192.168.1.5)
                if (cmsUrl.startsWith("http:/") && !cmsUrl.startsWith("http://")) {
                    cmsUrl = "http://" + cmsUrl.substring(6)
                } else if (cmsUrl.startsWith("https:/") && !cmsUrl.startsWith("https://")) {
                    cmsUrl = "https://" + cmsUrl.substring(7)
                }

                val serverKey = etServerKey.text.toString().trim()
                if (cmsUrl.isBlank() || serverKey.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "CMS URL and Server Key are required.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!cmsUrl.startsWith("http://") && !cmsUrl.startsWith("https://")) {
                    cmsUrl = "http://$cmsUrl"
                }
                prefs.cmsUrl = cmsUrl
                prefs.serverKey = serverKey
                prefs.displayName = etDisplayName.text.toString().trim().ifBlank { "Uruplayer" }
                val interval = etInterval.text.toString().toIntOrNull() ?: 60
                prefs.collectionInterval = interval

                val overscan = etOverscan.text.toString().toIntOrNull() ?: 0
                prefs.overscanPadding = overscan.coerceIn(0, 15)

                restartWorker(interval)
                Toast.makeText(this@SettingsActivity, "Settings saved.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        setupButtonFocus(btnSave, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnSave)

        // ── Forget Device Button ──
        val btnForget = Button(this).apply {
            text = "Forget Device & Reset"
            setTextColor(0xFFFF5555.toInt())
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Confirm Reset")
                    .setMessage("This will erase the device identity and all configuration. Continue?")
                    .setPositiveButton("Reset") { _, _ ->
                        prefs.clearAll()
                        WorkManager.getInstance(this@SettingsActivity).cancelUniqueWork("UruplayerCollectionCycle")
                        val intent = Intent(this@SettingsActivity, SetupActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finishAffinity()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        setupButtonFocus(btnForget, 0xFF4A1E1E.toInt(), 0xFFC0392B.toInt())
        layout.addView(btnForget)

        // ── Launcher Principal Button ──
        val isLauncher = com.urufile.uruplayer.util.PermissionHelper.isDefaultLauncher(this)
        val btnLauncher = Button(this).apply {
            text = if (isLauncher) "✅ UruPlayer es el Launcher Principal" else "🏠 Configurar como Launcher Principal"
            setOnClickListener {
                prefs.isSettingsOpen = true
                com.urufile.uruplayer.util.PermissionHelper.requestDefaultLauncher(this@SettingsActivity, 201)
            }
        }
        setupButtonFocus(btnLauncher, if (isLauncher) 0xFF2E5B3C.toInt() else 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnLauncher)

        // ── Permissions & Battery Optimization Button ──
        val btnPerms = Button(this).apply {
            text = "🛡️ Permisos y Optimización de Batería"
            setOnClickListener {
                val missing = com.urufile.uruplayer.util.PermissionHelper.getMissingRuntimePermissions(this@SettingsActivity)
                val hasOverlay = com.urufile.uruplayer.util.PermissionHelper.canDrawOverlays(this@SettingsActivity)
                val hasBattery = com.urufile.uruplayer.util.PermissionHelper.isIgnoringBatteryOptimizations(this@SettingsActivity)
                val currentLauncher = com.urufile.uruplayer.util.PermissionHelper.isDefaultLauncher(this@SettingsActivity)

                val msg = StringBuilder()
                msg.append("• Permisos de almacenamiento: ${if (missing.isEmpty()) "✅ Concedidos" else "❌ Pendientes"}\n")
                msg.append("• Superposición (Overlay): ${if (hasOverlay) "✅ Concedido" else "❌ Pendiente"}\n")
                msg.append("• Sin ahorro de batería: ${if (hasBattery) "✅ Optimizado" else "❌ Pendiente"}\n")
                msg.append("• Launcher principal: ${if (currentLauncher) "✅ Sí" else "❌ No"}\n\n")
                msg.append("Selecciona una acción:")

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Estado de Permisos")
                    .setMessage(msg.toString())
                    .setPositiveButton("Batería") { _, _ ->
                        prefs.isSettingsOpen = true
                        com.urufile.uruplayer.util.PermissionHelper.requestIgnoreBatteryOptimizations(this@SettingsActivity, 202)
                    }
                    .setNeutralButton("Overlay") { _, _ ->
                        prefs.isSettingsOpen = true
                        com.urufile.uruplayer.util.PermissionHelper.requestOverlayPermission(this@SettingsActivity, 203)
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
        setupButtonFocus(btnPerms, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnPerms)

        // ── Exit Button ──
        val btnExit = Button(this).apply {
            text = "Stop Watcher & Exit App"
            setOnClickListener {
                stopService(Intent(this@SettingsActivity, UruplayerWatcherService::class.java))
                finishAffinity()
                System.exit(0)
            }
        }
        setupButtonFocus(btnExit, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnExit)

        // ── Wi-Fi Settings Button ──
        val btnWifi = Button(this).apply {
            text = "Configurar Wi-Fi (Android)"
            setOnClickListener {
                try {
                    prefs.isSettingsOpen = true
                    startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                } catch (e: Exception) {
                    prefs.isSettingsOpen = false
                    Toast.makeText(this@SettingsActivity, "Error al abrir Wi-Fi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setupButtonFocus(btnWifi, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnWifi)

        // ── Bluetooth Settings Button ──
        val btnBluetooth = Button(this).apply {
            text = "Configurar Bluetooth (Android)"
            setOnClickListener {
                try {
                    prefs.isSettingsOpen = true
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: Exception) {
                    prefs.isSettingsOpen = false
                    Toast.makeText(this@SettingsActivity, "Error al abrir Bluetooth: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setupButtonFocus(btnBluetooth, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnBluetooth)

        // ── General Android Settings Button ──
        val btnAndroidSettings = Button(this).apply {
            text = "Ajustes del Sistema (Android)"
            setOnClickListener {
                try {
                    prefs.isSettingsOpen = true
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (e: Exception) {
                    prefs.isSettingsOpen = false
                    Toast.makeText(this@SettingsActivity, "Error al abrir Ajustes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setupButtonFocus(btnAndroidSettings, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnAndroidSettings)

        // ── Close Button ──
        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        setupButtonFocus(btnClose, 0xFF3C3D7A.toInt(), 0xFF5253A3.toInt())
        layout.addView(btnClose)

        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        // Reset settings watchdog bypass when returning to Settings
        prefs.isSettingsOpen = false
    }

    private fun makeLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xAAFFFFFF.toInt())
        setPadding(0, 16, 0, 4)
    }

    private fun makeEditText(value: String, inputType: Int): EditText = EditText(this).apply {
        setText(value)
        this.inputType = inputType
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0x55FFFFFF)
        setBackgroundColor(0xFF23233E.toInt()) // Clean dark background
        
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = (16 * dp).toInt()
        layoutParams = params

        setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(0xFF4A4B96.toInt()) // Focused highly visible indigo color
                v.scaleX = 1.02f
                v.scaleY = 1.02f
            } else {
                v.setBackgroundColor(0xFF23233E.toInt())
                v.scaleX = 1.0f
                v.scaleY = 1.0f
            }
        }
    }

    private fun restartWorker(intervalSeconds: Int) {
        val intervalMin = (intervalSeconds / 60L).coerceAtLeast(15L)
        val periodicRequest = PeriodicWorkRequestBuilder<CollectionCycleWorker>(
            intervalMin, TimeUnit.MINUTES
        ).build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UruplayerCollectionCycle",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    private fun setupButtonFocus(btn: Button, defaultBg: Int, focusedBg: Int) {
        btn.setBackgroundColor(defaultBg)
        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(focusedBg)
                v.scaleX = 1.04f
                v.scaleY = 1.04f
            } else {
                v.setBackgroundColor(defaultBg)
                v.scaleX = 1.0f
                v.scaleY = 1.0f
            }
        }
    }
}
