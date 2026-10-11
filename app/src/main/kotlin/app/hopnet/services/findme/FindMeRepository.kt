package app.hopnet.services.findme

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
import app.hopnet.core.client.IncomingBroadcast
import app.hopnet.core.client.OutgoingUpdate
import app.hopnet.core.protocol.Ids
import app.hopnet.sdk.Hopnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Find Me: someone lost in a crowd shares their name and GPS location with every Hopnet phone
 * nearby. It travels as a signed broadcast, phone to phone, with no server needed. Any phone
 * with internet on the way also uploads it, and the police page shows it.
 *
 * While the alert is active this phone re-sends it when the person moves, when the GPS fix
 * gets much better, and every few minutes. Each update replaces the previous one everywhere.
 */
class FindMeRepository(private val context: Context, private val scope: CoroutineScope) {
    private val dao = FindMeDatabase.create(context).dao()
    val location = LocationTracker(context)

    private val sendLock = Mutex()
    private val alertLock = Mutex()
    private var trackingJob: Job? = null

    /** The Find Me screen is showing, so new alerts are already visible and need no notification. */
    @Volatile
    var screenOpen = false

    /** Other people's alerts, lost first. Filter with [isVisible]; expired rows are cleaned up on start. */
    val alerts: Flow<List<AlertEntity>> = dao.alerts()
    val myAlert: Flow<MyAlertEntity?> = dao.myAlert()

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    /** How many people nearby are asking for help right now. */
    val helpCount: Flow<Int> = combine(alerts, clock) { list, now -> list.count { it.status == FindMeStatus.LOST && it.expiresAt > now } }

    fun start() {
        createNotificationChannel()
        scope.launch {
            val now = System.currentTimeMillis()
            dao.deleteExpiredAlerts(now)
            dao.deleteSendsBefore(now - SENDS_KEPT_MS)
        }
        scope.launch {
            Hopnet.broadcasts.collect {
                if (it.service != SERVICE) return@collect
                try {
                    onBroadcast(it)
                } catch (e: Exception) {
                    Hopnet.log("Find Me: bad alert ${it.msgId.take(6)} (${e.message})")
                }
            }
        }
        scope.launch { Hopnet.updates.collect { onUpdate(it) } }
        // An alert that was active when the app was killed keeps going.
        scope.launch { if (dao.getMyAlert()?.status == FindMeStatus.LOST) startTracking() }
    }

    // ---- my alert ------------------------------------------------------------------------------

    /** "I'm lost": starts sharing this phone's location until [markFound]. */
    suspend fun startLost(note: String) {
        sendLock.withLock {
            if (dao.getMyAlert()?.status == FindMeStatus.LOST) return@withLock
            dao.putMyAlert(
                MyAlertEntity(
                    caseId = Ids.newMsgId().take(16),
                    status = FindMeStatus.LOST,
                    note = note.trim().take(MAX_NOTE),
                    startedAt = System.currentTimeMillis(),
                )
            )
        }
        startTracking()
    }

    /** "I'm found": tells everyone nearby (and the police) and stops sharing location. */
    suspend fun markFound() {
        sendLock.withLock {
            stopTracking()
            val mine = dao.getMyAlert() ?: return
            if (mine.status != FindMeStatus.LOST) return
            if (!send(mine, FindMeStatus.FOUND, location.fix.value, System.currentTimeMillis())) {
                // Couldn't hand it to the mesh (not registered?): at least stop asking for help here.
                dao.putMyAlert(mine.copy(status = FindMeStatus.FOUND))
            }
        }
    }

    /** Clears the "you're marked as found" card. The found update keeps travelling in the mesh. */
    suspend fun dismiss() {
        sendLock.withLock {
            if (dao.getMyAlert()?.status == FindMeStatus.FOUND) dao.clearMyAlert()
        }
    }

    private fun startTracking() {
        synchronized(this) {
            if (trackingJob?.isActive == true) return
            trackingJob = scope.launch {
                location.acquire()
                try {
                    launch { location.fix.collect { maybeSend() } }
                    while (isActive) {
                        delay(TICK_MS)
                        maybeSend()
                    }
                } finally {
                    location.release()
                }
            }
        }
    }

    private fun stopTracking() {
        synchronized(this) {
            trackingJob?.cancel()
            trackingJob = null
        }
    }

    private suspend fun maybeSend() {
        sendLock.withLock {
            val mine = dao.getMyAlert() ?: return
            if (mine.status != FindMeStatus.LOST) return
            val fix = location.fix.value
            val now = System.currentTimeMillis()
            if (shouldSend(mine, fix, now)) send(mine, FindMeStatus.LOST, fix, now)
        }
    }

    /** Sends one update for my alert. Must hold [sendLock]. */
    private suspend fun send(mine: MyAlertEntity, kind: String, fix: Fix?, now: Long): Boolean {
        val profile = Hopnet.profile.value ?: return false
        val data = buildJsonObject {
            put("case", mine.caseId)
            put("name", profile.name)
            put("handle", profile.handle)
            if (fix != null) {
                put("lat", round(fix.lat, 1e6))
                put("lon", round(fix.lon, 1e6))
                fix.accuracy?.let { put("acc", round(it.toDouble(), 10.0)) }
                put("fixAt", fix.time)
            }
            if (mine.note.isNotBlank()) put("note", mine.note)
            LocationTracker.batteryPercent(context)?.let { put("battery", it) }
        }
        val msgId = Hopnet.newMessageId()
        dao.insertSend(MySendEntity(msgId, mine.caseId, now)) // before sending: progress can arrive at once
        try {
            Hopnet.broadcast(SERVICE, kind, data, msgId)
        } catch (e: Exception) {
            Hopnet.log("Find Me: could not send (${e.message})")
            return false
        }
        // A targeted update, so concurrent progress (peers, police) isn't overwritten.
        dao.markSent(
            caseId = mine.caseId, status = kind, at = now,
            lat = fix?.lat ?: mine.lat, lon = fix?.lon ?: mine.lon,
            accuracy = if (fix != null) fix.accuracy else mine.accuracy, fixAt = fix?.time ?: mine.fixAt,
        )
        return true
    }

    private suspend fun onUpdate(update: OutgoingUpdate) {
        val caseId = dao.caseOfSend(update.msgId) ?: return
        when (update) {
            is OutgoingUpdate.Forwarded -> dao.markForwarded(caseId, update.peers, update.viaUplink)
            is OutgoingUpdate.AtServer -> dao.markPolice(caseId, System.currentTimeMillis(), update.hops, update.path.joinToString(">"))
            is OutgoingUpdate.Rejected -> Hopnet.log("Find Me: server rejected update ${update.msgId.take(6)}")
        }
    }

    // ---- other people's alerts ---------------------------------------------------------------

    private suspend fun onBroadcast(b: IncomingBroadcast) {
        if (b.kind != FindMeStatus.LOST && b.kind != FindMeStatus.FOUND) return
        if (b.from == Hopnet.address) return
        val d = b.data
        val case = d.string("case")?.lowercase()?.takeIf { CASE.matches(it) } ?: return
        val lat = d.number("lat")?.takeIf { it in -90.0..90.0 }
        val lon = d.number("lon")?.takeIf { it in -180.0..180.0 }
        val hasFix = lat != null && lon != null
        val accuracy = d.number("acc")?.takeIf { it >= 0 }?.toFloat()
        val fixAt = d.long("fixAt")
        val battery = d.int("battery")?.takeIf { it in 0..100 }
        val name = d.string("name")?.trim()?.take(MAX_NAME)?.ifBlank { null } ?: "Someone"
        val handle = d.string("handle")?.trim()?.lowercase()?.takeIf { HANDLE.matches(it) }
        val note = d.string("note")?.trim()?.take(MAX_NOTE)?.ifBlank { null }
        val now = System.currentTimeMillis()

        alertLock.withLock {
            val existing = dao.alert(b.from)
            if (existing != null && (existing.sentAt > b.sentAt || (existing.sentAt == b.sentAt && existing.msgId >= b.msgId))) return
            // An update without a GPS fix keeps the last known location of the same incident.
            val previous = existing?.takeIf { it.caseId == case }
            val alert = AlertEntity(
                address = b.from,
                msgId = b.msgId,
                caseId = case,
                name = name,
                handle = handle,
                status = b.kind,
                lat = if (hasFix) lat else previous?.lat,
                lon = if (hasFix) lon else previous?.lon,
                accuracy = if (hasFix) accuracy else previous?.accuracy,
                fixAt = if (hasFix) fixAt else previous?.fixAt,
                note = note ?: previous?.note,
                battery = battery ?: previous?.battery,
                sentAt = b.sentAt,
                expiresAt = b.expiresAt,
                receivedAt = now,
                hops = b.hops,
                path = b.path.joinToString(">"),
            )
            dao.putAlert(alert)
            when {
                alert.status == FindMeStatus.FOUND -> cancelNotification(b.from)
                previous?.status != FindMeStatus.LOST && !screenOpen -> notifyLost(alert)
            }
        }
    }

    // ---- notifications -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Find Me alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When someone nearby is lost and asks for help"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyLost(alert: AlertEntity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_FINDME, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = alert.note?.let { "“$it”" } ?: "Open Hopnet to see how far away they are and which way to go."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${alert.name} is lost nearby and needs help")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(alert.address), notification)
    }

    private fun cancelNotification(address: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(address))
    }

    private fun notificationId(address: String) = "findme:$address".hashCode()

    companion object {
        const val SERVICE = "findme"
        const val MAX_NOTE = 140
        private const val MAX_NAME = 40
        private const val REQUEST_OPEN = 7301
        private const val CHANNEL_ID = "findme"
        private const val TICK_MS = 15_000L
        private const val MIN_INTERVAL_MS = 10_000L
        private const val HEARTBEAT_MS = 3 * 60_000L
        private const val MOVED_M = 25f
        private const val SENDS_KEPT_MS = 2L * 24 * 60 * 60 * 1000
        /** Found people stay on the list for a while so helpers see the good news. */
        const val FOUND_VISIBLE_MS = 30 * 60_000L
        private val CASE = Regex("^[0-9a-f]{8,32}$")
        private val HANDLE = Regex("^[a-z0-9_]{3,20}$")

        /** Whether this alert belongs on the list now. */
        fun AlertEntity.isVisible(now: Long) =
            expiresAt > now && (status == FindMeStatus.LOST || now - receivedAt < FOUND_VISIBLE_MS)

        /**
         * Send at once when starting, on the first GPS fix and after moving; otherwise at most
         * every [MIN_INTERVAL_MS], and a heartbeat every [HEARTBEAT_MS] (fresh battery, fresh expiry).
         */
        internal fun shouldSend(mine: MyAlertEntity, fix: Fix?, now: Long): Boolean {
            if (mine.lastSentAt == 0L) return true
            val since = now - mine.lastSentAt
            if (since < 0) return true // the clock moved back
            if (fix != null && !mine.hasFix) return true
            if (since < MIN_INTERVAL_MS) return false
            if (since >= HEARTBEAT_MS) return true
            if (fix == null || mine.lat == null || mine.lon == null || fix.time <= (mine.fixAt ?: 0L)) return false
            val moved = LocationTracker.distanceMeters(mine.lat, mine.lon, fix.lat, fix.lon)
            if (moved >= max(MOVED_M, fix.accuracy ?: 0f)) return true
            val before = mine.accuracy ?: return false
            val after = fix.accuracy ?: return false
            return after < before / 2 && before - after > 10f
        }

        private fun round(value: Double, scale: Double) = (value * scale).roundToLong() / scale

        private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
        private fun JsonObject.string(key: String) = primitive(key)?.takeIf { it.isString }?.content
        private fun JsonObject.number(key: String) = primitive(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.long(key: String) = primitive(key)?.takeIf { !it.isString }?.longOrNull
        private fun JsonObject.int(key: String) = primitive(key)?.takeIf { !it.isString }?.intOrNull
    }
}
