package com.example.login.view

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.AttendanceIdGenerator
import com.example.login.db.entity.Session
import com.example.login.db.entity.Student
import kotlinx.coroutines.launch
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.String
import kotlin.concurrent.thread
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.login.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import android.net.ConnectivityManager

class AttendanceActivity : AppCompatActivity() {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private val TAG = "NFC_DEBUG"


    companion object {
        private const val TAG_CLASSROOM = "CLASSROOM"
        private const val TAG_TEACHER = "TEACHER"
        private const val TAG_STUDENT = "STUDENT"
    }

    data class AttendanceCycle(
        val classroomId: String,
        var classroomName: String,
        var teacherId: String? = null,
        var teacherName: String? = null,
        var sessionId: String? = null,
        var startedAtMillis: Long = System.currentTimeMillis()
    )

    private val activeClasses = mutableMapOf<String, AttendanceCycle>()
    private var currentVisibleClassroomId: String? = null
    private var isTimeValid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        //if app exit then restore last cycle
        restoreLastCycleIfExists()

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlySync", ExistingPeriodicWorkPolicy.KEEP, request
        )

        // Check device time and match with server time
        checkDeviceTime { startClassroomScanFragment() }

        if (savedInstanceState == null) {
            val frag = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, TAG_CLASSROOM)
                .commit()
        }

        // Restore pending sessions (endTime empty) into activeClasses
        restorePendingSessions()
    }

    override fun onResume() {
        super.onResume()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val tagId = tag.id.joinToString(":") { String.format("%02X", it) }
        Log.d(TAG, "Tag UID: $tagId")
        readCustomCardData(tag)
    }

    // ---------------- NFC reading  ----------------
    private fun readCustomCardData(tag: Tag) {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val keyHex = prefs.getString("cpass", null)
        if (keyHex.isNullOrEmpty()) {
            Toast.makeText(this, "Missing authentication key (cpass)!", Toast.LENGTH_LONG).show()
            Log.e(TAG, "No key found in SharedPreferences.")
            return
        }

        val keyBytes = try { hexStringToByteArray(keyHex) } catch (e: Exception) {
            Toast.makeText(this, "Invalid hex key format!", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Key conversion failed: ${e.message}")
            return
        }
        Log.d(TAG, "Key bytes: ${keyBytes.joinToString(" ")}")
        Log.d(TAG, "Key bytes actual data: ${keyBytes.toString()}")



        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            Toast.makeText(this, "Not a MIFARE Classic card", Toast.LENGTH_LONG).show()
            Log.e(TAG, "MifareClassic not supported on this tag")
            return
        }

        try {
            mifare.connect()
            val sectorIndex = 0
            val auth = mifare.authenticateSectorWithKeyA(sectorIndex, keyBytes)
            if (!auth) {
                Toast.makeText(this, "Authentication failed!", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Authentication failed.")
                return
            }

            val firstBlock = mifare.sectorToBlock(sectorIndex)
            val blockCount = mifare.getBlockCountInSector(sectorIndex)
            var name: String? = null

            for (i in 0 until blockCount) {
                val blockIndex = firstBlock + i
                if (blockIndex == 0 || (blockIndex + 1) % 5 == 0) continue
                val data = mifare.readBlock(blockIndex)
                val rawText = String(data, Charsets.ISO_8859_1).replace("\u0000", "").trim()
                if (blockIndex == firstBlock + 1) {
                    name = rawText
                    Log.d(TAG, "Name: $rawText")
                }
                if (blockIndex == firstBlock + 2) {
                    val facilityCode = data.sliceArray(0..1).joinToString("") { "%02X".format(it) }
                    val idTypeHex = data.sliceArray(2..4).joinToString("") { "%02X".format(it) }
                    Log.d(TAG, "Block2 raw bytes hex: $idTypeHex")
                    val firstChar = idTypeHex.firstOrNull()?.uppercaseChar()
                    var typeChar: String? = null
                    var idValue = ""

                    if (firstChar != null && (firstChar == 'A' || firstChar == 'B' || firstChar == 'C')) {
                        typeChar = firstChar.toString()
                        idValue = idTypeHex.substring(1).trimStart('0').ifEmpty { "0" }
                    } else {
                        val slice = data.sliceArray(2..4)
                        val idBytes = ArrayList<Byte>()
                        for (b in slice) {
                            when (b.toInt() and 0xFF) {
                                0x41 -> typeChar = "A"
                                0x42 -> typeChar = "B"
                                0x43 -> typeChar = "C"
                                0x00 -> { }
                                else -> idBytes.add(b)
                            }
                        }
                        idValue = if (idBytes.isEmpty()) "" else idBytes.joinToString("") { "%02X".format(it) }.trimStart('0').ifEmpty { "0" }
                    }

                    if (typeChar == null) typeChar = "Unknown"
                    Log.d(TAG, "Parsed -> Facility: $facilityCode | Type: $typeChar | ID: $idValue")

                    handleNfcScan(name ?: "-", typeChar, idValue)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reading card: ${e.message}", e)
            Toast.makeText(this, "Error reading card: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try { mifare.close() } catch (_: Exception) {}
            Log.d(TAG, "Card connection closed.")
        }
    }

    // ---------------- High-level NFC handling ----------------
    private fun handleNfcScan(name: String, typeChar: String, idValue: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val nameParts = name.trim().split(" ").filter { it.isNotEmpty() }
            val part1 = nameParts.getOrNull(0)
            val part2 = nameParts.getOrNull(1)
            val part3 = nameParts.getOrNull(2)

            when (typeChar) {
                "A" -> {
                    val classObj = db.classDao().getClassById(idValue)
                    if (classObj != null) handleClassScan(classObj.classId, classObj.classShortName)
                    else Toast.makeText(this@AttendanceActivity, "Class not found!", Toast.LENGTH_SHORT).show()
                }
                "B" -> {
                    val teacher = db.teachersDao().getAndMatchTeacherByIdName(idValue, part1, part2, part3)
                    if (teacher != null) handleTeacherScan(teacher.staffId, teacher.staffName)
                    else Toast.makeText(this@AttendanceActivity, "Teacher not found!", Toast.LENGTH_SHORT).show()
                }
                "C" -> {
                    val student = db.studentsDao().getAndMatchStudentByIdName(idValue, part1, part2, part3)
                    if (student != null) handleStudentScan(student)
                    else Toast.makeText(this@AttendanceActivity, "Student not found!", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this@AttendanceActivity, "Unknown type: $typeChar", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // ----------------- Scan: Classroom -----------------
    private fun handleClassScan(classroomId: String, classroomName: String) {
        lifecycleScope.launch {
            val existing = activeClasses[classroomId]

            if (existing == null) {
                // 🔹 Start new cycle
                val cycle = AttendanceCycle(classroomId, classroomName)
                activeClasses[classroomId] = cycle
                currentVisibleClassroomId = classroomId
                Toast.makeText(this@AttendanceActivity, "Class started: $classroomId", Toast.LENGTH_SHORT).show()

                val frag = TeacherScanFragment.newInstance(classroomId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_TEACHER)
                    .commitAllowingStateLoss()
            } else {
                //  If same class → end popup
                if (currentVisibleClassroomId == classroomId) {
                    showEndClassDialog(classroomId)
                    return@launch
                }

                //  Resume existing class
                currentVisibleClassroomId = classroomId
                if (!existing.sessionId.isNullOrEmpty()) {
                    Toast.makeText(this@AttendanceActivity, "Resuming ${existing.classroomId}", Toast.LENGTH_SHORT).show()
                    val frag = StudentScanFragment.newInstance(existing.teacherName ?: "")
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, TAG_STUDENT)
                        .commitAllowingStateLoss()
                } else {
                    val frag = TeacherScanFragment.newInstance(existing.classroomId)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, TAG_TEACHER)
                        .commitAllowingStateLoss()
                }
            }
        }
    }

    private suspend fun existsIncompleteCycleBlockingNew(newClassroomId: String): Boolean {
        val db = AppDatabase.getDatabase(this@AttendanceActivity)
        for ((id, cycle) in activeClasses) {
            if (id == newClassroomId) continue
            if (!cycle.sessionId.isNullOrEmpty()) {
                val count = db.attendanceDao().getAttendancesForClass(cycle.sessionId!!, cycle.classroomId).size
                if (count == 0) return true
            }
        }
        return false
    }


// ----------------- Scan: Teacher -----------------
    private fun handleTeacherScan(teacherId: String, teacherName: String) {
        lifecycleScope.launch {
                // ✅ Check if any classroom is active
                if (currentVisibleClassroomId == null) {
                    Toast.makeText(
                        this@AttendanceActivity,
                        "Teacher: $teacherName\nID: $teacherId\n⚠️ Please scan classroom first to start class.",
                        Toast.LENGTH_LONG
                    ).show()

                    Log.d(TAG, "Teacher scanned without classroom: ID=$teacherId, Name=$teacherName")
                    return@launch
                }


            val classId = currentVisibleClassroomId ?: return@launch
            val cycle = activeClasses[classId] ?: return@launch


            if (cycle != null) {
                // ✅ NEW: If same teacher scans again → check if students were marked
                if (cycle.teacherId == teacherId && !cycle.sessionId.isNullOrEmpty()) {
                    val db = AppDatabase.getDatabase(this@AttendanceActivity)
                    val studentCount = db.attendanceDao()
                        .getAttendancesForTeacherId(cycle.sessionId!!, teacherId)
                        .size

                    if (studentCount == 0) {
                        // 🟢 No students —  Show popup confirmation
                        AlertDialog.Builder(this@AttendanceActivity)
                            .setTitle("Close Class?")
                            .setMessage("No students were marked present.\nDo you want to close this class?")
                            .setCancelable(false)
                            .setPositiveButton("Yes") { _, _ ->
                                lifecycleScope.launch {
                                    val endTime = SimpleDateFormat("HH:mm", Locale.getDefault())
                                        .format(getEstimatedCurrentTime())

                                    db.sessionDao().updateSessionEnd(cycle.sessionId!!, endTime)

                                    // 🧹 Clear active class and prefs
                                    activeClasses.remove(classId)
                                    currentVisibleClassroomId = null
                                    getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
                                        .edit().clear().apply()

                                    Toast.makeText(
                                        this@AttendanceActivity,
                                        "Class closed successfully at $endTime",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    // 🔄 Go back to main attendance screen
                                    val intent = Intent(this@AttendanceActivity, AttendanceActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
                                    finish()
                                }
                            }
                            .setNegativeButton("No") { dialog, _ ->
                                dialog.dismiss() // teacher can continue if they want
                            }
                            .show()

                    } else {
                        // 🟠 Students were marked → normal close dialog
                        showEndClassDialog(classId)
                        return@launch
                    }
                }
            }

            if (cycle.teacherId != null) {
                Toast.makeText(this@AttendanceActivity, "Teacher already scanned!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
            Log.d("SYNC_DEBUG", "LastSyncTime = ${prefs.getString("last_sync_time", "none")}")
            Log.d("SYNC_DEBUG", "LastSyncUptime = ${prefs.getLong("last_sync_uptime", 0L)}")
            Log.d("SYNC_DEBUG", "CurrentUptime = ${SystemClock.elapsedRealtime()}")


            if (isLastSyncExpired()) {
                Toast.makeText(this@AttendanceActivity,
                    "Last sync older than 24 hours. Please connect to network to sync time.",
                    Toast.LENGTH_LONG).show()
                return@launch
            }


            val sessionId = UUID.randomUUID().toString()
            val estimated = getEstimatedCurrentTime()
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(estimated)
            Log.d("SYNC_DEBUG", "Estimated time: $estimated")
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(estimated)
            Log.d("SYNC_DEBUG", "Start time: $startTime")

            val pref = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            val savedInstituteId = pref.getString("selectedInstituteIds", "")


            val session = Session(
                sessionId = sessionId,
                periodId = "",
                teacherId = teacherId,
                classId = "",
                subjectId = "",
                date = currentDate,
                startTime = startTime,
                endTime = "",
                isMerged = 0,
                instId = savedInstituteId!!,
                syncStatus = "pending"
            )
            Toast.makeText(this@AttendanceActivity, "Using estimated time: $startTime", Toast.LENGTH_SHORT).show()


            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            db.sessionDao().insertSession(session)

            cycle.teacherId = teacherId
            cycle.teacherName = teacherName
            cycle.sessionId = sessionId

            val frag = StudentScanFragment.newInstance(teacherName)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, TAG_STUDENT)
                .commitAllowingStateLoss()
        }
    }


// ----------------- Scan: Student -----------------
    private fun handleStudentScan(student: Student) {
        lifecycleScope.launch {

            // ✅ If no classroom started yet
            if (currentVisibleClassroomId == null) {
                Toast.makeText(
                    this@AttendanceActivity,
                    "Student: ${student.studentName}\nID: ${student.studentId}\n⚠️ Please scan classroom card first.",
                    Toast.LENGTH_LONG
                ).show()

                Log.d(TAG, "Student scanned without classroom: ID=${student.studentId}, Name=${student.studentName}")
                return@launch
            }

            val classId = currentVisibleClassroomId ?: return@launch
            val cycle = activeClasses[classId] ?: return@launch

            if (cycle.sessionId.isNullOrEmpty()) {
                Toast.makeText(this@AttendanceActivity, "Scan teacher first!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val existing = db.attendanceDao().getAttendanceForStudentInSession(cycle.sessionId!!, student.studentId)
            if (existing != null) {
                Toast.makeText(this@AttendanceActivity, "${student.studentName} already marked!", Toast.LENGTH_SHORT).show()
                return@launch
            }


            val estimated = getEstimatedCurrentTime()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(estimated)
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(estimated)
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(estimated)

            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            val savedInstituteId = prefs.getString("selectedInstituteIds", "")
            val savedInstituteName = prefs.getString("selectedInstituteNames", "")


            val attendance = Attendance(

                atteId = AttendanceIdGenerator.nextId(),
                sessionId = cycle.sessionId!!,
                studentId = student.studentId,
                studentName = student.studentName,
                classId = student.classId,
                status = "P",
                markedAt = timeStamp,
                syncStatus = "pending",
                instId = savedInstituteId!!,
                instShortName = savedInstituteName,
                date = currentDate,
                startTime = startTime,
                endTime = "",
                academicYear = "",
                period = "",
                teacherId =cycle.teacherId!!,
                teacherName = cycle.teacherName!!,

            )
            Log.d("SYNC_DEBUG_attandance", "Attendance: $attendance")
            db.attendanceDao().insertAttendance(attendance)
            saveCurrentCycle()
            val frag = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
            if (frag is StudentScanFragment) frag.addStudent(student)
        }
    }

    // ---------------- End / Submit class ----------------
    private fun showEndClassDialogForVisibleClass() {
        val classId = currentVisibleClassroomId ?: return
        showEndClassDialog(classId)
    }

    // ----------------- End Class -----------------
    private fun showEndClassDialog(classId: String) {
        val cycle = activeClasses[classId] ?: return
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val presentCount = if (!cycle.sessionId.isNullOrEmpty())
                db.attendanceDao().getAttendancesForClass(cycle.sessionId!!, classId).size else 0

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(getEstimatedCurrentTime())

            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Close Class: ${cycle.classroomId}")
                .setMessage("Do you want to close this class?")
                .setPositiveButton("Yes") { _, _ ->
                    lifecycleScope.launch {
                        cycle.sessionId?.let { db.sessionDao().updateSessionEnd(it, currentTime) }
                        cycle.sessionId?.let{db.attendanceDao().updateAttendanceEndTime(it, currentTime)}

                        Log.d("SESSION_END", "Session ${cycle.sessionId} closed at $currentTime")

                        val intent = Intent(this@AttendanceActivity, ClassSelectActivity::class.java)
                        intent.putExtra("SESSION_ID", cycle.sessionId)
                        intent.putExtra("TEACHER_ID", cycle.teacherId)
                        startActivity(intent)
                        activeClasses.remove(classId)
                        currentVisibleClassroomId = null
                    }
                }
                .setNegativeButton("No", null)
                .show()

            val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
            prefs.edit().clear().apply()

        }
    }

    // ---------------- Utilities ----------------
    private fun startClassroomScanFragment() {
        if (supportFragmentManager.findFragmentByTag(TAG_CLASSROOM) == null) {
            val fragment = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, TAG_CLASSROOM)
                .commit()
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        require(len % 2 == 0) { "Hex string must have even length" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun checkDeviceTime(onChecked: (() -> Unit)? = null) {
        if (!isNetworkAvailable()) {
            onChecked?.invoke() // skip check if offline
            return
        }

        thread {
            try {
                val connection = URL("https://google.com").openConnection()
                connection.connect()
                val networkTime = Date(connection.date)
                val deviceTime = Date(System.currentTimeMillis())

                val difference = kotlin.math.abs(deviceTime.time - networkTime.time)
                val diffMinutes = difference / (1000 * 60)

                Log.d("TIME_CHECK", "Device Time: $deviceTime")
                Log.d("TIME_CHECK", "Network Time: $networkTime")
                Log.d("TIME_CHECK", "Difference (minutes): $diffMinutes")

                if (diffMinutes > 2) {
                    isTimeValid = false
                    runOnUiThread { showTimeMismatchDialog() }
                } else {
                    isTimeValid = true
                    runOnUiThread { onChecked?.invoke() }
                }
            } catch (e: Exception) {
                Log.e("TIME_CHECK", "Error checking time: ${e.message}")
                isTimeValid = true
                runOnUiThread { onChecked?.invoke() }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        return net != null && net.isConnected
    }


    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                finish()
            }
            .show()
    }


    private fun saveCurrentCycle() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        val classId = currentVisibleClassroomId
        if (classId != null) {
            val cycle = activeClasses[classId]
            if (cycle != null) {
                editor.putString("classId", cycle.classroomId)
                editor.putString("className", cycle.classroomName)
                editor.putString("teacherId", cycle.teacherId)
                editor.putString("teacherName", cycle.teacherName)
                editor.putString("sessionId", cycle.sessionId)
                editor.apply()
            }
        }
    }


    private fun restoreLastCycleIfExists() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val classId = prefs.getString("classId", null)
        val sessionId = prefs.getString("sessionId", null)
        val className = prefs.getString("className", null)
        val teacherId = prefs.getString("teacherId", null)
        val teacherName = prefs.getString("teacherName", null)

        // No previous cycle saved
        if (classId.isNullOrEmpty() || sessionId.isNullOrEmpty()) {
            Log.d(TAG, "No previous attendance cycle found to restore.")
            return
        }

        // ✅ Recreate the previous attendance cycle
        val cycle = AttendanceCycle(
            classroomId = classId,
            classroomName = className ?: "",
            teacherId = teacherId,
            teacherName = teacherName,
            sessionId = sessionId
        )
        activeClasses[classId] = cycle
        currentVisibleClassroomId = classId
        Log.d(TAG, "Restoring last cycle for class: $classId, session: $sessionId")

        // ✅ Make sure the ClassroomScanFragment exists (inflate it if missing)
        var classroomFragment =
            supportFragmentManager.findFragmentByTag("CLASSROOM") as? ClassroomScanFragment
        if (classroomFragment == null) {
            classroomFragment = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, classroomFragment, "CLASSROOM")
                .commitNow() // commitNow ensures view is available immediately
        } else {
            supportFragmentManager.executePendingTransactions()
        }

        // ✅ Safely access the fragment's resume info layout
        val fragView = classroomFragment.view
        val layout = fragView?.findViewById<LinearLayout>(R.id.layoutResumeInfo)
        val tvClass = fragView?.findViewById<TextView>(R.id.tvResumeClass)
        val tvTeacher = fragView?.findViewById<TextView>(R.id.tvResumeTeacher)
        val tvStudents = fragView?.findViewById<TextView>(R.id.tvResumeStudents)

        // ✅ Load attendance count asynchronously and update UI
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val studentCount = db.attendanceDao()
                    .getAttendancesForClass(sessionId, classId)
                    .size

                runOnUiThread {
                    layout?.visibility = View.VISIBLE
                    tvClass?.text = "Resumed Class: ${className ?: "-"}"
                    tvTeacher?.text = "Teacher: ${teacherName ?: "-"}"
                    tvStudents?.text = "Present Students: $studentCount"
                }

                Toast.makeText(
                    this@AttendanceActivity,
                    "Resumed last session for $className",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring last cycle UI: ${e.message}", e)
            }
        }
    }

    private fun restorePendingSessions() {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val sessions = db.sessionDao().getAllSessions()
                sessions.filter { it.endTime.isNullOrEmpty() }.forEach { s ->
                    if (!activeClasses.containsKey(s.classId)) {
                        val classObj = db.classDao().getClassById(s.classId)
                        val classShort = classObj?.classShortName ?: s.classId
                        activeClasses[s.classId] = AttendanceCycle(
                            classroomId = s.classId,
                            classroomName = classShort,
                            teacherId = s.teacherId,
                            teacherName = "",
                            sessionId = s.sessionId
                        )
                        Log.d(TAG, "Restored pending session: ${s.sessionId} for class ${s.classId}")
                    }
                }

                // 🔹 Auto-resume last ongoing class
                if (activeClasses.isNotEmpty()) {
                    val first = activeClasses.values.first()
                    currentVisibleClassroomId = first.classroomId
                    val frag = StudentScanFragment.newInstance(first.teacherName ?: "")
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, "STUDENT")
                        .commitAllowingStateLoss()
                    Toast.makeText(this@AttendanceActivity, "Resumed class: ${first.classroomId}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring sessions: ${e.message}")
            }
        }
    }

    private fun getEstimatedCurrentTime(): Date {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastSyncStr = prefs.getString("last_sync_time", null) ?: return Date()
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return Date()

        return try {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
            val lastSyncDate = sdf.parse(lastSyncStr) ?: return Date()
            val uptimeDiff = SystemClock.elapsedRealtime() - lastUptime
            Date(lastSyncDate.time + uptimeDiff)
        } catch (e: Exception) {
            Date()
        }
    }


    private fun isLastSyncExpired(): Boolean {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return true

        val diffMillis = SystemClock.elapsedRealtime() - lastUptime
        val diffHours = diffMillis / (1000 * 60 * 60)

        return diffHours > 24
    }


}
