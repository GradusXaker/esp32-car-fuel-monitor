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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

class MainActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1
        private const val MIN_DISTANCE_CHANGE = 1f
        private const val MIN_TIME_UPDATE = 1000L
    }

    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFullCalib: Button
    private lateinit var btnEmptyCalib: Button
    private lateinit var btnResetStats: Button
    private lateinit var btnResetGPS: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtFuelLevel: TextView
    private lateinit var txtFuelLiters: TextView
    private lateinit var txtConsumption: TextView
    private lateinit var txtAvgConsumption: TextView
    private lateinit var txtGPSDistance: TextView
    private lateinit var txtTotalUsed: TextView
    private lateinit var txtTankCapacity: TextView
    private lateinit var txtRange: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var txtGPSStatus: TextView
    private lateinit var txtTripDistance: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var deviceSpinner: Spinner

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = ArrayList<String>()
    private val deviceMap = HashMap<String, BluetoothDevice>()
    private var readerThread: Thread? = null
    private var isBluetoothReceiverRegistered = false

    private var locationManager: LocationManager? = null
    private var currentSpeed = 0f
    private var gpsDistance = 0f
    private var tripDistance = 0f
    private var lastLocation: Location? = null
    private var gpsEnabled = false

    private var fuelLevel = 0f
    private var fuelLiters = 0f
    private var consumption = 0f
    private var avgConsumption = 0f
    private var rangeKm = 0f
    private var distance = 0f
    private var totalUsed = 0f
    private var tankCapacity = 60f

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            registerBluetoothReceiverIfNeeded()
            setupListeners()
            startDiscovery()
        } else {
            txtStatus.text = "BT выключен"
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceNameSafe(device: BluetoothDevice): String? {
        if (!hasBluetoothConnectPermission()) return null
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getBluetoothDeviceExtra(intent: Intent?): BluetoothDevice? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private val dataRunnable = object : Runnable {
        override fun run() {
            if (isConnected) { sendCommand("GET_STATUS"); handler.postDelayed(this, 2000) }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = getBluetoothDeviceExtra(intent)
                    if (device != null && hasBluetoothConnectPermission() && getDeviceNameSafe(device) != null) addDevice(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    txtStatus.text = "Поиск завершён"
                    btnScan.isEnabled = true
                    btnScan.text = "🔍 Поиск устройств"
                }
            }
        }
    }

    private fun registerBluetoothReceiverIfNeeded() {
        if (isBluetoothReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(bluetoothReceiver, filter)
        isBluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiverIfNeeded() {
        if (!isBluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver cleanup warning", e)
        } finally {
            isBluetoothReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
    }

    private fun initViews() {
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnFullCalib = findViewById(R.id.btnFullCalib)
        btnEmptyCalib = findViewById(R.id.btnEmptyCalib)
        btnResetStats = findViewById(R.id.btnResetStats)
        btnResetGPS = findViewById(R.id.btnResetGPS)
        txtStatus = findViewById(R.id.txtStatus)
        txtFuelLevel = findViewById(R.id.txtFuelLevel)
        txtFuelLiters = findViewById(R.id.txtFuelLiters)
        txtConsumption = findViewById(R.id.txtConsumption)
        txtAvgConsumption = findViewById(R.id.txtAvgConsumption)
        txtGPSDistance = findViewById(R.id.txtGPSDistance)
        txtTotalUsed = findViewById(R.id.txtTotalUsed)
        txtTankCapacity = findViewById(R.id.txtTankCapacity)
        txtRange = findViewById(R.id.txtRange)
        txtSpeed = findViewById(R.id.txtSpeed)
        txtGPSStatus = findViewById(R.id.txtGPSStatus)
        txtTripDistance = findViewById(R.id.txtTripDistance)
        progressBar = findViewById(R.id.progressBar)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        updateUIState(false)
    }

    @SuppressLint("MissingPermission")
    private fun checkPermissions() {
        val required = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (required.isNotEmpty()) ActivityCompat.requestPermissions(this, required.toTypedArray(), REQUEST_PERMISSIONS)
        else {
            initBluetooth()
            initGPS()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initBluetooth()
            initGPS()
            return
        }

        val hasAnyGranted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (hasAnyGranted) {
            initBluetooth()
            initGPS()
            showToast("Часть функций ограничена из-за разрешений")
        } else {
            txtStatus.text = "Нет разрешений"
            txtGPSStatus.text = "❌ GPS: нет разрешений"
            showToast("Нужны разрешения!")
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        try {
            val bm = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bluetoothAdapter = bm?.adapter
            if (bluetoothAdapter == null) { txtStatus.text = "BT не поддерживается"; return }
            if (!bluetoothAdapter!!.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else {
                registerBluetoothReceiverIfNeeded()
                setupListeners()
                startDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth init error", e)
            txtStatus.text = "Ошибка BT"
        }
    }

    @SuppressLint("MissingPermission")
    private fun initGPS() {
        try {
            val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFineLocation && !hasCoarseLocation) {
                txtGPSStatus.text = "❌ GPS: нет разрешений"
                return
            }
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            if (gpsEnabled) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_UPDATE, MIN_DISTANCE_CHANGE, this)
                txtGPSStatus.text = "📡 GPS: Поиск..."
            } else txtGPSStatus.text = "❌ GPS отключен"
        } catch (e: Exception) {
            Log.e(TAG, "GPS init error", e)
            txtGPSStatus.text = "❌ Ошибка GPS"
        }
    }

    override fun onLocationChanged(location: Location) {
        currentSpeed = if (location.hasSpeed()) location.speed * 3.6f else 0f
        if (currentSpeed < 0) currentSpeed = 0f
        txtSpeed.text = "%.0f".format(currentSpeed)
        txtSpeed.setTextColor(getColor(if (currentSpeed < 60) R.color.speed_low else if (currentSpeed < 120) R.color.speed_medium else R.color.speed_high))
        
        if (lastLocation != null) {
            val dist = lastLocation?.distanceTo(location) ?: 0f
            if (dist > 0 && dist < 1000) {
                gpsDistance += dist / 1000f; tripDistance += dist / 1000f
                txtGPSDistance.text = "%.2f".format(gpsDistance)
                txtTripDistance.text = "%.2f".format(tripDistance)
                if (isConnected) sendCommand("SPEED:${currentSpeed.toInt()}")
            }
        }
        lastLocation = location
        val sats = location.extras?.getInt("satellites") ?: 0
        txtGPSStatus.text = "📡 GPS: $sats сп. | %.1f км/ч".format(currentSpeed)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        try {
            deviceList.clear(); deviceMap.clear(); deviceList.add("🔍 Сканирование...")
            bluetoothAdapter?.bondedDevices?.forEach { addDevice(it) }
            if (!bluetoothAdapter!!.isDiscovering) bluetoothAdapter!!.startDiscovery()
            updateSpinner()
        } catch (e: Exception) {
            Log.e(TAG, "Discovery start error", e)
            txtStatus.text = "Ошибка поиска"
            btnScan.isEnabled = true
            btnScan.text = "🔍 Поиск устройств"
        }
    }

    private fun addDevice(d: BluetoothDevice) {
        val name = getDeviceNameSafe(d) ?: "Unknown"
        val label = "📱 $name (${d.address})"
        if (!deviceMap.containsKey(label)) {
            deviceList.add(label)
            deviceMap[label] = d
            updateSpinner()
        }
    }

    private fun updateSpinner() {
        try {
            val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceList)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            deviceSpinner.adapter = a
        } catch (e: Exception) {
            Log.e(TAG, "Spinner update error", e)
        }
    }

    private fun setupListeners() {
        btnScan.setOnClickListener { txtStatus.text = "Поиск..."; btnScan.isEnabled = false; btnScan.text = "⏳"; startDiscovery() }
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0 && !isConnected) {
                    val label = deviceSpinner.selectedItem.toString()
                    if (label != "🔍 Сканирование...") connectToDevice(label)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnDisconnect.setOnClickListener { disconnectDevice() }
        btnFullCalib.setOnClickListener { sendCommand("SET_FULL") }
        btnEmptyCalib.setOnClickListener { sendCommand("SET_EMPTY") }
        btnResetStats.setOnClickListener { showConfirm("Сброс статистики?", "RESET_STATS") }
        btnResetGPS.setOnClickListener { gpsDistance = 0f; tripDistance = 0f; txtGPSDistance.text = "0.00"; txtTripDistance.text = "0.00"; showToast("GPS сброшен") }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(label: String) {
        val d = deviceMap[label] ?: run { showToast("Не найдено"); return }
        val displayName = getDeviceNameSafe(d) ?: d.address
        txtStatus.text = "Подключение к $displayName..."
        deviceSpinner.isEnabled = false
        Thread {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = d.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (e: Exception) {
                    Log.w(TAG, "Cancel discovery warning", e)
                }
                bluetoothSocket?.connect()
                if (bluetoothSocket?.isConnected == true) {
                    isConnected = true
                    startReaderThread()
                    handler.post {
                        updateUIState(true)
                        txtStatus.text = "✅ $displayName"
                        showToast("Подключено")
                        handler.postDelayed(dataRunnable, 500)
                    }
                } else handler.post { txtStatus.text = "❌ Ошибка"; deviceSpinner.isEnabled = true }
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth connection error", e)
                handler.post { txtStatus.text = "❌ ${e.message}"; deviceSpinner.isEnabled = true }
            }
        }.start()
    }

    private fun disconnectDevice() {
        try {
            handler.removeCallbacks(dataRunnable)
            isConnected = false
            stopReaderThread()
            bluetoothSocket?.close()
            bluetoothSocket = null
            updateUIState(false)
            txtStatus.text = "Отключено"
            deviceSpinner.isEnabled = true
            showToast("Отключено")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    private fun updateUIState(c: Boolean) {
        btnDisconnect.isEnabled = c; btnFullCalib.isEnabled = c; btnEmptyCalib.isEnabled = c; btnResetStats.isEnabled = c; btnResetGPS.isEnabled = c; deviceSpinner.isEnabled = !c; btnScan.isEnabled = !c
        if (!c) resetData()
    }

    private fun resetData() { fuelLevel = 0f; fuelLiters = 0f; consumption = 0f; avgConsumption = 0f; rangeKm = 0f; distance = 0f; totalUsed = 0f; updateDataUI() }

    private fun sendCommand(cmd: String) {
        if (!isConnected || bluetoothSocket == null) return
        Thread {
            try {
                val o = bluetoothSocket?.outputStream
                o?.write((cmd + "\n").toByteArray())
                o?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send command error: $cmd", e)
            }
        }.start()
    }

    private fun showConfirm(msg: String, cmd: String) {
        try {
            AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Да") { _, _ -> sendCommand(cmd) }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Dialog error", e)
        }
    }

    private fun showToast(m: String) {
        handler.post {
            try {
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast error", e)
            }
        }
    }

    private fun startReaderThread() {
        stopReaderThread()
        readerThread = Thread {
            try {
                val socket = bluetoothSocket ?: return@Thread
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    parseIncomingLine(line)
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "Reader thread IO error", e)
                    handler.post {
                        txtStatus.text = "❌ Потеряно соединение"
                        disconnectDevice()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reader thread error", e)
            }
        }.apply {
            name = "BtReaderThread"
            start()
        }
    }

    private fun stopReaderThread() {
        try {
            readerThread?.interrupt()
            readerThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop reader thread error", e)
        }
    }

    private fun parseIncomingLine(raw: String) {
        val line = raw.trim()
        if (!line.startsWith("{")) {
            if (line.isNotEmpty()) Log.d(TAG, "BT message: $line")
            return
        }

        try {
            val json = JSONObject(line)
            fuelLevel = json.optDouble("fuel_level", fuelLevel.toDouble()).toFloat()
            fuelLiters = json.optDouble("fuel_liters", fuelLiters.toDouble()).toFloat()
            consumption = json.optDouble("consumption", consumption.toDouble()).toFloat()
            avgConsumption = json.optDouble("avg_consumption", avgConsumption.toDouble()).toFloat()
            rangeKm = json.optDouble("range_km", rangeKm.toDouble()).toFloat()
            distance = json.optDouble("distance", distance.toDouble()).toFloat()
            totalUsed = json.optDouble("total_used", totalUsed.toDouble()).toFloat()
            tankCapacity = json.optDouble("tank", tankCapacity.toDouble()).toFloat()
            handler.post { updateDataUI() }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: $line", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDataUI() {
        try {
            txtFuelLevel.text = "%.1f%%".format(fuelLevel); txtFuelLiters.text = "%.1f L".format(fuelLiters)
            txtConsumption.text = "%.1f L/100km".format(consumption); txtAvgConsumption.text = "%.1f L/100km".format(avgConsumption)
            txtGPSDistance.text = "%.2f".format(gpsDistance)
            txtTripDistance.text = "%.2f".format(tripDistance)
            txtTotalUsed.text = "%.1f L".format(totalUsed)
            txtTankCapacity.text = "%.0f L".format(tankCapacity); txtRange.text = "%.0f км".format(rangeKm)
            progressBar.progress = fuelLevel.toInt()
            progressBar.progressTintList = ContextCompat.getColorStateList(this, when { fuelLevel > 50 -> R.color.fuel_good; fuelLevel > 20 -> R.color.fuel_warning; else -> R.color.fuel_critical })
        } catch (e: Exception) {
            Log.e(TAG, "UI update error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            registerBluetoothReceiverIfNeeded()
            if (gpsEnabled && hasLocationPermission()) {
                val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_UPDATE, MIN_DISTANCE_CHANGE, this)
                }
            }
            if (isConnected) {
                startReaderThread()
                handler.postDelayed(dataRunnable, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterBluetoothReceiverIfNeeded()
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "onPause cleanup warning", e)
        }
        handler.removeCallbacks(dataRunnable)
        stopReaderThread()
    }

    override fun onDestroy() {
        unregisterBluetoothReceiverIfNeeded()
        disconnectDevice()
        super.onDestroy()
    }
    override fun onProviderEnabled(p: String) { if (p == LocationManager.GPS_PROVIDER) { gpsEnabled = true; txtGPSStatus.text = "📡 GPS: Включен" } }
    override fun onProviderDisabled(p: String) { if (p == LocationManager.GPS_PROVIDER) { gpsEnabled = false; txtGPSStatus.text = "❌ GPS: Отключен" } }
}
