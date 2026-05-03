package com.example.ble

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborListScreen() {
    val neighbors = NeighborTable.neighbors.collectAsStateWithLifecycle().value
    val directCount = neighbors.count { it.hopCount == 0 }
    val extendedCount = neighbors.size - directCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nearby Nodes")
                        Text(
                            text = "$directCount direct · $extendedCount extended",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (neighbors.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("No neighbors detected yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(neighbors, key = { "${it.nodeId}_${it.hopCount}" }) { entry ->
                    if (entry.hopCount == 0) {
                        DirectNeighborRow(entry)
                    } else {
                        ExtendedNeighborRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectNeighborRow(entry: NeighborEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.nodeId.take(8)}...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HopLabel(minHopCount = entry.hopCount)
            Text(
                text = "${secondsAgo(entry.lastSeen)}s ago",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBarsIcon(rssi = entry.rssi)
            Text(
                text = "${entry.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ExtendedNeighborRow(entry: NeighborEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.nodeId.take(8)}...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            HopLabel(minHopCount = entry.hopCount)
            Text(
                text = "via ${(entry.seenVia ?: "unknown").take(8)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "${entry.rssi} dBm",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun HopLabel(minHopCount: Int) {
    val hopLabel = when {
        minHopCount == 0 -> "direct"
        minHopCount == 1 -> "1 hop"
        else -> "$minHopCount hops"
    }

    Text(
        text = hopLabel,
        style = MaterialTheme.typography.labelSmall,
        color = if (minHopCount == 0) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        }
    )
}

private fun secondsAgo(lastSeenMs: Long): Long =
    ((System.currentTimeMillis() - lastSeenMs) / 1000L).coerceAtLeast(0L)

@Composable
private fun SignalBarsIcon(rssi: Int) {
    val activeBars = when {
        rssi > -50 -> 4
        rssi >= -65 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Canvas(modifier = Modifier.size(width = 20.dp, height = 16.dp)) {
        val barWidth = size.width / 6f
        val spacing = barWidth / 2f
        for (i in 0 until 4) {
            val heightFactor = (i + 1) / 4f
            val left = i * (barWidth + spacing)
            val top = size.height * (1f - heightFactor)
            val color = if (i < activeBars) activeColor else inactiveColor
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, size.height - top),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}
