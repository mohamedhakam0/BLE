package com.example.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import com.example.ble.AvatarManager
import com.example.ble.ui.ContactAvatarCircle
import com.example.ble.ui.KeysScreen
import com.example.ble.ui.LogViewerScreen
import com.example.ble.ui.MeshSettingsScreen
import com.example.ble.ui.TrustVerificationScreen
import com.example.ble.ui.theme.BLETheme
import com.example.ble.ui.theme.isDarkTheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

enum class Tab {
    LOGS, STRESS, MESSAGES, NETWORK, KEYS
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
        // Ensure the app draws behind system bars (keyboard, nav bar, status bar).
        // enableEdgeToEdge() already calls this, but we repeat it to make the intent explicit.
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
                // Animate the status-bar background on the same 700 ms curve as the theme colors.
                val statusBarColor by animateColorAsState(
                    targetValue = if (isDarkTheme) Color(0xFF0A0C10) else Color(0xFFF4F6FB),
                    animationSpec = tween(durationMillis = 700),
                    label = "statusBar"
                )
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = statusBarColor.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !isDarkTheme
                }

                var selectedTab by remember { mutableStateOf(Tab.MESSAGES) }
                var inChat by remember { mutableStateOf(false) }
                var activeContact by remember { mutableStateOf<ContactLastMessageRow?>(null) }
                var showSettings by remember { mutableStateOf(false) }

                data class PendingVerification(
                    val peerName: String,
                    val peerSenderId: String,
                    val publicKeyBytes: ByteArray,
                    val publicKeyB64: String
                )
                var pendingVerification by remember { mutableStateOf<PendingVerification?>(null) }

                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val bleAdvertiser = remember { BleAdvertiser(adapter) }

                BackHandler(enabled = pendingVerification != null) {
                    pendingVerification = null
                }
                BackHandler(enabled = inChat) {
                    inChat = false
                }
                BackHandler(enabled = showSettings) {
                    showSettings = false
                }

                // Notification deep link
                LaunchedEffect(Unit) {
                    val contactId = intent?.getStringExtra("contactId")
                    if (contactId != null) {
                        contactRepository.observeContactsWithLastMessage().collectLatest { rows ->
                            val found = rows.firstOrNull { it.senderId.equals(contactId, ignoreCase = true) }
                            if (found != null) {
                                NotificationHelper.clearConversation(applicationContext, found.senderId)
                                activeContact = found
                                inChat = true
                                selectedTab = Tab.MESSAGES
                            }
                        }
                    }
                }

                val pending = pendingVerification
                if (showSettings) {
                    MeshSettingsScreen(onBack = { showSettings = false })
                } else if (pending != null) {
                    TrustVerificationScreen(
                        peerName = pending.peerName,
                        peerSenderId = pending.peerSenderId,
                        publicKeyBytes = pending.publicKeyBytes,
                        onTrust = { finalName ->
                            val gradientSeed = if (pending.publicKeyBytes.size >= 3)
                                "%02X%02X%02X".format(
                                    pending.publicKeyBytes[0].toInt() and 0xFF,
                                    pending.publicKeyBytes[1].toInt() and 0xFF,
                                    pending.publicKeyBytes[2].toInt() and 0xFF
                                )
                            else ""
                            val contact = Contact(
                                senderId = pending.peerSenderId.trim().lowercase(),
                                nickname = finalName,
                                publicKey = pending.publicKeyB64,
                                dateAdded = System.currentTimeMillis(),
                                gradientSeed = gradientSeed
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                contactRepository.upsertContact(contact)
                                withContext(Dispatchers.Main) {
                                    val row = ContactLastMessageRow(
                                        senderId = contact.senderId,
                                        nickname = contact.nickname,
                                        publicKey = contact.publicKey,
                                        dateAdded = contact.dateAdded,
                                        gradientSeed = gradientSeed,
                                        lastText = null,
                                        lastTimestamp = null
                                    )
                                    activeContact = row
                                    inChat = true
                                    pendingVerification = null
                                }
                            }
                        },
                        onCancel = { pendingVerification = null }
                    )
                } else if (inChat && activeContact != null) {
                    val contact = activeContact!!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val hasAdvertise = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_ADVERTISE
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasConnect = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasAdvertise || !hasConnect) {
                            AppLogger.w("BLE", "Missing BLE permissions for chat")
                            inChat = false
                        }
                    }

                    if (inChat) {
                        val vm = remember(contact.senderId) {
                            ChatViewModel(
                                app = application as android.app.Application,
                                nodeIdentity = nodeIdentity,
                                advertiser = bleAdvertiser,
                                messageRepository = messageRepository,
                                contactRepository = contactRepository,
                                contactId = contact.senderId,
                                contactSenderIdHex = contact.senderId,
                                contactPublicKeyB64 = contact.publicKey
                            )
                        }
                        ChatScreen(
                            contactName = contact.nickname,
                            receiverIdHex = contact.senderId,
                            publicKeyB64 = contact.publicKey,
                            viewModel = vm,
                            onBack = { inChat = false }
                        )
                    }
                } else {
                    val showTopBar = selectedTab in listOf(Tab.MESSAGES, Tab.NETWORK, Tab.KEYS)

                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            if (showTopBar) PeerReachTopBar(onSettingsClick = { showSettings = true })
                        },
                        bottomBar = {
                            PeerReachBottomBar(
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it }
                            )
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            when (selectedTab) {
                                Tab.LOGS -> {
                                    LogViewerScreen(onBack = { selectedTab = Tab.MESSAGES })
                                }
                                Tab.STRESS -> {
                                    val stressVm: com.example.ble.debug.StressTestViewModel =
                                        viewModel(
                                            key = "stress_vm",
                                            factory = com.example.ble.debug.StressTestViewModel.Factory(
                                                application = application as android.app.Application,
                                                nodeIdentity = nodeIdentity,
                                                advertiser = bleAdvertiser,
                                                messageRepository = messageRepository,
                                                contactRepository = contactRepository
                                            )
                                        )
                                    com.example.ble.debug.StressTestScreen(
                                        viewModel = stressVm,
                                        onBack = { selectedTab = Tab.MESSAGES }
                                    )
                                }
                                Tab.MESSAGES -> {
                                    MessagesScreen(
                                        contactRepository = contactRepository,
                                        onOpenChat = {
                                            activeContact = it
                                            inChat = true
                                            NotificationHelper.clearConversation(applicationContext, it.senderId)
                                        },
                                        onGoToKeys = { selectedTab = Tab.KEYS }
                                    )
                                }
                                Tab.NETWORK -> {
                                    NeighborListScreen(contactRepository = contactRepository)
                                }
                                Tab.KEYS -> {
                                    KeysScreen(
                                        nickname = nickname,
                                        senderIdHex = localSenderIdHex,
                                        publicKeyB64 = publicKeyB64,
                                        contactRepository = contactRepository,
                                        hasPendingVerification = pendingVerification != null,
                                        onVerifyContact = { payload, publicKeyBytes ->
                                            pendingVerification = PendingVerification(
                                                peerName = payload.resolvedName().ifBlank { "Peer-${payload.senderId}" },
                                                peerSenderId = payload.senderId,
                                                publicKeyBytes = publicKeyBytes,
                                                publicKeyB64 = payload.publicKey
                                            )
                                        }
                                    )
                                }
                            }
                        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }
}

@Composable
private fun PeerReachTopBar(onSettingsClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Peer Reach",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "mesh · ble + lora · e2e",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { isDarkTheme = !isDarkTheme },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = if (isDarkTheme) "☀  Light" else "◑  Dark",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun PeerReachBottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        modifier = Modifier
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RectangleShape)
            .navigationBarsPadding()
    ) {
        data class NavItem(val tab: Tab, val icon: ImageVector, val label: String)
        val items = listOf(
            NavItem(Tab.LOGS, Icons.AutoMirrored.Filled.List, "Logs"),
            NavItem(Tab.STRESS, Icons.Filled.BugReport, "Stress"),
            NavItem(Tab.MESSAGES, Icons.Filled.ChatBubbleOutline, "Chats"),
            NavItem(Tab.NETWORK, Icons.Filled.Sensors, "Network"),
            NavItem(Tab.KEYS, Icons.Filled.QrCode2, "Keys"),
        )
        items.forEach { item ->
            NavigationBarItem(
                selected = selectedTab == item.tab,
                onClick = { onTabSelected(item.tab) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesScreen(
    contactRepository: ContactRepository,
    onOpenChat: (ContactLastMessageRow) -> Unit,
    onGoToKeys: () -> Unit = {}
) {
    val contactsFlow = remember { contactRepository.observeContactsWithLastMessage() }
    val contacts by contactsFlow.collectAsState(initial = emptyList())
    val nameMap = remember(contacts) {
        contacts.associate { it.senderId.lowercase() to it.nickname }
    }

    val context = LocalContext.current
    val messageRepository = remember { MessageRepository.getInstance(context) }
    val allStarred by messageRepository.observeAllStarred().collectAsState(initial = emptyList())

    var showStarredSheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    if (showStarredSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showStarredSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "All Starred Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (allStarred.isEmpty()) {
                    Text(
                        "No starred messages yet.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allStarred.forEach { msg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val senderLabel = if (msg.direction == MessageDirection.OUTGOING) {
                                        "You"
                                    } else {
                                        nameMap[msg.contactId.lowercase()]
                                            ?: msg.contactId.take(8).let { if (msg.contactId.length > 8) "$it…" else it }
                                    }
                                    Text(
                                        senderLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(msg.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Chats",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onGoToKeys) {
                    Icon(
                        Icons.Filled.QrCode2,
                        contentDescription = "Keys",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All starred messages") },
                            onClick = { menuExpanded = false; showStarredSheet = true }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (contacts.isEmpty()) {
            Text(
                "No contacts yet. Go to Keys tab to scan a friend's QR.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
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

@Composable
private fun ContactConversationRow(
    contact: ContactLastMessageRow,
    onClick: () -> Unit,
    contactRepository: ContactRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                AvatarManager.savePeerAvatar(context, contact.senderId, bytes)
            }
        }
    }

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
                TextButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        contactRepository.deleteContactAndChat(contact.senderId)
                    }
                    showDeleteContactDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteContactDialog = false }) { Text("Cancel") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatarCircle(
            senderIdHex = contact.senderId,
            publicKeyB64 = contact.publicKey,
            size = 44.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.nickname,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                contact.lastText ?: "No messages yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (time.isNotBlank()) {
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(Modifier.width(6.dp))
            }

            Box {
                IconButton(onClick = {
                    renameInput = contact.nickname
                    menuExpanded = true
                }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Change avatar") },
                        onClick = { menuExpanded = false; pickImage.launch("image/*") }
                    )
                    DropdownMenuItem(
                        text = { Text("Reset avatar") },
                        onClick = { menuExpanded = false; AvatarManager.clearPeerAvatar(context, contact.senderId) }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuExpanded = false; showRenameDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Chat") },
                        onClick = { menuExpanded = false; showDeleteChatDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Contact") },
                        onClick = { menuExpanded = false; showDeleteContactDialog = true }
                    )
                }
            }
        }
    }
}

private fun formatTime(timestampMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return String.format(Locale.getDefault(), "%02d:%02d", h, m)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
