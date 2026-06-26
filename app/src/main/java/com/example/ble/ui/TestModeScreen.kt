package com.example.ble.ui

import android.app.Application
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class TestScreen { PICKER, CONFIG, RUNNING, RESULTS }

@Composable
fun TestModeScreen(
    nodeIdentity: NodeIdentity,
    advertiser: BleAdvertiser,
    contactRepository: ContactRepository,
    application: Application,
    onBack: () -> Unit
) {
    val vm: TestModeViewModel = viewModel(
        factory = TestModeViewModel.Factory(application, nodeIdentity, advertiser, contactRepository)
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(TestScreen.PICKER) }
    var selectedPreset by remember { mutableStateOf<TestExperiments.Preset?>(null) }
    var currentConfig by remember { mutableStateOf<TestConfig?>(null) }

    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { contactRepository.getAllContacts() }
    }

    LaunchedEffect(uiState.isComplete, uiState.isRunning) {
        if (uiState.isComplete && !uiState.isRunning && screen == TestScreen.RUNNING) {
            screen = TestScreen.RESULTS
        }
    }

    BackHandler(enabled = screen != TestScreen.PICKER) {
        when (screen) {
            TestScreen.CONFIG  -> screen = TestScreen.PICKER
            TestScreen.RUNNING -> { vm.stopSession(); screen = TestScreen.RESULTS }
            TestScreen.RESULTS -> screen = TestScreen.PICKER
            else               -> Unit
        }
    }

    when (screen) {
        TestScreen.PICKER -> PickerScreen(
            onBack   = onBack,
            onSelect = { preset ->
                selectedPreset = preset
                screen = TestScreen.CONFIG
            }
        )

        TestScreen.CONFIG -> {
            val preset = selectedPreset ?: return
            ConfigScreen(
                preset    = preset,
                contacts  = contacts,
                onBack    = { screen = TestScreen.PICKER },
                onStart   = { config ->
                    currentConfig = config
                    vm.startSession(config)
                    screen = TestScreen.RUNNING
                }
            )
        }

        TestScreen.RUNNING -> {
            val cfg = currentConfig ?: return
            RunningScreen(
                config  = cfg,
                uiState = uiState,
                onStop  = { vm.stopSession(); screen = TestScreen.RESULTS }
            )
        }

        TestScreen.RESULTS -> {
            val cfg = currentConfig ?: return
            val context = LocalContext.current
            ResultsScreen(
                config          = cfg,
                uiState         = uiState,
                onExport        = {
                    val intent = vm.buildExportIntent()
                    if (intent != null) {
                        context.startActivity(Intent.createChooser(intent, "Export CSV"))
                    }
                },
                onRunAnother    = { screen = TestScreen.CONFIG },
                onNewExperiment = { screen = TestScreen.PICKER }
            )
        }
    }
}

// ── PICKER ────────────────────────────────────────────────────────────────────

@Composable
private fun PickerScreen(
    onBack: () -> Unit,
    onSelect: (TestExperiments.Preset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        ScreenTopBar(title = "Test Mode", onBack = onBack)

        Text(
            "Select experiment",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(TestExperiments.all) { preset ->
                ExperimentCard(preset = preset, onClick = { onSelect(preset) })
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun ExperimentCard(preset: TestExperiments.Preset, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preset.experimentId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${preset.messageCount} msg",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    if (preset.intervalMs == 0L) "burst" else "${preset.intervalMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ── CONFIG ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    preset: TestExperiments.Preset,
    contacts: List<Contact>,
    onBack: () -> Unit,
    onStart: (TestConfig) -> Unit
) {
    var role by remember { mutableStateOf(TestRole.SENDER) }
    var trialNum by remember { mutableStateOf("1") }
    var msgCount by remember { mutableStateOf(preset.messageCount) }
    var intervalMs by remember { mutableStateOf(preset.intervalMs) }
    var environment by remember { mutableStateOf("indoor-quiet") }
    var distanceLabel by remember { mutableStateOf("") }
    var targetPeer by remember { mutableStateOf("") }
    var loraEligible by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var isCustomRate by remember { mutableStateOf(false) }
    var customRateText by remember { mutableStateOf("") }
    var isCustomCount by remember { mutableStateOf(false) }
    var customCountText by remember { mutableStateOf("") }

    var envExpanded by remember { mutableStateOf(false) }
    var peerExpanded by remember { mutableStateOf(false) }
    val environments = listOf("indoor-quiet", "indoor-busy", "outdoor")
    val msgCounts = listOf(20, 30, 50, 100)
    val rates = listOf(10000L to "10s", 5000L to "5s", 2000L to "2s", 1000L to "1s", 0L to "Burst")

    val trialNumInt = trialNum.toIntOrNull()?.coerceAtLeast(1) ?: 1

    val selectedContact by remember(targetPeer, contacts) {
        derivedStateOf { contacts.firstOrNull { it.senderId.lowercase() == targetPeer.lowercase() } }
    }
    val peerLabel = when {
        selectedContact != null -> selectedContact!!.nickname
        targetPeer.isBlank()   -> "None — broadcast"
        else                   -> targetPeer
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        ScreenTopBar(title = preset.experimentId, onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Role
            ConfigSection("Role") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestRole.entries.forEach { r ->
                        val selected = role == r
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { role = r },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(
                                r.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Trial Number
            ConfigSection("Trial #") {
                OutlinedTextField(
                    value = trialNum,
                    onValueChange = { v -> if (v.length <= 4) trialNum = v.filter { it.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // Message Count
            ConfigSection("Message count") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    msgCounts.forEach { count ->
                        val selected = !isCustomCount && msgCount == count
                        FilterChip(
                            selected = selected,
                            onClick = { msgCount = count; isCustomCount = false },
                            label = { Text("$count") }
                        )
                    }
                    FilterChip(
                        selected = isCustomCount,
                        onClick = { isCustomCount = true },
                        label = { Text("Custom") }
                    )
                }
                if (isCustomCount) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customCountText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }
                            if ((digits.toIntOrNull() ?: 0) <= 999) customCountText = digits
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Number of messages", color = MaterialTheme.colorScheme.onSurface.copy(0.35f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // Rate
            ConfigSection("Rate") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rates.forEach { (ms, label) ->
                        val selected = !isCustomRate && intervalMs == ms
                        FilterChip(
                            selected = selected,
                            onClick = { intervalMs = ms; isCustomRate = false },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = isCustomRate,
                        onClick = { isCustomRate = true },
                        label = { Text("Custom") }
                    )
                }
                if (isCustomRate) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customRateText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }
                            if ((digits.toIntOrNull() ?: 0) <= 60) customRateText = digits
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Interval in seconds", color = MaterialTheme.colorScheme.onSurface.copy(0.35f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        suffix = { Text("s") }
                    )
                }
            }

            // Environment
            ConfigSection("Environment") {
                Box {
                    OutlinedButton(
                        onClick = { envExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(environment, modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(
                        expanded = envExpanded,
                        onDismissRequest = { envExpanded = false }
                    ) {
                        environments.forEach { env ->
                            DropdownMenuItem(
                                text = { Text(env) },
                                onClick = { environment = env; envExpanded = false }
                            )
                        }
                    }
                }
            }

            // Distance
            ConfigSection("Distance label") {
                OutlinedTextField(
                    value = distanceLabel,
                    onValueChange = { distanceLabel = it.take(30) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. 10m", color = MaterialTheme.colorScheme.onSurface.copy(0.35f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // Target peer — contact picker
            ConfigSection("Target peer (optional)") {
                ExposedDropdownMenuBox(
                    expanded = peerExpanded,
                    onExpandedChange = { peerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = peerLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = peerExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = peerExpanded,
                        onDismissRequest = { peerExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "None — broadcast",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            },
                            onClick = { targetPeer = ""; peerExpanded = false }
                        )
                        if (contacts.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                        contacts.forEach { contact ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            contact.nickname,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            contact.senderId,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                                        )
                                    }
                                },
                                onClick = { targetPeer = contact.senderId; peerExpanded = false }
                            )
                        }
                    }
                }
            }

            // LoRa flag
            ConfigSection("LoRa eligible") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Mark packets as LoRa-eligible\n(sets LORA_ELIGIBLE flag in header)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = loraEligible,
                        onCheckedChange = { loraEligible = it }
                    )
                }
            }

            // Notes
            ConfigSection("Notes") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(200) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(Modifier.height(4.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 4.dp
        ) {
            Button(
                onClick = {
                    val finalIntervalMs = if (isCustomRate)
                        (customRateText.toLongOrNull() ?: 5L) * 1000L
                    else intervalMs
                    val finalMsgCount = if (isCustomCount)
                        customCountText.toIntOrNull()?.coerceAtLeast(1) ?: msgCount
                    else msgCount
                    onStart(
                        TestConfig(
                            experimentId  = preset.experimentId,
                            trialNum      = trialNumInt,
                            role          = role,
                            messageCount  = finalMsgCount,
                            intervalMs    = finalIntervalMs,
                            environment   = environment,
                            distanceLabel = distanceLabel,
                            targetPeerId  = targetPeer.ifBlank { null },
                            loraEligible  = loraEligible,
                            notes         = notes
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Start Trial", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ConfigSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )
        content()
    }
}

// ── RUNNING ───────────────────────────────────────────────────────────────────

@Composable
private fun RunningScreen(
    config: TestConfig,
    uiState: TestUiState,
    onStop: () -> Unit
) {
    val total = config.messageCount
    val progressFraction = when (config.role) {
        TestRole.SENDER   -> if (total > 0) uiState.sentCount.toFloat() / total else 0f
        TestRole.RECEIVER -> if (total > 0) uiState.receivedCount.toFloat() / total else 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                config.experimentId,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Trial ${config.trialNum}  ·  ${config.role.name}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        Spacer(Modifier.height(32.dp))

        // Large MDR
        Text(
            "${(uiState.liveMdr * 100).roundToInt()}%",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "MDR",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )

        Spacer(Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (config.role) {
                TestRole.SENDER   -> "${uiState.sentCount} / $total sent"
                TestRole.RECEIVER -> "${uiState.receivedCount} / $total received"
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )

        Spacer(Modifier.height(24.dp))

        // Stats
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCell("SENT",  "${uiState.sentCount}")
                StatCell("RCVD",  "${uiState.receivedCount}")
                StatCell("ACKED", "${uiState.ackedCount}")
                StatCell("AVG RTT", if (uiState.meanLatencyMs > 0L) "${uiState.meanLatencyMs}ms" else "—")
            }
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.error
            )
        ) {
            Text("Stop", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── RESULTS ───────────────────────────────────────────────────────────────────

@Composable
private fun ResultsScreen(
    config: TestConfig,
    uiState: TestUiState,
    onExport: () -> Unit,
    onRunAnother: () -> Unit,
    onNewExperiment: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Results",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "${config.experimentId}  T${config.trialNum}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Summary card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BigMetric(
                        "${(uiState.liveMdr * 100).roundToInt()}%",
                        "MDR"
                    )
                    BigMetric(
                        if (uiState.meanLatencyMs > 0) "${uiState.meanLatencyMs}ms" else "—",
                        "AVG RTT"
                    )
                }

                Divider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outline
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCell("SENT",    "${uiState.sentCount}")
                    StatCell("RCVD",    "${uiState.receivedCount}")
                    StatCell("ACKED",   "${uiState.ackedCount}")
                    StatCell("PLANNED", "${config.messageCount}")
                }

                Divider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outline
                )

                SummaryRow("Role",        config.role.name)
                SummaryRow("Environment", config.environment)
                SummaryRow("Distance",    config.distanceLabel.ifBlank { "—" })
                SummaryRow("Rate",        if (config.intervalMs == 0L) "burst" else "${config.intervalMs / 1000}s/msg")
                SummaryRow("LoRa",        if (config.loraEligible) "eligible" else "off")
                if (config.notes.isNotBlank()) SummaryRow("Notes", config.notes)
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Export CSV", style = MaterialTheme.typography.labelLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRunAnother,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Another Trial") }
                OutlinedButton(
                    onClick = onNewExperiment,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("New Experiment") }
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun BigMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
