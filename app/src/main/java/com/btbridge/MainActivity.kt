package com.btbridge

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanBtn: Button
    private lateinit var deviceList: RecyclerView
    private lateinit var sendInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var recvText: TextView
    private lateinit var disconnectBtn: Button

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private val deviceAdapter = DeviceAdapter()
    private var connectedSocket: BluetoothSocket? = null
    private var currentDevice: BluetoothDevice? = null

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

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

    private val scanReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (discoveredDevices.none { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                            runOnUiThread { deviceAdapter.notifyDataSetChanged() }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        scanBtn.text = "Scan"
                        Toast.makeText(this@MainActivity, "Scan finished", Toast.LENGTH_SHORT).show()
                    }
                }
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

        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        deviceAdapter.onItemClick = { device -> connectToDevice(device) }

        scanBtn.setOnClickListener { toggleScan() }
        sendBtn.setOnClickListener { sendData() }
        disconnectBtn.setOnClickListener { disconnect() }

        if (!hasPermissions()) {
            requestPermissions()
        } else {
            checkBtEnabled()
        }
    }

    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, PERMISSIONS, 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasPermissions()) {
                checkBtEnabled()
            } else {
                statusText.text = "Permissions required"
            }
        }
    }

    private fun checkBtEnabled() {
        if (adapter == null) {
            statusText.text = "No Bluetooth adapter"
            return
        }
        if (adapter!!.isEnabled) {
            statusText.text = "Bluetooth ON - ${adapter!!.name}"
        } else {
            @Suppress("DEPRECATION")
            adapter!!.enable()
            statusText.text = "Enabling Bluetooth..."
            scanBtn.postDelayed({ checkBtEnabled() }, 1000)
        }
    }

    private fun toggleScan() {
        if (adapter?.isDiscovering == true) {
            adapter?.cancelDiscovery()
            scanBtn.text = "Scan"
            unregisterScanReceiver()
        } else {
            discoveredDevices.clear()
            deviceAdapter.notifyDataSetChanged()
            registerScanReceiver()
            adapter?.startDiscovery()
            scanBtn.text = "Scanning..."
            statusText.text = "Scanning..."
        }
    }

    private fun registerScanReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(scanReceiver, filter)
    }

    private fun unregisterScanReceiver() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    private fun connectToDevice(device: BluetoothDevice) {
        statusText.text = "Connecting to ${device.name ?: device.address}..."
        adapter?.cancelDiscovery()

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                connectedSocket = socket
                currentDevice = device

                runOnUiThread {
                    statusText.text = "Connected: ${device.name ?: device.address}"
                    sendBtn.isEnabled = true
                    disconnectBtn.isEnabled = true
                    scanBtn.isEnabled = false
                    Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
                }

                // Start reading in background
                startReading(socket)
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Connect failed: ${e.message}"
                    Toast.makeText(this, "Connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        runOnUiThread {
                            recvText.append("<< $data\n")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    recvText.append("Connection lost: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun sendData() {
        val data = sendInput.text.toString()
        if (data.isEmpty()) return

        val socket = connectedSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
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
                runOnUiThread {
                    Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun disconnect() {
        try {
            connectedSocket?.close()
        } catch (_: Exception) {}
        connectedSocket = null
        currentDevice = null

        runOnUiThread {
            statusText.text = "Disconnected"
            sendBtn.isEnabled = false
            disconnectBtn.isEnabled = false
            scanBtn.isEnabled = true
            recvText.text = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScanReceiver()
        adapter?.cancelDiscovery()
        try { connectedSocket?.close() } catch (_: Exception) {}
    }

    inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        var onItemClick: ((BluetoothDevice) -> Unit)? = null

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.deviceName)
            val addrText: TextView = view.findViewById(R.id.deviceAddr)
            val typeText: TextView = view.findViewById(R.id.deviceType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = discoveredDevices[position]
            holder.nameText.text = device.name ?: "Unknown"
            holder.addrText.text = device.address
            holder.typeText.text = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                else -> "?"
            }
            holder.itemView.setOnClickListener { onItemClick?.invoke(device) }
        }

        override fun getItemCount() = discoveredDevices.size
    }
}
