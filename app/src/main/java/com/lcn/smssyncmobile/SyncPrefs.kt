package com.lcn.smssyncmobile

import android.content.Context

object SyncPrefs {
    private const val PREF_NAME = "oneui_sms_sync_prefs"
    private const val KEY_AUTO_SYNC = "is_auto_sync_enabled"
    private const val KEY_SMS_LIMIT = "sms_limit_count"
    private const val KEY_INSTALL_TIME = "first_install_time"
    private const val KEY_IS_PRO = "is_pro_purchased"

    fun isAutoSync(context: Context): Boolean {
        // Nếu hết hạn dùng thử mà chưa mua Pro thì cưỡng chế tắt tự động đồng bộ
        if (isTrialExpired(context) && !isPro(context)) return false
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun getSmsLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SMS_LIMIT, 50)
    }

    fun setSmsLimit(context: Context, limit: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SMS_LIMIT, limit).apply()
    }

    // Quản lý thời gian dùng thử 90 ngày
    fun getInstallTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var installTime = prefs.getLong(KEY_INSTALL_TIME, 0L)
        if (installTime == 0L) {
            installTime = System.currentTimeMillis()
            prefs.edit().putLong(KEY_INSTALL_TIME, installTime).apply()
        }
        return installTime
    }

    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PRO, false)
    }

    fun setPro(context: Context, purchased: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_PRO, purchased).apply()
    }

    fun getRemainingDays(context: Context): Int {
        if (isPro(context)) return 999 // Không giới hạn nếu đã mua
        val installTime = getInstallTime(context)
        val TRIAL_DURATION_DAYS = 90L
        val elapsedMillis = System.currentTimeMillis() - installTime
        val elapsedDays = elapsedMillis / (1000 * 60 * 60 * 24)
        val remaining = TRIAL_DURATION_DAYS - elapsedDays
        return if (remaining > 0) remaining.toInt() else 0
    }

    fun isTrialExpired(context: Context): Boolean {
        if (isPro(context)) return false
        return getRemainingDays(context) <= 0
    }
}
