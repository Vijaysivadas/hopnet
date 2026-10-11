package app.hopnet.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hopnet.BuildConfig
import app.hopnet.sdk.Hopnet
import app.hopnet.services.messaging.ChatRepository
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(Hopnet.status.value.label) }
    var server by rememberSaveable { mutableStateOf(Hopnet.savedServerUrl ?: BuildConfig.DEFAULT_SERVER_URL) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val handleOk = ChatRepository.isValidHandle(handle)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            HopMark()
            Spacer(Modifier.height(20.dp))
            Text("Hopnet", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "Use online services without internet. Your message hops phone to phone until it reaches one that's online.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
                label = { Text("Username") },
                prefix = { Text("@") },
                supportingText = { Text("3–20 letters, numbers or _. Friends message you with this.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(16) },
                label = { Text("Phone label") },
                supportingText = { Text("Shown to nearby phones and in hop paths, e.g. A, B, Ravi-phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it.trim() },
                label = { Text("Hopnet server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            Hopnet.setLabel(label.ifBlank { handle })
                            Hopnet.register(server, handle, name)
                        } catch (e: Exception) {
                            error = "Couldn't register: ${e.message ?: "server unreachable"}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && name.isNotBlank() && handleOk && server.startsWith("http"),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Join Hopnet")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Needs internet just this once. After that Hopnet works offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HopMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.35f, 0.6f, 1f).forEach { alpha ->
            Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape))
            Box(Modifier.size(width = 18.dp, height = 2.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
        }
        Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
fun PermissionsScreen(onResult: () -> Unit) {
    val context = LocalContext.current
    var asked by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked++
        onResult()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("Let Hopnet find nearby phones", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Messages travel over Bluetooth and Wi-Fi between phones running Hopnet. Android asks you to allow this.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Reason(Icons.Filled.Share, "Nearby devices", "To pass messages to and from phones around you.")
            Reason(Icons.Filled.Place, "Location", "Android requires it to scan for nearby phones. Hopnet only shares your location if you use Find Me to ask for help.")
            if (Build.VERSION.SDK_INT >= 33) {
                Reason(Icons.Filled.Notifications, "Notifications", "To tell you when a message arrives.")
            }
            Reason(Icons.Filled.Lock, "Private by design", "Phones that relay your message can't read it.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(Hopnet.requiredPermissions()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Allow")
            }
            if (asked > 0) {
                Spacer(Modifier.height(12.dp))
                Text("Some permissions are still missing.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
