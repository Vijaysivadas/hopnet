package app.hopnet.services.findme

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import app.hopnet.sdk.Hopnet
import app.hopnet.sdk.MeshStatus
import app.hopnet.services.findme.FindMeRepository.Companion.isVisible
import app.hopnet.ui.components.HopPath
import app.hopnet.ui.components.PulseDot
import app.hopnet.ui.theme.LocalMeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindMeScreen(onBack: () -> Unit) {
    val repo = AppGraph.findMe
    val context = LocalContext.current
    val mine by repo.myAlert.collectAsStateWithLifecycle(null)
    val alerts by repo.alerts.collectAsStateWithLifecycle(emptyList())
    val myFix by repo.location.fix.collectAsStateWithLifecycle()
    val status by Hopnet.status.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    val heading = rememberHeading()
    val scope = rememberCoroutineScope()
    var askHelp by remember { mutableStateOf(false) }
    var confirmFound by remember { mutableStateOf(false) }

    // GPS runs while this screen is visible (to measure distances) and while my alert is active.
    LifecycleStartEffect(Unit) {
        repo.location.acquire()
        repo.screenOpen = true
        onStopOrDispose {
            repo.location.release()
            repo.screenOpen = false
        }
    }

    val visible = alerts.filter { it.isVisible(now) }
    val lostCount = visible.count { it.status == FindMeStatus.LOST }
    val locationProblem = remember(now) {
        when {
            !LocationTracker.hasPermission(context) -> "Hopnet doesn't have the location permission. Allow it in the app settings."
            !LocationTracker.isEnabled(context) -> "Location is off. Turn it on: Find Me needs GPS, and nearby phones can't be found without it."
            else -> null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Me") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mine") {
                MyAlertCard(
                    mine = mine, myFix = myFix, status = status, now = now,
                    onAskHelp = { askHelp = true },
                    onFound = { confirmFound = true },
                    onDone = { scope.launch { repo.dismiss() } },
                )
            }
            locationProblem?.let { problem -> item(key = "location") { WarningRow(problem) } }
            item(key = "title") {
                Text(
                    if (lostCount == 0) "People nearby who need help" else "People nearby who need help · $lostCount",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No one nearby has asked for help. If someone does, they appear here with how far away they are and which way to walk, even with no internet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(visible, key = { it.address }) { AlertCard(it, myFix, heading, now) }
        }
    }

    if (askHelp) AskHelpDialog(onDismiss = { askHelp = false }, onSend = { note ->
        askHelp = false
        scope.launch { repo.startLost(note) }
    })
    if (confirmFound) {
        AlertDialog(
            onDismissRequest = { confirmFound = false },
            title = { Text("Are you safe?") },
            text = { Text("Everyone nearby, and the police, will see that you've been found. Your location stops being shared.") },
            confirmButton = {
                Button(onClick = {
                    confirmFound = false
                    scope.launch { repo.markFound() }
                }) { Text("Yes, I'm found") }
            },
            dismissButton = { TextButton(onClick = { confirmFound = false }) { Text("Not yet") } },
        )
    }
}

// ---- my alert --------------------------------------------------------------------------------

@Composable
private fun MyAlertCard(
    mine: MyAlertEntity?,
    myFix: Fix?,
    status: MeshStatus,
    now: Long,
    onAskHelp: () -> Unit,
    onFound: () -> Unit,
    onDone: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    when {
        mine == null -> Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Lost in the crowd?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap Find Me. Your name and GPS location go to every Hopnet phone nearby, even with no internet, and to the police as soon as any phone on the way has internet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAskHelp,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.Filled.LocationOn, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find Me", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        mine.status == FindMeStatus.LOST -> Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(error)
                    Spacer(Modifier.width(6.dp))
                    Text("You're asking for help", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = error)
                }
                Spacer(Modifier.height(10.dp))
                StatusLine(
                    when {
                        myFix != null -> "Your location: within ${myFix.accuracy?.let { "${it.roundToInt()} m" } ?: "a few metres"} (GPS)"
                        mine.hasFix -> "Sharing your last location from ${ago(now - (mine.fixAt ?: now))}"
                        else -> "Waiting for GPS. Step into the open if you can. Your alert has already gone out without a location."
                    }
                )
                StatusLine(
                    when {
                        status.gateway -> "This phone has internet: sent straight to the police too"
                        mine.relayPeers > 0 -> "Passed to ${mine.relayPeers} nearby phone${if (mine.relayPeers == 1) "" else "s"}, which pass it on"
                        status.peers.isNotEmpty() -> "Sending to ${status.peers.size} nearby phone${if (status.peers.size == 1) "" else "s"}…"
                        else -> "No Hopnet phone in range yet. Your alert leaves as soon as one comes near."
                    }
                )
                if (mine.policeAt != null) {
                    StatusLine("✓ The police have your alert" + (mine.policeHops?.takeIf { it > 0 }?.let { " · $it hop${if (it == 1) "" else "s"} away" } ?: ""))
                    mine.policePath?.takeIf { it.isNotBlank() }?.let { HopPath("$it>police", LocalMeshColors.current.online, Modifier.padding(start = 4.dp, bottom = 4.dp)) }
                } else {
                    StatusLine("Police: waiting for a phone with internet to carry your alert")
                }
                StatusLine(
                    if (mine.lastSentAt == 0L) "Sending your alert…"
                    else "Updated ${ago(now - mine.lastSentAt)} · ${mine.updates} update${if (mine.updates == 1) "" else "s"} sent"
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onFound, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("I'm found / safe")
                }
            }
        }

        else -> Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("You're marked as found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nearby phones will show that you're safe, and the police page updates as soon as a phone with internet carries it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
private fun AskHelpDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask for help?") },
        text = {
            Column {
                Text("Your name and GPS location will show on every Hopnet phone nearby and on the police page.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(FindMeRepository.MAX_NOTE) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Where you are, what you're wearing") },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(note) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Send Find Me") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WarningRow(text: String) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

// ---- other people's alerts -------------------------------------------------------------------

@Composable
private fun AlertCard(alert: AlertEntity, myFix: Fix?, heading: State<Float?>, now: Long) {
    val lost = alert.status == FindMeStatus.LOST
    val colors = LocalMeshColors.current
    val accent = if (lost) MaterialTheme.colorScheme.error else colors.online
    val target = if (lost && alert.lat != null && alert.lon != null && myFix != null) {
        LocationTracker.distanceAndBearing(myFix.lat, myFix.lon, alert.lat, alert.lon)
    } else null

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = if (lost) 0.10f else 0.08f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(alert.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        alert.handle?.let {
                            Text(
                                "  @$it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }
                    Text(
                        if (lost) "Needs help · ${ago(now - alert.sentAt)}" else "✓ Found and safe · ${ago(now - alert.sentAt)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
                if (target != null) DirectionArrow(bearing = target.second, heading = heading, myFix = myFix, color = accent)
            }

            if (lost) {
                Spacer(Modifier.height(10.dp))
                when {
                    target != null -> {
                        val (distance, bearing) = target
                        val spread = (alert.accuracy ?: 0f) + (myFix?.accuracy ?: 0f)
                        Text(
                            if (distance <= spread) "Very close · within about ${formatDistance(spread)}"
                            else "${formatDistance(distance)} ${compassWord(bearing)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            buildString {
                                alert.accuracy?.let { append("Their GPS ±${it.roundToInt()} m") }
                                alert.fixAt?.let { fixAt ->
                                    if (now - fixAt > STALE_FIX_MS) append(if (isEmpty()) "Location from ${ago(now - fixAt)}" else " · location from ${ago(now - fixAt)}")
                                }
                            }.ifEmpty { "Walk the way the arrow points" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    alert.lat == null || alert.lon == null -> Text(
                        "Their phone has no GPS fix yet. The alert updates when it gets one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        "Waiting for your own GPS to show how far they are · ${formatCoordinates(alert.lat, alert.lon)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            alert.note?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "“$it”",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        alert.battery?.let { append("Battery $it% · ") }
                        append("reached you via ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HopPath(alert.path, colors.mesh)
            }
        }
    }
}

/**
 * Points toward the lost person. With a compass it turns with the phone, so "up" means "walk
 * this way"; without one it points relative to north and says so.
 */
@Composable
private fun DirectionArrow(bearing: Float, heading: State<Float?>, myFix: Fix?, color: Color) {
    val hasCompass by remember(heading) { derivedStateOf { heading.value != null } }
    val declination = remember(myFix?.lat?.roundToInt(), myFix?.lon?.roundToInt()) {
        myFix?.let { GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination } ?: 0f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.size(40.dp).graphicsLayer {
                    // Read here, not in composition, so compass updates only redraw the arrow.
                    val trueHeading = heading.value?.let { it + declination } ?: 0f
                    rotationZ = bearing - trueHeading
                }
            ) {
                val w = size.width
                val h = size.height
                val arrow = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(w * 0.9f, h)
                    lineTo(w / 2f, h * 0.72f)
                    lineTo(w * 0.1f, h)
                    close()
                }
                drawPath(arrow, color)
            }
        }
        if (!hasCompass) Text("↑ = north", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compass heading in degrees from magnetic north, or null if the phone has no rotation sensor. */
@Composable
private fun rememberHeading(): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val (axisX, axisY) = when (displayRotation(context)) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values.copyOf(minOf(event.values.size, 4)))
                SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val degrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                heading.value = smoothAngle(heading.value, degrees)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int = runCatching {
    if (Build.VERSION.SDK_INT >= 30) context.display.rotation
    else context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
}.getOrDefault(Surface.ROTATION_0)

/** Low-pass filter that goes the short way round 359° → 0°. */
private fun smoothAngle(previous: Float?, next: Float): Float {
    if (previous == null) return next
    val diff = ((next - previous + 540f) % 360f) - 180f
    return (previous + diff * 0.2f + 360f) % 360f
}

// ---- formatting ------------------------------------------------------------------------------

private const val STALE_FIX_MS = 5 * 60_000L

private val DIRECTIONS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

internal fun compassWord(bearing: Float): String = DIRECTIONS[(((bearing % 360f) + 360f) % 360f / 45f).roundToInt() % 8]

internal fun formatDistance(meters: Float): String = when {
    meters < 10f -> "${meters.roundToInt()} m"
    meters < 1000f -> "${(meters / 5f).roundToInt() * 5} m"
    else -> String.format(Locale.US, "%.1f km", meters / 1000f)
}

internal fun formatCoordinates(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

internal fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ${s % 3600 / 60} min ago"
    }
}
