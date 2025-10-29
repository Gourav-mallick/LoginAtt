package com.example.login.utility


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

object NetworkTimeUtil {

    suspend fun getNetworkTime(): Date? = withContext(Dispatchers.IO) {
        try {
            val client = NTPUDPClient()
            client.defaultTimeout = 5000
            val address = InetAddress.getByName("time.google.com")
            val info = client.getTime(address)
            client.close()
            Date(info.message.receiveTimeStamp.time)
        } catch (e: Exception) {
            null
        }
    }

    fun formatDateTime(date: Date?): String {
        if (date == null) return "N/A"
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(date)
    }
}
