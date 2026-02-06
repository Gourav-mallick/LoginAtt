package com.example.login.view


import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityPeriodCourseSelectBinding
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Course
import kotlinx.coroutines.launch



class SubjectSelectActivity : ComponentActivity() {

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

        // 🔹 FIRST read intent
        sessionId = intent.getStringExtra("SESSION_ID") ?: return
        selectedClasses =
            intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()

        // 🔹 Disable back
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@SubjectSelectActivity, "Back disabled", Toast.LENGTH_SHORT).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 🔹 Save state
        getSharedPreferences("APP_STATE", MODE_PRIVATE)
            .edit()
            .putBoolean("IS_IN_PERIOD_SELECT", true)
            .putString("SESSION_ID", sessionId)
            .apply()

        // 🔹 NOW load preselected courses
        lifecycleScope.launch {
            val session = db.sessionDao().getSessionById(sessionId)
            val teacherId = session?.teacherId ?: ""

            val preSelectedCourseIds =
                db.coursePeriodDao().getCourseIdsForTeacherAndClasses(
                    teacherId,
                    selectedClasses
                )

            Log.d("PRESELECT", "Teacher=$teacherId → $preSelectedCourseIds")
            // 🔹 ADD LOGS HERE
            Log.d("DEBUG_SUBJECT", "SessionId = $sessionId")
            Log.d("DEBUG_SUBJECT", "TeacherId = $teacherId")
            Log.d("DEBUG_SUBJECT", "SelectedClasses = $selectedClasses")

            val all = db.coursePeriodDao().getAllCoursePeriods()
            Log.d("DEBUG_DB", "All CoursePeriods = $all")

            val courses = db.courseDao().getAllCourses()

            Log.d("COURSE_TABLE", "All Courses IDs = ${courses.map { it.courseId }}")
            Log.d("PRESELECT", "PreSelected IDs = $preSelectedCourseIds")


            for (p in all) {
                Log.d("DEBUG_DB_ROW",
                    "cpId=${p.cpId} teacher=${p.teacherId} class=${p.classId} course=${p.courseId}")
            }

            val test = db.coursePeriodDao()
                .getCourseIdsForTeacherAndClasses(teacherId, selectedClasses)

            Log.d("DEBUG_TEST_QUERY", "Result = $test")


            loadCourses(preSelectedCourseIds)
        }

        binding.btnContinue.setOnClickListener { handleContinue() }
    }

    /*
    // 🔹 Predefined period dropdown (later can be dynamic)
    private fun setupPeriodDropdown() {
        val periodList = listOf("1", "2", "3", "4", "5", "6")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periodList)
        binding.spinnerPeriod.adapter = adapter
    }
     */

    // 🔹 Load courses from DB
    private fun loadCourses(preSelected: List<String>) {
        lifecycleScope.launch {
            val courses = db.courseDao().getAllCourses()

            // 🔹 Sort → selected first
            val sortedCourses = courses.sortedWith(
                compareByDescending<Course> { preSelected.contains(it.courseId.trim()) }
                    .thenBy { it.courseTitle }   // optional: alphabetical inside group
            )

            val adapter = SubjectSelectAdapter(
                courses = sortedCourses,
                preSelectedCourseIds = preSelected
            ) { selectedIds ->
                selectedCourseIds.clear()
                selectedCourseIds.addAll(selectedIds)
            }

            Log.d("VERIFY_ALL", "PreSelected = $preSelected")

            for (c in courses) {
                val match = preSelected.contains(c.courseId.trim())
                Log.d(
                    "VERIFY_MATCH",
                    "Course=${c.courseId} | Match=$match"
                )
            }


            // ⭐ IMPORTANT — keep Activity list in sync initially
            selectedCourseIds.clear()
            selectedCourseIds.addAll(preSelected)

            binding.recyclerViewCourses.layoutManager =
                LinearLayoutManager(this@SubjectSelectActivity)
            binding.recyclerViewCourses.adapter = adapter
            adapter.notifyDataSetChanged()
        }
    }



    // 🔹 Handle "Continue" button click
    private fun handleContinue() {

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)

            val isNoCourse = selectedCourseIds.isEmpty()

            if (isNoCourse) {
                Toast.makeText(this@SubjectSelectActivity, "Please select course", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {

                // --------------------------------------------------
                // 1. Build FALLBACK (combined selected courses)
                // --------------------------------------------------

                val fallbackDetails = db.courseDao().getCourseDetailsForIds(selectedCourseIds)

                if (fallbackDetails.isEmpty()) {
                    Toast.makeText(this@SubjectSelectActivity, "No course details found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val fallbackCpIds = fallbackDetails.mapNotNull { it.cpId }.distinct().joinToString(",")
                val fallbackCourseIds = fallbackDetails.mapNotNull { it.courseId }.distinct().joinToString(",")
                val fallbackCourseTitles = fallbackDetails.mapNotNull { it.courseTitle }.distinct().joinToString(",")
                val fallbackCourseShortNames = fallbackDetails.mapNotNull { it.courseShortName }.distinct().joinToString(",")
                val fallbackSubjectIds = fallbackDetails.mapNotNull { it.subjectId }.distinct().joinToString(",")
                val fallbackSubjectTitles = fallbackDetails.mapNotNull { it.subjectTitle }.distinct().joinToString(",")
                val fallbackClassShortNames = fallbackDetails.mapNotNull { it.classShortName }.distinct().joinToString(",")
                val fallbackMpIds = fallbackDetails.mapNotNull { it.mpId }.distinct().joinToString(",")
                val fallbackMpLongTitles = fallbackDetails.mapNotNull { it.mpLongTitle }.distinct().joinToString(",")

                // --------------------------------------------------
                // 2. Get all attendance rows for session
                // --------------------------------------------------

                val allAttendance = db.attendanceDao().getAttendanceBySessionId(sessionId)

                // --------------------------------------------------
                // 3. Per-student mapping
                // --------------------------------------------------

                for (att in allAttendance) {

                    val studentId = att.studentId

                    // 🔎 Find matching schedule for this student
                    val schedule = db.studentScheduleDao()
                        .findScheduleForStudentAndCourses(studentId, selectedCourseIds)

                    if (schedule != null) {
                        // ==============================
                        // 🎯 MATCH FOUND → Use student's course
                        // ==============================

                        val courseDetails = db.courseDao()
                            .getCourseDetailsForIds(listOf(schedule.courseId))
                            .firstOrNull()

                        if (courseDetails != null) {

                            db.attendanceDao().updateAttendanceWithCourseDetailsForStudent(
                                sessionId = sessionId,
                                studentId = studentId,
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

                            Log.d(
                                "SCHEDULE_MATCH",
                                "Student=$studentId → Course=${courseDetails.courseId} → Cp=${courseDetails.cpId}"
                            )
                        }

                    } else {
                        // ==============================
                        // ❌ NO MATCH → Use fallback (selected courses)
                        // ==============================

                        db.attendanceDao().updateAttendanceWithCourseDetailsForStudent(
                            sessionId = sessionId,
                            studentId = studentId,
                            cpId = fallbackCpIds,
                            courseId = fallbackCourseIds,
                            courseTitle = fallbackCourseTitles,
                            subjectId = fallbackSubjectIds,
                            courseShortName = fallbackCourseShortNames,
                            subjectTitle = fallbackSubjectTitles,
                            classShortName = fallbackClassShortNames,
                            mpId = fallbackMpIds,
                            mpLongTitle = fallbackMpLongTitles
                        )

                        Log.d(
                            "SCHEDULE_FALLBACK",
                            "Student=$studentId → Used fallback → CpIds=$fallbackCpIds"
                        )
                    }
                }


                // --------------------------------------------------
                // 4. Update session subject (CSV)
                // --------------------------------------------------

                db.sessionDao().updateSessionPeriodAndSubject(
                    sessionId,
                    fallbackCourseIds
                )

                logAllAttendance(sessionId)

                // --------------------------------------------------
                // 5. Clear resume state
                // --------------------------------------------------

                getSharedPreferences("APP_STATE", MODE_PRIVATE)
                    .edit()
                    .remove("IS_IN_PERIOD_SELECT")
                    .remove("SESSION_ID")
                    .apply()

                Toast.makeText(this@SubjectSelectActivity, "Attendance mapped successfully", Toast.LENGTH_SHORT).show()

                // --------------------------------------------------
                // 6. Move to next screen
                // --------------------------------------------------

                val intent = Intent(this@SubjectSelectActivity, AttendanceOverviewActivity::class.java)
                intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                intent.putExtra("SESSION_ID", sessionId)
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SubjectSelectActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }



    private suspend fun logAllAttendance(sessionId: String) {

        val list = db.attendanceDao().getAttendanceBySessionId(sessionId)

        Log.d("ATTENDANCE_DEBUG", "------ TOTAL = ${list.size} ------")

        for (a in list) {
            Log.d(
                "ATTENDANCE_ROW",
                """
            StudentId      = ${a.studentId}
            ClassId        = ${a.classId}
            CpId           = ${a.cpId}
            CourseId       = ${a.courseId}
            CourseTitle    = ${a.courseTitle}
            SubjectId      = ${a.subjectId}
            SubjectTitle   = ${a.subjectTitle}
            TeacherId      = ${a.teacherId}
            MpId           = ${a.mpId}
            SessionId      = ${a.sessionId}
            Status         = ${a.status}
            -----------------------------
            """.trimIndent()
            )
        }
    }

}
