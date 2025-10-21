package com.example.login.view

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.login.R
import android.app.AlertDialog
import android.provider.Settings
import java.net.URL
import java.util.*
import kotlin.concurrent.thread


class AttendanceActivity : AppCompatActivity() {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private val TAG = "NFC_DEBUG"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        checkDeviceTime()
    }

    override fun onResume() {
        super.onResume()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return

        // ✅ Get NFC Tag UID
        val tagId = tag.id.joinToString(":") { String.format("%02X", it) }
        Log.d(TAG, "Tag UID: $tagId")
        Toast.makeText(this, "Tag ID: $tagId", Toast.LENGTH_SHORT).show()

        readCustomCardData(tag)
    }

    private fun readCustomCardData(tag: Tag) {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val keyHex = prefs.getString("cpass", null)

        if (keyHex.isNullOrEmpty()) {
            Toast.makeText(this, "Missing authentication key (cpass)!", Toast.LENGTH_LONG).show()
            Log.e(TAG, "No key found in SharedPreferences.")
            return
        }

        val keyBytes = try {
            hexStringToByteArray(keyHex)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid hex key format!", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Key conversion failed: ${e.message}")
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            Toast.makeText(this, "Not a MIFARE Classic card", Toast.LENGTH_LONG).show()
            Log.e(TAG, "MifareClassic not supported on this tag")
            return
        }

        try {
            mifare.connect()
            val sectorIndex = 0
            val auth = mifare.authenticateSectorWithKeyA(sectorIndex, keyBytes)
            if (!auth) {
                Toast.makeText(this, "Authentication failed!", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Authentication failed.")
                return
            }

            val firstBlock = mifare.sectorToBlock(sectorIndex)
            val blockCount = mifare.getBlockCountInSector(sectorIndex)
            Log.d(TAG, "Reading sector $sectorIndex -> blocks [$firstBlock .. ${firstBlock + blockCount}]")

            for (i in 0 until blockCount) {
                val blockIndex = firstBlock + i
                if (blockIndex == 0 || (blockIndex + 1) % 5 == 0) continue

                val data = mifare.readBlock(blockIndex)
                val rawText = String(data, Charsets.ISO_8859_1).replace("\u0000", "").trim()
                Log.d(TAG, "Block Raw text[$blockIndex] : $rawText")

                // 🔹 Block 1 → Name
                if (blockIndex == firstBlock + 1) {
                    Toast.makeText(this, "Name: $rawText", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Name: $rawText")
                }

                
// 🔹 Block 2 → Facility + Type + ID (handle ASCII type bytes and hex-first-nibble like "C080")
                if (blockIndex == firstBlock + 2) {
                    val facilityCode = data.sliceArray(0..1).joinToString("") { "%02X".format(it) }

                    // full hex string of bytes 2..4, e.g. "0041FF" or "C08000"
                    val idTypeHex = data.sliceArray(2..4).joinToString("") { "%02X".format(it) }
                    Log.d(TAG, "Block2 raw bytes hex: $idTypeHex")

                    // 1) If the first hex char is A/B/C -> treat that as Type and rest as ID (e.g. "C080" -> type C, id 80)
                    val firstChar = idTypeHex.firstOrNull()?.uppercaseChar()
                    var typeChar: String? = null
                    var idValue = ""

                    if (firstChar != null && (firstChar == 'A' || firstChar == 'B' || firstChar == 'C')) {
                        typeChar = firstChar.toString()
                        idValue = idTypeHex.substring(1).trimStart('0').ifEmpty { "0" }
                    } else {
                        // 2) Fallback: look for ASCII bytes 0x41/0x42/0x43 among the raw bytes,
                        //    and treat other non-zero bytes as ID bytes
                        val slice = data.sliceArray(2..4)
                        val idBytes = ArrayList<Byte>()
                        for (b in slice) {
                            when (b.toInt() and 0xFF) {
                                0x41 -> typeChar = "A"
                                0x42 -> typeChar = "B"
                                0x43 -> typeChar = "C"
                                0x00 -> { /* skip padding */ }
                                else -> idBytes.add(b)
                            }
                        }
                        idValue = if (idBytes.isEmpty()) "" else idBytes.joinToString("") { "%02X".format(it) }.trimStart('0').ifEmpty { "0" }
                    }

                    if (typeChar == null) typeChar = "Unknown"

                    Log.d(TAG, "Parsed -> Facility: $facilityCode | Type: $typeChar | ID: $idValue")
                    Toast.makeText(
                        this,
                        "Facility: $facilityCode\nType: $typeChar\nID: $idValue",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reading card: ${e.message}", e)
            Toast.makeText(this, "Error reading card: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try {
                mifare.close()
                Log.d(TAG, "Card connection closed.")
            } catch (_: Exception) {
            }
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        require(len % 2 == 0) { "Hex string must have even length" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }



    // ✅ Function to check device time vs actual internet time
    private fun checkDeviceTime() {
        thread {
            try {
                // Get network time from Google server
                val connection = URL("https://google.com").openConnection()
                connection.connect()
                val networkTime = Date(connection.date)
                val deviceTime = Date(System.currentTimeMillis())

                // Allow small tolerance (2 minutes)
                val timeDifference = kotlin.math.abs(deviceTime.time - networkTime.time)
                val timeDiffInMinutes = timeDifference / (1000 * 60)

                Log.d("TIME_CHECK", "Device Time: $deviceTime")
                Log.d("TIME_CHECK", "Network Time: $networkTime")
                Log.d("TIME_CHECK", "Difference (min): $timeDiffInMinutes")

                if (timeDiffInMinutes > 2) {
                    runOnUiThread {
                        showTimeMismatchDialog()
                    }
                }

            } catch (e: Exception) {
                Log.e("TIME_CHECK", "Error checking time: ${e.message}")
            }
        }
    }

    // ✅ Function to show dialog if time mismatch
    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                // Open device date & time settings
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

}
