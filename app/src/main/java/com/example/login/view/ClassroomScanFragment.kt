package com.example.login.view

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.login.R
//import com.example.login.utils.DataSyncToServer
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.Button



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
        btnRefresh = view.findViewById(R.id.btnRefresh)

        tvSyncStatus.text = "Sync Status : Please sync device."
      //  tvInstruction.text = "Follow Instruction.."

        btnRefresh.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirm Sync")
                .setMessage("Do you want to send pending attendance to the server?")
                .setPositiveButton("Yes") { _, _ ->
                    // Step 2: Show username/password popup
        //            showDialogBoxForEnterCredential()

                }
                .setNegativeButton("No", null)
                .show()
        }

    }

/*
    private fun showDialogBoxForEnterCredential() {
        val dialogView = layoutInflater.inflate(R.layout.validate_for_sync_data_to_server, null)
        val edtUserName = dialogView.findViewById<EditText>(R.id.edtUserName)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)  // prevent outside touch to dismiss
            .create()

        btnSubmit.setOnClickListener {
            val username = edtUserName.text.toString().trim()
            val password = edtPassword.text.toString().trim()
   //         validateUserCredentialForSynData(username, password)
            dialog.dismiss() // dismiss only after valid submission
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun validateUserCredentialForSynData(username: String, password: String) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "") ?: ""
        val savedPassword = prefs.getString("password", "") ?: ""

        if (username == savedUsername && password == savedPassword) {
            Toast.makeText(requireContext(), "Credentials correct! Syncing...", Toast.LENGTH_SHORT)
                .show()

            // Perform sync
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    DataSyncToServer.syncDataToServer(requireContext())
                    Toast.makeText(
                        requireContext(),
                        "Sync completed successfully",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Sync failed: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        } else {
            Toast.makeText(requireContext(), "Incorrect username or password.", Toast.LENGTH_SHORT)
                .show()
        }
    }



 */


}

