package app.hopnet.services.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import app.hopnet.sdk.Hopnet
import app.hopnet.ui.components.MeshMode
import app.hopnet.ui.components.color
import app.hopnet.ui.components.mode
import app.hopnet.ui.theme.LocalMeshColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(handle: String, onBack: () -> Unit, onOpenNetwork: () -> Unit) {
    val repo = AppGraph.chat
    val messagesFlow = remember(handle) { repo.messages(handle) }
    val conversationFlow = remember(handle) { repo.conversation(handle) }
    val messages by messagesFlow.collectAsStateWithLifecycle(emptyList())
    val conversation by conversationFlow.collectAsStateWithLifecycle(null)
    val status by Hopnet.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(handle) { mutableStateOf("") }

    DisposableEffect(handle) {
        repo.openConversation = handle
        onDispose { if (repo.openConversation == handle) repo.openConversation = null }
    }
    LaunchedEffect(handle, messages.size) { repo.markRead(handle) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(conversation?.peerName ?: handle, size = 36)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(conversation?.peerName ?: handle, style = MaterialTheme.typography.titleMedium)
                            Text("@$handle", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    RouteHint(status.mode(), status.peers.size, onOpenNetwork)
                    Row(Modifier.padding(start = 12.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(1000) },
                            placeholder = { Text("Message") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                        )
                        FilledIconButton(
                            onClick = {
                                val text = draft.trim()
                                draft = ""
                                scope.launch { repo.send(handle, text) }
                            },
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp).size(48.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { MessageBubble(it) }
        }
    }
}

/** One line above the composer telling the user which way a new message would go. */
@Composable
private fun RouteHint(mode: MeshMode, peers: Int, onClick: () -> Unit) {
    val text = when (mode) {
        MeshMode.GATEWAY -> "Online · messages go straight to the server"
        MeshMode.MESH -> "Offline · messages will hop via $peers nearby phone${if (peers == 1) "" else "s"}"
        MeshMode.ALONE -> "Offline · messages wait here until a Hopnet phone comes near"
        MeshMode.OFF -> "Mesh is off"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = mode.color(),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MessageBubble(m: MessageEntity) {
    val mine = m.outgoing
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.sentAt)),
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
        if (mine) {
            Spacer(Modifier.size(3.dp))
            DeliveryLine(m)
        }
    }
}

/** The postal tracking line under an outgoing message. */
@Composable
private fun DeliveryLine(m: MessageEntity) {
    val colors = LocalMeshColors.current
    val (icon, color, label) = when (m.status) {
        MessageStatus.QUEUED -> Triple(Icons.Filled.Refresh, colors.waiting, "Waiting for nearby phones")
        MessageStatus.RELAYED -> Triple(
            Icons.Filled.Share, colors.mesh,
            if (m.viaUplink) "Uploading to server" else "Passed to ${m.relayPeers} phone${if (m.relayPeers == 1) "" else "s"}",
        )
        MessageStatus.AT_SERVER -> Triple(Icons.Filled.Check, colors.online, "Reached server")
        MessageStatus.DELIVERED -> Triple(Icons.Filled.CheckCircle, colors.online, "Delivered")
        else -> Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, m.failReason ?: "Not delivered")
    }
    Box(Modifier.padding(end = 6.dp)) { StatusRow(icon, color, label) }
}

@Composable
private fun StatusRow(icon: ImageVector, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}
