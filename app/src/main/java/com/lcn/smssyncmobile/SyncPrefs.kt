package com.lcn.smssyncmobile

import android.content.Context

object SyncPrefs {
    private const val PREF_NAME = "oneui_sms_sync_prefs"
    private const val KEY_AUTO_SYNC = "is_auto_sync_enabled"
    private const val KEY_SMS_LIMIT = "sms_limit_count"

    fun isAutoSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun getSmsLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SMS_LIMIT, 50) // Mặc định 50 tin
    }

    fun setSmsLimit(context: Context, limit: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SMS_LIMIT, limit).apply()
    }
}
