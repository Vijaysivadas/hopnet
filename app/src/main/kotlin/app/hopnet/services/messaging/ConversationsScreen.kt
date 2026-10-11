package app.hopnet.services.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import app.hopnet.sdk.Hopnet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val conversations by AppGraph.chat.conversations.collectAsStateWithLifecycle(emptyList())
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New message") },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { showNew = true },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No conversations yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Message anyone on Hopnet by their username. It finds its way through nearby phones, even with no signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(conversations, key = { it.peerHandle }) { c ->
                    ConversationRow(c) { onOpenChat(c.peerHandle) }
                    HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showNew) {
        NewChatDialog(onDismiss = { showNew = false }, onStart = { handle ->
            showNew = false
            onOpenChat(handle)
        })
    }
}

@Composable
private fun ConversationRow(c: ConversationEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.peerName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(c.lastAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.lastText.ifEmpty { "@${c.peerHandle}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (c.unread > 0) Badge { Text("${c.unread}") }
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Int = 48) {
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = if (size >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Ask for a username. When online we check it exists; offline the server will tell us later. */
@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val status by Hopnet.status.collectAsStateWithLifecycle()
    var handle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val valid = ChatRepository.isValidHandle(handle)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column {
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20); error = null },
                    label = { Text("Username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(
                            error ?: if (status.gateway) "We'll check this username now."
                            else "You're offline. If this username doesn't exist, the server will tell you once your message gets there."
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !checking,
                onClick = {
                    val h = ChatRepository.normalizeHandle(handle)
                    if (!status.gateway) {
                        scope.launch { AppGraph.chat.startConversation(h, null) }
                        onStart(h)
                        return@TextButton
                    }
                    checking = true
                    scope.launch {
                        val user = runCatching { Hopnet.lookupUser(h) }
                        checking = false
                        when {
                            user.isFailure -> { AppGraph.chat.startConversation(h, null); onStart(h) }
                            user.getOrNull() == null -> error = "No one on Hopnet is called @$h"
                            else -> { AppGraph.chat.startConversation(h, user.getOrNull()!!.name); onStart(h) }
                        }
                    }
                },
            ) { Text(if (checking) "Checking…" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
