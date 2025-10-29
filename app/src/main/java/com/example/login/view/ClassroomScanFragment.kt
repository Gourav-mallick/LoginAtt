package com.example.login.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.login.R
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.thread


class ClassroomScanFragment : Fragment() {

    private lateinit var tvSyncStatus: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var btnRefresh: ImageButton

    companion object {
        fun newInstance() = ClassroomScanFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
            = inflater.inflate(R.layout.fragment_classroom_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvSyncStatus = view.findViewById(R.id.tvSyncStatus)
     //   tvInstruction = view.findViewById(R.id.tvInstruction)
    //    btnRefresh = view.findViewById(R.id.btnRefresh)

     //   tvSyncStatus.text = "Tap to send attendance.."


        val tvDate = view.findViewById<TextView>(R.id.tvDate)
        val tvTime = view.findViewById<TextView>(R.id.tvTime)


        // Set current date and time
        val currentDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        tvDate.text = "$currentDate"
        tvTime.text = "$currentTime"


        val prefs = requireContext().getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val tvLastSync = view.findViewById<TextView>(R.id.tvLastSync)

        val lastSync = prefs.getString("last_sync_time", null)
        if (lastSync != null) {
            tvLastSync.text = "Last Sync: $lastSync"
        } else {
            tvLastSync.text = "Last Sync: --"
        }

// Listen for broadcast updates
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val time = intent?.getStringExtra("time") ?: return
                tvLastSync.text = "Last Sync: $time"
            }
        }
        val filter = IntentFilter("SYNC_UPDATE")
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(receiver, filter)
        }


// Optional: show offline hours
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val stored = prefs.getString("last_sync_time", null)
                if (stored != null) {
                    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
                    val last = sdf.parse(stored)
                    val diffHours = ((Date().time - last.time) / (1000 * 60 * 60)).toInt()
                    tvSyncStatus.text = if (diffHours > 0) "Working offline for $diffHours hrs" else ""
                }
                delay(60_000)
            }
        }


        //  tvInstruction.text = "Follow Instruction.."
        // 🔹 Disable back press (both button and gesture)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        requireContext(),
                        "Back is disabled on this screen",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )


        tvSyncStatus.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirm Sync")
                .setMessage("Do you want to send pending attendance to the server?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(requireContext(), SyncAttendanceToServer::class.java)
                    startActivity(intent)


                }
                .setNegativeButton("No", null)
                .show()
        }


    }

/*
    // 🔹 Popup for entering username & password - no use
    private fun showDialogBoxForCredentials() {
        val dialogView = layoutInflater.inflate(R.layout.validate_for_sync_data_to_server, null)
        val edtUserName = dialogView.findViewById<EditText>(R.id.edtUserName)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnSubmit.setOnClickListener {
            val username = edtUserName.text.toString().trim()
            val password = edtPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter both fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (validateUserCredentials(username, password)) {
                dialog.dismiss()
                Toast.makeText(requireContext(), "Credentials verified!", Toast.LENGTH_SHORT).show()

                // ✅ Navigate to SyncActivity
                val intent = Intent(requireContext(), SyncAttendanceToServer::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Invalid username or password", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // 🔹 Simple validation function using SharedPreferences
    private fun validateUserCredentials(username: String, password: String): Boolean {
        val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "abc") // default username for test
        val savedPassword = prefs.getString("password", "1234")  // default password for test

        return username == savedUsername && password == savedPassword
    }

 */


}

