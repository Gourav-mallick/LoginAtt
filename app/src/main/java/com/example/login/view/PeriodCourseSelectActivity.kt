package com.example.login.view


import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityPeriodCourseSelectBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch



class PeriodCourseSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodCourseSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var selectedClasses: List<String>

    private val selectedCourseIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodCourseSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()

        setupPeriodDropdown()
        loadCourses()

        binding.btnContinue.setOnClickListener {
            handleContinue()
        }
        binding.checkboxAddManualCourse.setOnCheckedChangeListener { _, isChecked ->
            binding.inputManualCourseTitle.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

    }

    // 🔹 Predefined period dropdown (later can be dynamic)
    private fun setupPeriodDropdown() {
        val periodList = listOf("1", "2", "3", "4", "5", "6") // TODO: Replace with DB data
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periodList)
        binding.spinnerPeriod.adapter = adapter
    }

    // 🔹 Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch {
            val courses = db.courseDao().getAllCourses()

            val adapter = CourseMultiSelectAdapter(courses) { selectedIds ->
                selectedCourseIds.clear()
                selectedCourseIds.addAll(selectedIds)
            }

            binding.recyclerViewCourses.layoutManager = LinearLayoutManager(this@PeriodCourseSelectActivity)
            binding.recyclerViewCourses.adapter = adapter
        }
    }

    // 🔹 Handle "Continue" button click
    private fun handleContinue() {
        val selectedPeriod = binding.spinnerPeriod.selectedItem?.toString()
        if (selectedPeriod.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a period", Toast.LENGTH_SHORT).show()
            return
        }

        val isManual = binding.checkboxAddManualCourse.isChecked
        val manualCourseName = binding.inputManualCourseTitle.text.toString().trim()


        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@PeriodCourseSelectActivity)
            val isMultiClass = selectedClasses.size > 1


            if (isManual) {
                if (manualCourseName.isEmpty()) {
                    Toast.makeText(this@PeriodCourseSelectActivity, "Please enter manual course name", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 🔹 Create dummy IDs
                val dummyCpId = java.util.UUID.randomUUID().toString().take(4)
                val dummyCourseId = java.util.UUID.randomUUID().toString().take(4)
                val dummySubjectId = java.util.UUID.randomUUID().toString().take(4)

                // 🔹 Update session + attendance with dummy data
                for (classId in selectedClasses) {
                    db.attendanceDao().updateAttendanceWithCourseDetails(
                        sessionId = sessionId,
                        cpId = dummyCpId,
                        courseId = dummyCourseId,
                        courseTitle = manualCourseName,
                        subjectId = dummySubjectId,
                        courseShortName = manualCourseName.take(6).uppercase(),
                        subjectTitle = manualCourseName,
                        classShortName = classId,
                        mpId = "${System.currentTimeMillis()}",
                        mpLongTitle = "Manual Added"
                    )
                }

                db.sessionDao().updateSessionPeriodAndSubject(sessionId, selectedPeriod, dummyCourseId)

                markAbsenteesForAllClasses(
                    db = db,
                    cpId = dummyCpId,
                    courseId = dummyCourseId,
                    courseTitle = manualCourseName,
                    courseShortName = manualCourseName.take(6).uppercase(),
                    subjectId = dummySubjectId,
                    subjectTitle = manualCourseName,
                    classShortName = selectedClasses.joinToString(","),
                    mpId = "${System.currentTimeMillis()}",
                    mpLongTitle = "Manual Added"
                )

                Toast.makeText(this@PeriodCourseSelectActivity, "Manual course added successfully", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val isMultiCourse = selectedCourseIds.size > 1
            val isNoCourse = selectedCourseIds.isEmpty()

            try {
                when {
                    // ✅ CASE 1 / 2C — No course selected → Manual subject instance
                    isNoCourse -> {
                        Toast.makeText(this@PeriodCourseSelectActivity, "Please select or add a course", Toast.LENGTH_SHORT).show()
                    }

                    // ✅ CASE 1B / 2B — Multiple courses selected
                    isMultiCourse -> {
                        val courseDetails = db.courseDao().getCourseDetailsForIds(selectedCourseIds)

                        if (courseDetails.isEmpty()) {
                            Toast.makeText(this@PeriodCourseSelectActivity, "No course details found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val combinedCpIds = courseDetails.mapNotNull { it.cpId }.joinToString(",")
                        val combinedCourseIds = courseDetails.mapNotNull { it.courseId }.joinToString(",")
                        val combinedCourseTitles = courseDetails.mapNotNull { it.courseTitle }.joinToString(",")
                        val combinedCourseShortNames = courseDetails.mapNotNull { it.courseShortName }.joinToString(",")
                        val combinedSubjectIds = courseDetails.mapNotNull { it.subjectId }.joinToString(",")
                        val combinedSubjectTitles = courseDetails.mapNotNull { it.subjectTitle }.joinToString(",")
                        val combinedClassShortNames = courseDetails.mapNotNull { it.classShortName }.distinct().joinToString(",")
                        val combinedMpIds = courseDetails.mapNotNull { it.mpId }.distinct().joinToString(",")
                        val combinedMpLongTitles = courseDetails.mapNotNull { it.mpLongTitle }.distinct().joinToString(",")

                        // 🔹 Apply combined details to all selected classes
                        for (classId in selectedClasses) {
                            db.attendanceDao().updateAttendanceWithCourseDetails(
                                sessionId = sessionId,
                                cpId = combinedCpIds,
                                courseId = combinedCourseIds,
                                courseTitle = combinedCourseTitles,
                                subjectId = combinedSubjectIds,
                                courseShortName = combinedCourseShortNames,
                                subjectTitle = combinedSubjectTitles,
                                classShortName = combinedClassShortNames,
                                mpId = combinedMpIds,
                                mpLongTitle = combinedMpLongTitles
                            )
                        }

                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, selectedPeriod, combinedCourseIds)
                        markAbsenteesForAllClasses(
                            db = db,
                            cpId = combinedCpIds,
                            courseId = combinedCourseIds,
                            courseTitle = combinedCourseTitles,
                            courseShortName = combinedCourseShortNames,
                            subjectId = combinedSubjectIds,
                            subjectTitle = combinedSubjectTitles,
                            classShortName = combinedClassShortNames,
                            mpId = combinedMpIds,
                            mpLongTitle = combinedMpLongTitles
                        )


                        Toast.makeText(this@PeriodCourseSelectActivity, "Multiple courses applied successfully", Toast.LENGTH_SHORT).show()
                    }

                    // CASE 1A / 2A — Single course selected
                    else -> {
                        val courseId = selectedCourseIds.first()
                        val courseDetails = db.courseDao().getCourseDetailsForIds(listOf(courseId)).firstOrNull()

                        if (courseDetails == null) {
                            Toast.makeText(this@PeriodCourseSelectActivity, "Course details not found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        for (classId in selectedClasses) {
                            db.attendanceDao().updateAttendanceWithCourseDetails(
                                sessionId = sessionId,
                                cpId = courseDetails.cpId,
                                courseId = courseDetails.courseId,
                                courseTitle = courseDetails.courseTitle,
                                subjectId = courseDetails.subjectId,
                                courseShortName = courseDetails.courseShortName,
                                subjectTitle = courseDetails.subjectTitle,
                                classShortName = courseDetails.classShortName,
                                mpId = courseDetails.mpId,
                                mpLongTitle = courseDetails.mpLongTitle
                            )
                        }

                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, selectedPeriod, courseId)
                        markAbsenteesForAllClasses(
                            db = db,
                            cpId = courseDetails.cpId,
                            courseId = courseDetails.courseId,
                            courseTitle = courseDetails.courseTitle,
                            courseShortName = courseDetails.courseShortName,
                            subjectId = courseDetails.subjectId,
                            subjectTitle = courseDetails.subjectTitle,
                            classShortName = courseDetails.classShortName,
                            mpId = courseDetails.mpId,
                            mpLongTitle = courseDetails.mpLongTitle
                        )


                        Toast.makeText(this@PeriodCourseSelectActivity, "Single course applied successfully", Toast.LENGTH_SHORT).show()
                    }
                }

                // ✅ Optional: Log final attendance
                val allAttendance = db.attendanceDao().getAllAttendance()
                for (record in allAttendance) {
                    Log.d(
                        "AttendanceLog",
                        "Student: ${record.studentId}, Class: ${record.classId}, Status: ${record.status}, Courses: ${record.courseId}, Session: ${record.sessionId}"
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@PeriodCourseSelectActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


//Handle Absent Student Attandance
// ✅ Handle Absent Student Attendance with correct course info
private suspend fun markAbsenteesForAllClasses(
    db: AppDatabase,
    cpId: String? = null,
    courseId: String? = null,
    courseTitle: String? = null,
    courseShortName: String? = null,
    subjectId: String? = null,
    subjectTitle: String? = null,
    classShortName: String? = null,
    mpId: String? = null,
    mpLongTitle: String? = null
) {
    for (classId in selectedClasses) {
        val allStudents = db.studentsDao().getAllStudents().filter { it.classId == classId }
        val presentRecords = db.attendanceDao().getAttendancesForClass(sessionId, classId)
        val presentStudentIds = presentRecords.map { it.studentId }
        val absentStudents = allStudents.filterNot { it.studentId in presentStudentIds }

        for (student in absentStudents) {
            val absentRecord = com.example.login.db.entity.Attendance(
                atteId = java.util.UUID.randomUUID().toString(),
                sessionId = sessionId,
                studentId = student.studentId,
                classId = classId,
                status = "A", // Absent
                markedAt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date()),
                syncStatus = "pending",
                instId = student.instId,
                cpId = cpId,
                courseId = courseId,
                courseTitle = courseTitle,
                courseShortName = courseShortName,
                subjectId = subjectId,
                subjectTitle = subjectTitle,
                classShortName = classShortName,
                mpId = mpId,
                mpLongTitle = mpLongTitle
            )
            db.attendanceDao().insertAttendance(absentRecord)
        }

        Log.d("ABSENT_MARK", "Class $classId → ${absentStudents.size} absentees marked with course data.")
    }
}

}
