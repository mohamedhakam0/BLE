/**
 * Compose UI screen for inspecting, filtering, clearing, and sharing in-app logs.
 *
 * The screen reads entries from `AppLogger`, supports text filtering, and exports logs as
 * a cache file shared through a FileProvider URI.
 */
package com.example.ble.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ble.AppLogger
import java.io.File

/**
 * Renders a searchable log list with actions to clear and share log output.
 *
 * @param onBack callback used to navigate back to the previous screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    // A small tick to force refresh when screen is open (logs may be appended from threads).
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            refreshTick++
        }
    }

    val entries = remember(refreshTick) { AppLogger.snapshot() }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter {
            it.tag.contains(query, ignoreCase = true) || it.message.contains(query, ignoreCase = true)
        }
    }

    fun shareLogsAsFile() {
        val text = AppLogger.dumpText(query)

        val senderId = extractSenderIdOrFallback(text)
        val firstTs = extractFirstTimestampOrNow(text)
        val safeTs = firstTs.replace(':', '-').replace('.', '-').replace(' ', '_')
        val fileName = "${senderId}_${safeTs}.txt"

        val file = File(context.cacheDir, fileName)
        file.writeText(text)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share logs"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                actions = {
                    IconButton(onClick = { AppLogger.clear(); refreshTick++ }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                    IconButton(onClick = { shareLogsAsFile() }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                label = { Text("Filter by tag or message") }
            )

            Spacer(Modifier.height(8.dp))

            val newestFirst = filtered.asReversed()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    items = newestFirst,
                    key = { it.timestamp + it.tag + it.message.hashCode() }
                ) { e ->
                    val idx = newestFirst.indexOf(e)
                    val bg = if (idx % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${e.timestamp} ${e.level.name}/${e.tag}: ${e.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Unspecified
                        )
                    }
                }
            }
        }
    }
}

/** Extracts the first timestamp token from dump text, with a safe fallback. */
private fun extractFirstTimestampOrNow(text: String): String {
    // Expected line format: "HH:mm:ss.SSS ..."
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "00:00:00.000"
    val ts = firstLine.takeWhile { it != ' ' }
    return if (ts.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"))) ts else "00:00:00.000"
}

/** Best-effort sender-id extraction used to build shared log file names. */
private fun extractSenderIdOrFallback(text: String): String {
    // We don’t have a guaranteed identity line in logs, so use best-effort:
    // Look for "senderId=" or "myId=" hex-ish tokens; fallback to "unknown".
    val regexes = listOf(
        Regex("senderId=([0-9a-fA-F]{8,})"),
        Regex("myId=([0-9a-fA-F]{8,})"),
        Regex("sender_id=([0-9a-fA-F]{8,})")
    )

    for (r in regexes) {
        val m = r.find(text)
        if (m != null) return m.groupValues[1]
    }

    return "unknown"
}
