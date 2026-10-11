package app.hopnet.services.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.AppGraph
import kotlinx.coroutines.launch

/**
 * A web page or PDF, read over the mesh one part (~2.6 KB of text) at a time. Each part is one
 * small envelope, so nobody's battery or bandwidth pays for a page nobody finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(target: ReaderTarget, onBack: () -> Unit) {
    val repo = AppGraph.info
    val rowsFlow = remember(target) { repo.reader(target.key, target.firstId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        repo.screenOpen = true
        onDispose { repo.screenOpen = false }
    }
    LaunchedEffect(target.key) { target.key?.let { repo.readPart(it, 1, target.title) } }

    val key = target.key ?: rows.firstNotNullOfOrNull { it.linkKey }
    val parts = rows.filter { it.state == InfoState.ANSWERED && it.ok }.distinctBy { it.part }.sortedBy { it.part ?: 1 }
    val waiting = rows.firstOrNull { it.pending }
    val failure = rows.lastOrNull { it.state == InfoState.FAILED || (it.state == InfoState.ANSWERED && !it.ok) }
    val total = parts.maxOfOrNull { it.parts ?: 1 }
    val next = (parts.maxOfOrNull { it.part ?: 1 } ?: 0) + 1
    val title = parts.firstOrNull()?.title ?: target.title
    val source = parts.firstOrNull()?.source

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(source, total?.let { "${parts.size} of $it part${if (it == 1) "" else "s"}" }).joinToString(" · ")
                                .ifEmpty { "Fetching through the mesh" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(parts, key = { it.id }) { part ->
                Column {
                    if ((part.part ?: 1) > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text("  part ${part.part} of ${part.parts}  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.weight(1f))
                        }
                    }
                    SelectionContainer {
                        Text(part.body.orEmpty(), style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        when {
                            waiting != null -> {
                                Text(if (parts.isEmpty()) "Fetching the page" else "Fetching part ${waiting.part ?: next}",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                ProgressLine(waiting)
                            }
                            failure != null && (parts.isEmpty() || (failure.part ?: 0) >= next) -> {
                                Text(failure.title ?: "Couldn't fetch this page", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                Text(failure.body ?: failure.failReason.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { scope.launch { repo.retry(failure) } }) { Icon(Icons.Filled.Refresh, null); Text("  Try again") }
                            }
                            total != null && next <= total && key != null -> {
                                Button(onClick = { scope.launch { repo.readPart(key, next, title) } }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Load part $next of $total")
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Each part is one ~3 KB message through the mesh.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            parts.isNotEmpty() -> Text("End of page", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            else -> Text("Fetching the page…", style = MaterialTheme.typography.titleSmall)
                        }
                        parts.lastOrNull()?.let {
                            Spacer(Modifier.height(8.dp))
                            CostLine(it)
                            JourneyLine(it)
                        }
                    }
                }
            }
        }
    }
}
