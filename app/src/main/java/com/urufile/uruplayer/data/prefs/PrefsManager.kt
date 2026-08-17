package com.urufile.uruplayer.data.prefs

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── CMS Config ──────────────────────────────────────────────────────────
    var cmsUrl: String
        get() = prefs.getString(KEY_CMS_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CMS_URL, value).apply()

    var serverKey: String
        get() = prefs.getString(KEY_SERVER_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_KEY, value).apply()

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "Uruplayer") ?: "Uruplayer"
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var collectionInterval: Int
        get() = prefs.getInt(KEY_COLLECTION_INTERVAL, 60)
        set(value) = prefs.edit().putInt(KEY_COLLECTION_INTERVAL, value).apply()

    // ── Device Identity ──────────────────────────────────────────────────────
    var hardwareKey: String
        get() = prefs.getString(KEY_HARDWARE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HARDWARE_KEY, value).apply()

    var xmrPublicKey: String
        get() = prefs.getString(KEY_XMR_PUB_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XMR_PUB_KEY, value).apply()

    var xmrPrivateKey: String
        get() = prefs.getString(KEY_XMR_PRIV_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XMR_PRIV_KEY, value).apply()

    var xmrChannel: String
        get() = prefs.getString(KEY_XMR_CHANNEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XMR_CHANNEL, value).apply()

    // ── Player State ─────────────────────────────────────────────────────────
    var currentLayoutId: Int
        get() = prefs.getInt(KEY_CURRENT_LAYOUT_ID, -1)
        set(value) = prefs.edit().putInt(KEY_CURRENT_LAYOUT_ID, value).apply()

    var lastScheduleXml: String
        get() = prefs.getString(KEY_LAST_SCHEDULE_XML, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SCHEDULE_XML, value).apply()

    var lastRequiredFilesXml: String
        get() = prefs.getString(KEY_LAST_REQUIRED_FILES_XML, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_REQUIRED_FILES_XML, value).apply()

    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTHORIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_AUTHORIZED, value).apply()

    var lastSyncError: String
        get() = prefs.getString(KEY_LAST_SYNC_ERROR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_ERROR, value).apply()

    var isSettingsOpen: Boolean
        get() = prefs.getBoolean(KEY_IS_SETTINGS_OPEN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_SETTINGS_OPEN, value).apply()

    var overscanPadding: Int
        get() = prefs.getInt(KEY_OVERSCAN_PADDING, 0)
        set(value) = prefs.edit().putInt(KEY_OVERSCAN_PADDING, value).apply()

    // ── Validation ───────────────────────────────────────────────────────────
    fun isConfigured(): Boolean = cmsUrl.isNotBlank() && serverKey.isNotBlank()

    fun clearDeviceIdentity() {
        prefs.edit()
            .remove(KEY_HARDWARE_KEY)
            .remove(KEY_XMR_PUB_KEY)
            .remove(KEY_XMR_PRIV_KEY)
            .remove(KEY_XMR_CHANNEL)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "uruplayer_prefs"
        private const val KEY_CMS_URL = "cms_url"
        private const val KEY_SERVER_KEY = "server_key"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_COLLECTION_INTERVAL = "collection_interval"
        private const val KEY_HARDWARE_KEY = "hardware_key"
        private const val KEY_XMR_PUB_KEY = "xmr_pub_key"
        private const val KEY_XMR_PRIV_KEY = "xmr_priv_key"
        private const val KEY_XMR_CHANNEL = "xmr_channel"
        private const val KEY_CURRENT_LAYOUT_ID = "current_layout_id"
        private const val KEY_LAST_SCHEDULE_XML = "last_schedule_xml"
        private const val KEY_LAST_REQUIRED_FILES_XML = "last_required_files_xml"
        private const val KEY_IS_AUTHORIZED = "is_authorized"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        private const val KEY_IS_SETTINGS_OPEN = "is_settings_open"
        private const val KEY_OVERSCAN_PADDING = "overscan_padding"
    }
}
