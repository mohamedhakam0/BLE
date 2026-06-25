package com.example.ble.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.ClipData
import java.util.Locale
import kotlinx.coroutines.launch

@DebugOnly
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StressTestScreen(
    viewModel: StressTestViewModel,
    onBack: () -> Unit,
    onShowLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isRunning by viewModel.isRunning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val results by viewModel.results.collectAsState()
    val currentLabel by viewModel.currentLabel.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val selectedContact by viewModel.selectedContact.collectAsState()
    var dropdownExpanded by remember { mutableStateOf(false) }

    var countInput by remember { mutableStateOf("500") }
    var intervalInput by remember { mutableStateOf("2000") }
    var activeTargetCount by remember { mutableIntStateOf(500) }

    val parsedCount = countInput.toIntOrNull()?.coerceAtLeast(1)
    val parsedInterval = intervalInput.toLongOrNull()?.coerceAtLeast(0L)

    val progressDenominator = if (isRunning) activeTargetCount else (parsedCount ?: activeTargetCount)
    val progressFraction = if (progressDenominator > 0) {
        progress.toFloat() / progressDenominator.toFloat()
    } else {
        0f
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onShowLogs) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "View Logs")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { if (!isRunning) dropdownExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedContact?.displayName ?: "No contacts available",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Test target contact") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    enabled = !isRunning && contacts.isNotEmpty()
                )
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    if (contacts.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No contacts found — scan a QR code first") },
                            onClick = { dropdownExpanded = false }
                        )
                    } else {
                        contacts.forEach { contact ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = contact.displayName,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = contact.senderIdHex.take(16) + "...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectContact(contact)
                                    dropdownExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }

            Text(
                text = "Results saved to Downloads/stress_*.csv",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
            value = countInput,
            onValueChange = { countInput = it.filter(Char::isDigit) },
            label = { Text("Message count") },
            enabled = !isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

            OutlinedTextField(
            value = intervalInput,
            onValueChange = { intervalInput = it.filter(Char::isDigit) },
            label = { Text("Interval ms") },
            enabled = !isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

            Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val count = parsedCount ?: return@Button
                    val interval = parsedInterval ?: return@Button
                    activeTargetCount = count
                    viewModel.startUnidirectional(count, interval)
                },
                enabled = !isRunning && parsedCount != null && parsedInterval != null && selectedContact != null && contacts.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Unidirectional")
            }

            Button(
                onClick = {
                    val count = parsedCount ?: return@Button
                    val interval = parsedInterval ?: return@Button
                    activeTargetCount = count
                    viewModel.startBidirectional(count, interval)
                },
                enabled = !isRunning && parsedCount != null && parsedInterval != null && selectedContact != null && contacts.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Bidirectional")
            }
        }

            LinearProgressIndicator(
            progress = { progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )

            Text("Sent: $progress / $progressDenominator messages")

            Button(
            onClick = { viewModel.stop() },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop")
        }

            if (results != null) {
                val result = results!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("MDR: ${formatPercent(result.mdr)}")
                        Text("Mean latency: ${result.meanLatencyMs} ms")
                        Text("Min latency: ${result.minLatencyMs} ms")
                        Text("Max latency: ${result.maxLatencyMs} ms")
                        Text("ACK count: ${result.totalAcked}")
                        Text("Packet loss rate: ${formatPercent(result.packetLossRate)}")
                        Text("RSSI mean: ${formatFloat(result.rssiMean)}")
                        Text("RSSI min: ${result.rssiMin}")
                        Text("RSSI max: ${result.rssiMax}")
                        Text("Duration: ${result.durationMs} ms")
                    }
                }
            

                Button(
                onClick = {
                    val csvLine = buildString {
                        append(currentLabel)
                        append(',')
                        append(result.totalSent)
                        append(',')
                        append(result.totalAcked)
                        append(',')
                        append(formatPercentValue(result.mdr))
                        append(',')
                        append(result.meanLatencyMs)
                        append(',')
                        append(result.minLatencyMs)
                        append(',')
                        append(result.maxLatencyMs)
                        append(',')
                        append(formatPercentValue(result.packetLossRate))
                        append(',')
                        append(formatFloat(result.rssiMean))
                        append(',')
                        append(result.rssiMin)
                        append(',')
                        append(result.rssiMax)
                        append(',')
                        append(result.durationMs)
                    }
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("stress test results", csvLine))
                        )
                    }
                },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy Results")
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Label, Sent, Acked, MDR%, MeanLatMs, MinLatMs, MaxLatMs, PacketLoss%, RSSImean, RSSImin, RSSImax, DurationMs",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatPercent(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

private fun formatPercentValue(value: Float): String =
    String.format(Locale.US, "%.1f", value * 100f)

private fun formatFloat(value: Float): String =
    String.format(Locale.US, "%.1f", value)




