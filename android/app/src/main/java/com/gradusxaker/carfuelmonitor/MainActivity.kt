package com.gradusxaker.carfuelmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
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
        private const val REQUEST_BLUETOOTH_PERMISSION = 1
        private const val REQUEST_ENABLE_BT = 2
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFullCalib: Button
    private lateinit var btnEmptyCalib: Button
    private lateinit var btnResetStats: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtFuelLevel: TextView
    private lateinit var txtFuelLiters: TextView
    private lateinit var txtConsumption: TextView
    private lateinit var txtDistance: TextView
    private lateinit var txtTotalUsed: TextView
    private lateinit var txtTankCapacity: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var deviceSpinner: Spinner

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val handler = Handler(Looper.getMainLooper())
    private val pairedDevices = ArrayList<String>()
    private val pairedDevicesMap = HashMap<String, BluetoothDevice>()

    private var fuelLevel = 0f
    private var fuelLiters = 0f
    private var consumption = 0f
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

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            initViews()
            initBluetooth()
            setupListeners()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Ошибка запуска: " + e.message)
        }
    }

    private fun initViews() {
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnFullCalib = findViewById(R.id.btnFullCalib)
        btnEmptyCalib = findViewById(R.id.btnEmptyCalib)
        btnResetStats = findViewById(R.id.btnResetStats)
        txtStatus = findViewById(R.id.txtStatus)
        txtFuelLevel = findViewById(R.id.txtFuelLevel)
        txtFuelLiters = findViewById(R.id.txtFuelLiters)
        txtConsumption = findViewById(R.id.txtConsumption)
        txtDistance = findViewById(R.id.txtDistance)
        txtTotalUsed = findViewById(R.id.txtTotalUsed)
        txtTankCapacity = findViewById(R.id.txtTankCapacity)
        progressBar = findViewById(R.id.progressBar)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        updateUIState(false)
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                txtStatus.text = "Bluetooth не поддерживается"
                btnConnect.isEnabled = false
                return
            }

            checkPermissions()
        } catch (e: Exception) {
            e.printStackTrace()
            txtStatus.text = "Ошибка Bluetooth"
            btnConnect.isEnabled = false
        }
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
        } else {
            loadPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        try {
            pairedDevices.clear()
            pairedDevicesMap.clear()
            pairedDevices.add("Выберите устройство...")

            val paired = bluetoothAdapter?.bondedDevices
            if (!paired.isNullOrEmpty()) {
                for (device in paired) {
                    val name = device.name ?: "Unknown"
                    pairedDevices.add(name)
                    pairedDevicesMap[name] = device
                }
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            deviceSpinner.adapter = adapter
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Ошибка загрузки устройств")
        }
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            val selectedDevice = deviceSpinner.selectedItem.toString()
            if (selectedDevice != "Выберите устройство...") {
                connectToDevice(selectedDevice)
            } else {
                showToast("Выберите устройство из списка")
            }
        }

        btnDisconnect.setOnClickListener {
            disconnectDevice()
        }

        btnFullCalib.setOnClickListener {
            sendCommand("SET_FULL")
        }

        btnEmptyCalib.setOnClickListener {
            sendCommand("SET_EMPTY")
        }

        btnResetStats.setOnClickListener {
            showConfirmDialog("Сбросить всю статистику?", "RESET_STATS")
        }

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && !isConnected) {
                    btnConnect.isEnabled = true
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceName: String) {
        val device = pairedDevicesMap[deviceName]
        if (device == null) {
            showToast("Устройство не найдено")
            return
        }

        txtStatus.text = "Подключение..."
        btnConnect.isEnabled = false

        Thread {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()

                if (bluetoothSocket?.isConnected == true) {
                    isConnected = true
                    handler.post {
                        updateUIState(true)
                        txtStatus.text = "Подключено: $deviceName"
                        showToast("Подключено к $deviceName")
                        handler.postDelayed(dataRunnable, 1000)
                    }
                } else {
                    handler.post {
                        txtStatus.text = "Ошибка подключения"
                        btnConnect.isEnabled = true
                        showToast("Не удалось подключиться")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                handler.post {
                    txtStatus.text = "Ошибка: ${e.message}"
                    btnConnect.isEnabled = true
                    showToast("Ошибка подключения")
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
            showToast("Отключено")
        } catch (e: IOException) {
            showToast("Ошибка отключения")
        }
    }

    private fun updateUIState(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnFullCalib.isEnabled = connected
        btnEmptyCalib.isEnabled = connected
        btnResetStats.isEnabled = connected
        deviceSpinner.isEnabled = !connected

        if (!connected) {
            resetData()
        }
    }

    private fun resetData() {
        fuelLevel = 0f
        fuelLiters = 0f
        consumption = 0f
        distance = 0f
        totalUsed = 0f
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
                .setPositiveButton("Да") { _, _ ->
                    sendCommand(command)
                }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDataUI() {
        try {
            txtFuelLevel.text = "%.1f%%".format(fuelLevel)
            txtFuelLiters.text = "%.1f L".format(fuelLiters)
            txtConsumption.text = "%.1f L/100km".format(consumption)
            txtDistance.text = "%.0f km".format(distance)
            txtTotalUsed.text = "%.1f L".format(totalUsed)
            txtTankCapacity.text = "%.0f L".format(tankCapacity)

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.all { perm: Int -> perm == PackageManager.PERMISSION_GRANTED }) {
                loadPairedDevices()
            } else {
                showToast("Необходимы разрешения для работы Bluetooth")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (bluetoothAdapter != null && !bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dataRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
    }
}
