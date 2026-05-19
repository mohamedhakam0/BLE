package com.example.ble

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ble.ui.ContactAvatarCircle
import com.example.ble.ui.ContactInfoSheet
import com.example.ble.ui.theme.NodeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    contactName: String,
    receiverIdHex: String,
    gradientSeedHex: String = "",
    publicKeyB64: String = "",
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var inputFocused by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) AvatarManager.save(context, receiverIdHex, bmp)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Clear chat history?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteHistory(receiverIdHex)
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showInfoSheet) {
        ContactInfoSheet(
            contactName = contactName,
            senderIdHex = receiverIdHex,
            gradientSeedHex = gradientSeedHex,
            publicKeyB64 = publicKeyB64,
            onDismiss = { showInfoSheet = false }
        )
    }

    val seenMsgIds = remember { HashSet<String>() }
    var initialSnapshotDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (!initialSnapshotDone) {
            messages.forEach { seenMsgIds.add(it.msgIdHex) }
            initialSnapshotDone = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
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
                if (gradientSeedHex.length >= 6) {
                    ContactAvatarCircle(
                        senderIdHex = receiverIdHex,
                        gradientSeedHex = gradientSeedHex,
                        size = 36.dp
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
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
                                showDeleteDialog = true
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
                            onClick = { menuExpanded = false; AvatarManager.delete(context, receiverIdHex) }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            onClick = { menuExpanded = false; showDeleteDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Contact info") },
                            onClick = { menuExpanded = false; showInfoSheet = true }
                        )
                    }
                }
            }
            Divider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        }

        // Messages
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = false,
                verticalArrangement = Arrangement.Bottom,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = messages,
                    key = { idx, msg -> "${msg.msgIdHex}-$idx" }
                ) { _, msg ->
                    val animateOnFirstShow = initialSnapshotDone && !seenMsgIds.contains(msg.msgIdHex)
                    MessageBubble(message = msg, animateOnFirstShow = animateOnFirstShow)
                    LaunchedEffect(msg.msgIdHex) { seenMsgIds.add(msg.msgIdHex) }
                }
            }
        }

        // Input bar
        Divider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
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
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@FloatingActionButton
                    input = ""
                    viewModel.sendMessage(text)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    animateOnFirstShow: Boolean
) {
    val isMine = message.isMine
    val timeText = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampMs))
    }

    val alphaAnim = remember(message.msgIdHex) { Animatable(1f) }
    val offsetAnim = remember(message.msgIdHex) { Animatable(0f) }

    LaunchedEffect(message.msgIdHex, animateOnFirstShow) {
        if (animateOnFirstShow) {
            alphaAnim.snapTo(0f)
            offsetAnim.snapTo(-12f)
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing))
            offsetAnim.animateTo(0f, animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing))
        } else {
            alphaAnim.snapTo(1f)
            offsetAnim.snapTo(0f)
        }
    }

    val sentShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    val receivedShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                alpha = alphaAnim.value
                translationY = offsetAnim.value
            },
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(
                    if (isMine) {
                        Modifier.background(
                            brush = Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(0.85f)
                                )
                            ),
                            shape = sentShape
                        )
                    } else {
                        Modifier
                            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = receivedShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, receivedShape)
                    }
                )
                .padding(10.dp)
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
                            DeliveryStatus.SENT -> {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Sent",
                                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DeliveryStatus.DELIVERED -> {
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(NodeGreen)
                                )
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
            }
        }
    }
}
