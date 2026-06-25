package com.example.ble.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// ── Prefs helper — called from MainActivity and (future) identity-reset flow ──────────
object OnboardingPrefs {
    private const val PREFS_FILE = "peer_reach_prefs"
    private const val KEY_DONE   = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()

    /** Clear so onboarding shows again — call when node identity is reset. */
    fun reset(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_DONE).apply()
}

// ── Colour palette (onboarding is always dark regardless of app theme) ───────────────
private val ObBg     = Color(0xFF0A0C10)
private val ObText1  = Color(0xFFF0F2F8)
private val ObText2  = Color(0xFF9BA3B8)
private val ObText3  = Color(0xFF5C647A)
private val ObAccent = Color(0xFF4A9EFF)
private val ObGreen  = Color(0xFF34D399)
private val ObAmber  = Color(0xFFFBBF24)
private val ObPurple = Color(0xFFA78BFA)

private data class ObSlide(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val body: String,                       // ** wraps bold/highlighted spans
    val chips: List<Pair<String, Color>>
)

// Slot 0 = splash (handled separately); slots 1-6 = content slides
private val SLIDES: Array<ObSlide?> = arrayOf(
    null,
    ObSlide(
        icon = Icons.Filled.WifiOff, iconTint = ObAccent,
        title = "Works without internet",
        body  = "Peer Reach creates its own network between devices. " +
                "**No Wi-Fi, no cellular, no data plan required.** " +
                "It works in remote areas, during outages, or anywhere infrastructure is unavailable.",
        chips = listOf("Offline-first" to ObAccent, "No SIM needed" to ObGreen, "No account" to ObAmber)
    ),
    ObSlide(
        icon = Icons.Filled.Sensors, iconTint = ObGreen,
        title = "BLE mesh networking",
        body  = "Your phone broadcasts encrypted packets over **Bluetooth Low Energy**. " +
                "Messages hop from device to device — each node relays what it receives, " +
                "extending the network range automatically.",
        chips = listOf("BLE 5.0" to ObGreen, "LE Coded PHY" to ObGreen, "Multi-hop relay" to ObGreen)
    ),
    ObSlide(
        icon = Icons.Filled.Router, iconTint = ObAmber,
        title = "LoRa extends your reach",
        body  = "ESP32 relay nodes bridge BLE clusters over **LoRa radio**, " +
                "stretching the network up to **1.4 km**. " +
                "Your message travels BLE → ESP32 → LoRa → ESP32 → BLE, " +
                "reaching peers far beyond Bluetooth range.",
        chips = listOf("868 MHz" to ObAmber, "~1.4 km range" to ObAmber, "LR1120" to ObAmber)
    ),
    ObSlide(
        icon = Icons.Filled.Lock, iconTint = ObPurple,
        title = "End-to-end encrypted",
        body  = "Every message is encrypted with **AES-128-GCM** using keys only you and your contact hold. " +
                "Keys are exchanged in person via QR code — relay nodes forward encrypted blobs " +
                "they **cannot read**.",
        chips = listOf("Curve25519" to ObPurple, "AES-128-GCM" to ObPurple, "QR key exchange" to ObPurple)
    ),
    ObSlide(
        icon = Icons.Filled.Hub, iconTint = ObAccent,
        title = "Fully decentralised",
        body  = "There is **no server, no account, no authority**. Every device is equal. " +
                "The network forms itself from whoever is nearby. " +
                "If a node disappears, messages reroute through other peers automatically.",
        chips = listOf("No server" to ObAccent, "Self-forming" to ObAccent, "Resilient routing" to ObAccent)
    ),
    ObSlide(
        icon = Icons.Filled.VerifiedUser, iconTint = ObGreen,
        title = "Two quick permissions",
        body  = "**Camera** is used only to scan a contact's QR code for key exchange — " +
                "never for anything else.\n\n" +
                "**Bluetooth** lets your phone discover nearby devices and exchange messages " +
                "over the mesh.\n\n" +
                "**Approximate location** is required by Android for BLE scanning. " +
                "Your location is computed locally and is **never transmitted to any server or third party**.",
        chips = listOf("Camera · QR only" to ObGreen, "Location stays on-device" to ObGreen)
    ),
)

private const val SLIDE_COUNT = 7   // slides 0-6

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var slide by remember { mutableStateOf(0) }

    // Only request permissions that haven't been granted yet
    val toRequest = remember {
        buildList<String> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onComplete() }

    fun enter() {
        if (toRequest.isEmpty()) onComplete()
        else launcher.launch(toRequest.toTypedArray())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ObBg)
    ) {
        // ── Slide area (leaves 200dp at bottom for controls) ──────────────────────────
        AnimatedContent(
            targetState = slide,
            transitionSpec = {
                val fwd = targetState > initialState
                (slideInHorizontally { if (fwd) it else -it } + fadeIn()) togetherWith
                (slideOutHorizontally { if (fwd) -it else it } + fadeOut())
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 200.dp),
            contentAlignment = Alignment.Center,
            label = "ob_slide"
        ) { s ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp)
                    .statusBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                if (s == 0) ObSplash()
                else SLIDES[s]?.let { ObContent(it) }
            }
        }

        // ── Bottom controls ───────────────────────────────────────────────────────────
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(ObBg)
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(SLIDE_COUNT) { i ->
                    val active = i == slide
                    Box(
                        Modifier
                            .width(if (active) 20.dp else 6.dp)
                            .height(6.dp)
                            .background(
                                if (active) ObAccent else Color(0x26FFFFFF),
                                RoundedCornerShape(3.dp)
                            )
                            .clickable { slide = i }
                    )
                }
            }

            // Navigation buttons
            if (slide == 0) {
                Button(
                    onClick = { slide = 1 },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObAccent),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Get Started", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { slide-- },
                        modifier = Modifier.weight(1f).height(50.dp),
                        border = BorderStroke(1.dp, Color(0x26FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ObText2),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { if (slide < SLIDE_COUNT - 1) slide++ else enter() },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObAccent),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            if (slide == SLIDE_COUNT - 1) "Allow & Enter" else "Next",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Skip link on middle slides, otherwise a fixed-height spacer to avoid layout jump
            if (slide in 1 until SLIDE_COUNT - 1) {
                Text(
                    "Skip intro",
                    fontSize = 12.sp,
                    color = ObText3,
                    modifier = Modifier
                        .clickable { slide = SLIDE_COUNT - 1 }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ── Splash slide (index 0) ────────────────────────────────────────────────────────────

@Composable
private fun ObSplash() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Three concentric rings around an icon — mimics the HTML mockup logo ring
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp).border(1.dp, ObAccent.copy(alpha = 0.06f), CircleShape))
            Box(Modifier.size(90.dp).border(1.dp, ObAccent.copy(alpha = 0.12f), CircleShape))
            Box(
                Modifier
                    .size(70.dp)
                    .background(ObAccent.copy(alpha = 0.08f), CircleShape)
                    .border(1.5.dp, ObAccent.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Sensors,
                    contentDescription = null,
                    tint = ObAccent,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            "Peer Reach",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = ObText1,
            letterSpacing = (-1).sp
        )

        Spacer(Modifier.height(10.dp))

        Text(
            "mesh · ble + lora · end-to-end encrypted\nno internet · no towers · just people",
            fontSize = 13.sp,
            color = ObText3,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

// ── Content slide (indices 1-6) ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObContent(data: ObSlide) {
    // Box + verticalScroll: centres content when it fits, scrolls when it doesn't
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon circle with faint outer ring
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(92.dp).border(1.dp, data.iconTint.copy(alpha = 0.08f), CircleShape))
                Box(
                    Modifier
                        .size(72.dp)
                        .background(data.iconTint.copy(alpha = 0.10f), CircleShape)
                        .border(1.5.dp, data.iconTint.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = data.icon,
                        contentDescription = null,
                        tint = data.iconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                data.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = ObText1,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
                lineHeight = 28.sp
            )

            Spacer(Modifier.height(14.dp))

            // Body text with **bold** spans
            val body = buildAnnotatedString {
                data.body.split("**").forEachIndexed { i, part ->
                    if (i % 2 == 0) append(part)
                    else withStyle(SpanStyle(color = ObText1, fontWeight = FontWeight.Medium)) {
                        append(part)
                    }
                }
            }
            Text(
                text = body,
                fontSize = 14.sp,
                color = ObText2,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 310.dp)
            )

            Spacer(Modifier.height(20.dp))

            // Chips — wrap to a new line if they don't fit
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                data.chips.forEach { (label, color) ->
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier
                            .background(color.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
