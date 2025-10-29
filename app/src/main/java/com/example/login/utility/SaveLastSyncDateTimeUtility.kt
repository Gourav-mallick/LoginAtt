package com.example.login.utility


import android.content.Context
import java.util.*

object SyncPrefs {
    private const val PREFS_NAME = "LoginPrefs"
    private const val KEY_LAST_SYNC = "last_sync_time"

    fun saveLastSync(context: Context, date: Date) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC, date.time)
            .apply()
    }

    fun getLastSync(context: Context): Date? {
        val time = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0L)
        return if (time == 0L) null else Date(time)
    }
}
