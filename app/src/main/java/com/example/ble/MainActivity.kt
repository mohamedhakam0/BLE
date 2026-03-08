package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.ble.ui.theme.BLETheme
import java.util.*

// UUIDs
val SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
val RX_CHAR_UUID: UUID = UUID.fromString("9a429160-290d-4113-8706-28274b43b8b1")
val TX_CHAR_UUID: UUID = UUID.fromString("8b7a6c5d-4e3f-2a1b-0c9d-8e7f6a5b4c3d")
val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

data class ChatMessage(val text: String, val isFromMe: Boolean, val timestamp: Long = System.currentTimeMillis())
data class DiscoveredDevice(val name: String, val address: String, val device: BluetoothDevice)

enum class ConnectionStatus {
    DISCONNECTED,
    SCANNING,
    ADVERTISING,
    CONNECTING,
    CONNECTED
}

enum class AppMode {
    CENTRAL,
    PERIPHERAL
}

class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // Central variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Peripheral variables
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    // State holders
    private val _scanResults = mutableStateListOf<DiscoveredDevice>()
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _connectionStatus = mutableStateOf(ConnectionStatus.DISCONNECTED)
    private val _connectedDeviceName = mutableStateOf<String?>(null)
    private val _appMode = mutableStateOf(AppMode.CENTRAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BLETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleApp(
                        modifier = Modifier.padding(innerPadding),
                        scanResults = _scanResults,
                        messages = _messages,
                        status = _connectionStatus.value,
                        appMode = _appMode.value,
                        connectedDeviceName = _connectedDeviceName.value,
                        onScanClick = { startScan() },
                        onAdvertiseClick = { startAdvertising() },
                        onDeviceClick = { connectToDevice(it) },
                        onSendMessage = { sendMessage(it) },
                        onDisconnect = { disconnect() },
                        onModeChange = { newMode -> 
                            disconnect() // Disconnect when switching modes
                            _appMode.value = newMode 
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) return

        _scanResults.clear()
        _connectionStatus.value = ConnectionStatus.SCANNING

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPhy(BluetoothDevice.PHY_LE_CODED)
                }
            }
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        
        // Stop scan after 10 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (_connectionStatus.value == ConnectionStatus.SCANNING) {
                scanner.stopScan(scanCallback)
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!hasPermissions()) return

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Toast.makeText(this, "Bluetooth Advertiser not available", Toast.LENGTH_SHORT).show()
            return
        }

        _connectionStatus.value = ConnectionStatus.ADVERTISING

        // Setup GATT Server
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // RX Characteristic (Read/Notify) - For sending data from Peripheral to Central
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Client Config Descriptor for notifications
        val clientConfigDesc = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        rxChar.addDescriptor(clientConfigDesc)

        // TX Characteristic (Write) - For receiving data from Central
        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        bluetoothGattServer?.addService(service)

        // Start Advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("BLE", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising failed: $errorCode")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val address = device.address
            
            val exists = _scanResults.any { it.address == address }
            if (!exists) {
                _scanResults.add(DiscoveredDevice(name, address, device))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermissions()) return
        
        // Stop scanning if active
        if (_connectionStatus.value == ConnectionStatus.SCANNING) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _connectedDeviceName.value = device.name ?: device.address
        
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        if (hasPermissions()) {
            // Central cleanup
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            
            // Peripheral cleanup
            if (bluetoothGattServer != null) {
               connectedDevice?.let { bluetoothGattServer?.cancelConnection(it) }
               bluetoothGattServer?.clearServices()
               bluetoothGattServer?.close()
               bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        }
        bluetoothGatt = null
        txCharacteristic = null
        bluetoothGattServer = null
        connectedDevice = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _messages.clear()
        _connectedDeviceName.value = null
    }

    // Central Callback
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                }
                gatt.close()
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
                    val rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
                    
                    if (rxCharacteristic != null) {
                        gatt.setCharacteristicNotification(rxCharacteristic, true)
                        val descriptor = rxCharacteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChange(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChange(characteristic, value)
        }
        
        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                val data = value ?: characteristic.value
                val message = String(data, Charsets.UTF_8)
                runOnUiThread {
                    _messages.add(ChatMessage(message, false))
                }
            }
        }
    }
    
    // Peripheral Callback
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
             if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                runOnUiThread {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _connectedDeviceName.value = device.name ?: device.address
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                runOnUiThread {
                    _connectionStatus.value = ConnectionStatus.ADVERTISING // Go back to advertising
                    _connectedDeviceName.value = null
                    Toast.makeText(this@MainActivity, "Device Disconnected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val message = String(value, Charsets.UTF_8)
                runOnUiThread {
                    _messages.add(ChatMessage(message, false))
                }
                
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
             if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR) {
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(text: String) {
        if (!hasPermissions()) return
        
        if (_appMode.value == AppMode.CENTRAL) {
            // Central sending to Peripheral (Write to TX char)
             if (bluetoothGatt == null || txCharacteristic == null) {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
                return
            }
            val characteristic = txCharacteristic!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(
                    characteristic,
                    text.toByteArray(Charsets.UTF_8),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                characteristic.value = text.toByteArray(Charsets.UTF_8)
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        } else {
            // Peripheral sending to Central (Notify on RX char)
            val device = connectedDevice
             if (bluetoothGattServer == null || device == null) {
                Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
                return
            }
            
            val service = bluetoothGattServer?.getService(SERVICE_UUID)
            val rxChar = service?.getCharacteristic(RX_CHAR_UUID)
            
            if (rxChar != null) {
                rxChar.value = text.toByteArray(Charsets.UTF_8)
                bluetoothGattServer?.notifyCharacteristicChanged(device, rxChar, false)
            }
        }
        
        _messages.add(ChatMessage(text, true))
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        } else {
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                 return false
             }
        }
        return true
    }
    
    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}

@Composable
fun BleApp(
    modifier: Modifier = Modifier,
    scanResults: List<DiscoveredDevice>,
    messages: List<ChatMessage>,
    status: ConnectionStatus,
    appMode: AppMode,
    connectedDeviceName: String?,
    onScanClick: () -> Unit,
    onAdvertiseClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit,
    onModeChange: (AppMode) -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val allGranted = perms.values.all { it }
            hasPermissions = allGranted
            if (!allGranted) {
                Toast.makeText(context, "Permissions required for BLE", Toast.LENGTH_LONG).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        val requiredPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        launcher.launch(requiredPerms)
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Mode Switcher
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = appMode == AppMode.CENTRAL,
                onClick = { onModeChange(AppMode.CENTRAL) },
                label = { Text("Central (Client)") },
                leadingIcon = { if (appMode == AppMode.CENTRAL) Icon(Icons.Filled.Check, contentDescription = null) }
            )
            FilterChip(
                selected = appMode == AppMode.PERIPHERAL,
                onClick = { onModeChange(AppMode.PERIPHERAL) },
                label = { Text("Peripheral (Server)") },
                leadingIcon = { if (appMode == AppMode.PERIPHERAL) Icon(Icons.Filled.Check, contentDescription = null) }
            )
        }
        
        // Status Bar
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (status == ConnectionStatus.CONNECTED) Color(0xFFE0F7FA) else Color(0xFFEEEEEE)
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Status: $status", style = MaterialTheme.typography.bodyLarge)
                if (status == ConnectionStatus.CONNECTED && connectedDeviceName != null) {
                    Text(text = "Connected to: $connectedDeviceName", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (status == ConnectionStatus.CONNECTED) {
             Button(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) {
                Text("Disconnect")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Chat UI
            ChatScreen(
                messages = messages,
                onSendMessage = onSendMessage
            )
        } else {
            if (appMode == AppMode.CENTRAL) {
                // Scan UI
                Button(
                    onClick = onScanClick,
                    enabled = status != ConnectionStatus.SCANNING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (status == ConnectionStatus.SCANNING) "Scanning..." else "Start Scan")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(scanResults) { device ->
                        DeviceItem(device = device, onClick = { onDeviceClick(device.device) })
                    }
                }
            } else {
                // Peripheral UI
                 Button(
                    onClick = onAdvertiseClick,
                    enabled = status != ConnectionStatus.ADVERTISING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (status == ConnectionStatus.ADVERTISING) "Advertising..." else "Start Advertising")
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Waiting for connection...", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name, style = MaterialTheme.typography.titleMedium)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit
) {
    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                MessageItem(message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (textState.isNotBlank()) {
                        onSendMessage(textState)
                        textState = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.widthIn(max = 250.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(8.dp),
                color = if (message.isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
