package com.example.login.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Entity(tableName = "students")
@Parcelize
data class Student(
    @PrimaryKey val studentId: String,
    val studentName: String,
    val classId: String,
    val instId: String, // store the institute ID
) : Parcelable



@Entity(tableName = "teachers")
@Parcelize
data class Teacher(
    @PrimaryKey val staffId: String,
    val staffName: String,
    val instId: String // store the institute ID
) : Parcelable


@Entity(tableName = "course_periods")
@Parcelize
data class CoursePeriod(
    @PrimaryKey val cpId: String,
    val courseId: String,
    val classId: String,
    val teacherId: String?,
    val mpId: String?, // term/period reference
    val mpLongTitle: String?,

    ): Parcelable


@Entity(tableName = "courses")
@Parcelize
data class Course(
    @PrimaryKey val courseId: String,
    val subjectId: String,
    val courseTitle: String,
    val courseShortName: String
) : Parcelable


@Entity(tableName = "subjects")
@Parcelize
data class Subject(
    @PrimaryKey val subjectId: String,
    val subjectTitle: String
) : Parcelable

@Entity(tableName = "classes")
@Parcelize
data class Class(
    @PrimaryKey val classId: String,
    val classShortName: String
) : Parcelable




@Entity(tableName = "sessions")
@Parcelize
data class Session(
    @PrimaryKey
    val sessionId: String,
    val classId: String,
    val teacherId: String,
    val subjectId:  String,
  //  val headId: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val instId: String,
    val isMerged: Int,
    val periodId: String,
    val syncStatus:String,
    val isSubmitted: Int = 0
) : Parcelable


@Entity(tableName = "attendance")
@Parcelize
data class Attendance(
    @PrimaryKey
    val atteId: String,
    val instId: String,
    val classId: String,
    val markedAt: String,
    val sessionId: String,
    val status: String,
    val studentId: String,
    val syncStatus: String,
    // ✅ New fields for course/subject/class mapping
    val cpId: String? = null,               // Course Period ID
    val courseId: String? = null,           // Course ID
    val courseTitle: String? = null,        // Full course title
    val courseShortName: String? = null,    // Short name of course
    val subjectId: String? = null,          // Linked subject ID
    val subjectTitle: String? = null,       // Subject title
    val classShortName: String? = null,     // Human-readable class short name
    val mpId: String? = null,               // Master period ID / term ID
    val mpLongTitle: String? = null         // Master period long title
):Parcelable



// 🔹 Data class for joined info (not an @Entity)
data class CourseFullInfo(
    val cpId: String?,
    val courseId: String?,
    val courseTitle: String?,
    val courseShortName: String?,
    val subjectId: String?,
    val subjectTitle: String?,
    val classShortName: String?,
    val mpId: String?,
    val mpLongTitle: String?
)


@Entity(tableName = "ActiveClassCycle")
data class ActiveClassCycle(
    @PrimaryKey val classroomId: String,
    val classroomName: String,
    val teacherId: String?,
    val teacherName: String?,
    val sessionId: String?,
    val startedAtMillis: Long
    // Add presentCount or other fields if needed
)


