package com.example.login.view

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.login.R
import android.app.AlertDialog
import android.provider.Settings
import com.example.login.db.dao.AppDatabase
import java.net.URL
import java.util.*
import kotlin.concurrent.thread
import androidx.lifecycle.lifecycleScope
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.Session
import com.example.login.db.entity.Student
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat


class AttendanceActivity : AppCompatActivity() {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private val TAG = "NFC_DEBUG"

    private var currentClassroomId: String? = null
    private var currentTeacherId: String? = null
    private var currentClassroomName: String? = null
    private var currentTeacherName: String? = null
    private var currentSessionId: String? = null
    private var isClassStarted = false
    private var isTimeValid = false

    // private val scannedStudents = mutableListOf<Student>()

    companion object {
        private const val TAG_CLASSROOM = "CLASSROOM"
        private const val TAG_TEACHER = "TEACHER"
        private const val TAG_STUDENT = "STUDENT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        checkDeviceTime {
            startClassroomScan()
        }

        if (savedInstanceState == null) {
            val frag = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, "CLASSROOM")
                .commit()
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
        try {
            nfcAdapter.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return

        //  Get NFC Tag UID
        val tagId = tag.id.joinToString(":") { String.format("%02X", it) }
        Log.d(TAG, "Tag UID: $tagId")
     //   Toast.makeText(this, "Tag ID: $tagId", Toast.LENGTH_SHORT).show()

        readCustomCardData(tag)
    }


    // -----------------------------------------------------------------------
    // 🔹 Step 1: Read Custom Card Data (NFC)
    // -----------------------------------------------------------------------
    private fun readCustomCardData(tag: Tag) {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val keyHex = prefs.getString("cpass", null)

        if (keyHex.isNullOrEmpty()) {
            Toast.makeText(this, "Missing authentication key (cpass)!", Toast.LENGTH_LONG).show()
            Log.e(TAG, "No key found in SharedPreferences.")
            return
        }

        val keyBytes = try {
            hexStringToByteArray(keyHex)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid hex key format!", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Key conversion failed: ${e.message}")
            return
        }

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
       //     Log.d(TAG, "Reading sector $sectorIndex -> blocks [$firstBlock .. ${firstBlock + blockCount}]")

            var name: String? = null
            var typeChar: String? = null
            var idValue: String? = null

            for (i in 0 until blockCount) {
                val blockIndex = firstBlock + i
                if (blockIndex == 0 || (blockIndex + 1) % 5 == 0) continue

                val data = mifare.readBlock(blockIndex)
                val rawText = String(data, Charsets.ISO_8859_1).replace("\u0000", "").trim()
           //     Log.d(TAG, "Block Raw text[$blockIndex] : $rawText")

                // 🔹 Block 1 → Name
                if (blockIndex == firstBlock + 1) {
                    name = rawText
                 //   Toast.makeText(this, "Name: $name", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Name: $rawText")
                }

                
// 🔹 Block 2 → Facility + Type + ID (handle ASCII type bytes and hex-first-nibble like "C080")
                if (blockIndex == firstBlock + 2) {
                    val facilityCode = data.sliceArray(0..1).joinToString("") { "%02X".format(it) }

                    // full hex string of bytes 2..4, e.g. "0041FF" or "C08000"
                    val idTypeHex = data.sliceArray(2..4).joinToString("") { "%02X".format(it) }
                    Log.d(TAG, "Block2 raw bytes hex: $idTypeHex")

                    // 1) If the first hex char is A/B/C -> treat that as Type and rest as ID (e.g. "C080" -> type C, id 80)
                    val firstChar = idTypeHex.firstOrNull()?.uppercaseChar()
                    var typeChar: String? = null
                    var idValue = ""

                    if (firstChar != null && (firstChar == 'A' || firstChar == 'B' || firstChar == 'C')) {
                        typeChar = firstChar.toString()
                        idValue = idTypeHex.substring(1).trimStart('0').ifEmpty { "0" }
                    } else {
                        // 2) Fallback: look for ASCII bytes 0x41/0x42/0x43 among the raw bytes,
                        //    and treat other non-zero bytes as ID bytes
                        val slice = data.sliceArray(2..4)
                        val idBytes = ArrayList<Byte>()
                        for (b in slice) {
                            when (b.toInt() and 0xFF) {
                                0x41 -> typeChar = "A"
                                0x42 -> typeChar = "B"
                                0x43 -> typeChar = "C"
                                0x00 -> { /* skip padding */ }
                                else -> idBytes.add(b)
                            }
                        }
                        idValue = if (idBytes.isEmpty()) "" else idBytes.joinToString("") { "%02X".format(it) }.trimStart('0').ifEmpty { "0" }
                    }

                    if (typeChar == null) typeChar = "Unknown"



                    Log.d(TAG, "Parsed -> Facility: $facilityCode | Type: $typeChar | ID: $idValue")
              /*
                    Toast.makeText(
                        this,
                        "Facility: $facilityCode\nType: $typeChar\nID: $idValue",
                        Toast.LENGTH_LONG
                    ).show()

               */


                    if (typeChar != null && idValue != null) {
                        Log.d(TAG, "Parsed: Type=$typeChar | ID=$idValue | Name=$name")
                        handleNfcScan(name ?: "-", typeChar!!, idValue!!)
                    } else {
                        Toast.makeText(this, "Invalid card data!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reading card: ${e.message}", e)
            Toast.makeText(this, "Error reading card: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try {
                mifare.close()
                Log.d(TAG, "Card connection closed.")
            } catch (_: Exception) {
            }
        }
    }

    private fun startClassroomScan() {
        if (isTimeValid) {
            if (supportFragmentManager.findFragmentByTag("CLASSROOM") == null) {
                val fragment = ClassroomScanFragment.newInstance()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment, "CLASSROOM")
                    .commit()
            }
        } else {
            Toast.makeText(this, "Please correct your device time before starting attendance.", Toast.LENGTH_LONG).show()
        }
    }


    private fun handleNfcScan(name: String, typeChar: String, idValue: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)

            // Split name safely
            val nameParts = name.trim().split(" ").filter { it.isNotEmpty() }

            val part1 = nameParts.getOrNull(0)
            val part2 = nameParts.getOrNull(1)
            val part3 = nameParts.getOrNull(2)

            when (typeChar) {
                "A" -> { // Class Card
                    val classObj = db.classDao().getClassById(idValue)
                    if (classObj != null) handleClassScan(classObj.classId, classObj.classShortName) //Toast.makeText(this@AttendanceActivity, "Class found!", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@AttendanceActivity, "Class not found!", Toast.LENGTH_SHORT).show()
                }

                "B" -> { // Teacher Card
                    val teacher = db.teachersDao().getAndMatchTeacherByIdName( idValue,
                        part1,
                        part2,
                        part3)
                    if (teacher != null) handleTeacherScan(teacher.staffId, teacher.staffName) // Toast.makeText(this@AttendanceActivity, "Teacher  found!", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@AttendanceActivity, "Teacher not found!", Toast.LENGTH_SHORT).show()
                }

                "C" -> { // Student Card
                    val student = db.studentsDao().getAndMatchStudentByIdName( idValue,
                        part1,
                        part2,
                        part3)
                    if (student != null) handleStudentScan(student)//Toast.makeText(this@AttendanceActivity, "Student found!", Toast.LENGTH_SHORT).show() //
                    else Toast.makeText(this@AttendanceActivity, "Student not found!", Toast.LENGTH_SHORT).show()
                }

                else -> Toast.makeText(this@AttendanceActivity, "Unknown type: $typeChar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleClassScan(classroomId: String, classroomName: String) {
        lifecycleScope.launch {
            if (!isClassStarted) {
                // Start class
                currentClassroomId = classroomId
                currentClassroomName = classroomName
                isClassStarted = true

                Toast.makeText(this@AttendanceActivity, "Class started: $classroomName", Toast.LENGTH_SHORT).show()

                // Navigate to Teacher Scan Fragment
                val frag = TeacherScanFragment.newInstance(classroomName)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_TEACHER)
                    .commitAllowingStateLoss()

            } else {
                if (currentClassroomId == classroomId) {
                    // Same classroom scanned → show first popup
                    Toast.makeText(this@AttendanceActivity, "Same classroom scanned!", Toast.LENGTH_SHORT).show()
                    runOnUiThread { showEndClassDialog() }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@AttendanceActivity,
                            "Different classroom scanned! End current first.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }


    private fun handleTeacherScan(teacherId: String, teacherName: String) {
        lifecycleScope.launch {

            if (!isClassStarted || currentClassroomId == null) {
                // Card type hardcoded as TeacherCard
                val cardType = "TeacherCard"
              //  showCardPopup(teacherName, cardType, teacherId)
                Toast.makeText(this@AttendanceActivity, "Scan classroom first!", Toast.LENGTH_SHORT).show()
                return@launch
            }


            if (currentTeacherId != null) {
                Toast.makeText(this@AttendanceActivity, "Teacher already scanned!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            currentTeacherId = teacherId
            currentTeacherName = teacherName

            // Create a new session
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
                syncStatus = "pending",
            )

            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            db.sessionDao().insertSession(session)
            currentSessionId = sessionId

            Log.d("SESSION_CREATE", "New Session Created: $session")

            // Navigate to Student Scan Fragment
            val frag = StudentScanFragment.newInstance( teacherName)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag,
                    com.example.login.view.AttendanceActivity.Companion.TAG_STUDENT
                )
                .commitAllowingStateLoss()
        }
    }


    private fun handleStudentScan(student: Student) {
        lifecycleScope.launch {
            // Ensure class and teacher are active
            if (!isClassStarted || currentTeacherId == null) {
                Toast.makeText(this@AttendanceActivity, "Scan teacher first!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val db = AppDatabase.getDatabase(this@AttendanceActivity)

            // ✅ Check if student already marked present in this session
            val existingAttendance = db.attendanceDao()
                .getAttendanceForStudentInSession(currentSessionId ?: return@launch, student.studentId)

            if (existingAttendance != null) {
                // Already scanned — show toast
                Toast.makeText(
                    this@AttendanceActivity,
                    "${student.studentName} already scanned!",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // ✅ Add to fragment UI
            val fragment = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
            val added = if (fragment is StudentScanFragment) {
                fragment.addStudent(student)
            } else false

            if (!added) {
                Toast.makeText(
                    this@AttendanceActivity,
                    "${student.studentName} already marked present!",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // ✅ Save attendance in DB
            val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val attendance = Attendance(
                atteId = UUID.randomUUID().toString(),
                sessionId = currentSessionId ?: return@launch,
                studentId = student.studentId,
                classId = student.classId,
                status = "P",
                markedAt = timeStamp,
                syncStatus = "pending",
                instId = student.instId,
            )
            db.attendanceDao().insertAttendance(attendance)

            Log.d("ATTENDANCE_INSERT", "Attendance Saved: $attendance")

            (fragment as? StudentScanFragment)?.updateInstruction("Scan next student card")
        }
    }


    private fun showEndClassDialog() {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        AlertDialog.Builder(this)
            .setTitle("Close Class")
            .setMessage(
                if (getPresentCount() == 0) "No student scanned! End class anyway?"
                else "Do you want to close the current class?"
            )
            .setPositiveButton("Yes") { _, _ ->

                // 1️⃣ Update session end time in DB
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(this@AttendanceActivity)
                    currentSessionId?.let { sessionId ->
                        db.sessionDao().updateSessionEnd(sessionId, currentTime)
                    }


                }

                // 2️⃣ Directly navigate using currentSessionId and currentTeacherId
                val intent = Intent(this, ClassSelectActivity::class.java)
                intent.putExtra("SESSION_ID", currentSessionId)
                intent.putExtra("TEACHER_ID", currentTeacherId)
              //  intent.putParcelableArrayListExtra("SCANNED_STUDENTS", ArrayList(scannedStudents))

                startActivity(intent)
                finish()


            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun getPresentCount(): Int {
        val frag = supportFragmentManager.findFragmentByTag(com.example.login.view.AttendanceActivity.Companion.TAG_STUDENT)
        return if (frag is StudentScanFragment) frag.getStudentCount() else 0
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



    // ✅ Function to check device time vs actual internet time
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
                isTimeValid = true  // allow if no internet
                runOnUiThread { onChecked?.invoke() }
            }
        }
    }

    // ✅ Function to show dialog if time mismatch
    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                // Open device date & time settings
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

}
