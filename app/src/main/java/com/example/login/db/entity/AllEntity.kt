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
    val syncStatus: String
):Parcelable




