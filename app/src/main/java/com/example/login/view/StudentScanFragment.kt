package com.example.login.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.login.R
import com.example.login.db.entity.Student


class StudentScanFragment : Fragment() {

    private lateinit var tvPresentCount: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvTeacherName: TextView
    private lateinit var tvLastStudent: TextView

    private var presentCount = 0

    companion object {
        private const val ARG_TEACHER = "arg_teacher"

        fun newInstance(teacherName: String) = StudentScanFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TEACHER, teacherName)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_student_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvPresentCount = view.findViewById(R.id.tvPresentCount)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        tvTeacherName = view.findViewById(R.id.tvTeacherName)
        tvLastStudent = view.findViewById(R.id.tvLastStudent)

        tvInstruction.text = "Tap student card to mark present."

        val teacher = arguments?.getString(ARG_TEACHER) ?: "-"
        tvTeacherName.text = "Teacher: $teacher"

        updatePresentCount()
    }

    /**
     * Call this method when a student card is scanned.
     * Logic same as before: increment count, update last scanned, update instruction
     */
    fun addStudent(student: Student): Boolean {
        // Optional: prevent duplicate student scans
        // You can implement a Set or DB check if needed
        presentCount++
        tvPresentCount.text = "Present Students: $presentCount"
        tvLastStudent.text = " ${student.studentName} - Present"
        updateInstruction("Tap student card / Other Card")
        return true
    }

    fun updateInstruction(text: String) {
        tvInstruction.text = text
    }

    fun resetAttendance() {
        presentCount = 0
        tvPresentCount.text = "Present Students: 0"
        tvLastStudent.text = "-"
        tvInstruction.text = "Scan student/other card "
    }

    private fun updatePresentCount() {
        tvPresentCount.text = "Present Students: $presentCount"
    }
    fun getStudentCount(): Int {
        return presentCount
    }
}
