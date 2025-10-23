package com.example.login.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.Course
import com.example.login.db.entity.Student
import com.example.login.db.entity.Subject
import com.example.login.db.entity.Teacher
import com.example.login.db.entity.Class
import com.example.login.db.entity.CoursePeriod
import com.example.login.db.entity.Session

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


    @Query("""
    SELECT * FROM students 
    WHERE studentId = :id
    AND (
        LOWER(studentName) LIKE '%' || LOWER(:namePart1) || '%' 
        OR LOWER(studentName) LIKE '%' || LOWER(:namePart2) || '%' 
        OR LOWER(studentName) LIKE '%' || LOWER(:namePart3) || '%'
    )
    LIMIT 1
""")
    suspend fun getAndMatchStudentByIdName(
        id: String,
        namePart1: String?,
        namePart2: String?,
        namePart3: String?
    ): Student?


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


    @Query("""
    SELECT * FROM teachers 
    WHERE staffId = :id
    AND (
        LOWER(staffName) LIKE '%' || LOWER(:namePart1) || '%' 
        OR LOWER(staffName) LIKE '%' || LOWER(:namePart2) || '%' 
        OR LOWER(staffName) LIKE '%' || LOWER(:namePart3) || '%'
    )
    LIMIT 1
""")
    suspend fun getAndMatchTeacherByIdName(
        id: String,
        namePart1: String?,
        namePart2: String?,
        namePart3: String?
    ): Teacher?


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


@Dao
interface SessionDao {

    // Insert new session
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)

    // Optional: Get all sessions
    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<Session>

    @Query("UPDATE sessions SET endTime = :endTime WHERE sessionId = :sessionId")
    suspend fun updateSessionEnd(sessionId: String,  endTime: String)

    // Optional: Get current session by ID
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): Session?
}


@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(attendance: Attendance): Long


    @Query("SELECT DISTINCT classId FROM attendance WHERE sessionId = :sessionId")
    suspend fun getDistinctClassIdsForCurrentSession(sessionId: String): List<String>

    @Query("DELETE FROM attendance WHERE sessionId = :sessionId AND classId NOT IN (:selectedClassIds)")
    suspend fun deleteAttendanceNotInClasses(selectedClassIds: List<String>, sessionId: String)


}
