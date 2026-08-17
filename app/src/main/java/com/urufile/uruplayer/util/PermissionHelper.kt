package com.urufile.uruplayer.util

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

object PermissionHelper {

    private const val TAG = "PermissionHelper"

    /**
     * Devuelve la lista de permisos estándar en tiempo de ejecución (Runtime Permissions)
     * que aún no han sido concedidos, adaptado según la versión de Android del dispositivo.
     */
    fun getMissingRuntimePermissions(context: Context): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+ (API 33+)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6.0 - 12 (API 23 - 32)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) { // Android 6.0 - 10 (API 23 - 29)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Verifica si la app tiene permiso de superposición (Overlay / SYSTEM_ALERT_WINDOW).
     * Requerido para el WatcherService para restaurar el reproductor si cae a segundo plano.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Solicita al usuario el permiso de superposición.
     */
    fun requestOverlayPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.e(TAG, "Error abriendo ajustes de overlay: ${e.message}")
                try {
                    activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), requestCode)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error secundario abriendo ajustes de overlay: ${e2.message}")
                }
            }
        }
    }

    /**
     * Verifica si la app está exenta del ahorro de batería (Doze mode) para Digital Signage 24/7.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    /**
     * Solicita la exención de optimización de batería.
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.e(TAG, "Error solicitando ignore battery optimizations: ${e.message}")
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e(TAG, "Error abriendo ajustes de batería: ${e2.message}")
                }
            }
        }
    }

    /**
     * Verifica si UruPlayer está configurado como el Launcher (pantalla de inicio) predeterminado.
     */
    fun isDefaultLauncher(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val currentPackage = resolveInfo?.activityInfo?.packageName
        return currentPackage == context.packageName
    }

    /**
     * Solicita al usuario seleccionar UruPlayer como Launcher principal / Pantalla de Inicio.
     * En Android 10+ (API 29+) utiliza RoleManager. En versiones anteriores abre el selector de Home.
     */
    fun requestDefaultLauncher(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = activity.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                        activity.startActivityForResult(roleIntent, requestCode)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RoleManager error al solicitar ROLE_HOME: ${e.message}")
            }
        }

        // Fallback para Android < 10 o dispositivos sin soporte de RoleManager
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(Intent.createChooser(homeIntent, "Selecciona UruPlayer como pantalla de inicio predeterminada"))
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo selector de launcher: ${e.message}")
            try {
                activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e2: Exception) {
                Log.e(TAG, "Error abriendo ACTION_HOME_SETTINGS: ${e2.message}")
            }
        }
    }

    /**
     * Verifica permiso de instalación de paquetes en Android 8.0+ (Oreo, API 26+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Solicita permiso para instalar actualizaciones de APKs.
     */
    fun requestInstallPackagesPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.e(TAG, "Error abriendo unknown app sources: ${e.message}")
            }
        }
    }
}
