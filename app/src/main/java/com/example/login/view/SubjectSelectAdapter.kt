package com.example.login.view

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import com.example.login.databinding.ItemCourseCheckboxBinding
import com.example.login.db.entity.Course

class SubjectSelectAdapter(
    private val courses: List<Course>,
    preSelectedCourseIds: List<String>,
    private val onSelectionChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<SubjectSelectAdapter.CourseViewHolder>() {

    private val selectedCourses = mutableSetOf<String>()
    private var originalList: List<Course> = courses.toList()
    private var filteredList: MutableList<Course> = courses.toMutableList()


//    init {
//        selectedCourses.addAll(preSelectedCourseIds.map { it.trim() })
//        Log.d("ADAPTER_INIT", "PreSelected = $selectedCourses")
//
//        Log.d("ADAPTER_LIST", "Courses size = ${courses}")
//        Log.d("ADAPTER_LIST", "Course IDs = ${courses.map { it.courseId }}")
//        // notify activity initial selection
//        onSelectionChanged(selectedCourses.toList())
//    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val binding = ItemCourseCheckboxBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CourseViewHolder(binding)
    }

//    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
//
//        val course = filteredList[position]
//        Log.d("RAW_COURSE", course.toString())
//
//        holder.binding.checkboxCourse.setOnCheckedChangeListener(null)
//
//        val isSelected = selectedCourses.any {
//            it.trim() == course.courseId.trim()
//        }
//
//        Log.d(
//            "CHECK_MATCH",
//            "Bind Course=${course.courseId} | Selected=$selectedCourses | isChecked=$isSelected"
//        )
//
//        holder.binding.checkboxCourse.isChecked = isSelected
//
//        holder.binding.checkboxCourse.text =
//            "${course.courseTitle} (${course.courseShortName})"
//
//        holder.binding.checkboxCourse.setOnCheckedChangeListener { _, isChecked ->
//
//            if (isChecked) {
//                selectedCourses.add(course.courseId.trim())
//            } else {
//                selectedCourses.remove(course.courseId.trim())
//            }
//
//            Log.d("CHECK_CLICK", "Now Selected = $selectedCourses")
//
//            onSelectionChanged(selectedCourses.toList())
//        }
//    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {

        val course = filteredList[position]

        holder.binding.checkboxCourse.setOnCheckedChangeListener(null)

        // Always unchecked initially
        holder.binding.checkboxCourse.isChecked =
            selectedCourses.contains(course.courseId.trim())

        holder.binding.checkboxCourse.text =
            "${course.courseTitle} (${course.courseShortName})"

        holder.binding.checkboxCourse.setOnCheckedChangeListener { _, isChecked ->

            if (isChecked) {
                selectedCourses.add(course.courseId.trim())
            } else {
                selectedCourses.remove(course.courseId.trim())
            }

            onSelectionChanged(selectedCourses.toList())
        }
    }

    override fun getItemCount(): Int = filteredList.size

    inner class CourseViewHolder(val binding: ItemCourseCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)


    fun filter(query: String) {
        val q = query.lowercase().trim()

        filteredList = if (q.isEmpty()) {
            originalList.toMutableList()
        } else {
            originalList.filter {
                it.courseTitle.lowercase().contains(q) ||
                        it.courseShortName.lowercase().contains(q) ||
                        it.courseId.lowercase().contains(q)
            }.toMutableList()
        }

        notifyDataSetChanged()
    }

}


