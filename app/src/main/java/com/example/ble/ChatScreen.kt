package com.example.ble

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName: String,
    receiverIdHex: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var inputFocused by remember { mutableStateOf(false) }

    BackHandler {
        if (inputFocused) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        } else {
            onBack()
        }
    }

    // Hidden triple-tap gesture state
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Auto-scroll to the newest message whenever the list grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete chat history?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteHistory(receiverIdHex)
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Track which messages existed on initial load so we only animate *new* ones.
    val seenMsgIds = remember { HashSet<String>() }
    var initialSnapshotDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (!initialSnapshotDone) {
            messages.forEach { seenMsgIds.add(it.msgIdHex) }
            initialSnapshotDone = true
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = contactName,
                        modifier = Modifier.clickable {
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
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // keyboard pushes layout up
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = false,
                    // Space ABOVE the first item so short chats are pinned to bottom.
                    verticalArrangement = Arrangement.Bottom,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { idx, msg -> "${msg.msgIdHex}-$idx" }
                    ) { _, msg ->
                        val animateOnFirstShow = initialSnapshotDone && !seenMsgIds.contains(msg.msgIdHex)
                        MessageBubble(message = msg, animateOnFirstShow = animateOnFirstShow)

                        LaunchedEffect(msg.msgIdHex) {
                            seenMsgIds.add(msg.msgIdHex)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { inputFocused = it.isFocused },
                    placeholder = { Text("Message") },
                    singleLine = false,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.None
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@Button
                        input = ""
                        viewModel.sendMessage(text)
                    },
                    enabled = input.isNotBlank()
                ) {
                    Text("Send")
                }
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

    // Safe fade + subtle slide (no AnimatedVisibility / no animateItemPlacement)
    val alphaAnim = remember(message.msgIdHex) { Animatable(1f) }
    val offsetAnim = remember(message.msgIdHex) { Animatable(0f) }

    LaunchedEffect(message.msgIdHex, animateOnFirstShow) {
        if (animateOnFirstShow) {
            alphaAnim.snapTo(0f)
            offsetAnim.snapTo(-12f)
            alphaAnim.animateTo(
                1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            )
            offsetAnim.animateTo(
                0f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            )
        } else {
            alphaAnim.snapTo(1f)
            offsetAnim.snapTo(0f)
        }
    }

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
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isMine) Color(0xFFE1FFC7) else Color(0xFFFFFFFF)
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(message.text)
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )

                    if (isMine) {
                        Spacer(Modifier.width(6.dp))
                        when (message.status) {
                            DeliveryStatus.SENT -> {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Sent",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DeliveryStatus.DELIVERED -> {
                                Row {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Delivered",
                                        tint = Color(0xFF009688),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Delivered",
                                        tint = Color(0xFF009688),
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
}
