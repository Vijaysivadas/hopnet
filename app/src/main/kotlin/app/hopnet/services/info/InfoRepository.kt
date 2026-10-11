package app.hopnet.services.info

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hopnet.MainActivity
import app.hopnet.core.client.IncomingMessage
import app.hopnet.core.client.OutgoingUpdate
import app.hopnet.sdk.Hopnet
import app.hopnet.services.findme.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/** Where a question is about: this phone's GPS fix (GPS needs no internet) or a place the person typed. */
data class Where(val fix: Fix?, val place: String?) {
    val usable: Boolean get() = !place.isNullOrBlank() || fix != null
    val label: String get() = place?.takeIf { it.isNotBlank() } ?: "here"
}

data class Category(val id: String, val emoji: String, val title: String)
data class Topic(val id: String, val title: String)

/** What a person can ask for. Ids match the server's tools in server/hopnet_server/info. */
object InfoTools {
    val categories = listOf(
        Category("hospital", "🏥", "Hospitals"),
        Category("shelter", "🏫", "Shelters & camps"),
        Category("pharmacy", "💊", "Pharmacies"),
        Category("police", "👮", "Police"),
        Category("fire", "🚒", "Fire & rescue"),
        Category("water", "🚰", "Drinking water"),
        Category("food", "🛒", "Food & groceries"),
        Category("fuel", "⛽", "Fuel"),
        Category("atm", "🏧", "ATMs & banks"),
    )
    val topics = listOf(
        Topic("top", "Top stories"), Topic("kerala", "Kerala"), Topic("india", "India"), Topic("world", "World"),
        Topic("business", "Business"), Topic("sport", "Sport"), Topic("tech", "Science & tech"),
    )

    fun emoji(tool: String) = when (tool) {
        "brief" -> "🧭"; "weather" -> "🌦️"; "alerts" -> "⚠️"; "nearby" -> "📍"; "news" -> "📰"
        "wiki" -> "📖"; "search" -> "🔎"; "ask" -> "✨"; "read" -> "📄"; else -> "🌐"
    }
}

/**
 * Live Info on the phone: questions go up through the mesh as Hopnet.send("info", "query", ...),
 * the server reads the internet, and a small signed card comes back. This class keeps the cards,
 * turns SDK progress into states, and notifies when an answer (or a watched alert) arrives while
 * the person is elsewhere: on a mesh, answers can take minutes.
 */
class InfoRepository(private val context: Context, private val scope: CoroutineScope) {
    private val dao = InfoDatabase.create(context).dao()
    private val prefs = context.getSharedPreferences("live_info", Context.MODE_PRIVATE)

    /** The Live Info screen is showing, so answers need no notification. */
    @Volatile
    var screenOpen = false

    val feed: Flow<List<InfoCardEntity>> = dao.feed()
    val unseenAlerts: Flow<Int> = dao.unseenCount()

    fun reader(key: String?, firstId: String?) = dao.reader(key.orEmpty(), firstId.orEmpty())

    data class Watch(val until: Long, val confirmed: Boolean)

    private val _watch = MutableStateFlow(loadWatch())
    /** "Tell me about new warnings here", while it lasts. */
    val watch: StateFlow<Watch?> = _watch.asStateFlow()

    fun start() {
        createNotificationChannels()
        scope.launch { dao.deleteOlderThan(System.currentTimeMillis() - KEEP_MS) }
        scope.launch { Hopnet.updates.collect { onUpdate(it) } }
        scope.launch {
            Hopnet.incoming.collect {
                if (it.service != SERVICE) return@collect
                try {
                    onIncoming(it)
                } catch (e: Exception) {
                    Hopnet.log("Live Info: bad answer ${it.msgId.take(6)} (${e.message})")
                }
            }
        }
    }

    // ---- asking --------------------------------------------------------------------------------

    suspend fun briefing(where: Where) = ask("brief", "Briefing · ${where.label}", place(where))
    suspend fun weather(where: Where) = ask("weather", "Weather · ${where.label}", place(where))
    suspend fun alerts(where: Where) = ask("alerts", "Alerts · ${where.label}", place(where))

    suspend fun nearby(category: Category, where: Where) =
        ask("nearby", "${category.emoji} ${category.title} · ${where.label}", place(where) + ("cat" to JsonPrimitive(category.id)))

    suspend fun news(topic: Topic) = ask("news", "News · ${topic.title}", mapOf("topic" to JsonPrimitive(topic.id)))
    suspend fun newsSearch(q: String) = ask("news", "News · “$q”", mapOf("q" to JsonPrimitive(q)))
    suspend fun wiki(q: String) = ask("wiki", "Wikipedia · “$q”", mapOf("q" to JsonPrimitive(q)))
    suspend fun search(q: String) = ask("search", "Web · “$q”", mapOf("q" to JsonPrimitive(q)))
    suspend fun askAnything(q: String) = ask("ask", "“$q”", mapOf("q" to JsonPrimitive(q)))

    /** Opens a page by link; returns the request id the reader follows until the page's key is known. */
    suspend fun openLink(url: String): String? = ask("read", url, mapOf("url" to JsonPrimitive(url.trim()), "part" to JsonPrimitive(1)), part = 1)

    /** Asks for one part of a page; does nothing if that part is already here or on its way. */
    suspend fun readPart(key: String, part: Int, title: String) {
        if (dao.readerPart(key, part) != null) return
        ask("read", title, mapOf("key" to JsonPrimitive(key), "part" to JsonPrimitive(part)), linkKey = key, part = part)
    }

    suspend fun retry(card: InfoCardEntity) {
        if (card.pushed) return
        dao.delete(card.id)
        ask(card.tool, card.label, card.params.filterKeys { it != "tool" }, linkKey = card.linkKey, part = card.part)
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun clearFinished() = dao.clearFinished()
    suspend fun markAlertsSeen() = dao.markAllSeen()

    private fun place(where: Where): Map<String, JsonPrimitive> {
        val place = where.place?.trim()
        if (!place.isNullOrEmpty()) return mapOf("place" to JsonPrimitive(place))
        val fix = where.fix ?: return emptyMap()
        // ~11 m: precise enough for "nearest hospital", and the server rounds further for weather.
        return mapOf("lat" to JsonPrimitive(round4(fix.lat)), "lon" to JsonPrimitive(round4(fix.lon)))
    }

    private suspend fun ask(
        tool: String,
        label: String,
        params: Map<String, JsonElement>,
        kind: String = "query",
        linkKey: String? = null,
        part: Int? = null,
    ): String? {
        val data = JsonObject(mapOf("tool" to JsonPrimitive(tool)) + params)
        val msgId = Hopnet.newMessageId()
        dao.insert(
            InfoCardEntity(
                id = "o:$msgId", tool = tool, label = label, paramsJson = data.toString(), askedAt = System.currentTimeMillis(),
                state = InfoState.QUEUED, linkKey = linkKey, part = part,
            )
        )
        return try {
            Hopnet.send(SERVICE, kind, data, msgId, ttlMs = QUESTION_TTL_MS)
            "o:$msgId"
        } catch (e: Exception) {
            dao.markFailed("o:$msgId", e.message ?: "Could not send")
            null
        }
    }

    // ---- watching ------------------------------------------------------------------------------

    /** Starts watching this spot for new official warnings. The first answer is the current alerts. */
    suspend fun watchHere(fix: Fix, hours: Int = WATCH_HOURS) {
        val params = mapOf("topic" to JsonPrimitive("alerts"), "lat" to JsonPrimitive(round4(fix.lat)),
            "lon" to JsonPrimitive(round4(fix.lon)), "hours" to JsonPrimitive(hours))
        saveWatch(Watch(System.currentTimeMillis() + hours * HOUR_MS, confirmed = false))
        if (ask("alerts", "🔔 Watching alerts · here", params, kind = "watch") == null) saveWatch(null)
    }

    suspend fun stopWatching() {
        saveWatch(null)
        try {
            Hopnet.send(SERVICE, "unwatch", buildJsonObject { put("topic", "alerts") }, ttlMs = QUESTION_TTL_MS)
        } catch (_: Exception) {
        }
    }

    private fun loadWatch(): Watch? {
        val until = prefs.getLong("watch_until", 0)
        return if (until > System.currentTimeMillis()) Watch(until, prefs.getBoolean("watch_confirmed", false)) else null
    }

    private fun saveWatch(watch: Watch?) {
        prefs.edit().putLong("watch_until", watch?.until ?: 0).putBoolean("watch_confirmed", watch?.confirmed == true).apply()
        _watch.value = watch
    }

    // ---- what comes back -----------------------------------------------------------------------

    private suspend fun onUpdate(update: OutgoingUpdate) {
        val id = "o:${update.msgId}"
        when (update) {
            is OutgoingUpdate.Forwarded -> dao.markForwarded(id, update.peers, update.viaUplink)
            is OutgoingUpdate.AtServer -> dao.markAtServer(id, update.hops, update.path.joinToString(">"))
            is OutgoingUpdate.Rejected -> dao.markFailed(id, "Rejected by the server")
        }
    }

    private suspend fun onIncoming(msg: IncomingMessage) {
        val d = msg.data
        fun str(key: String) = d[key]?.jsonPrimitive?.contentOrNull
        when (msg.kind) {
            "answer" -> {
                val ref = str("ref")
                val title = str("title") ?: "Answer"
                val watchUntil = d["watch"]?.jsonPrimitive?.longOrNull
                val id = if (ref != null) "o:$ref" else "p:${msg.msgId}"
                if (ref == null || dao.get(id) == null) {
                    // A watched alert the server pushed, or an answer to a question this phone forgot.
                    val tool = str("tool") ?: "info"
                    val inserted = dao.insert(
                        InfoCardEntity(
                            id = id, tool = tool, label = title, paramsJson = "{}", askedAt = System.currentTimeMillis(),
                            state = InfoState.QUEUED, pushed = ref == null, seen = ref != null || screenOpen,
                        )
                    ) != -1L
                    if (!inserted) return // the same push via another path
                }
                dao.answer(
                    id = id, at = System.currentTimeMillis(), ok = d["ok"]?.jsonPrimitive?.booleanOrNull ?: true, title = title,
                    body = str("body"), items = d["items"]?.toString(), source = str("src"), url = str("url"),
                    level = str("lvl"), ai = d["ai"]?.jsonPrimitive?.booleanOrNull == true,
                    part = d["part"]?.jsonPrimitive?.intOrNull, parts = d["parts"]?.jsonPrimitive?.intOrNull, key = str("key"),
                    fetchedAt = d["at"]?.jsonPrimitive?.longOrNull, bytes = d["bytes"]?.jsonPrimitive?.longOrNull,
                    cached = d["cached"]?.jsonPrimitive?.booleanOrNull == true, wire = d["wire"]?.jsonPrimitive?.intOrNull,
                    downHops = msg.hops, downPath = msg.path.joinToString(">"), watchUntil = watchUntil,
                )
                if (watchUntil != null && _watch.value != null) saveWatch(Watch(watchUntil, confirmed = true))
                when {
                    ref == null -> notify(id, "⚠️ $title", str("body") ?: "New warning for the area you're watching", urgent = true)
                    !screenOpen && str("tool") != "read" -> notify(id, "${InfoTools.emoji(str("tool") ?: "")} $title",
                        "Your answer came back through ${msg.hops} hop${if (msg.hops == 1) "" else "s"}", urgent = false)
                }
            }
            "watch" -> if (d["on"]?.jsonPrimitive?.booleanOrNull == false) saveWatch(null)
        }
    }

    // ---- notifications ---------------------------------------------------------------------------

    private fun createNotificationChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Live alerts for watched areas", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ANSWERS, "Live Info answers", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(id: String, title: String, text: String, urgent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_INFO, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFY_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, if (urgent) CHANNEL_ALERTS else CHANNEL_ANSWERS)
            .setSmallIcon(if (urgent) android.R.drawable.stat_sys_warning else android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    companion object {
        const val SERVICE = "info"
        private const val CHANNEL_ALERTS = "info_alerts"
        private const val CHANNEL_ANSWERS = "info_answers"
        private const val NOTIFY_REQUEST = 7301
        private const val HOUR_MS = 60 * 60 * 1000L
        /** A question that hasn't reached a gateway in 6 hours is stale (weather, news). */
        private const val QUESTION_TTL_MS = 6 * HOUR_MS
        private const val WATCH_HOURS = 72
        private const val KEEP_MS = 7 * 24 * HOUR_MS

        private fun round4(x: Double) = (x * 10_000).roundToLong() / 10_000.0
    }
}
