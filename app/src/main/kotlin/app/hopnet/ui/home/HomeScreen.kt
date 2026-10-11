package app.hopnet.ui.home

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import app.hopnet.sdk.Hopnet
import app.hopnet.services.HopService
import app.hopnet.services.ServiceCatalog
import app.hopnet.services.findme.FindMeStatus
import app.hopnet.ui.components.MeshStatusCard
import app.hopnet.ui.components.PulseDot
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenService: (HopService) -> Unit, onOpenNetwork: () -> Unit) {
    val status by Hopnet.status.collectAsStateWithLifecycle()
    val profile by Hopnet.profile.collectAsStateWithLifecycle()
    val unread by AppGraph.chat.unreadTotal.collectAsStateWithLifecycle(0)
    val needHelp by AppGraph.findMe.helpCount.collectAsStateWithLifecycle(0)
    val myAlert by AppGraph.findMe.myAlert.collectAsStateWithLifecycle(null)
    val infoAlerts by AppGraph.info.unseenAlerts.collectAsStateWithLifecycle(0)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hopnet", fontWeight = FontWeight.Bold)
                        profile?.let {
                            Text("@${it.handle} · ${status.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = { IconButton(onClick = onOpenNetwork) { Icon(Icons.Filled.Share, contentDescription = "Network") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { MeshStatusCard(status, onClick = onOpenNetwork) }
            val banner = when {
                myAlert?.status == FindMeStatus.LOST -> "You're asking for help · tap to see who has your alert"
                needHelp > 0 -> "$needHelp ${if (needHelp == 1) "person" else "people"} nearby need${if (needHelp == 1) "s" else ""} help · tap to see where"
                else -> null
            }
            if (banner != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { FindMeBanner(banner) { onOpenService(ServiceCatalog.findMe) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Services", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            items(ServiceCatalog.all, key = { it.id }) { service ->
                val badge = when (service.id) {
                    ServiceCatalog.messages.id -> unread
                    ServiceCatalog.findMe.id -> needHelp
                    ServiceCatalog.liveInfo.id -> infoAlerts
                    else -> 0
                }
                ServiceTile(service, badge = badge) {
                    if (service.available) onOpenService(service)
                    else scope.launch { snackbar.showSnackbar("${service.title} is coming soon. It will use the same offline mesh.") }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Every Hopnet phone relays sealed messages for others. Relays can't read them; only the Hopnet server can open your request, and only the recipient can open the reply. " +
                        "Find Me alerts are different on purpose: they're signed so nobody can fake them, but every phone nearby can read them so people can come and help.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun FindMeBanner(text: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.error
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(color)
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun ServiceTile(service: HopService, badge: Int, onClick: () -> Unit) {
    val accent = if (service.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(156.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = if (service.available) 2.dp else 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp).alpha(if (service.available) 1f else 0.6f)) {
                Box(
                    Modifier.size(44.dp).background(accent.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(service.icon, contentDescription = null, tint = accent) }
                Spacer(Modifier.height(12.dp))
                Text(service.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    service.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                when {
                    !service.available -> Text(
                        "Soon",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    badge > 0 -> Badge { Text("$badge") }
                }
            }
        }
    }
}
