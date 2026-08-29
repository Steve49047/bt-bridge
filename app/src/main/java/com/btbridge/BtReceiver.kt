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
        Log.i(TAG, "BroadcastReceiver onReceive: action=${intent.action}")

        if (intent.action != "com.btbridge.CMD") return

        val pending = goAsync()
        Thread {
            try {
                val dir = File(SHARED_DIR)
                dir.mkdirs()

                val cmdFile = File(CMD_FILE)
                if (!cmdFile.exists()) {
                    Log.w(TAG, "No cmd.json found")
                    writeResult(error("No command file"))
                    return@Thread
                }

                val cmdText = cmdFile.readText()
                Log.i(TAG, "Command: $cmdText")
                cmdFile.delete()

                val json = JSONObject(cmdText)
                val cmd = json.getString("cmd")

                val result = processCommand(cmd, json)
                writeResult(result)
                Log.i(TAG, "Result: $result")
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
            "scan", "scan_start", "scan_stop", "list_devices" ->
                ok().put("note", "请在 APP 内扫描")
            else -> error("未知命令: $cmd")
        }
    }

    private fun getStatus(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (adapter != null) {
            ok().put("enabled", adapter.isEnabled)
                .put("name", adapter.name ?: "Unknown")
                .put("address", adapter.address ?: "Unknown")
        } else {
            error("无蓝牙适配器")
        }
    }

    private fun enableBt(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            adapter.enable()
        }
        Thread.sleep(500)
        return ok().put("enabled", adapter.isEnabled)
    }

    private fun disableBt(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")
        if (adapter.isEnabled) {
            @Suppress("DEPRECATION")
            adapter.disable()
        }
        Thread.sleep(500)
        return ok().put("enabled", adapter.isEnabled)
    }

    private fun listBonded(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")
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
            ?: return error("无蓝牙适配器")

        if (connectedSockets.containsKey(address)) {
            return ok().put("connected", true).put("address", address)
        }

        val device = adapter.getRemoteDevice(address)
        val socket = try {
            device.createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            )
        } catch (e: Exception) {
            return error("创建 Socket 失败: ${e.message}")
        }

        return try {
            adapter.cancelDiscovery()
            socket.connect()
            connectedSockets[address] = socket
            ok().put("connected", true).put("address", address)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            error("连接失败: ${e.message}")
        }
    }

    private fun disconnect(address: String): JSONObject {
        val socket = connectedSockets.remove(address)
        return if (socket != null) {
            try { socket.close() } catch (_: Exception) {}
            ok().put("disconnected", true).put("address", address)
        } else {
            error("未连接到 $address")
        }
    }

    private fun sendData(address: String, data: String): JSONObject {
        val socket = connectedSockets[address]
            ?: return error("未连接到 $address")
        return try {
            socket.outputStream.write(data.toByteArray())
            socket.outputStream.flush()
            ok().put("sent", true).put("bytes", data.toByteArray().size)
        } catch (e: Exception) {
            connectedSockets.remove(address)
            error("发送失败: ${e.message}")
        }
    }

    private fun readData(address: String): JSONObject {
        val socket = connectedSockets[address]
            ?: return error("未连接到 $address")
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
            error("读取失败: ${e.message}")
        }
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun error(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun writeResult(json: JSONObject) {
        try {
            File(SHARED_DIR).mkdirs()
            File(RESULT_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "写入结果失败: ${e.message}")
        }
    }
}
