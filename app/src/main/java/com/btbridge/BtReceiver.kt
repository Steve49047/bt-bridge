package com.btbridge

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BtReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BtBridge"
        const val SHARED_DIR = "/sdcard/bt-bridge"
        const val CMD_FILE = "$SHARED_DIR/cmd.json"
        const val RESULT_FILE = "$SHARED_DIR/result.json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.btbridge.CMD") return

        val pending = goAsync()
        Thread {
            try {
                File(SHARED_DIR).mkdirs()
                val cmdFile = File(CMD_FILE)
                if (!cmdFile.exists()) {
                    writeResult(error("No command file"))
                    return@Thread
                }

                val json = JSONObject(cmdFile.readText())
                cmdFile.delete()

                val cmd = json.getString("cmd")
                Log.i(TAG, "Received command: $cmd")

                // Forward to service for processing
                val svcIntent = Intent(context, BtService::class.java).apply {
                    putExtra("cmd", cmd)
                    putExtra("json", json.toString())
                }
                context.startForegroundService(svcIntent)

                // Wait for service to write result
                var wait = 0
                val maxWait = if (cmd == "scan") 250 else 150
                while (wait < maxWait) {
                    if (File(RESULT_FILE).exists()) {
                        break
                    }
                    Thread.sleep(100)
                    wait++
                }

                if (File(RESULT_FILE).exists()) {
                    // Result already written by service
                } else {
                    writeResult(error("Timeout"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                writeResult(error(e.message ?: "Unknown error"))
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun error(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun writeResult(json: JSONObject) {
        try {
            File(SHARED_DIR).mkdirs()
            File(RESULT_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result: ${e.message}")
        }
    }
}
