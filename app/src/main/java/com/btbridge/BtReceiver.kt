package com.btbridge

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
        val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
        var scanReceiver: BroadcastReceiver? = null
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

                val result = processCommand(context, cmd, json)
                writeResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                writeResult(error(e.message ?: "Unknown error"))
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun processCommand(context: Context, cmd: String, json: JSONObject): JSONObject {
        return when (cmd) {
            "status" -> getStatus()
            "enable" -> enableBt()
            "disable" -> disableBt()
            "scan", "scan_start" -> scanStart(context)
            "scan_stop" -> scanStop(context)
            "list_devices" -> listDevices()
            "bonded" -> listBonded()
            "pair" -> pair(json.getString("address"))
            "connect" -> connect(json.getString("address"), json.optString("type", "rfcomm"))
            "disconnect" -> disconnect(json.getString("address"))
            "send" -> sendData(json.getString("address"), json.getString("data"))
            "read" -> readData(json.getString("address"))
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

    private fun scanStart(context: Context): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")

        discoveredDevices.clear()

        scanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            discoveredDevices[it.address] = it
                            Log.d(TAG, "Found: ${it.name ?: "Unknown"} [${it.address}]")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(scanReceiver, filter)

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val started = adapter.startDiscovery()
        return ok().put("scanning", started)
    }

    private fun scanStop(context: Context): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")
        adapter.cancelDiscovery()

        scanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            scanReceiver = null
        }

        return ok().put("stopped", true)
    }

    private fun listDevices(): JSONObject {
        val arr = JSONArray()
        discoveredDevices.forEach { (addr, dev) ->
            arr.put(JSONObject().apply {
                put("address", addr)
                put("name", dev.name ?: "Unknown")
                put("bonded", dev.bondState == BluetoothDevice.BOND_BONDED)
            })
        }
        return ok().put("devices", arr).put("count", arr.length())
    }

    private fun listBonded(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")

        val arr = JSONArray()
        adapter.bondedDevices?.forEach { dev ->
            arr.put(JSONObject().apply {
                put("address", dev.address)
                put("name", dev.name ?: "Unknown")
                put("type", deviceTypeToString(dev.type))
            })
        }
        return ok().put("devices", arr).put("count", arr.length())
    }

    private fun pair(address: String): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")

        val device = adapter.getRemoteDevice(address)
        return try {
            val method = device.javaClass.getMethod("createBond")
            val result = method.invoke(device) as Boolean
            ok().put("pairing", result).put("address", address)
        } catch (e: Exception) {
            error("Pair failed: ${e.message}")
        }
    }

    private fun connect(address: String, type: String): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("No Bluetooth adapter")

        val device = adapter.getRemoteDevice(address)
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        if (connectedSockets.containsKey(address)) {
            return ok().put("connected", true).put("address", address)
        }

        val socket = try {
            device.createRfcommSocketToServiceRecord(uuid)
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
            input.read(buffer).let { len ->
                if (len > 0) {
                    ok().put("data", String(buffer, 0, len)).put("bytes", len)
                } else {
                    ok().put("data", "").put("bytes", 0)
                }
            }
        } catch (e: Exception) {
            connectedSockets.remove(address)
            error("Read failed: ${e.message}")
        }
    }

    private fun deviceTypeToString(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        else -> "Unknown"
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
