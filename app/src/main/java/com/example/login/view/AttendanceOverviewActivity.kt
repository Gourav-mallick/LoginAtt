package com.example.login.view

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityAttendanceOverviewBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch

class AttendanceOverviewActivity : ComponentActivity() {

    private lateinit var binding: ActivityAttendanceOverviewBinding
    private lateinit var db: AppDatabase
    private lateinit var selectedClasses: List<String>
    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""

        loadOverviewData()

        binding.btnSubmitAttendance.setOnClickListener {
            val intent = Intent(this, AttendanceActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun loadOverviewData() {
        lifecycleScope.launch {
            val classSummaries = mutableListOf<ClassOverviewData>()

            for (classId in selectedClasses) {
                val students = db.studentsDao().getAllStudents().filter { it.classId == classId }
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
                        className = classId,
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
}

data class ClassOverviewData(
    val className: String,
    val totalStudents: Int,
    val presentCount: Int,
    val absentCount: Int,
  //  val presentStudents: List<String>,
)
