package com.example.login.utility


import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.login.utility.NetworkTimeUtil
import com.example.login.utility.SyncPrefs
import java.util.*

class TimeSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val networkTime = NetworkTimeUtil.getNetworkTime()
        if (networkTime != null) {
            val localTime = Date()
            val diff = kotlin.math.abs(localTime.time - networkTime.time)

            // Threshold = 2 minutes
            if (diff > 2 * 60 * 1000) {
                // Log or notify mismatch (optional)
                // You can trigger a notification or flag mismatch
            }

            // Save last sync time (even if mismatch)
            SyncPrefs.saveLastSync(context, networkTime)
        }
        return Result.success()
    }
}
