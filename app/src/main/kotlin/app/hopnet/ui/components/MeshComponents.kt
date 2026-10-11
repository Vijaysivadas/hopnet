package app.hopnet.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hopnet.sdk.MeshStatus
import app.hopnet.ui.theme.LocalMeshColors

enum class MeshMode { OFF, GATEWAY, MESH, ALONE }

fun MeshStatus.mode(): MeshMode = when {
    gateway -> MeshMode.GATEWAY
    !serviceRunning -> MeshMode.OFF
    peers.isNotEmpty() -> MeshMode.MESH
    else -> MeshMode.ALONE
}

@Composable
fun MeshMode.color(): Color = when (this) {
    MeshMode.GATEWAY -> LocalMeshColors.current.online
    MeshMode.MESH -> LocalMeshColors.current.mesh
    MeshMode.ALONE -> LocalMeshColors.current.waiting
    MeshMode.OFF -> LocalMeshColors.current.off
}

/** Small breathing dot: "the radio is alive". */
@Composable
fun PulseDot(color: Color, active: Boolean = true, size: Int = 12) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "s")
    val fade by transition.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(Modifier.size((size * 2.4).dp), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(size.dp).scale(scale).alpha(fade).background(color, CircleShape))
        Box(Modifier.size(size.dp).background(color, CircleShape))
    }
}

@Composable
fun MeshStatusCard(status: MeshStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mode = status.mode()
    val accent = mode.color()
    val n = status.peers.size
    val (title, body) = when (mode) {
        MeshMode.GATEWAY -> "Online · you're a gateway" to
            "This phone carries messages from ${if (n == 0) "nearby phones" else "$n nearby phone${if (n == 1) "" else "s"}"} to the internet."
        MeshMode.MESH -> "Offline · $n phone${if (n == 1) "" else "s"} nearby" to
            "Messages hop through them until they reach a phone with internet."
        MeshMode.ALONE -> "Offline · nobody nearby yet" to
            "Messages wait safely on your phone and leave as soon as another Hopnet phone comes in range."
        MeshMode.OFF -> "Mesh is off" to "Starting the relay…"
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(accent, active = mode != MeshMode.OFF)
                Spacer(Modifier.size(6.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (status.peers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.peers.forEach { peer ->
                        Chip(peer.label + if (peer.gateway) "  ⇧" else "", if (peer.gateway) LocalMeshColors.current.online else accent)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${status.stats.router.forwarded} relayed · ${status.stored} carried · ${status.stats.router.uploaded} uploaded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** Renders a hop path like "A › B › G" as small pills. */
@Composable
fun HopPath(path: String, color: Color, modifier: Modifier = Modifier) {
    val hops = path.split(">").filter { it.isNotBlank() }
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        hops.forEachIndexed { i, hop ->
            if (i > 0) Text(" › ", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
            Text(
                hop,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
