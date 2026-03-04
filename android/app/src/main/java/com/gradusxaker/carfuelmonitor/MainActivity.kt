package com.gradusxaker.carfuelmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_ENABLE_BT = 2
    }

    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFullCalib: Button
    private lateinit var btnEmptyCalib: Button
    private lateinit var btnResetStats: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtFuelLevel: TextView
    private lateinit var txtFuelLiters: TextView
    private lateinit var txtConsumption: TextView
    private lateinit var txtAvgConsumption: TextView
    private lateinit var txtDistance: TextView
    private lateinit var txtTotalUsed: TextView
    private lateinit var txtTankCapacity: TextView
    private lateinit var txtRange: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var deviceSpinner: Spinner

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false
    
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = ArrayList<String>()
    private val deviceMap = HashMap<String, BluetoothDevice>()
    
    private var fuelLevel = 0f
    private var fuelLiters = 0f
    private var consumption = 0f
    private var avgConsumption = 0f
    private var rangeKm = 0f
    private var distance = 0f
    private var totalUsed = 0f
    private var tankCapacity = 60f

    private val dataRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendCommand("GET_STATUS")
                handler.postDelayed(this, 2000)
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && device.name != null) {
                        addDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    txtStatus.text = "Поиск завершён"
                    btnScan.isEnabled = true
                    btnScan.text = "🔍 Поиск устройств"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            initViews()
            checkPermissions()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Ошибка: " + e.message)
        }
    }

    private fun initViews() {
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnFullCalib = findViewById(R.id.btnFullCalib)
        btnEmptyCalib = findViewById(R.id.btnEmptyCalib)
        btnResetStats = findViewById(R.id.btnResetStats)
        txtStatus = findViewById(R.id.txtStatus)
        txtFuelLevel = findViewById(R.id.txtFuelLevel)
        txtFuelLiters = findViewById(R.id.txtFuelLiters)
        txtConsumption = findViewById(R.id.txtConsumption)
        txtAvgConsumption = findViewById(R.id.txtAvgConsumption)
        txtDistance = findViewById(R.id.txtDistance)
        txtTotalUsed = findViewById(R.id.txtTotalUsed)
        txtTankCapacity = findViewById(R.id.txtTankCapacity)
        txtRange = findViewById(R.id.txtRange)
        progressBar = findViewById(R.id.progressBar)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        updateUIState(false)
    }

    @SuppressLint("MissingPermission")
    private fun checkPermissions() {
        val requiredPermissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (requiredPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            initBluetooth()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initBluetooth()
            } else {
                showToast("Нужны разрешения для Bluetooth")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            if (bluetoothAdapter == null) {
                txtStatus.text = "Bluetooth не поддерживается"
                return
            }
            if (!bluetoothAdapter!!.isEnabled) {
                val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
            } else {
                setupListeners()
                startDiscovery()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            txtStatus.text = "Ошибка Bluetooth"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                setupListeners()
                startDiscovery()
            } else {
                txtStatus.text = "Bluetooth выключен"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        try {
            deviceList.clear()
            deviceMap.clear()
            deviceList.add("🔍 Сканирование...")
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            registerReceiver(bluetoothReceiver, filter)
            val pairedDevices = bluetoothAdapter?.bondedDevices
            if (!pairedDevices.isNullOrEmpty()) {
                for (device in pairedDevices) {
                    addDevice(device)
                }
            }
            if (!bluetoothAdapter!!.isDiscovering) {
                bluetoothAdapter!!.startDiscovery()
            }
            updateSpinner()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addDevice(device: BluetoothDevice) {
        val name = device.name ?: "Unknown"
        if (!deviceMap.containsKey(name)) {
            deviceList.add("📱 $name")
            deviceMap[name] = device
            updateSpinner()
        }
    }

    private fun updateSpinner() {
        try {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            deviceSpinner.adapter = adapter
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        btnScan.setOnClickListener {
            txtStatus.text = "Поиск устройств..."
            btnScan.isEnabled = false
            btnScan.text = "⏳ Поиск..."
            startDiscovery()
        }
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && !isConnected) {
                    val deviceName = deviceSpinner.selectedItem.toString().replace("📱 ", "")
                    if (deviceName != "🔍 Сканирование...") {
                        connectToDevice(deviceName)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnDisconnect.setOnClickListener { disconnectDevice() }
        btnFullCalib.setOnClickListener { sendCommand("SET_FULL") }
        btnEmptyCalib.setOnClickListener { sendCommand("SET_EMPTY") }
        btnResetStats.setOnClickListener { showConfirmDialog("Сбросить статистику?", "RESET_STATS") }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceName: String) {
        val device = deviceMap[deviceName]
        if (device == null) {
            showToast("Устройство не найдено")
            return
        }
        txtStatus.text = "Подключение к $deviceName..."
        deviceSpinner.isEnabled = false
        btnScan.isEnabled = false
        Thread {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try { bluetoothAdapter?.cancelDiscovery() } catch (e: Exception) {}
                bluetoothSocket?.connect()
                if (bluetoothSocket?.isConnected == true) {
                    isConnected = true
                    handler.post {
                        updateUIState(true)
                        txtStatus.text = "✅ Подключено: $deviceName"
                        showToast("Подключено")
                        handler.postDelayed(dataRunnable, 500)
                    }
                } else {
                    handler.post {
                        txtStatus.text = "❌ Ошибка подключения"
                        deviceSpinner.isEnabled = true
                        btnScan.isEnabled = true
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                handler.post {
                    txtStatus.text = "❌ Ошибка: ${e.message}"
                    deviceSpinner.isEnabled = true
                    btnScan.isEnabled = true
                }
            }
        }.start()
    }

    private fun disconnectDevice() {
        try {
            handler.removeCallbacks(dataRunnable)
            bluetoothSocket?.close()
            bluetoothSocket = null
            isConnected = false
            updateUIState(false)
            txtStatus.text = "Отключено"
            deviceSpinner.isEnabled = true
            btnScan.isEnabled = true
            showToast("Отключено")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUIState(connected: Boolean) {
        btnDisconnect.isEnabled = connected
        btnFullCalib.isEnabled = connected
        btnEmptyCalib.isEnabled = connected
        btnResetStats.isEnabled = connected
        deviceSpinner.isEnabled = !connected
        btnScan.isEnabled = !connected
        if (!connected) resetData()
    }

    private fun resetData() {
        fuelLevel = 0f; fuelLiters = 0f; consumption = 0f; avgConsumption = 0f
        rangeKm = 0f; distance = 0f; totalUsed = 0f
        updateDataUI()
    }

    private fun sendCommand(command: String) {
        if (!isConnected || bluetoothSocket == null) return
        Thread {
            try {
                val outputStream = bluetoothSocket?.outputStream
                outputStream?.write((command + "\n").toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun showConfirmDialog(message: String, command: String) {
        try {
            AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("Да") { _, _ -> sendCommand(command) }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showToast(message: String) {
        handler.post {
            try { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDataUI() {
        try {
            txtFuelLevel.text = "%.1f%%".format(fuelLevel)
            txtFuelLiters.text = "%.1f L".format(fuelLiters)
            txtConsumption.text = "%.1f L/100km".format(consumption)
            txtAvgConsumption.text = "%.1f L/100km".format(avgConsumption)
            txtDistance.text = "%.0f km".format(distance)
            txtTotalUsed.text = "%.1f L".format(totalUsed)
            txtTankCapacity.text = "%.0f L".format(tankCapacity)
            txtRange.text = "%.0f км".format(rangeKm)
            progressBar.progress = fuelLevel.toInt()
            val color = when {
                fuelLevel > 50 -> ContextCompat.getColor(this, R.color.fuel_good)
                fuelLevel > 20 -> ContextCompat.getColor(this, R.color.fuel_warning)
                else -> ContextCompat.getColor(this, R.color.fuel_critical)
            }
            progressBar.progressTintList = ContextCompat.getColorStateList(this, color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        handler.removeCallbacks(dataRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
    }
}
