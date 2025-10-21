package com.example.login.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.login.db.entity.Course
import com.example.login.db.entity.Student
import com.example.login.db.entity.Subject
import com.example.login.db.entity.Teacher
import com.example.login.db.entity.Class
import com.example.login.db.entity.CoursePeriod

@Dao
interface StudentsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(students: List<Student>)

    @Query("SELECT * FROM students WHERE instId = :instId")
    suspend fun getStudentsByInstitute(instId: String): List<Student>

    @Query("SELECT * FROM students WHERE studentId = :studentId")
    suspend fun getStudentById(studentId: String): Student

    @Query("SELECT * FROM students ")
    suspend fun getAllStudents(): List<Student>
}


@Dao
interface TeachersDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(teachers: List<Teacher>)

    @Query("SELECT * FROM teachers WHERE instId = :instId")
    suspend fun getTeachersByInstitute(instId: String): List<Teacher>

    @Query("SELECT * FROM teachers WHERE staffId = :teacherId")
    suspend fun getTeacherById(teacherId: String): Teacher?

    @Query("SELECT * FROM teachers")
    suspend fun getAllTeachers(): List<Teacher>
}


@Dao
interface SubjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subjects: List<Subject>)

    @Query("SELECT * FROM subjects")
    suspend fun getAllSubjects(): List<Subject>
}

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<Course>)

    @Query("SELECT * FROM courses")
    suspend fun getAllCourses(): List<Course>
}



@Dao
interface ClassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(classes: List<Class>)

    @Query("SELECT * FROM classes WHERE classId = :classId")
    suspend fun getClassById(classId: String): Class?


    @Query("SELECT * FROM classes")
    suspend fun getAllClasses(): List<Class>
}



@Dao
interface CoursePeriodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(periods: List<CoursePeriod>)

    @Query("SELECT * FROM course_periods")
    suspend fun getAllCoursePeriods(): List<CoursePeriod>
}
