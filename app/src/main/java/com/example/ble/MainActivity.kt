package com.example.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ble.ui.QrGenerateScreen
import com.example.ble.ui.LogViewerScreen
import com.example.ble.ui.QrScanScreen
import com.example.ble.ui.theme.BLETheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainScreen {
    CONTACTS,
    CHAT,
    QR_GENERATE,
    QR_SCAN,
    LOGS
}
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_NOTIF = 101
    }

    private lateinit var nodeIdentity: NodeIdentity
    private lateinit var contactRepository: ContactRepository
    private lateinit var messageRepository: MessageRepository
    private var localSenderIdHex: String = "00000000"
    private var localPublicKey: ByteArray = byteArrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestRuntimePermissionsIfNeeded()

        ForegroundMeshService.start(applicationContext)

        nodeIdentity = NodeIdentity(this)
        contactRepository = ContactRepository.getInstance(this)
        messageRepository = MessageRepository.getInstance(this)

        val identity = nodeIdentity.getOrCreateIdentity()
        localSenderIdHex = identity.senderId.toHex()
        localPublicKey = identity.publicKey

        if (nodeIdentity.getNickname().isNullOrBlank()) {
            nodeIdentity.setNickname("Node-$localSenderIdHex")
        }

        val nickname = nodeIdentity.getNickname() ?: "Node-$localSenderIdHex"
        val publicKeyB64 = Base64.encodeToString(localPublicKey, Base64.NO_WRAP)

        setContent {
            BLETheme {
                var currentScreen by remember { mutableStateOf(MainScreen.CONTACTS) }
                var activeContact by remember { mutableStateOf<ContactLastMessageRow?>(null) }

                // notification deep link
                LaunchedEffect(Unit) {
                    val contactId = intent?.getStringExtra("contactId")
                    if (contactId != null) {
                        // active contact resolution via flow snapshot
                        contactRepository.observeContactsWithLastMessage().collectLatest { rows ->
                            val found = rows.firstOrNull { it.senderId.equals(contactId, ignoreCase = true) }
                            if (found != null) {
                                // Clear existing notification + buffer for this conversation.
                                NotificationHelper.clearConversation(applicationContext, found.senderId)
                                activeContact = found
                                currentScreen = MainScreen.CHAT
                            }
                        }
                    }
                }

                when (currentScreen) {
                    MainScreen.CONTACTS -> {
                        ContactsHomeScreen(
                            contactRepository = contactRepository,
                            myNickname = nickname,
                            mySenderIdHex = localSenderIdHex,
                            myPublicKeyB64 = publicKeyB64,
                            onOpenChat = {
                                activeContact = it
                                currentScreen = MainScreen.CHAT
                                // Clear existing notification + buffer when entering chat.
                                NotificationHelper.clearConversation(applicationContext, it.senderId)
                            },
                            onShowQr = { currentScreen = MainScreen.QR_GENERATE },
                            onScanQr = { currentScreen = MainScreen.QR_SCAN },
                            onShowLogs = { currentScreen = MainScreen.LOGS }
                        )
                    }

                    MainScreen.CHAT -> {
                        val contact = activeContact
                        if (contact == null) {
                            currentScreen = MainScreen.CONTACTS
                        } else {
                            // Explicit permission gate: avoids SecurityException on Android 12+.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val hasAdvertise = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.BLUETOOTH_ADVERTISE
                                ) == PackageManager.PERMISSION_GRANTED
                                val hasConnect = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasAdvertise || !hasConnect) {
                                    AppLogger.w("BLE", "Missing BLE permissions for chat (advertise=$hasAdvertise connect=$hasConnect)")
                                    currentScreen = MainScreen.CONTACTS
                                    return@BLETheme
                                }
                            }

                            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                            val adapter = bluetoothManager.adapter
                            val advertiser = remember { BleAdvertiser(adapter) }

                            val vm = remember(contact.senderId) {
                                ChatViewModel(
                                    app = application as android.app.Application,
                                    nodeIdentity = nodeIdentity,
                                    advertiser = advertiser,
                                    messageRepository = messageRepository,
                                    contactId = contact.senderId,
                                    contactSenderIdHex = contact.senderId
                                )
                            }

                            com.example.ble.ChatScreen(
                                contactName = contact.nickname,
                                receiverIdHex = contact.senderId,
                                viewModel = vm,
                                onBack = { currentScreen = MainScreen.CONTACTS }
                            )
                        }
                    }

                    MainScreen.QR_GENERATE -> {
                        QrGenerateScreen(
                            nickname = nickname,
                            senderIdHex = localSenderIdHex,
                            publicKeyB64 = publicKeyB64,
                            onBack = { currentScreen = MainScreen.CONTACTS }
                        )
                    }

                    MainScreen.QR_SCAN -> {
                        QrScanScreen(
                            contactRepository = contactRepository,
                            onContactAdded = {
                                currentScreen = MainScreen.CONTACTS
                            },
                            onBack = { currentScreen = MainScreen.CONTACTS }
                        )
                    }

                    MainScreen.LOGS -> {
                        LogViewerScreen(
                            onBack = { currentScreen = MainScreen.CONTACTS }
                        )
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_ADVERTISE
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }

        // Android 13+ requires runtime notification permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Foreground state is now determined inside ForegroundMeshService via ActivityManager.
    }

    override fun onPause() {
        // Foreground state is now determined inside ForegroundMeshService via ActivityManager.
        super.onPause()
    }
}

@Composable
private fun ContactConversationRow(
    contact: ContactLastMessageRow,
    onClick: () -> Unit,
    contactRepository: ContactRepository
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var menuExpanded by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(contact.nickname) }

    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var showDeleteContactDialog by remember { mutableStateOf(false) }

    val time = remember(contact.lastTimestamp) {
        contact.lastTimestamp?.let { formatTime(it) } ?: ""
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                Column {
                    Text("Edit this contact's display name")
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = renameInput,
                        onValueChange = { renameInput = it.take(50) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = renameInput.trim()
                    if (cleaned.isEmpty()) return@TextButton
                    CoroutineScope(Dispatchers.IO).launch {
                        contactRepository.renameContact(contact.senderId, cleaned)
                    }
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            title = { Text("Delete Chat") },
            text = { Text("This will delete all messages with this contact. The contact will remain in your list.") },
            confirmButton = {
                TextButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        contactRepository.deleteChat(contact.senderId)
                    }
                    showDeleteChatDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteContactDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteContactDialog = false },
            title = { Text("Delete Contact") },
            text = { Text("If you delete this contact you will not be able to message them again until you scan their QR code.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            contactRepository.deleteContactAndChat(contact.senderId)
                        }
                        showDeleteContactDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteContactDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    contact.lastText ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (time.isNotBlank()) {
                    Text(time, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.width(6.dp))
                }

                Box {
                    IconButton(
                        onClick = {
                            // Ensure renameInput is always current when opening.
                            renameInput = contact.nickname
                            menuExpanded = true
                        }
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuExpanded = false
                                showRenameDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Chat") },
                            onClick = {
                                menuExpanded = false
                                showDeleteChatDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Contact") },
                            onClick = {
                                menuExpanded = false
                                showDeleteContactDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsHomeScreen(
    contactRepository: ContactRepository,
    myNickname: String,
    mySenderIdHex: String,
    myPublicKeyB64: String,
    onOpenChat: (ContactLastMessageRow) -> Unit,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onShowLogs: () -> Unit
) {
    val contactsFlow = remember { contactRepository.observeContactsWithLastMessage() }
    val contacts by contactsFlow.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onShowQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCode2, contentDescription = "My QR")
                Spacer(Modifier.width(4.dp))
                Text("My QR")
            }
            Button(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Scan QR")
                Spacer(Modifier.width(4.dp))
                Text("Scan QR")
            }

            IconButton(onClick = onShowLogs) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs")
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Chats", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        if (contacts.isEmpty()) {
            Text("No contacts yet. Scan a friend's QR.", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts) { c ->
                    ContactConversationRow(
                        contact = c,
                        onClick = { onOpenChat(c) },
                        contactRepository = contactRepository
                    )
                }
            }
        }
    }
}

private fun formatTime(timestampMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(timestampMs))
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
