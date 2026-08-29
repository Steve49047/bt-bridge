package com.btbridge

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BtReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BtBridge"
        const val SHARED_DIR = "/sdcard/bt-bridge"
        const val CMD_FILE = "$SHARED_DIR/cmd.json"
        const val RESULT_FILE = "$SHARED_DIR/result.json"
        val connectedSockets = ConcurrentHashMap<String, BluetoothSocket>()
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

                val result = processCommand(cmd, json)
                writeResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                writeResult(error(e.message ?: "Unknown error"))
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun processCommand(cmd: String, json: JSONObject): JSONObject {
        return when (cmd) {
            "status" -> getStatus()
            "enable" -> enableBt()
            "disable" -> disableBt()
            "connect" -> connect(json.getString("address"))
            "disconnect" -> disconnect(json.getString("address"))
            "send" -> sendData(json.getString("address"), json.getString("data"))
            "read" -> readData(json.getString("address"))
            "bonded" -> listBonded()
            "list_devices" -> ok().put("devices", JSONArray()).put("count", 0).put("note", "Use app UI to scan")
            "scan", "scan_start", "scan_stop" -> ok().put("note", "Use app UI to scan")
            else -> error("Unknown command: $cmd")
        }
    }

    private fun getStatus(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (adapter != null) {
            ok().put("enabled", adapter.isEnabled)
                .put("name", adapter.name ?: "Unknown")
                .put("address", adapter.address ?: "Unknown")
        } else {
            error("No Bluetooth adapter")
        }
    }

    private fun enableBt(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            adapter.enable()
        }
        Thread.sleep(500)
        return ok().put("enabled", adapter.isEnabled)
    }

    private fun disableBt(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")
        if (adapter.isEnabled) {
            @Suppress("DEPRECATION")
            adapter.disable()
        }
        Thread.sleep(500)
        return ok().put("enabled", adapter.isEnabled)
    }

    private fun listBonded(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")
        val arr = JSONArray()
        adapter.bondedDevices?.forEach { dev ->
            arr.put(JSONObject().apply {
                put("address", dev.address)
                put("name", dev.name ?: "Unknown")
            })
        }
        return ok().put("devices", arr).put("count", arr.length())
    }

    private fun connect(address: String): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")

        val device = adapter.getRemoteDevice(address)

        if (connectedSockets.containsKey(address)) {
            return ok().put("connected", true).put("address", address)
        }

        val socket = try {
            device.createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            )
        } catch (e: Exception) {
            return error("Socket creation failed: ${e.message}")
        }

        return try {
            adapter.cancelDiscovery()
            socket.connect()
            connectedSockets[address] = socket
            ok().put("connected", true).put("address", address)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            error("Connect failed: ${e.message}")
        }
    }

    private fun disconnect(address: String): JSONObject {
        val socket = connectedSockets.remove(address)
        return if (socket != null) {
            try { socket.close() } catch (_: Exception) {}
            ok().put("disconnected", true).put("address", address)
        } else {
            error("Not connected to $address")
        }
    }

    private fun sendData(address: String, data: String): JSONObject {
        val socket = connectedSockets[address]
            ?: return error("Not connected to $address")
        return try {
            socket.outputStream.write(data.toByteArray())
            socket.outputStream.flush()
            ok().put("sent", true).put("bytes", data.toByteArray().size)
        } catch (e: Exception) {
            connectedSockets.remove(address)
            error("Send failed: ${e.message}")
        }
    }

    private fun readData(address: String): JSONObject {
        val socket = connectedSockets[address]
            ?: return error("Not connected to $address")
        return try {
            val input = socket.inputStream
            val buffer = ByteArray(4096)
            val len = input.read(buffer)
            if (len > 0) {
                ok().put("data", String(buffer, 0, len)).put("bytes", len)
            } else {
                ok().put("data", "").put("bytes", 0)
            }
        } catch (e: Exception) {
            connectedSockets.remove(address)
            error("Read failed: ${e.message}")
        }
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun error(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun writeResult(json: JSONObject) {
        try {
            File(SHARED_DIR).mkdirs()
            File(RESULT_FILE).writeText(json.toString())
            Log.d(TAG, "Result: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result: ${e.message}")
        }
    }
}
