package com.example.ble

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ble.ui.GradientAvatarCircle
import com.example.ble.ui.theme.Accent
import com.example.ble.ui.theme.AccentGlow
import com.example.ble.ui.theme.NodeGreen
import com.example.ble.ui.theme.NodeGreenDim
import com.example.ble.ui.theme.NodeAmber
import com.example.ble.ui.theme.NodeAmberDim
import com.example.ble.ui.theme.NodePurple
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NeighborListScreen(contactRepository: ContactRepository? = null) {
    val neighbors = NeighborTable.neighbors.collectAsStateWithLifecycle().value
    val directNeighbors = neighbors.filter { it.hopCount == 0 }
    val directCount = directNeighbors.size
    val extendedCount = neighbors.size - directCount

    val contactsFlow = remember(contactRepository) { contactRepository?.observeContactsWithLastMessage() }
    val contacts by contactsFlow?.collectAsState(initial = emptyList()) ?: remember { androidx.compose.runtime.mutableStateOf(emptyList()) }
    val nameMap = remember(contacts) {
        contacts.associate { it.senderId.lowercase() to it.nickname }
    }
    val gradientSeedMap = remember(contacts) {
        contacts.associate { it.senderId.lowercase() to it.gradientSeed }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Page header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Network",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "${neighbors.size} nodes",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Mesh visualization canvas
        MeshCanvas(directNeighbors = directNeighbors)

        // Legend
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendItem(color = Accent, label = "this device")
            LegendItem(color = NodeGreen, label = "ble peer")
        }

        // Stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(value = "$directCount", label = "DIRECT", valueColor = NodeGreen, modifier = Modifier.weight(1f))
            StatCard(value = "$extendedCount", label = "EXTENDED", valueColor = NodeAmber, modifier = Modifier.weight(1f))
            StatCard(value = "${neighbors.size}", label = "TOTAL", valueColor = Accent, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Node list
        if (neighbors.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No neighbors detected yet",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(neighbors, key = { "${it.nodeId}_${it.hopCount}" }) { entry ->
                    NeighborRow(
                        entry = entry,
                        contactName = nameMap[entry.nodeId.lowercase()],
                        gradientSeedHex = gradientSeedMap[entry.nodeId.lowercase()] ?: ""
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshCanvas(directNeighbors: List<NeighborEntry>) {
    val accentColor = Accent
    val greenColor = NodeGreen
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, outlineColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 14.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = minOf(size.width, size.height) / 2.8f

            // Pulse rings around center
            drawCircle(
                color = accentColor.copy(alpha = 0.08f),
                radius = radius * 1.2f,
                center = Offset(centerX, centerY),
                style = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.04f),
                radius = radius * 1.6f,
                center = Offset(centerX, centerY),
                style = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                )
            )

            // Draw connections from center to each neighbor
            val nodeCount = directNeighbors.size
            val nodePositions = mutableListOf<Offset>()

            for (i in 0 until nodeCount) {
                val angle = (2.0 * PI * i / nodeCount) - PI / 2.0
                val dist = radius * 0.75f + (i % 3) * radius * 0.15f
                val nx = centerX + (cos(angle) * dist).toFloat()
                val ny = centerY + (sin(angle) * dist).toFloat()
                nodePositions.add(Offset(nx, ny))

                // Connection line
                drawLine(
                    color = greenColor.copy(alpha = 0.15f),
                    start = Offset(centerX, centerY),
                    end = Offset(nx, ny),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                )
            }

            // Draw center node (this device)
            drawCircle(
                color = accentColor.copy(alpha = 0.15f),
                radius = 18f,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = accentColor,
                radius = 10f,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = accentColor,
                radius = 14f,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2f)
            )

            // Draw neighbor nodes
            nodePositions.forEachIndexed { i, pos ->
                drawCircle(
                    color = greenColor.copy(alpha = 0.12f),
                    radius = 12f,
                    center = pos
                )
                drawCircle(
                    color = greenColor,
                    radius = 7f,
                    center = pos
                )
                drawCircle(
                    color = greenColor,
                    radius = 10f,
                    center = pos,
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                value,
                color = valueColor,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace
            )
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun NeighborRow(
    entry: NeighborEntry,
    contactName: String? = null,
    gradientSeedHex: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (contactName != null && gradientSeedHex.length >= 6) {
                GradientAvatarCircle(gradientSeedHex = gradientSeedHex, size = 32.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (entry.hopCount == 0) NodeGreen else NodeAmber,
                            CircleShape
                        )
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                if (contactName != null) {
                    Text(
                        contactName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${entry.nodeId.take(8)}...",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                } else {
                    Text(
                        "${entry.nodeId.take(8)}...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (entry.hopCount == 0) "direct · ${secondsAgo(entry.lastSeen)}s ago"
                    else "${entry.hopCount} hops · via ${(entry.seenVia ?: "?").take(6)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }

        // RSSI
        Column(horizontalAlignment = Alignment.End) {
            SignalBarsIcon(rssi = entry.rssi)
            Text(
                "${entry.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 9.sp
            )
        }
    }
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

    Canvas(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
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
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}
