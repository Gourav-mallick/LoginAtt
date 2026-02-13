package com.digitaledu.RfidAttendance2.db.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.digitaledu.RfidAttendance2.db.entity.Attendance
import com.digitaledu.RfidAttendance2.db.entity.Student
import com.digitaledu.RfidAttendance2.db.entity.Teacher
import com.digitaledu.RfidAttendance2.db.entity.Course
import com.digitaledu.RfidAttendance2.db.entity.Subject
import com.digitaledu.RfidAttendance2.db.entity.Class
import com.digitaledu.RfidAttendance2.db.entity.CoursePeriod
import com.digitaledu.RfidAttendance2.db.entity.Session
import com.digitaledu.RfidAttendance2.db.entity.ActiveClassCycle
import com.digitaledu.RfidAttendance2.db.entity.Institute
import com.digitaledu.RfidAttendance2.db.entity.SchoolPeriod
import com.digitaledu.RfidAttendance2.db.entity.StudentSchedule


@Database(entities = [
    Student::class,
    Teacher::class,
    Course::class,
    Subject::class,
    Class::class,
    CoursePeriod::class,
    Session::class,
    Attendance::class,
    ActiveClassCycle::class,
    Institute::class,
    SchoolPeriod::class,
    StudentSchedule::class,

],
    version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun studentsDao(): StudentsDao
    abstract fun teachersDao(): TeachersDao
    abstract fun courseDao(): CourseDao
    abstract fun subjectDao(): SubjectDao
    abstract fun classDao(): ClassDao
    abstract fun coursePeriodDao(): CoursePeriodDao
    abstract fun sessionDao(): SessionDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun activeClassCycleDao(): ActiveClassCycleDao

    abstract fun instituteDao():InstituteDao
    abstract fun schoolPeriodDao(): SchoolPeriodDao
    abstract fun studentScheduleDao(): StudentScheduleDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
