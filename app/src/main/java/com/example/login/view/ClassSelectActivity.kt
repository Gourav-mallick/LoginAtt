package com.example.login.view

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityClassSelectBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch

class ClassSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityClassSelectBinding
    private lateinit var db: AppDatabase
    private val selectedClassIds = mutableListOf<String>()
    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return

        lifecycleScope.launch {
            val allClasses = db.classDao().getAllClasses()

            // 🔹 Find which classes have attendance in this session
            val preSelected = db.attendanceDao().getDistinctClassIdsForCurrentSession(sessionId)

            val adapter = ClassSelectAdapter(allClasses, preSelected) { selectedIds ->
                selectedClassIds.clear()
                selectedClassIds.addAll(selectedIds)
            }

            binding.recyclerViewClasses.layoutManager = LinearLayoutManager(this@ClassSelectActivity)
            binding.recyclerViewClasses.adapter = adapter
        }

        binding.btnContinue.setOnClickListener {
            if (selectedClassIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one class", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // 🔹 Remove attendance for unselected classes
                db.attendanceDao().deleteAttendanceNotInClasses(selectedClassIds, sessionId)

                Toast.makeText(this@ClassSelectActivity, "Classes updated", Toast.LENGTH_SHORT).show()
/*
                // 🔹 Navigate to next screen
                val intent = Intent(this@ClassSelectActivity, PeriodCourseSelectActivity::class.java)
                intent.putExtra("SESSION_ID", sessionId)
                intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClassIds))
                startActivity(intent)
                finish()

 */
            }
        }
    }
}
