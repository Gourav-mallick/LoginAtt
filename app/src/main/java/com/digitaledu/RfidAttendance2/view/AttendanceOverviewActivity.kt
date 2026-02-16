package com.digitaledu.RfidAttendance2.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitaledu.RfidAttendance2.databinding.ActivityAttendanceOverviewBinding
import com.digitaledu.RfidAttendance2.db.dao.AppDatabase
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback
import com.digitaledu.RfidAttendance2.R
import com.digitaledu.RfidAttendance2.api.ApiClient
import com.digitaledu.RfidAttendance2.api.ApiService
import com.digitaledu.RfidAttendance2.db.entity.Attendance
import com.digitaledu.RfidAttendance2.utility.DatabaseCleanupUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class AttendanceOverviewActivity : ComponentActivity() {

    private lateinit var binding: ActivityAttendanceOverviewBinding
    private lateinit var db: AppDatabase
    private lateinit var selectedClasses: List<String>
    private lateinit var sessionId: String

    // 🔹 Track whether back press is disabled
    private var backDisabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)


        val menuBtn = findViewById<ImageView>(R.id.btnMenu)

        menuBtn.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.menu_attendance, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {

                    R.id.menu_cancel_session -> {
                        confirmCancelSession()
                        true
                    }

                    else -> false
                }
            }
            popup.show()
        }

        db = AppDatabase.getDatabase(this)
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""

        // ✅ Disable back press & back gesture for this screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backDisabled) {
                    // Do nothing — block back press
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadOverviewData()

        binding.btnSubmitAttendance.setOnClickListener {
            submitAttendanceForSession()
   /*
          val intent = Intent(this, AttendanceActivity::class.java)
            startActivity(intent)
            finish()

    */
        }
    }



    private fun confirmCancelSession() {

        if (sessionId.isBlank()) {
            Toast.makeText(this, "No active session!", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Cancel Session")
            .setMessage("This will DELETE this session and all attendance.\nAre you sure?")
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ ->
                cancelCurrentSession()
            }
            .setNegativeButton("No", null)
            .show()
    }
    private fun cancelCurrentSession() {

        if (sessionId.isBlank()) return

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@AttendanceOverviewActivity)

            try {

                // 1️⃣ Delete attendance of this session
                db.attendanceDao().deleteAttendanceBySessionId(sessionId)

                // 2️⃣ Delete session
                db.sessionDao().deleteSessionById(sessionId)

                // 3️⃣ Delete ActiveClassCycle rows related to this session (if any)
                db.activeClassCycleDao().deleteBySessionId(sessionId)

                // 4️⃣ Clear saved cycle prefs (if stored earlier)
                getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
                    .edit().clear().apply()

                Toast.makeText(
                    this@AttendanceOverviewActivity,
                    "Session cancelled successfully",
                    Toast.LENGTH_LONG
                ).show()

                // 5️⃣ Go back to fresh Attendance screen
                restartFresh()

            } catch (e: Exception) {
                Toast.makeText(
                    this@AttendanceOverviewActivity,
                    "Error cancelling session",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    private fun restartFresh() {
        val intent = Intent(this, AttendanceActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }


    private fun loadOverviewData() {
        lifecycleScope.launch {
            val classSummaries = mutableListOf<ClassOverviewData>()

            for (classId in selectedClasses) {
                val students = db.studentsDao().getAllStudents().filter { it.classId == classId }
                val classObj=db.classDao().getClassById(classId)
                val classShortName = classObj?.classShortName ?: classId
                Log.d("ATTENDANCE_DEBUG", "classShortName is=$classShortName")

                val attendance = db.attendanceDao().getAttendancesForClass(sessionId, classId)

                val totalStudents = students.size
                val presentCount = attendance.count { it.status == "P" }
                val absentCount = totalStudents - presentCount
          /*
                val presentStudents = students.filter { s ->
                    attendance.any { it.studentId == s.studentId && it.status == "P" }
                }.map { it.studentName }

          */


                classSummaries.add(
                    ClassOverviewData(
                        className = classShortName,
                        totalStudents = totalStudents,
                        presentCount = presentCount,
                        absentCount = absentCount,
                     //   presentStudents = presentStudents,

                    )
                )
            }

            val adapter = AttendanceOverviewAdapter(classSummaries) { className ->
                Toast.makeText(this@AttendanceOverviewActivity, "Edit clicked for $className", Toast.LENGTH_SHORT).show()
            }

            binding.recyclerViewOverview.layoutManager = LinearLayoutManager(this@AttendanceOverviewActivity)
            binding.recyclerViewOverview.adapter = adapter
        }
    }


    private fun submitAttendanceForSession() {
        lifecycleScope.launch {

            // Show spinner
            binding.progressBar.visibility = View.VISIBLE
            delay(2000) // show for 2s

            try {
                val attendanceList = db.attendanceDao().getAttendanceBySessionId(sessionId)
              //  Log.d("AttendanceOverview", "Attendance list: $attendanceList")

                if (attendanceList.isEmpty()) {
                    binding.progressBar.visibility = View.GONE
                 //   showPopup("No attendance found for this session.")
                    Log.d("AttendanceOverview", "No attendance found for this session.")
                    return@launch
                }

                // Get baseUrl & hash from SharedPreferences
                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "")!!
              //  val hash = "trr36pdthb9xbhcppyqkgbpkq"
                val hash=prefs.getString("hash", "")!!


                val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

                // Prepare JSON payload
                val attArray = JSONArray()
                for (att in attendanceList) {
                    Log.d("SYNC_REQUEST msg", "$att")
                    val mappedList = mapAttendanceToApiFormat(att)

                    for (json in mappedList) {
                        attArray.put(json)
                        // 🔹 ADD THIS LINE
                        Log.d("FINAL_JSON", json.toString())
                    }
                }

// 🔹 ADD THIS LINE
                Log.d("SYNC_COUNT", "Total objects prepared = ${attArray.length()}")

                val requestBodyJson = JSONObject().apply {
                    put("attParamDataObj", JSONObject().apply {
                        put("attDataArr", attArray)
                        put("attAttachmentArr", JSONArray())
                        put("attendanceMethod", "periodDayWiseAttendance")
                        put("loggedInUsrId", "1")
                    })
                }

                Log.d("SYNC_REQUEST", requestBodyJson.toString())

                val mediaType = MediaType.parse("application/json; charset=utf-8")
                val requestBody = RequestBody.create(mediaType, requestBodyJson.toString())

                val response = apiService.postAttendanceSync(
                    r = "api/v1/Att/ManageMarkingGlobalAtt",
                    requestBody = requestBody
                )

                // 🔹 Log HTTP status
                Log.d("SYNC_HTTP", "Code: ${response.code()}")
                Log.d("SYNC_HTTP", "Message: ${response.message()}")
                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful && response.body() != null) {
                    val bodyString = response.body()!!.string()
                    Log.d("SYNC_RESPONSE", bodyString)

                    try {
                        val json = JSONObject(bodyString)
                        val collection = json.optJSONObject("collection")
                        val responseObj = collection?.optJSONObject("response")
                        val apiStatus = responseObj?.optString("status", "FAILED") ?: "FAILED"
                        val apiMsgArray = responseObj?.optJSONArray("msgAr")
                        val msg = apiMsgArray?.optString(0) ?: "Attendance synced successfully"

                        Log.d("SYNC_PARSED", "Status = $apiStatus")
                        Log.d("SYNC_PARSED", "Message = $msg")


                        if (apiStatus.equals("SUCCESS", ignoreCase = true)) {
                            // Delete Attendance records from DB & Session if it successfully sent to server
                            db.attendanceDao().updateSyncStatusBySession(sessionId, "complete")
                            db.sessionDao().updateSessionSyncStatusToComplete(sessionId, "complete")

                            DatabaseCleanupUtils.deleteSyncedAttendances(this@AttendanceOverviewActivity)
                            DatabaseCleanupUtils.deleteSyncedSessions(this@AttendanceOverviewActivity)

                            withContext(Dispatchers.Main) {
                                showPopupWithOk(msg)
                            }


                        } else {
                            Log.e("SYNC_FAIL", "Server returned failure: $msg")

                            withContext(Dispatchers.Main) {
                                showPopupWithOk("Attendance saved locally. You can sync later.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SYNC_PARSE_ERROR", "JSON parse error: ${e.message}")

                        withContext(Dispatchers.Main) {
                            showPopupWithOk("Attendance saved locally. You can sync later.")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showPopupWithOk("Attendance saved locally. You can sync later.")
                    }
                }



            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                withContext(Dispatchers.Main) {
                    showPopupWithOk("Server not reachable. Attendance saved locally, will sync later.")
                }
            }
        }
    }


    private suspend fun mapAttendanceToApiFormat(att: Attendance): List<JSONObject> {

        val date = att.date
        val startTime = att.startTime
        val endTime = att.endTime
        val dataStartTime = toApiDateTime(date, startTime)
        val dataEndTime   = toApiDateTime(date, endTime)

        val classShort = db.classDao().getClassById(att.classId)?.classShortName ?: ""

        val periodIds = if (!att.attSchoolPeriodId.isNullOrBlank()) {
            att.attSchoolPeriodId.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            listOf("") // fallback single
        }


        val result = mutableListOf<JSONObject>()

        for (spId in periodIds) {

            val obj = JSONObject().apply {

                put("studentId", att.studentId)
                put("instId", att.instId)
                put("instShortName", att.instShortName ?: "")
                put("academicYear",  att.academicYear)
                put("classId", att.classId)
                put("classShortName", classShort)
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
                // put("status", att.status)
                // You can extend more mappings as per your actual backend requirement
                put("studentClass", classShort)
                put("attCodetitle", "present")
                put("courseSelectionMode","")
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
                put("attSchoolPeriodId",spId)
                put("attSchoolPeriodTitle","")
                put("attSessionStartDateTime",dataStartTime )
                put("attSessionEndDateTime",dataEndTime)
                put("attCapturingIntervalDateTime","")
                put("attCapturingIntervalInSec","")
                put("attCapturingCycleState","")
                put("attCategory","Regular")
                put("studAttComment","")
                put("attSessionStudId","")
                put("attCodeId","1")
                put("attCodeLngName","present")
                put("attCode","P")
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

            result.add(obj)
        }

        return result
    }

    private fun showPopupWithOk(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                // ✅ Go back to AttendanceActivity
                val intent = Intent(this@AttendanceOverviewActivity, AttendanceActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            .show()
    }


    private fun toApiDateTime(date: String, time: String?): String {
        if (time.isNullOrBlank()) return "$date 00:00:00"

        return try {
            val inputFormats = listOf(
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("HH:mm", Locale.getDefault()),
                SimpleDateFormat("h:mm", Locale.getDefault()) // handles "1:30"
            )

            var parsed: Date? = null
            for (fmt in inputFormats) {
                try {
                    parsed = fmt.parse(time)
                    if (parsed != null) break
                } catch (_: Exception) {}
            }

            if (parsed == null) return "$date 00:00:00"

            val out = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(parsed)
            "$date $out"

        } catch (e: Exception) {
            "$date 00:00:00"
        }
    }


}

data class ClassOverviewData(
    val className: String,
    val totalStudents: Int,
    val presentCount: Int,
    val absentCount: Int,
  //  val presentStudents: List<String>,
)
