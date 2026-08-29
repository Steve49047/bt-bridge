package com.btbridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BtReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BtBridge"
        const val SHARED_DIR = "/sdcard/bt-bridge"
        const val CMD_FILE = "$SHARED_DIR/cmd.json"
        const val RESULT_FILE = "$SHARED_DIR/result.json"
        val classicSockets = ConcurrentHashMap<String, BluetoothSocket>()
        val bleGattMap = ConcurrentHashMap<String, BluetoothGatt>()
        val bleTxCharMap = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
        val bleRxCharMap = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
        val bleRecvBuffer = ConcurrentHashMap<String, StringBuilder>()
        val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
            // Classic Bluetooth
            "connect" -> connectClassic(json.getString("address"))
            "disconnect" -> disconnect(json.getString("address"))
            "send" -> sendClassicData(json.getString("address"), json.getString("data"))
            "read" -> readClassicData(json.getString("address"))
            "bonded" -> listBonded()
            // BLE
            "ble_connect" -> connectBle(json.getString("address"))
            "ble_disconnect" -> disconnectBle(json.getString("address"))
            "ble_send" -> sendBleData(json.getString("address"), json.getString("data"))
            "ble_read" -> readBleData(json.getString("address"))
            "ble_scan" -> bleScan(json.optInt("timeout", 5))
            // List
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
                .put("classic_connections", classicSockets.size)
                .put("ble_connections", bleGattMap.size)
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
                put("type", when (dev.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                    BluetoothDevice.DEVICE_TYPE_LE -> "ble"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                    else -> "unknown"
                })
            })
        }
        return ok().put("devices", arr).put("count", arr.length())
    }

    // ========== Classic Bluetooth ==========

    private fun connectClassic(address: String): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")

        if (classicSockets.containsKey(address)) {
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
            classicSockets[address] = socket
            ok().put("connected", true).put("address", address)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            error("连接失败: ${e.message}")
        }
    }

    private fun sendClassicData(address: String, data: String): JSONObject {
        val socket = classicSockets[address]
            ?: return error("未连接到 $address")
        return try {
            socket.outputStream.write(data.toByteArray())
            socket.outputStream.flush()
            ok().put("sent", true).put("bytes", data.toByteArray().size)
        } catch (e: Exception) {
            classicSockets.remove(address)
            error("发送失败: ${e.message}")
        }
    }

    private fun readClassicData(address: String): JSONObject {
        val socket = classicSockets[address]
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
            classicSockets.remove(address)
            error("读取失败: ${e.message}")
        }
    }

    // ========== BLE ==========

    private fun bleScan(timeoutSec: Int): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")
        val scanner = adapter.bluetoothLeScanner
            ?: return error("BLE扫描器不可用")

        val results = mutableListOf<ScanResult>()
        val latch = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (results.none { it.device.address == result.device.address }) {
                    results.add(result)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }
            override fun onBatchScanResults(resultsList: MutableList<ScanResult>?) {
                resultsList?.let { list ->
                    list.forEach { r ->
                        if (results.none { it.device.address == r.device.address }) {
                            results.add(r)
                        }
                    }
                }
            }
        }

        try {
            // Scan with NUS filter
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(filters, settings, callback)

            // Also do unfiltered scan to catch all BLE devices
            scanner.startScan(callback)

            Thread.sleep(timeoutSec * 1000L)
            scanner.stopScan(callback)
        } catch (e: Exception) {
            return error("扫描失败: ${e.message}")
        }

        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().apply {
                put("address", r.device.address)
                put("name", r.device.name ?: "Unknown")
                put("rssi", r.rssi)
                put("has_nus", r.scanRecord?.serviceUuids?.any {
                    it.uuid == NUS_SERVICE_UUID
                } ?: false)
            })
        }
        return ok().put("devices", arr).put("count", arr.length())
    }

    private fun connectBle(address: String): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return error("无蓝牙适配器")

        if (bleGattMap.containsKey(address)) {
            return ok().put("connected", true).put("address", address)
        }

        val device = adapter.getRemoteDevice(address)
        val latch = CountDownLatch(1)
        var connectResult = ""

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectResult = "connected"
                    gatt.discoverServices()
                } else {
                    connectResult = "disconnected:$status"
                    latch.countDown()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectResult = "service_discovery_failed:$status"
                    latch.countDown()
                    return
                }

                // Try NUS first
                val nusService = gatt.getService(NUS_SERVICE_UUID)
                if (nusService != null) {
                    val txChar = nusService.getCharacteristic(NUS_TX_UUID)
                    val rxChar = nusService.getCharacteristic(NUS_RX_UUID)

                    if (txChar != null && rxChar != null) {
                        bleTxCharMap[address] = txChar
                        bleRxCharMap[address] = rxChar
                        bleGattMap[address] = gatt
                        bleRecvBuffer[address] = StringBuilder()

                        // Enable notifications
                        gatt.setCharacteristicNotification(txChar, true)
                        val descriptor = txChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }

                        connectResult = "nus_connected"
                        latch.countDown()
                        return
                    }
                }

                // Fallback: find any notify characteristic
                for (svc in gatt.services) {
                    for (ch in svc.characteristics) {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            bleTxCharMap[address] = ch
                            bleGattMap[address] = gatt
                            bleRecvBuffer[address] = StringBuilder()

                            gatt.setCharacteristicNotification(ch, true)
                            val descriptor = ch.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }

                            connectResult = "connected:${svc.uuid}"
                            latch.countDown()
                            return
                        }
                    }
                }

                connectResult = "no_usable_service"
                latch.countDown()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                bleRecvBuffer[address]?.append(String(value))
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    bleRecvBuffer[address]?.append(String(characteristic.value))
                }
            }
        }

        try {
            device.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return error("BLE连接异常: ${e.message}")
        }

        return when {
            connectResult.startsWith("nus_connected") ->
                ok().put("connected", true).put("address", address).put("service", "NUS")
            connectResult.startsWith("connected") ->
                ok().put("connected", true).put("address", address).put("service", connectResult)
            connectResult == "no_usable_service" ->
                error("未找到可用的BLE服务")
            else ->
                error("BLE连接失败: $connectResult")
        }
    }

    private fun sendBleData(address: String, data: String): JSONObject {
        val gatt = bleGattMap[address] ?: return error("未BLE连接到 $address")
        val rxChar = bleRxCharMap[address]
            ?: return error("未找到写入特征")

        return try {
            rxChar.value = data.toByteArray()
            gatt.writeCharacteristic(rxChar)
            ok().put("sent", true).put("bytes", data.toByteArray().size)
        } catch (e: Exception) {
            error("发送失败: ${e.message}")
        }
    }

    private fun readBleData(address: String): JSONObject {
        val buffer = bleRecvBuffer[address] ?: return error("未BLE连接到 $address")
        return if (buffer.isNotEmpty()) {
            val data = buffer.toString()
            buffer.clear()
            ok().put("data", data).put("bytes", data.toByteArray().size)
        } else {
            ok().put("data", "").put("bytes", 0)
        }
    }

    private fun disconnectBle(address: String): JSONObject {
        val gatt = bleGattMap.remove(address)
        bleTxCharMap.remove(address)
        bleRxCharMap.remove(address)
        bleRecvBuffer.remove(address)
        return if (gatt != null) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {}
            ok().put("disconnected", true).put("address", address)
        } else {
            error("未BLE连接到 $address")
        }
    }

    private fun disconnect(address: String): JSONObject {
        val classicSocket = classicSockets.remove(address)
        if (classicSocket != null) {
            try { classicSocket.close() } catch (_: Exception) {}
        }
        val bleResult = disconnectBle(address)
        return ok().put("disconnected", true).put("address", address)
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
