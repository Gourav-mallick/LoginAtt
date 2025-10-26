package com.example.login.view

import android.app.PendingIntent
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
import com.example.login.db.entity.Session
import com.example.login.db.entity.Student
import kotlinx.coroutines.launch
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread


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

        checkDeviceTime { startClassroomScanFragment() }

        if (savedInstanceState == null) {
            val frag = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, TAG_CLASSROOM)
                .commit()
        }

        // Restore pending sessions (endTime empty) into activeClasses
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
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring sessions: ${e.message}")
            }
        }
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

    // ---------------- NFC reading (same as before) ----------------
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

    // ---------------- Class scan (room) ----------------
    private fun handleClassScan(classroomId: String, classroomName: String) {
        lifecycleScope.launch {
            try {
                // Block starting other class if there is an incomplete class elsewhere
                if (existsIncompleteCycleBlockingNew(classroomId)) {
                    Toast.makeText(this@AttendanceActivity,
                        "Please mark at least one student or end the current class before starting a different one.",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }

                val existing = activeClasses[classroomId]

                if (existing == null) {
                    // Start new cycle
                    val cycle = AttendanceCycle(classroomId = classroomId, classroomName = classroomName, startedAtMillis = System.currentTimeMillis())
                    activeClasses[classroomId] = cycle
                    currentVisibleClassroomId = classroomId

                    Toast.makeText(this@AttendanceActivity, "Class started: $classroomName", Toast.LENGTH_SHORT).show()

                    val frag = TeacherScanFragment.newInstance(classroomId)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, TAG_TEACHER)
                        .commitAllowingStateLoss()

                } else {
                    // If user scans same room card while it's currently visible -> show End dialog
                    if (currentVisibleClassroomId == classroomId) {
                        showEndClassDialog(classroomId)
                        return@launch
                    }

                    // Resume existing cycle
                    currentVisibleClassroomId = classroomId

                    if (existing.teacherId != null && existing.sessionId != null) {
                        Toast.makeText(this@AttendanceActivity, "Resuming ${existing.classroomName}", Toast.LENGTH_SHORT).show()
                        val frag = StudentScanFragment.newInstance(existing.teacherName ?: "")
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, frag, TAG_STUDENT)
                            .commitAllowingStateLoss()
                    } else {
                        val frag = TeacherScanFragment.newInstance(existing.classroomName)
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, frag, TAG_TEACHER)
                            .commitAllowingStateLoss()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleClassScan error: ${e.message}")
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

    // ---------------- Teacher scan ----------------
    private fun handleTeacherScan(teacherId: String, teacherName: String) {
        lifecycleScope.launch {
            try {
                val classId = currentVisibleClassroomId
                if (classId == null) {
                    Toast.makeText(this@AttendanceActivity, "Scan classroom first!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val cycle = activeClasses[classId] ?: run {
                    Toast.makeText(this@AttendanceActivity, "Class not started. Scan classroom first!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (cycle.teacherId != null) {
                    Toast.makeText(this@AttendanceActivity, "Teacher already scanned for this class!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val sessionId = UUID.randomUUID().toString()
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

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
                    instId = "INST001",
                    syncStatus = "pending"
                )

                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                db.sessionDao().insertSession(session)

                cycle.teacherId = teacherId
                cycle.teacherName = teacherName
                cycle.sessionId = sessionId

                Log.d(TAG, "New Session Created: $session")

                val frag = StudentScanFragment.newInstance(teacherName)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_STUDENT)
                    .commitAllowingStateLoss()

            } catch (e: Exception) {
                Log.e(TAG, "handleTeacherScan error: ${e.message}")
            }
        }
    }

    // ---------------- Student scan ----------------
    private fun handleStudentScan(student: Student) {
        lifecycleScope.launch {
            try {
                val classId = currentVisibleClassroomId
                if (classId == null) {
                    Toast.makeText(this@AttendanceActivity, "Scan classroom first!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val cycle = activeClasses[classId]
                if (cycle == null || cycle.sessionId.isNullOrEmpty()) {
                    Toast.makeText(this@AttendanceActivity, "Scan teacher first!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val existingAttendance = db.attendanceDao().getAttendanceForStudentInSession(cycle.sessionId!!, student.studentId)
                if (existingAttendance != null) {
                    Toast.makeText(this@AttendanceActivity, "${student.studentName} already scanned!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val attendance = Attendance(
                    atteId = UUID.randomUUID().toString(),
                    sessionId = cycle.sessionId!!,
                    studentId = student.studentId,
                    classId = student.classId,
                    status = "P",
                    markedAt = timeStamp,
                    syncStatus = "pending",
                    instId = student.instId
                )

                db.attendanceDao().insertAttendance(attendance)
                Log.d(TAG, "Attendance Saved: $attendance")

                val frag = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
                if (frag is StudentScanFragment) {
                    frag.addStudent(student)
                    frag.updateInstruction("Scan next student card")
                }

            } catch (e: Exception) {
                Log.e(TAG, "handleStudentScan error: ${e.message}")
            }
        }
    }

    // ---------------- End / Submit class ----------------
    private fun showEndClassDialogForVisibleClass() {
        val classId = currentVisibleClassroomId ?: return
        showEndClassDialog(classId)
    }

    private fun showEndClassDialog(classId: String) {
        val cycle = activeClasses[classId] ?: return
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            // Get present count from fragment,
            val frag = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
            val presentCount = if (frag is StudentScanFragment) frag.getStudentCount() else 0

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Close Class: ${cycle.classroomId}")
                .setMessage(if (presentCount == 0) "No student scanned! End class anyway?" else "Do you want to close the current class?")
                .setPositiveButton("Yes") { _, _ ->
                    lifecycleScope.launch {
                        cycle.sessionId?.let { sessionId ->
                            db.sessionDao().updateSessionEnd(sessionId, currentTime)
                        }

                        val intent = Intent(this@AttendanceActivity, ClassSelectActivity::class.java)
                        intent.putExtra("SESSION_ID", cycle.sessionId)
                        intent.putExtra("TEACHER_ID", cycle.teacherId)
                        startActivity(intent)

                        activeClasses.remove(classId)

                        if (activeClasses.isNotEmpty()) {
                            Toast.makeText(this@AttendanceActivity, "There are other unsubmitted classes. Scan their room cards to complete.", Toast.LENGTH_LONG).show()
                        }

                        if (currentVisibleClassroomId == classId) currentVisibleClassroomId = null
                    }
                }
                .setNegativeButton("No", null)
                .show()
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

    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
