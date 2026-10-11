package app.hopnet.ui.network

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.sdk.Hopnet
import app.hopnet.ui.components.Chip
import app.hopnet.ui.components.PulseDot
import app.hopnet.ui.components.color
import app.hopnet.ui.components.mode
import app.hopnet.ui.theme.LocalMeshColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The mesh "radar": this phone, its links, counters, live log, and demo topology controls. */
@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Hopnet.status.collectAsStateWithLifecycle()
    val profile by Hopnet.profile.collectAsStateWithLifecycle()
    val log by Hopnet.log.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf(status.label) }
    var allow by remember { mutableStateOf(status.allowlist.sorted().joinToString(", ")) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val power = context.getSystemService(PowerManager::class.java)
    val batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("This phone") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(status.mode().color(), active = status.radioOn)
                        Text(
                            when {
                                status.gateway -> "Gateway: connected to server"
                                status.networkAvailable -> "Has a network, server not reachable yet"
                                else -> "No internet: relaying over the mesh"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    KeyValue("Handle", profile?.let { "@${it.handle}" } ?: "not registered")
                    KeyValue("Node id", status.nodeId)
                    KeyValue("Address", Hopnet.address.take(16) + "…")
                    KeyValue("Server", profile?.serverUrl ?: "—")
                    KeyValue("Radio", if (status.radioOn) "on (advertising + scanning)" else "off")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(label, { label = it.take(16) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Hopnet.setLabel(label) }, enabled = label.isNotBlank() && label != status.label) { Text("Save") }
                    }
                }
            }

            item {
                Section("Nearby phones (${status.peers.size})") {
                    if (!Hopnet.isLocationEnabled(context)) {
                        Text("Location is switched off. Android needs it on to scan for nearby phones.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }) { Text("Turn on Location") }
                    }
                    if (status.peers.isEmpty()) {
                        Text("No links yet. Other phones need Hopnet open with Bluetooth on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    status.peers.forEach { peer ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(peer.label, if (peer.gateway) LocalMeshColors.current.online else LocalMeshColors.current.mesh)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                (if (peer.gateway) "gateway · " else "") + "↓${peer.rx} ↑${peer.tx}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                val r = status.stats.router
                Section("Counters") {
                    KeyValue("Carrying now", "${status.stored} envelope(s)")
                    KeyValue("Received / duplicates", "${r.received} / ${r.duplicates}")
                    KeyValue("Forwarded to phones", "${r.forwarded}")
                    KeyValue("Uploaded to server", "${r.uploaded}")
                    KeyValue("Receipts seen", "${r.receipts}")
                    KeyValue("Dropped (expired, hop limit, forged)", "${r.dropped}")
                }
            }

            item {
                Section("Demo: force a chain") {
                    Text(
                        "In one room every phone can see every other. To show multi-hop delivery, list the only labels this phone may link with, e.g. A: \"B\", B: \"A, C\", C: \"B, G\". Leave empty to link with anyone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(allow, { allow = it }, label = { Text("Only link with") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Hopnet.setAllowlist(allow.split(',', ' ').filter { it.isNotBlank() }.toSet()) }) { Text("Apply") }
                    }
                }
            }

            item {
                Section("Controls") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.serviceRunning) {
                            OutlinedButton(onClick = { Hopnet.stopMesh(context) }) { Text("Stop relay") }
                        } else {
                            OutlinedButton(onClick = { Hopnet.startMesh(context) }) { Text("Start relay") }
                        }
                        OutlinedButton(onClick = { scope.launch { Hopnet.resetMeshStore() } }) { Text("Clear carried") }
                    }
                    if (!batteryExempt) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("Keep relaying in background") }
                    }
                }
            }

            item { Text("Live log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
            items(log.asReversed().take(150)) { line ->
                Text(
                    "${timeFormat.format(Date(line.at))}  ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp),
        )
    }
}
