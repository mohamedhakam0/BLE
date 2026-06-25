package com.example.ble

import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import com.example.ble.AvatarManager
import com.example.ble.ui.ContactAvatarCircle
import com.example.ble.ui.ContactInfoSheet
import com.example.ble.ui.theme.NodeGreen
import androidx.compose.foundation.layout.wrapContentHeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class BubblePosition(val message: ChatUiMessage, val bounds: Rect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName: String,
    receiverIdHex: String,
    publicKeyB64: String = "",
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val contacts by viewModel.contacts.collectAsState(initial = emptyList())
    val starredMessages by viewModel.starredMessages.collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    // 250-byte BLE Extended Advertising cap minus the 41-byte fixed header (which includes the 16-byte GCM auth tag)
    val maxPayloadBytes = 250 - PacketSerializer.FIXED_HEADER_SIZE
    val inputBytes = input.toByteArray(Charsets.UTF_8).size
    val shakeOffset = remember { Animatable(0f) }
    var shakeGuard by remember { mutableStateOf(false) }
    val counterColor by animateColorAsState(
        targetValue = when {
            inputBytes.toFloat() / maxPayloadBytes > 0.9f -> MaterialTheme.colorScheme.error
            inputBytes.toFloat() / maxPayloadBytes > 0.75f -> Color(0xFFFF9800)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        },
        label = "counterColor"
    )
    val canSend = input.trim().isNotEmpty() && inputBytes <= maxPayloadBytes
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                AvatarManager.savePeerAvatar(context, receiverIdHex, bytes)
            }
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var inputFocused by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showBtOffDialog by remember { mutableStateOf(false) }
    var forceLoraEligible by remember { mutableStateOf(false) }
    var loraOnlyRx by remember { mutableStateOf(false) }

    // Auto-clear LoRa-only mode when leaving the screen
    DisposableEffect(Unit) {
        onDispose { ForegroundMeshService.loraOnlyRxMode = false }
    }

    // Tracks the input bar's laid-out height in px. Used to add bottom content padding to the
    // message list so the last message is never hidden behind the floating input bar.
    var inputBarHeightPx by remember { mutableIntStateOf(0) }

    // Context menu state
    var contextMenuState by remember { mutableStateOf<BubblePosition?>(null) }
    var showForwardSheet by remember { mutableStateOf(false) }
    var forwardMsg by remember { mutableStateOf<ChatUiMessage?>(null) }
    var showPathSheet by remember { mutableStateOf(false) }
    var pathMsg by remember { mutableStateOf<ChatUiMessage?>(null) }
    var showStarredSheet by remember { mutableStateOf(false) }
    var showCustomEmojiDialog by remember { mutableStateOf(false) }
    var customEmojiTarget by remember { mutableStateOf("") }
    var customEmojiInput by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    // When the keyboard opens or closes the input bar height changes, which shifts the
    // list's effective bottom. Re-scroll to the last item so it stays visible above the IME.
    LaunchedEffect(inputBarHeightPx) {
        if (inputBarHeightPx > 0 && messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(messages.lastIndex) }
        }
    }

    // When a reaction is added to any message the cell grows by ~26 dp (pill height),
    // which shifts the viewport and hides the bottom of the list. Only re-scroll if the
    // user was already at (or one item away from) the bottom so we don't yank them
    // back while they are reading older messages.
    val totalReactions = remember(messages) { messages.sumOf { it.reactions.size } }
    LaunchedEffect(totalReactions) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastIndex   = messages.lastIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= lastIndex - 1) {
            runCatching { listState.scrollToItem(lastIndex) }
        }
    }

    // Show a persistent notification when a message cannot be delivered via BLE and would
    // require LoRa forwarding. The message has already been saved to the DB; it will appear
    // in the chat list with a "sent" status until a LoRa path is available.
    LaunchedEffect(viewModel) {
        viewModel.routeEvents.collect { event ->
            when (event) {
                is RouteEvent.LoraRequired ->
                    Toast.makeText(
                        context,
                        "Contact not in BLE range — message queued for LoRa forwarding",
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    // Clear chat dialog
    if (showBtOffDialog) {
        AlertDialog(
            onDismissRequest = { showBtOffDialog = false },
            title = { Text("Bluetooth is off") },
            text = { Text("Please enable Bluetooth to send messages over the mesh.") },
            confirmButton = {
                TextButton(onClick = { showBtOffDialog = false }) { Text("OK") }
            }
        )
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            title = { Text("Clear chat history?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteChatDialog = false
                    viewModel.deleteHistory(receiverIdHex)
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Custom emoji dialog
    if (showCustomEmojiDialog) {
        AlertDialog(
            onDismissRequest = { showCustomEmojiDialog = false; customEmojiInput = "" },
            title = { Text("React with emoji") },
            text = {
                TextField(
                    value = customEmojiInput,
                    onValueChange = { if (it.length <= 2) customEmojiInput = it },
                    singleLine = true,
                    placeholder = { Text("Type an emoji") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val emoji = customEmojiInput.trim()
                    if (emoji.isNotEmpty()) {
                        viewModel.sendReaction(customEmojiTarget, emoji)
                        contextMenuState = null
                    }
                    showCustomEmojiDialog = false
                    customEmojiInput = ""
                }) { Text("React") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomEmojiDialog = false; customEmojiInput = "" }) { Text("Cancel") }
            }
        )
    }

    // Contact info sheet
    if (showInfoSheet) {
        ContactInfoSheet(
            contactName = contactName,
            senderIdHex = receiverIdHex,
            publicKeyB64 = publicKeyB64,
            onDismiss = { showInfoSheet = false }
        )
    }

    // Forward sheet
    if (showForwardSheet && forwardMsg != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showForwardSheet = false; forwardMsg = null },
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
                    "Forward to",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                val otherContacts = contacts.filter {
                    !it.senderId.equals(receiverIdHex, ignoreCase = true)
                }
                if (otherContacts.isEmpty()) {
                    Text(
                        "No other contacts to forward to.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    otherContacts.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    forwardMsg?.let { msg -> viewModel.forwardMessage(msg.text, c) }
                                    showForwardSheet = false
                                    forwardMsg = null
                                    Toast.makeText(context, "Forwarded to ${c.nickname}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactAvatarCircle(
                                senderIdHex = c.senderId,
                                publicKeyB64 = c.publicKey,
                                size = 36.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.nickname,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // Show Path sheet
    if (showPathSheet && pathMsg != null) {
        val msg = pathMsg!!
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPathSheet = false; pathMsg = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Message Path",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (msg.deletedForEveryone) {
                    Text(
                        "This message was deleted",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (msg.deletedAt > 0) {
                        PathRow(
                            "Deleted at",
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.deletedAt))
                        )
                    }
                } else {
                    PathRow("Direction", if (msg.isMine) "Outgoing" else "Incoming")
                    PathRow("Hop count", if (msg.hopCount >= 0) "${msg.hopCount}" else "unknown")
                    PathRow("Sent at", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.sendTimestampMs)))
                    if (msg.isMine) {
                        PathRow(
                            "ACK received",
                            if (msg.ackTimestamp > 0)
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.ackTimestamp))
                            else "pending"
                        )
                        PathRow(
                            "Round-trip time",
                            if (msg.ackRttMs >= 0) "${msg.ackRttMs} ms" else "pending"
                        )
                    }
                }
            }
        }
    }

    // Starred messages sheet
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
                    "Starred Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (starredMessages.isEmpty()) {
                    Text(
                        "No starred messages yet.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        starredMessages.forEach { msg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        if (msg.isMine) "You" else contactName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestampMs)),
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

    // Animation tracking
    val seenMsgIds = remember { HashSet<String>() }
    var initialSnapshotDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (!initialSnapshotDone) {
            messages.forEach { seenMsgIds.add(it.msgIdHex) }
            initialSnapshotDone = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content (blurred when overlay is active)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(if (contextMenuState != null) Modifier.blur(8.dp) else Modifier)
        ) {
            // Header
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text("Back", color = MaterialTheme.colorScheme.primary)
                    }
                    ContactAvatarCircle(
                        senderIdHex = receiverIdHex,
                        publicKeyB64 = publicKeyB64,
                        size = 36.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = contactName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                val now = System.currentTimeMillis()
                                if (now - lastTapMs > 1000L) tapCount = 0
                                lastTapMs = now
                                tapCount += 1
                                if (tapCount >= 3) {
                                    tapCount = 0
                                    showDeleteChatDialog = true
                                }
                            }
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                                onClick = { menuExpanded = false; AvatarManager.clearPeerAvatar(context, receiverIdHex) }
                            )
                            DropdownMenuItem(
                                text = { Text("Starred messages") },
                                onClick = { menuExpanded = false; showStarredSheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                onClick = { menuExpanded = false; showDeleteChatDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Contact info") },
                                onClick = { menuExpanded = false; showInfoSheet = true }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Force LoRa TX",
                                        color = if (forceLoraEligible) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                trailingIcon = if (forceLoraEligible) {{
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }} else null,
                                onClick = { forceLoraEligible = !forceLoraEligible }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "LoRa-only RX",
                                        color = if (loraOnlyRx) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                trailingIcon = if (loraOnlyRx) {{
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }} else null,
                                onClick = {
                                    loraOnlyRx = !loraOnlyRx
                                    ForegroundMeshService.loraOnlyRxMode = loraOnlyRx
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
                if (forceLoraEligible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "LoRa eligible ON — packets marked for LoRa routing",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                if (loraOnlyRx) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "LoRa-only RX — direct BLE from phone is ignored",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Messages — weight(1f) here is NOT reduced by the keyboard because the
            // input bar lives outside this Column in the outer Box.
            // listBottomPadding = full current height of the input bar (content + nav bar +
            // keyboard when IME is open). This is exactly the gap the list needs so the last
            // item is always positioned above the visible input surface.
            val density = LocalDensity.current
            val listBottomPadding = with(density) { inputBarHeightPx.toDp() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = false,
                    verticalArrangement = Arrangement.Bottom,
                    contentPadding = PaddingValues(top = 8.dp, bottom = listBottomPadding)
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { idx, msg -> "${msg.msgIdHex}-$idx" }
                    ) { _, msg ->
                        val animateOnFirstShow = initialSnapshotDone && !seenMsgIds.contains(msg.msgIdHex)
                        MessageBubble(
                            message = msg,
                            animateOnFirstShow = animateOnFirstShow,
                            onLongPress = { bounds ->
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                contextMenuState = BubblePosition(msg, bounds)
                            }
                        )
                        LaunchedEffect(msg.msgIdHex) { seenMsgIds.add(msg.msgIdHex) }
                    }
                }
            }
        } // end main Column

        // Input bar — lives outside the main Column so its imePadding never reduces
        // the message list's height. It floats above the keyboard independently.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coords ->
                    // Track the current full height every frame. imePadding() is applied
                    // before this callback, so this value already includes keyboard height
                    // when the IME is open — exactly what listBottomPadding needs.
                    inputBarHeightPx = coords.size.height
                }
                .then(if (contextMenuState != null) Modifier.blur(8.dp) else Modifier)
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = input,
                        onValueChange = { new ->
                            val bytes = new.toByteArray(Charsets.UTF_8).size
                            if (bytes <= maxPayloadBytes) {
                                input = new
                            } else if (!shakeGuard) {
                                shakeGuard = true
                                scope.launch {
                                    shakeOffset.animateTo(-8f, tween(50))
                                    shakeOffset.animateTo(8f, tween(50))
                                    shakeOffset.animateTo(-6f, tween(50))
                                    shakeOffset.animateTo(6f, tween(50))
                                    shakeOffset.animateTo(0f, tween(50))
                                    shakeGuard = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = shakeOffset.value.dp)
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = {
                            Text(
                                "Message",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        },
                        singleLine = false,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.None),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    if (inputBytes > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (inputBytes.toFloat() / maxPayloadBytes >= 0.9f) {
                                Text(
                                    "Max $maxPayloadBytes bytes (LoRa packet limit)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = counterColor,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Text(
                                "$inputBytes / $maxPayloadBytes",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = counterColor
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (!canSend) return@FloatingActionButton
                        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        if (btManager?.adapter?.isEnabled == false) {
                            showBtOffDialog = true
                            return@FloatingActionButton
                        }
                        val text = input.trim()
                        input = ""
                        viewModel.sendMessage(text, forceLoraEligible)
                    },
                    containerColor = if (canSend) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }

        // Context menu overlay
        val overlayState = contextMenuState
        if (overlayState != null) {
            val overlayMsg = overlayState.message
            MessageContextOverlay(
                state = overlayState,
                onDismiss = { contextMenuState = null },
                onReact = { emoji ->
                    viewModel.sendReaction(overlayMsg.msgIdHex, emoji)
                    contextMenuState = null
                },
                onCustomEmoji = {
                    customEmojiTarget = overlayMsg.msgIdHex
                    showCustomEmojiDialog = true
                },
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("message", overlayMsg.text))
                        )
                    }
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    contextMenuState = null
                },
                onStar = {
                    viewModel.toggleStar(overlayMsg.msgIdHex)
                    contextMenuState = null
                },
                onShowPath = {
                    pathMsg = overlayMsg
                    contextMenuState = null
                    showPathSheet = true
                },
                onForward = {
                    forwardMsg = overlayMsg
                    contextMenuState = null
                    showForwardSheet = true
                },
                onResend = {
                    viewModel.resendMessage(overlayMsg.msgIdHex)
                    contextMenuState = null
                },
                onDeleteForMe = {
                    viewModel.deleteForMe(overlayMsg.msgIdHex)
                    contextMenuState = null
                },
                onDeleteForEveryone = {
                    viewModel.deleteForEveryone(overlayMsg.msgIdHex)
                    contextMenuState = null
                }
            )
        }
    }
}

private enum class DeleteConfirmMode { FOR_ME, FOR_EVERYONE }

@Composable
private fun MessageContextOverlay(
    state: BubblePosition,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onCustomEmoji: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onShowPath: () -> Unit,
    onForward: () -> Unit,
    onResend: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    val message = state.message
    val bounds  = state.bounds
    val density = LocalDensity.current
    var deleteConfirmMode by remember { mutableStateOf<DeleteConfirmMode?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenWidthPx  = with(density) { maxWidth.toPx() }

        val emojiBarHeightPx = with(density) { 56.dp.toPx() }
        val gapPx            = with(density) { 8.dp.toPx() }
        val menuWidthPx      = with(density) { 220.dp.toPx() }
        // Estimate menu card height: 4 base items + optional resend + 1 or 2 delete items
        val deleteItemCount  = 1 + if (message.isMine) 1 else 0
        val itemCount        = 4 + (if (message.isMine && message.status == DeliveryStatus.SENT) 1 else 0) + deleteItemCount
        val menuHeightPx     = with(density) { (itemCount * 46 + 2).dp.toPx() }

        val totalAboveNeeded = emojiBarHeightPx + gapPx * 2 + menuHeightPx
        val showAbove        = bounds.top >= totalAboveNeeded

        // Emoji bar: always just above the bubble (or just below if no space)
        val emojiBarTopPx = if (showAbove)
            bounds.top - gapPx - emojiBarHeightPx
        else
            bounds.bottom + gapPx

        // Menu card: above emoji bar when showing above, below it when showing below
        val menuTopRaw = if (showAbove)
            emojiBarTopPx - gapPx - menuHeightPx
        else
            emojiBarTopPx + emojiBarHeightPx + gapPx
        val menuTopPx = menuTopRaw.coerceIn(gapPx, screenHeightPx - menuHeightPx - gapPx)

        // Horizontal: right-align for outgoing, left-align for incoming
        val sidePaddingPx = with(density) { 8.dp.toPx() }
        val menuXPx = if (message.isMine)
            (bounds.right - menuWidthPx).coerceIn(sidePaddingPx, screenWidthPx - menuWidthPx - sidePaddingPx)
        else
            bounds.left.coerceIn(sidePaddingPx, screenWidthPx - menuWidthPx - sidePaddingPx)

        // ── Scrim ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onDismiss()
                }
        )

        // ── Re-rendered bubble (sharp, above scrim) ────────────────────────
        Box(
            modifier = Modifier.absoluteOffset {
                IntOffset(bounds.left.toInt(), bounds.top.toInt())
            }
        ) {
            BubbleContent(message = message)
        }

        // ── Emoji reaction bar ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(0, emojiBarTopPx.coerceAtLeast(0f).toInt()) }
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FIXED_EMOJIS.forEach { emoji ->
                        Text(
                            emoji,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .clickable { onReact(emoji) }
                                .padding(4.dp)
                        )
                    }
                    Text(
                        "+",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clickable { onCustomEmoji() }
                            .padding(4.dp)
                    )
                }
            }
        }

        // ── Context menu card ──────────────────────────────────────────────
        // wrapContentHeight: card must be only as tall as its items, never fills screen.
        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(menuXPx.toInt(), menuTopPx.toInt()) }
                .width(220.dp)
                .wrapContentHeight()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column {
                    ContextMenuItem(Icons.AutoMirrored.Filled.Send, "Forward") { onForward() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    ContextMenuItem(Icons.Default.ContentCopy, "Copy") { onCopy() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    ContextMenuItem(
                        if (message.starred) Icons.Filled.Star else Icons.Outlined.Star,
                        if (message.starred) "Unstar" else "Star",
                        tint = if (message.starred) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    ) { onStar() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    ContextMenuItem(Icons.Default.Info, "Show path") { onShowPath() }
                    if (message.isMine && message.status == DeliveryStatus.SENT) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                        ContextMenuItem(Icons.Default.Refresh, "Resend") { onResend() }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    if (deleteConfirmMode == null) {
                        ContextMenuItem(
                            Icons.Default.Delete,
                            "Delete for me",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        ) { deleteConfirmMode = DeleteConfirmMode.FOR_ME }
                        if (message.isMine) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                            ContextMenuItem(
                                Icons.Default.Delete,
                                "Delete for everyone",
                                tint = MaterialTheme.colorScheme.error
                            ) { deleteConfirmMode = DeleteConfirmMode.FOR_EVERYONE }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                if (deleteConfirmMode == DeleteConfirmMode.FOR_ME)
                                    "Delete for me?" else "Delete for everyone?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        when (deleteConfirmMode) {
                                            DeleteConfirmMode.FOR_ME       -> onDeleteForMe()
                                            DeleteConfirmMode.FOR_EVERYONE -> onDeleteForEveryone()
                                            null                           -> {}
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        "Confirm",
                                        color = if (deleteConfirmMode == DeleteConfirmMode.FOR_EVERYONE)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                TextButton(
                                    onClick = { deleteConfirmMode = null },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    animateOnFirstShow: Boolean,
    onLongPress: (Rect) -> Unit
) {
    if (message.deletedForEveryone) {
        TombstoneBubble(message = message)
        return
    }

    val isMine = message.isMine

    val alphaAnim  = remember(message.msgIdHex) { Animatable(1f) }
    val offsetAnim = remember(message.msgIdHex) { Animatable(0f) }

    LaunchedEffect(message.msgIdHex, animateOnFirstShow) {
        if (animateOnFirstShow) {
            alphaAnim.snapTo(0f); offsetAnim.snapTo(-12f)
            alphaAnim.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            offsetAnim.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        } else {
            alphaAnim.snapTo(1f); offsetAnim.snapTo(0f)
        }
    }

    // Capture the bubble box bounds for the context menu anchor
    var bubbleCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    val hasReactions = message.reactions.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 12.dp)
            .graphicsLayer { alpha = alphaAnim.value; translationY = offsetAnim.value },
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            // Single Box: bubble + reaction pill share the same layout boundary
            Box {
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { bubbleCoords = it }
                        .pointerInput(message.msgIdHex) {
                            detectTapGestures(onLongPress = {
                                bubbleCoords?.boundsInWindow()?.let { onLongPress(it) }
                            })
                        }
                ) {
                    BubbleContent(message = message, hasReactions = hasReactions)
                }

                if (hasReactions) {
                    val groups = message.reactions.groupBy { it.emoji }
                    ReactionPill(
                        groups = groups,
                        isMine = isMine,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .absoluteOffset(x = (-12).dp)
                    )
                }
            }
        }
    }
}

/** Muted placeholder shown when a message was deleted for everyone. No long-press, no reactions. */
@Composable
private fun TombstoneBubble(message: ChatUiMessage) {
    val isMine = message.isMine
    val sentShape     = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    val receivedShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    val shape = if (isMine) sentShape else receivedShape

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 12.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "This message was deleted",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

/** Renders the visual bubble box — shared between the list and the context menu overlay. */
@Composable
private fun BubbleContent(message: ChatUiMessage, hasReactions: Boolean = false) {
    val isMine = message.isMine
    val timeText = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampMs))
    }
    val sentShape     = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    val receivedShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)

    // Extra bottom padding reserves space so the reaction pill never overlaps the timestamp row.
    // 26 dp = ~24 dp pill height + 2 dp breathing room.
    val bottomPad = if (hasReactions) 26.dp else 10.dp

    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .then(
                if (isMine) Modifier.background(
                    brush = Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(0.85f))
                    ),
                    shape = sentShape
                )
                else Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, receivedShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, receivedShape)
            )
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = bottomPad)
    ) {
        Column {
            Text(
                message.text,
                color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (message.starred) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                               else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
                if (isMine) {
                    Spacer(Modifier.width(6.dp))
                    when (message.status) {
                        DeliveryStatus.SENT -> Icon(
                            Icons.Filled.Check,
                            contentDescription = "Sent",
                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        DeliveryStatus.DELIVERED -> {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(NodeGreen))
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Delivered",
                                tint = NodeGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            if (isMine && message.status == DeliveryStatus.DELIVERED && message.ackRttMs >= 0) {
                val hopLabel = if (message.hopCount == 0) "direct" else "${message.hopCount} hops"
                val rttLabel = if (message.ackRttMs < 1000) "${message.ackRttMs}ms"
                              else "${"%.1f".format(message.ackRttMs / 1000.0)}s"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        "$hopLabel · $rttLabel",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    )
                }
            }
            if (!isMine && message.hopCount >= 0) {
                val hopLabel = if (message.hopCount == 0) "direct" else "${message.hopCount} hops"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        hopLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

/**
 * Rounded pill showing emoji reactions attached to a bubble's bottom-left corner.
 * Up to 3 emoji groups are shown; excess groups are collapsed into a +N label.
 */
@Composable
private fun ReactionPill(
    groups: Map<String, List<ReactionDisplay>>,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val pillColor  = if (isMine) MaterialTheme.colorScheme.primaryContainer
                     else        MaterialTheme.colorScheme.surface
    val textColor  = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                     else        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val borderColor = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                      else        MaterialTheme.colorScheme.outline

    val entries = groups.entries.toList()
    val visible  = entries.take(3)
    val overflow = entries.size - visible.size

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = pillColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            visible.forEach { (emoji, reactors) ->
                Text(emoji, fontSize = 13.sp)
                if (reactors.size > 1) {
                    Text(
                        "${reactors.size}",
                        fontSize = 10.sp,
                        color = textColor
                    )
                }
            }
            if (overflow > 0) {
                Text(
                    "+$overflow",
                    fontSize = 10.sp,
                    color = textColor
                )
            }
        }
    }
}
