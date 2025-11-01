package com.example.login.view

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Attendance
import com.example.login.utility.CheckNetworkAndInternetUtils
import com.example.login.utility.DatabaseCleanupUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response


class SyncAttendanceToServer : AppCompatActivity(){

    private lateinit var db: AppDatabase
    private lateinit var apiService: ApiService
    private lateinit var sharedPreferences: SharedPreferences

   // private val hash = "trr36pdthb9xbhcppyqkgbpkq"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Layout should contain a "Sync" button (e.g. R.layout.activity_sync_attendance)
        setContentView(R.layout.activity_sync_attendance)

        db = AppDatabase.Companion.getDatabase(this)
        sharedPreferences = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val hash= sharedPreferences.getString("hash", null)

        val baseUrl = sharedPreferences.getString("baseUrl", "https://testvps.digitaledu.in/") ?: ""
     //   val hash = sharedPreferences.getString("HASH_KEY", null)

        apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            syncPendingAttendance()
        }
    }

    private fun syncPendingAttendance() {
        val progressBar = findViewById<ProgressBar>(R.id.progressSync)
        val statusText = findViewById<TextView>(R.id.tvSyncStatus)

        lifecycleScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Checking network..."
            }

            // Step 1: Check if any network is available
            val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SyncAttendanceToServer)
            if (!hasNetwork) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = " No network connection."
                    Toast.makeText(
                        this@SyncAttendanceToServer,
                        "Please connect to Wi-Fi or Mobile data.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

        // ✅ Step 2: Check if real internet access exists
            val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()
            if (!hasInternet) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "️ No internet access."
                    Toast.makeText(
                        this@SyncAttendanceToServer,
                        "Internet not reachable. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Syncing attendance..."
            }

            try {
                val pendingList = db.attendanceDao().getPendingAttendances()

                if (pendingList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusText.text = "No pending attendance to sync."
                        Toast.makeText(
                            this@SyncAttendanceToServer,
                            "No pending attendance",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                // 🔹 Group pending attendance by class and count present students
                val classCounts = pendingList
                    .groupBy { it.classId ?: "Unknown Class" }
                    .mapValues { (_, list) -> list.count { it.status == "P" } }

                  // 🔹 Prepare summary text
                val summary = classCounts.entries.joinToString("\n") {
                    "Class :${it.key}: Present Students - ${it.value}"
                }

               // 🔹 Show on Toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SyncAttendanceToServer,
                        summary.ifEmpty { "No attendance data found" },
                        Toast.LENGTH_LONG
                    ).show()
                }

                val attArray = JSONArray()
                for (att in pendingList) attArray.put(mapAttendanceToApiFormat(att))

                val requestBodyJson = JSONObject().apply {
                    put("attParamDataObj", JSONObject().apply {
                        put("attDataArr", attArray)
                        put("attAttachmentArr", JSONArray())
                        put("attendanceMethod", "periodDayWiseAttendance")
                        put("loggedInUsrId", "1")
                    })
                }
                val jsonString = requestBodyJson.toString()
                Log.d("SYNC_JSON", jsonString)
                val response = sendToServer(jsonString)


                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {

                        withContext(Dispatchers.Main) {
                            statusText.text = "Processing response..."
                        }

                        // Add delay (e.g., 2 seconds)
                        kotlinx.coroutines.delay(2000)

                        val bodyString = response.body()!!.string()
                        Log.d("SYNC_RESPONSE", bodyString)

                        try {
                            val json = JSONObject(bodyString)
                            val collection = json.optJSONObject("collection")
                            val responseObj = collection?.optJSONObject("response")
                            val apiStatus = responseObj?.optString("status", "FAILED") ?: "FAILED"
                            val apiMsgArray = responseObj?.optJSONArray("msgAr")
                            val msg = apiMsgArray?.optString(0) ?: "Attendance synced successfully"
                            if (apiStatus.equals("SUCCESS", ignoreCase = true)) {
                                // ✅ Server accepted data
                                pendingList.forEach {
                                    db.attendanceDao().updateSyncStatus(it.atteId, "complete")
                                    db.sessionDao().updateSessionSyncStatusToComplete(it.sessionId, "complete")
                                }

                                // Delete only synced attendance
                                DatabaseCleanupUtils.deleteSyncedAttendances(this@SyncAttendanceToServer)
                                DatabaseCleanupUtils.deleteSyncedSessions(this@SyncAttendanceToServer)
                                statusText.text = msg
                                Toast.makeText(
                                    this@SyncAttendanceToServer,
                                    "Server accepted sync!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                //  Server rejected data
                                val errorMsg = if (apiMsgArray != null && apiMsgArray.length() > 0)
                                    apiMsgArray.join(", ")
                                else
                                    "Server reported failure"

                                statusText.text = "⚠️ Sync failed"
                                Toast.makeText(
                                    this@SyncAttendanceToServer,
                                    "Sync failed: $errorMsg",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (e: Exception) {
                            statusText.text = " Invalid server response"
                            Toast.makeText(
                                this@SyncAttendanceToServer,
                                "Response parsing error: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        statusText.text = "❌ Network error }"
                        Toast.makeText(
                            this@SyncAttendanceToServer,
                            "HTTP Error ${response.code()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "Error: ${e.localizedMessage}"
                    Toast.makeText(
                        this@SyncAttendanceToServer,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun sendToServer(dataParam: String): Response<ResponseBody> {
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, dataParam)

        return apiService.postAttendanceSync(
            r = "api/v1/Att/ManageMarkingGlobalAtt",
            requestBody = requestBody
        )
    }


    private fun mapAttendanceToApiFormat(att: Attendance): JSONObject {

        val date=att.date
        val startTime=att.startTime
        val endtime=att.endTime

        val dataStartTime="$date $startTime:00"

        val dataEndTime="$date $endtime:00"

        return JSONObject().apply {
            put("studentId", att.studentId)
            put("instId", att.instId)
            put("instShortName", att.instShortName ?: "")
            put("academicYear",  "2024")
            put("classId", att.classId)
            put("classShortName", att.classShortName ?: "")
            put("subjectId", att.subjectId ?: "")
            put("subjectCode", att.subjectId ?: "")
            put("subjectShortName", att.subjectTitle ?: "")
            put("courseId", att.courseId ?: "")
            put("courseShortName", att.courseShortName ?: "")
            put("cpId", att.cpId ?: "")
            put("cpShortName", "")
            put("mpId", att.mpId ?: "")
            put("mpShortName", att.mpLongTitle ?: "")
            put("attDate", att.date)
            put("attSchoolPeriodStartTime", att.startTime)
            put("attSchoolPeriodEndTime", att.endTime)
            put("period", att.period)
            put("status", att.status)
            // You can extend more mappings as per your actual backend requirement
            put("studentClass", att.classShortName ?: "")
            put("attCodetitle", "present")
            put("courseSelectionMode","mandatory")
            put("stfId",att.teacherId)
            put("stfFML","")
            put("studId",att.studentId)
            put("studfFML","")
            put("studfLFM","")
            put("studentName",att.studentName)
            put("studAltId",att.atteId)
            put("studRollNo","")
            put("int_rollNo","")
            put("attCycleId","")
            put("attSessionId",att.sessionId)
            put("attSchoolPeriodId","1")
            put("attSchoolPeriodTitle","")
            put("attSessionStartDateTime",dataStartTime )
            put("attSessionEndDateTime",dataEndTime)
            put("attCapturingIntervalDateTime","")
            put("attCapturingIntervalInSec","")
            put("attCapturingCycleState","")
            put("attCategory","Regular")
            put("studAttComment","")
            put("attSessionStudId","")
            put("attCodeId",att.atteId)
            put("attCodeLngName","present")
            put("attCode",att.status)
            put("studAttStartDateTime",dataStartTime)
            put("studAttEndDateTime",dataEndTime)
            put("studAttTotalDuration","")
            put("atsaId","")
            put("atsaIsProxy","")
            put("atsaDistanceDeltaInMeter","")
            put("isSelfUsrAttMarked","")
            put("attCoLectureCpIds","")
            put("toRemoveCoLecturerCpIds","")
            put("toAddCoLecturerCpIds","")
            put("status","A")


        }
    }
}