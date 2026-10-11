package app.hopnet.services.info

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import app.hopnet.sdk.Hopnet
import app.hopnet.sdk.MeshStatus
import app.hopnet.services.findme.Fix
import app.hopnet.services.findme.LocationTracker
import app.hopnet.services.findme.compassWord
import app.hopnet.services.findme.formatDistance
import app.hopnet.ui.components.HopPath
import app.hopnet.ui.components.MeshMode
import app.hopnet.ui.components.color
import app.hopnet.ui.components.mode
import app.hopnet.ui.theme.LocalMeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Opens a page in the reader: by its link key, or by the id of the request that opened a typed link. */
data class ReaderTarget(val key: String?, val firstId: String?, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit, onOpenReader: (ReaderTarget) -> Unit, onOpenNetwork: () -> Unit) {
    val repo = AppGraph.info
    val location = AppGraph.findMe.location
    val context = LocalContext.current
    val fix by location.fix.collectAsStateWithLifecycle()
    val cards by repo.feed.collectAsStateWithLifecycle(emptyList())
    val status by Hopnet.status.collectAsStateWithLifecycle()
    val watch by repo.watch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var place by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }

    DisposableEffect(Unit) {
        location.acquire()
        repo.screenOpen = true
        onDispose {
            location.release()
            repo.screenOpen = false
        }
    }
    LaunchedEffect(cards.firstOrNull()?.id) { repo.markAlertsSeen() }

    val where = Where(fix, place.ifBlank { null })
    fun go(block: suspend () -> Unit) {
        scope.launch { block() }
    }
    fun openItem(item: InfoItem) {
        val key = item.key
        val phone = item.meta?.let { PHONE.find(it)?.groupValues?.get(1) }
        when {
            key != null -> onOpenReader(ReaderTarget(key, null, item.title))
            phone != null -> context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filter { it.isDigit() || it == '+' }}")))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live Info", fontWeight = FontWeight.SemiBold)
                        Text(
                            routeText(status),
                            style = MaterialTheme.typography.labelSmall,
                            color = status.mode().color(),
                            modifier = Modifier.clickable(onClick = onOpenNetwork),
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (cards.any { !it.pending }) {
                        IconButton(onClick = { go { repo.clearFinished() } }) { Icon(Icons.Filled.Delete, "Clear answers") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WhereCard(fix, place, onPlace = { place = it.take(80) })
            }
            item {
                Button(
                    onClick = { go { repo.briefing(where) } },
                    enabled = where.usable,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("🧭  Briefing for ${where.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Weather, official warnings and headlines in one message", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                QuickActions(
                    enabled = where.usable,
                    onWeather = { go { repo.weather(where) } },
                    onAlerts = { go { repo.alerts(where) } },
                    onCategory = { c -> go { repo.nearby(c, where) } },
                )
            }
            item { NewsTopics { t -> go { repo.news(t) } } }
            item {
                AskBox(
                    text = question,
                    onText = { question = it.take(300) },
                    onAsk = {
                        val q = question.trim()
                        question = ""
                        if (looksLikeLink(q)) {
                            go { repo.openLink(q)?.let { id -> onOpenReader(ReaderTarget(null, id, q)) } }
                        } else {
                            go { repo.askAnything(q) }
                        }
                    },
                    onWiki = { val q = question.trim(); question = ""; go { repo.wiki(q) } },
                    onNews = { val q = question.trim(); question = ""; go { repo.newsSearch(q) } },
                )
            }
            item {
                WatchCard(watch, fix, now) { on ->
                    go { if (on) fix?.let { repo.watchHere(it) } else repo.stopWatching() }
                }
            }
            if (cards.isEmpty()) {
                item {
                    Text(
                        "Answers appear here. The server reads the internet for you and sends back a few kilobytes. " +
                            "Through the mesh that can take a few minutes, and you'll get a notification when it arrives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                item { Text("Answers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
            }
            items(cards, key = { it.id }) { card ->
                InfoCardView(
                    card, fix, now,
                    onItem = ::openItem,
                    onRetry = { go { repo.retry(card) } },
                    onDelete = { go { repo.delete(card.id) } },
                )
            }
        }
    }
}

private val PHONE = Regex("☎\\s*([+\\d][\\d ()-]{5,})")
private val DISTANCE_PREFIX = Regex("^[\\d.,]+ k?m [NSEW]{1,2}(\\s·\\s)?")

private fun looksLikeLink(text: String) =
    text.startsWith("http://", true) || text.startsWith("https://", true) ||
        (!text.contains(' ') && Regex("^[\\w.-]+\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(text))

private fun routeText(status: MeshStatus): String {
    val n = status.peers.size
    return when (status.mode()) {
        MeshMode.GATEWAY -> "Online · questions go straight to the server"
        MeshMode.MESH -> "Offline · questions hop via $n nearby phone${if (n == 1) "" else "s"}"
        MeshMode.ALONE -> "Offline · questions wait until a Hopnet phone comes near"
        MeshMode.OFF -> "Mesh is off"
    }
}

// ---- top of the screen ---------------------------------------------------------------------------

@Composable
private fun WhereCard(fix: Fix?, place: String, onPlace: (String) -> Unit) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📍", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.padding(start = 10.dp)) {
                    val (title, detail) = when {
                        place.isNotBlank() -> "Asking about $place" to "Clear the box to use your GPS location again."
                        fix != null -> "Your location" + (fix.accuracy?.let { " · ±${it.toInt()} m" } ?: "") to
                            "GPS works with no internet. Only the Hopnet server can read it."
                        !LocationTracker.isEnabled(context) -> "Location is off" to "Turn it on for weather and places near you, or type a place."
                        else -> "Finding you…" to "GPS works with no internet. Step outside if it takes long, or type a place."
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = place,
                onValueChange = onPlace,
                singleLine = true,
                placeholder = { Text("Or type a place, e.g. Munnar") },
                trailingIcon = {
                    if (place.isNotEmpty()) IconButton(onClick = { onPlace("") }) { Icon(Icons.Filled.Clear, "Use my location") }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(enabled: Boolean, onWeather: () -> Unit, onAlerts: () -> Unit, onCategory: (Category) -> Unit) {
    Column {
        SectionLabel("Near you")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onWeather, enabled = enabled, label = { Text("🌦️  Weather") })
            AssistChip(onClick = onAlerts, enabled = enabled, label = { Text("⚠️  Official alerts") })
            InfoTools.categories.forEach { c ->
                AssistChip(onClick = { onCategory(c) }, enabled = enabled, label = { Text("${c.emoji}  ${c.title}") })
            }
        }
    }
}

@Composable
private fun NewsTopics(onTopic: (Topic) -> Unit) {
    Column {
        SectionLabel("News")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTools.topics.forEach { t -> SuggestionChip(onClick = { onTopic(t) }, label = { Text(t.title) }) }
        }
    }
}

@Composable
private fun AskBox(text: String, onText: (String) -> Unit, onAsk: () -> Unit, onWiki: () -> Unit, onNews: () -> Unit) {
    val ready = text.trim().length >= 2
    val link = looksLikeLink(text.trim())
    Column {
        SectionLabel("Ask the internet")
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text("Ask anything, search, or paste a link") },
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (ready) onAsk() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAsk, enabled = ready) { Text(if (link) "📄 Open page" else "✨ Ask") }
            if (!link) {
                OutlinedButton(onClick = onWiki, enabled = ready) { Text("Wikipedia") }
                OutlinedButton(onClick = onNews, enabled = ready) { Text("News") }
            }
        }
    }
}

@Composable
private fun WatchCard(watch: InfoRepository.Watch?, fix: Fix?, now: Long, onToggle: (Boolean) -> Unit) {
    val active = watch != null && watch.until > now
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔔  Alert me about new warnings here", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        active && watch?.confirmed == true -> "Watching until ${dayTime(watch.until)}. The server checks official alerts every few minutes and pushes new ones through the mesh."
                        active -> "Starting… waiting for the server to confirm."
                        fix == null -> "Needs your GPS location. Lasts 3 days."
                        else -> "The server watches official alerts for this spot for 3 days and pushes new ones to you."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = active, onCheckedChange = onToggle, enabled = active || fix != null, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

// ---- answer cards ---------------------------------------------------------------------------------

@Composable
private fun InfoCardView(
    card: InfoCardEntity,
    fix: Fix?,
    now: Long,
    onItem: (InfoItem) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val failed = card.state == InfoState.FAILED || (card.state == InfoState.ANSWERED && !card.ok)
    val accent = levelColor(card.level, failed)
    val container = when {
        card.level == "danger" && !failed -> scheme.errorContainer.copy(alpha = 0.45f)
        card.pushed -> scheme.tertiaryContainer.copy(alpha = 0.6f)
        else -> scheme.surfaceContainerLow
    }
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(InfoTools.emoji(card.tool), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        if (card.pushed) "Pushed by your alert watch" else card.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(ago(card.answeredAt ?: card.askedAt, now), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "Remove", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.padding(end = 12.dp)) {
                when {
                    card.pending -> ProgressLine(card)
                    card.state == InfoState.FAILED -> {
                        Text(card.failReason ?: "Couldn't send", color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Text("  Try again") }
                    }
                    else -> {
                        if (card.ai) AiBadge()
                        Text(card.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (failed) scheme.error else Color.Unspecified)
                        card.body?.takeIf { it.isNotBlank() }?.let { body ->
                            Spacer(Modifier.height(4.dp))
                            val long = body.length > 420
                            Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = if (long && !expanded) 9 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                            if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
                        }
                        val items = remember(card.itemsJson) { card.items }
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            items.forEachIndexed { i, item ->
                                if (i > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                                ItemRow(item, fix, accent, onItem)
                            }
                        }
                        if (failed) TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Text("  Try again") }
                        Spacer(Modifier.height(8.dp))
                        CostLine(card)
                        JourneyLine(card)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: InfoItem, fix: Fix?, accent: Color, onItem: (InfoItem) -> Unit) {
    val dial = item.meta?.contains("☎") == true
    val tappable = item.key != null || dial
    val live = if (fix != null && item.lat != null && item.lon != null) {
        val (meters, bearing) = LocationTracker.distanceAndBearing(fix.lat, fix.lon, item.lat, item.lon)
        "${formatDistance(meters)} ${compassWord(bearing)} of you now"
    } else null
    Row(
        Modifier.fillMaxWidth().let { if (tappable) it.clickable { onItem(item) } else it }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.level != null) {
            Box(Modifier.padding(end = 10.dp).size(8.dp).background(levelColor(item.level, false), CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // The server's "160 m NE" was measured from where you asked; once GPS knows better, show only that.
            val fixed = item.meta?.let { if (live != null) it.replace(DISTANCE_PREFIX, "").ifBlank { null } else it }
            val meta = listOfNotNull(live, fixed)
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (live != null) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.key != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Read", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (dial) Text("📞", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AiBadge() {
    Text(
        "✨ AI answer · check the sources",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** "Open-Meteo · fetched 9:05 · 712 KB from the internet → 1.2 KB through the mesh" */
@Composable
internal fun CostLine(card: InfoCardEntity) {
    val bits = listOfNotNull(
        card.source,
        card.fetchedAt?.let { "fetched ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" },
    )
    if (bits.isNotEmpty()) {
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val fetched = card.bytesFetched
    val wire = card.wireBytes
    if (fetched != null && wire != null && fetched > 0) {
        val ratio = if (fetched > wire) " · ${String.format(Locale.US, "%,d", fetched / wire)}× smaller" else ""
        Text(
            "${formatBytes(fetched)}${if (card.cached) " (cached)" else ""} from the internet → ${formatBytes(wire.toLong())} through the mesh$ratio",
            style = MaterialTheme.typography.labelSmall,
            color = LocalMeshColors.current.online,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun JourneyLine(card: InfoCardEntity) {
    val colors = LocalMeshColors.current
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        card.upPath?.takeIf { it.isNotBlank() }?.let {
            Text("⬆ ", style = MaterialTheme.typography.labelSmall, color = colors.mesh)
            HopPath(it, colors.mesh)
            Spacer(Modifier.width(8.dp))
        }
        card.downPath?.takeIf { it.isNotBlank() }?.let {
            Text("⬇ ", style = MaterialTheme.typography.labelSmall, color = colors.online)
            HopPath(it, colors.online)
        }
    }
}

/** Where a question is on its way: like the postal tracking line under a chat message. */
@Composable
internal fun ProgressLine(card: InfoCardEntity) {
    val colors = LocalMeshColors.current
    val (text, color) = when (card.state) {
        InfoState.QUEUED -> "🕓 Waiting for nearby phones" to colors.waiting
        InfoState.RELAYED -> (if (card.viaUplink) "⇧ Uploading to the server" else "↗ Passed to ${card.relayPeers} phone${if (card.relayPeers == 1) "" else "s"}, hopping toward the internet") to colors.mesh
        else -> "✓ Reached the server · reading the internet for you" to colors.online
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
        card.upPath?.takeIf { it.isNotBlank() }?.let { HopPath(it, colors.mesh, Modifier.padding(top = 4.dp)) }
        if (card.state == InfoState.AT_SERVER) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = color)
        }
    }
}

@Composable
private fun levelColor(level: String?, failed: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        failed -> scheme.error
        level == "danger" -> scheme.error
        level == "warn" -> scheme.tertiary
        else -> scheme.primary
    }
}

internal fun formatBytes(n: Long): String = when {
    n < 1000 -> "$n B"
    n < 1_000_000 -> String.format(Locale.US, "%.1f KB", n / 1000.0)
    else -> String.format(Locale.US, "%.1f MB", n / 1_000_000.0)
}

private fun dayTime(ms: Long): String =
    java.text.SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))

private fun ago(ms: Long, now: Long): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}
