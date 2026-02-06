package com.example.login.repository

import android.content.Context
import android.util.Log
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.*
import com.example.login.utility.TripleDESUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DataSyncRepository(private val context: Context) {

    private val TAG = "DataSyncRepository"

    suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {

        val rParam = "api/v1/StudentEnrollment/GetStudList"

        val studentsList = mutableListOf<Student>()
        val classList = mutableListOf<Class>()

        // 🔹 Split multiple institute IDs
        val instList = instIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (instId in instList) {

            val dataParam =
                "{\"studListParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instId\"}}"

            val response = apiService.getStudents(rParam, dataParam)

            if (response.isSuccessful && response.body() != null) {

                val jsonString = response.body()!!.string()
                val json = JSONObject(jsonString)
                val collection = json.optJSONObject("collection")
                val responseObj = collection?.optJSONObject("response")
                val dataArray = responseObj?.optJSONArray("studentData") ?: JSONArray()

                Log.d(TAG, "INST=$instId → students=${dataArray.length()}")

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)

                    val studentId = obj.optString("studentId", "")
                    val studentName = obj.optString("studentName", "")
                    val classId = obj.optString("classId", "")
                    val classShortName = obj.optString("classShortName", "")

                    // 🔹 Assign institute from loop (IMPORTANT)
                    studentsList.add(
                        Student(
                            studentId,
                            studentName,
                            classId,
                            instId
                        )
                    )
                    Log.d(
                        TAG,
                        "TEACHER_PARSED → id=$studentId, name=$studentName,classid=$classId instId=$instId"
                    )

                    classList.add(Class(classId, classShortName))
                }

            } else {
                Log.e(TAG, "STUDENT_API_FAILED → instId=$instId ${response.errorBody()?.string()}")
            }
        }

        // 🔹 Save merged data once
        db.studentsDao().insertAll(studentsList)
        db.classDao().insertAll(classList)

        Log.d(TAG, "Inserted ${studentsList.size} students and ${classList.size} classes.")

        true
    }

    suspend fun fetchAndSaveTeachers(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {

        val rParam = "api/v1/User/GetUserRegisteredDetails"
        val teachersList = mutableListOf<Teacher>()

        // 🔹 Split multiple institute IDs
        val instList = instIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (instId in instList) {

            val dataParam =
                "{\"userRegParamData\":{\"userType\":\"staff\",\"registrationType\":\"FingerPrint\",\"school_id\":\"$instId\"}}"

            // 🔹 Log API request
            Log.d(TAG, "TEACHER_API_REQUEST → instId=$instId")
            Log.d(TAG, "TEACHER_API_REQUEST → r=$rParam")
            Log.d(TAG, "TEACHER_API_REQUEST → data=$dataParam")

            val response = apiService.getTeachers(rParam, dataParam)

            if (response.isSuccessful && response.body() != null) {

                val jsonString = response.body()!!.string()
                val json = JSONObject(jsonString)

                val dataArray = json.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optJSONArray("userRegisteredData") ?: JSONArray()

                Log.d(TAG, "INST=$instId → teachers=${dataArray.length()}")

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)

                    val staffProfile = obj.optString("staffProfile", "")
                    if (!staffProfile.equals("teacher", ignoreCase = true)) continue

                    val staffId = obj.optString("staffId", "")
                    val staffName = obj.optString("staffName", "")

                    // 🔹 Force assign correct instituteId from loop
                    teachersList.add(
                        Teacher(
                            staffId,
                            staffName,
                            instId
                        )
                    )

                    Log.d(
                        TAG,
                        "TEACHER_PARSED → id=$staffId, name=$staffName, instId=$instId"
                    )
                }

            } else {
                Log.e(TAG, "TEACHER_API_FAILED → instId=$instId ${response.errorBody()?.string()}")
            }
        }

        // 🔹 Save merged data once
        db.teachersDao().insertAll(teachersList)

        Log.d(TAG, "Inserted ${teachersList.size} teachers.")

        true
    }

    suspend fun syncSubjectInstances(
        apiService: ApiService,
        db: AppDatabase
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/CoursePeriod/SubjectInstances"
        val dataParam = "{\"cpParamData\":{\"actionType\":\"markCpAttendance2\"}}"
        val response = apiService.getSubjectInstances(rParam, dataParam)

        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val dataArray = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("subjectInstancesData") ?: JSONArray()

            if (dataArray.length() == 0) {
                Log.w(TAG, "No subject instance data found.")
                return@withContext false
            }

            val coursePeriods = mutableListOf<CoursePeriod>()
            val courses = mutableListOf<Course>()
            val subjects = mutableListOf<Subject>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                subjects.add(Subject(obj.optString("subjectIds"), obj.optString("subjectTitles")))
                courses.add(Course(obj.optString("courseIds"), obj.optString("subjectIds"), obj.optString("courseTitles"), obj.optString("courseTitles")))
                val teacherIdsRaw = obj.optString("teacherIds")  // ",89,109,110,113,246,"
                val teacherList = teacherIdsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                for (teacherId in teacherList) {
                    coursePeriods.add(
                        CoursePeriod(
                            cpId = obj.optString("cpIds"),
                            courseId = obj.optString("courseIds"),
                            classId = obj.optString("classIds"),
                            teacherId = teacherId,   // 🔹 ONE teacher per row
                            mpId = obj.optString("mpId"),
                            mpLongTitle = obj.optString("mpLongTitle")
                        )
                    )
                }
            }

            db.subjectDao().insertAll(subjects)
            db.courseDao().insertAll(courses)
            db.coursePeriodDao().insertAll(coursePeriods)
            Log.d(TAG, "Subjects: ${subjects.size}, Courses: ${courses.size}, CoursePeriods: ${coursePeriods.size}")
            true
        } else {
            Log.e(TAG, "SUBJECT_INSTANCE_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }


    suspend fun fetchAndSaveSchoolPeriods(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val instIdList = instIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            for (instId in instIdList) {

                // 🔹 Get syear dynamically per institute
                val sYear = db.instituteDao().getInstituteYearById(instId) ?: ""

                Log.d("SYNC_PERIOD", "Institute=$instId | AcademicYear=$sYear")

                if (sYear.isBlank()) {
                    Log.w("SYNC_PERIOD", "Skipping instId=$instId (empty academic year)")
                    continue
                }

                val rParam = "api/v1/Att/ManageMarkingGlobalAtt"
                val dataParam = """
            {
              "attParamDataObj":{
                "actionType":"getPeriodDetailsForMarkingGlobalAtt",
                "actionData":{
                  "instId":"$instId",
                  "syear":"$sYear",
                  "attendanceMethod":"periodDayWiseAttendance"
                }
              }
            }
            """.trimIndent()

                // 🔹 Log request
                Log.d("PERIOD_API_REQUEST", "instId=$instId")
                Log.d("PERIOD_API_REQUEST", dataParam)

                val response = apiService.getPeriodDetails(rParam, dataParam)

                if (!response.isSuccessful || response.body() == null) {
                    Log.e("PERIOD_API_FAILED", "instId=$instId | ${response.errorBody()?.string()}")
                    continue
                }

                val respString = response.body()!!.string()
                Log.d("PERIOD_API_RESPONSE", "instId=$instId | $respString")

                val dataArr = JSONObject(respString)
                    .optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optJSONArray("data") ?: JSONArray()

                val periodList = mutableListOf<SchoolPeriod>()

                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.getJSONObject(i)

                    periodList.add(
                        SchoolPeriod(
                            spId = obj.optString("spId"),
                            spTitle = obj.optString("spTitle"),
                            spStartTime = obj.optString("spStartTime"),
                            spEndTime = obj.optString("spEndTime"),
                            spIstTime = obj.optString("spIstTime"),
                            instId = instId     // 🔹 CRITICAL: tag with institute
                        )
                    )
                }

                if (periodList.isNotEmpty()) {
                    db.schoolPeriodDao().insertAll(periodList)
                }

                Log.d(
                    "SYNC_PERIOD",
                    "Saved ${periodList.size} periods for instId=$instId"
                )
            }

            return@withContext true

        } catch (e: Exception) {
            Log.e("SYNC_PERIOD_ERROR", "Exception: ${e.message}", e)
            return@withContext false
        }
    }


    suspend fun fetchDeviceDataToServer(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/Hardware/DeviceUtilityMgmt"
        val dataParam = (context as? com.example.login.view.SelectInstituteActivity)?.getDeviceUtilityQueryParams(context)
            ?: return@withContext false

        val response = apiService.getDeveiceDataToserver(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            Log.d(TAG, "HARDWARE_RESPONSE: $jsonString")

            val hwMgmtData = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONObject("hwMgmtData")

            if (hwMgmtData != null) {
                val cfg = hwMgmtData.optJSONObject("cfg")
                val deconfigstr = cfg?.optString("deconfigstr")
                if (!deconfigstr.isNullOrEmpty()) {
                    val decryptedStr = TripleDESUtility().getDecryptedStr(deconfigstr)
                    Log.d(TAG, "Decrypted Config: $decryptedStr")
                }
            }
            true
        } else {
            Log.e(TAG, "DEVICE_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }



    suspend fun fetchAndSaveStudentSchedulingData(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rParam = "api/v1/Schedule/GetStudList"
            val dataParam =
                "{\"schedulingParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

            val response = apiService.getStudentScheduleList(rParam, dataParam)

            if (!response.isSuccessful || response.body() == null) {
                Log.e(TAG, "SCHEDULE_API_FAILED: ${response.errorBody()?.string()}")
                return@withContext false
            }

            val jsonString = response.body()!!.string()
            Log.d(TAG, "RAW_SCHEDULE_JSON: $jsonString")

            val json = JSONObject(jsonString)
            val dataArray = json
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("studentSchedulingData") ?: JSONArray()

            val scheduleList = mutableListOf<StudentSchedule>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                scheduleList.add(
                    StudentSchedule(
                        scheduleId = obj.optString("scheduleId", ""),
                        studentId = obj.optString("studentId", ""),
                        cpId = obj.optString("cpId", ""),
                        courseId = obj.optString("courseId", ""),
                        scheduleStartDate = obj.optString("scheduleStartDate", ""),
                        scheduleEndDate = obj.optString("scheduleEndDate", ""),
                        syncStatus = "complete"
                    )
                )
            }

            db.studentScheduleDao().insertAll(scheduleList)
            Log.d(TAG, "Saved ${scheduleList.size} student schedule rows")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "SCHEDULE_EXCEPTION: ${e.message}")
            return@withContext false
        }
    }

}
