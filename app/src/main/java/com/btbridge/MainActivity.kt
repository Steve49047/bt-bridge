package com.btbridge

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanBtn: Button
    private lateinit var deviceList: RecyclerView
    private lateinit var sendInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var recvText: TextView
    private lateinit var disconnectBtn: Button
    private lateinit var termuxBtn: Button

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val discoveredDevices = mutableListOf<ScanResult>()
    private val discoveredClassicDevices = mutableListOf<BluetoothDevice>()
    private val deviceAdapter = DeviceAdapter()
    private var connectedSocket: BluetoothSocket? = null
    private var currentDevice: BluetoothDevice? = null
    private var bleGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private var connectionMode: ConnectionMode = ConnectionMode.NONE

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    // Nordic UART Service UUIDs
    private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    private enum class ConnectionMode { NONE, CLASSIC, BLE }

    private val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            if (discoveredDevices.none { it.device.address == dev.address }) {
                discoveredDevices.add(result)
                runOnUiThread { deviceAdapter.notifyDataSetChanged() }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanBtn.text = "扫描"
                statusText.text = "BLE扫描失败: $errorCode"
            }
        }
    }

    private val classicScanReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (discoveredClassicDevices.none { d -> d.address == it.address }) {
                            discoveredClassicDevices.add(it)
                            runOnUiThread { deviceAdapter.notifyDataSetChanged() }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        if (scanBtn.isEnabled) {
                            scanBtn.text = "扫描"
                            Toast.makeText(this@MainActivity, "扫描完成", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { statusText.text = "BLE已连接，发现服务..." }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    isBleConnected = false
                    connectionMode = ConnectionMode.NONE
                    bleGatt = null
                    txCharacteristic = null
                    statusText.text = "BLE已断开"
                    sendBtn.isEnabled = false
                    disconnectBtn.isEnabled = false
                    scanBtn.isEnabled = true
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { statusText.text = "服务发现失败: $status" }
                return
            }

            // Try NUS first
            var service = gatt.getService(NUS_SERVICE_UUID)
            if (service != null) {
                txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
                val rxChar = service.getCharacteristic(NUS_RX_UUID)
                if (txCharacteristic != null && rxChar != null) {
                    isBleConnected = true
                    connectionMode = ConnectionMode.BLE
                    // Enable notifications on TX
                    gatt.setCharacteristicNotification(txCharacteristic, true)
                    val descriptor = txCharacteristic!!.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    runOnUiThread {
                        val devName = currentDevice?.name ?: currentDevice?.address ?: "?"
                        statusText.text = "BLE已连接: $devName (NUS)"
                        sendBtn.isEnabled = true
                        disconnectBtn.isEnabled = true
                        scanBtn.isEnabled = false
                        recvText.append("<< NUS服务已连接\n")
                    }
                    return
                }
            }

            // Fallback: try all services/characteristics
            for (svc in gatt.services) {
                for (ch in svc.characteristics) {
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        txCharacteristic = ch
                        gatt.setCharacteristicNotification(ch, true)
                        val descriptor = ch.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                        isBleConnected = true
                        connectionMode = ConnectionMode.BLE
                        runOnUiThread {
                            val devName = currentDevice?.name ?: currentDevice?.address ?: "?"
                            statusText.text = "BLE已连接: $devName (${svc.uuid})"
                            sendBtn.isEnabled = true
                            disconnectBtn.isEnabled = true
                            scanBtn.isEnabled = false
                            recvText.append("<< 服务已连接: ${svc.uuid}\n")
                        }
                        return
                    }
                }
            }
            runOnUiThread { statusText.text = "未找到可用服务" }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val data = String(characteristic.value)
                runOnUiThread { recvText.append("<< $data\n") }
                // Write to file for Termux
                try {
                    File("/sdcard/bt-bridge/ble_recv.txt").appendText(data)
                } catch (_: Exception) {}
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val data = String(value)
            runOnUiThread { recvText.append("<< $data\n") }
            try {
                File("/sdcard/bt-bridge/ble_recv.txt").appendText(data)
            } catch (_: Exception) {}
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { recvText.append("<< 写入失败: $status\n") }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { recvText.append("<< 通知已启用\n") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanBtn = findViewById(R.id.scanBtn)
        deviceList = findViewById(R.id.deviceList)
        sendInput = findViewById(R.id.sendInput)
        sendBtn = findViewById(R.id.sendBtn)
        recvText = findViewById(R.id.recvText)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        termuxBtn = findViewById(R.id.termuxBtn)

        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter
        deviceAdapter.onItemClick = { dev -> connectToDevice(dev) }

        scanBtn.setOnClickListener { toggleScan() }
        sendBtn.setOnClickListener { sendData() }
        disconnectBtn.setOnClickListener { disconnect() }
        termuxBtn.setOnClickListener { setupTermux() }

        sendBtn.isEnabled = false
        disconnectBtn.isEnabled = false

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val perms = PERMISSIONS.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            checkStoragePermission()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请授予文件管理权限", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                checkBtEnabled()
            }
        } else {
            checkBtEnabled()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            checkBtEnabled()
        }
    }

    private fun checkBtEnabled() {
        if (btAdapter == null) {
            statusText.text = "未找到蓝牙适配器"
            return
        }
        if (btAdapter!!.isEnabled) {
            statusText.text = "蓝牙已开启 - ${btAdapter!!.name}"
        } else {
            @Suppress("DEPRECATION")
            btAdapter!!.enable()
            statusText.text = "正在开启蓝牙..."
            statusText.postDelayed({ checkBtEnabled() }, 1000)
        }
    }

    private fun toggleScan() {
        if (btAdapter?.isDiscovering == true) {
            btAdapter?.cancelDiscovery()
            try { btAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
            try { unregisterReceiver(classicScanReceiver) } catch (_: Exception) {}
            scanBtn.text = "扫描"
        } else {
            discoveredDevices.clear()
            discoveredClassicDevices.clear()
            deviceAdapter.notifyDataSetChanged()

            // Start BLE scan
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                btAdapter?.bluetoothLeScanner?.startScan(filters, settings, bleScanCallback)
            } catch (_: Exception) {}

            // Also start classic discovery
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            try {
                registerReceiver(classicScanReceiver, filter)
            } catch (_: Exception) {}
            btAdapter?.startDiscovery()

            scanBtn.text = "停止扫描"
            statusText.text = "正在扫描 (BLE + 经典)..."
        }
    }

    private fun connectToDevice(item: Any) {
        btAdapter?.cancelDiscovery()
        try { btAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
        try { unregisterReceiver(classicScanReceiver) } catch (_: Exception) {}
        scanBtn.text = "扫描"

        when (item) {
            is ScanResult -> connectBle(item.device)
            is BluetoothDevice -> connectClassic(item)
        }
    }

    private fun connectBle(device: BluetoothDevice) {
        recvText.text = ""
        currentDevice = device
        statusText.text = "正在BLE连接 ${device.name ?: device.address}..."
        connectionMode = ConnectionMode.BLE

        bleGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun connectClassic(device: BluetoothDevice) {
        recvText.text = ""
        statusText.text = "正在连接 ${device.name ?: device.address}..."

        Thread {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                runOnUiThread { recvText.append(">> 正在配对...\n") }
                try {
                    val method = device.javaClass.getMethod("createBond")
                    method.invoke(device)
                    var wait = 0
                    while (device.bondState == BluetoothDevice.BOND_BONDING && wait < 150) {
                        Thread.sleep(100)
                        wait++
                    }
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        runOnUiThread {
                            statusText.text = "配对失败 bondState=${device.bondState}"
                            recvText.append("<< 配对失败\n")
                        }
                        return@Thread
                    }
                    runOnUiThread { recvText.append("<< 配对成功\n") }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "配对异常: ${e.message}"
                        recvText.append("<< 配对异常: ${e.message}\n")
                    }
                    return@Thread
                }
            }

            var socket: BluetoothSocket? = null

            // Try SPP
            runOnUiThread { recvText.append(">> 尝试 SPP UUID...\n") }
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                runOnUiThread { recvText.append("<< SPP 连接成功\n") }
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                runOnUiThread { recvText.append("<< SPP 失败: ${e.message}\n") }
            }

            // Try RFCOMM channels 1-5
            if (socket == null) {
                for (ch in 1..5) {
                    runOnUiThread { recvText.append(">> 尝试 RFCOMM channel $ch...\n") }
                    try {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val s = method.invoke(device, ch) as BluetoothSocket
                        s.connect()
                        socket = s
                        runOnUiThread { recvText.append("<< Channel $ch 连接成功\n") }
                        break
                    } catch (e: Exception) {
                        runOnUiThread { recvText.append("<< Channel $ch 失败: ${e.message}\n") }
                    }
                }
            }

            if (socket != null && socket.isConnected) {
                connectedSocket = socket
                currentDevice = device
                connectionMode = ConnectionMode.CLASSIC
                runOnUiThread {
                    statusText.text = "已连接: ${device.name ?: device.address}"
                    sendBtn.isEnabled = true
                    disconnectBtn.isEnabled = true
                    scanBtn.isEnabled = false
                }
                startReading(socket)
            } else {
                try { socket?.close() } catch (_: Exception) {}
                runOnUiThread {
                    statusText.text = "所有方式均失败"
                    Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startReading(socket: BluetoothSocket) {
        Thread {
            try {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (socket.isConnected) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        val data = String(buffer, 0, len)
                        runOnUiThread { recvText.append("<< $data\n") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { recvText.append("连接断开\n") }
            }
        }.start()
    }

    private fun sendData() {
        val data = sendInput.text.toString()
        if (data.isEmpty()) return

        when (connectionMode) {
            ConnectionMode.BLE -> sendBleData(data)
            ConnectionMode.CLASSIC -> sendClassicData(data)
            ConnectionMode.NONE -> Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBleData(data: String) {
        val gatt = bleGatt ?: return
        val rxChar = txCharacteristic?.let { svc ->
            // Find RX characteristic from the same service
            gatt.getService(svc.service?.uuid)?.getCharacteristic(NUS_RX_UUID)
        }

        if (rxChar == null) {
            // Fallback: try to find any writable characteristic
            for (svc in gatt.services) {
                for (ch in svc.characteristics) {
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        ch.value = data.toByteArray()
                        gatt.writeCharacteristic(ch)
                        runOnUiThread {
                            recvText.append(">> $data\n")
                            sendInput.text.clear()
                        }
                        return
                    }
                }
            }
            runOnUiThread { Toast.makeText(this, "未找到可写特征", Toast.LENGTH_SHORT).show() }
            return
        }

        rxChar.value = data.toByteArray()
        gatt.writeCharacteristic(rxChar)
        runOnUiThread {
            recvText.append(">> $data\n")
            sendInput.text.clear()
        }
    }

    private fun sendClassicData(data: String) {
        val socket = connectedSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                socket.outputStream.write(data.toByteArray())
                socket.outputStream.flush()
                runOnUiThread {
                    recvText.append(">> $data\n")
                    sendInput.text.clear()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun disconnect() {
        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt = null
        txCharacteristic = null
        isBleConnected = false

        try { connectedSocket?.close() } catch (_: Exception) {}
        connectedSocket = null
        currentDevice = null
        connectionMode = ConnectionMode.NONE

        runOnUiThread {
            statusText.text = "已断开"
            sendBtn.isEnabled = false
            disconnectBtn.isEnabled = false
            scanBtn.isEnabled = true
            recvText.text = ""
        }
    }

    private fun setupTermux() {
        disconnect()
        Toast.makeText(this, "已切换到 Termux 模式", Toast.LENGTH_SHORT).show()
        statusText.text = "Termux 模式 - 请在终端操作"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(classicScanReceiver) } catch (_: Exception) {}
        btAdapter?.cancelDiscovery()
        try { btAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
        bleGatt?.disconnect()
        bleGatt?.close()
        try { connectedSocket?.close() } catch (_: Exception) {}
    }

    inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        var onItemClick: ((Any) -> Unit)? = null

        // Mix BLE and Classic devices into one list
        private val allItems: List<Any>
            get() {
                val list = mutableListOf<Any>()
                list.addAll(discoveredDevices)
                list.addAll(discoveredClassicDevices)
                return list
            }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.deviceName)
            val addr: TextView = v.findViewById(R.id.deviceAddr)
            val type: TextView = v.findViewById(R.id.deviceType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val item = allItems[pos]
            when (item) {
                is ScanResult -> {
                    holder.name.text = item.device.name ?: "未知BLE设备"
                    holder.addr.text = item.device.address
                    holder.type.text = "BLE"
                }
                is BluetoothDevice -> {
                    holder.name.text = item.name ?: "未知设备"
                    holder.addr.text = item.address
                    holder.type.text = when (item.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        else -> "?"
                    }
                }
            }
            holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
        }

        override fun getItemCount() = allItems.size
    }
}
