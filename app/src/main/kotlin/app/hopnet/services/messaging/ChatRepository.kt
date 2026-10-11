package app.hopnet.services.messaging

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Messages service on the phone. Sending is just Hopnet.send("chat", "message", ...);
 * everything else here is bookkeeping: turning SDK progress events into ticks and
 * incoming DOWN messages into conversations and notifications.
 */
class ChatRepository(private val context: Context, private val scope: CoroutineScope) {
    private val dao = ChatDatabase.create(context).dao()
    private val conversationLock = Mutex()

    /** The chat currently on screen, so we don't notify or count it as unread. */
    @Volatile
    var openConversation: String? = null

    val conversations: Flow<List<ConversationEntity>> = dao.conversations()
    val unreadTotal: Flow<Int> = dao.unreadTotal()

    fun messages(handle: String) = dao.messages(handle)

    fun conversation(handle: String) = dao.conversation(handle)

    fun start() {
        createNotificationChannel()
        scope.launch { Hopnet.updates.collect { onUpdate(it) } }
        scope.launch { Hopnet.incoming.collect { if (it.service == SERVICE) onIncoming(it) } }
    }

    suspend fun send(toHandle: String, text: String) {
        val handle = normalizeHandle(toHandle)
        val msgId = Hopnet.newMessageId()
        val now = System.currentTimeMillis()
        dao.insertMessage(MessageEntity("o:$msgId", handle, outgoing = true, text = text, sentAt = now, status = MessageStatus.QUEUED))
        touchConversation(handle, null, text, now, unreadDelta = 0)
        try {
            Hopnet.send(SERVICE, "message", buildJsonObject { put("to", handle); put("text", text) }, msgId)
        } catch (e: Exception) {
            dao.markFailed("o:$msgId", e.message ?: "Could not send")
        }
    }

    suspend fun markRead(handle: String) = dao.markRead(handle)

    suspend fun startConversation(handle: String, name: String?) {
        val h = normalizeHandle(handle)
        if (dao.getConversation(h) == null) touchConversation(h, name, "", System.currentTimeMillis(), 0)
    }

    private suspend fun onUpdate(update: OutgoingUpdate) {
        val id = "o:${update.msgId}"
        when (update) {
            is OutgoingUpdate.Forwarded -> dao.markForwarded(id, update.peers, update.viaUplink)
            is OutgoingUpdate.AtServer -> dao.markAtServer(id, update.hops, update.path.joinToString(">"))
            is OutgoingUpdate.Rejected -> dao.markFailed(id, "Rejected by server")
        }
    }

    private suspend fun onIncoming(msg: IncomingMessage) {
        val data = msg.data
        fun str(key: String) = data[key]?.jsonPrimitive?.content
        when (msg.kind) {
            "message" -> {
                val from = str("from") ?: return
                val text = str("text").orEmpty()
                val ref = str("ref") ?: msg.msgId
                val sentAt = data["sentAt"]?.jsonPrimitive?.longOrNull ?: msg.sentAt
                val inserted = dao.insertMessage(
                    MessageEntity(
                        id = "i:$ref", peerHandle = from, outgoing = false, text = text, sentAt = sentAt,
                        status = MessageStatus.RECEIVED, hops = msg.hops, path = msg.path.joinToString(">"),
                    )
                ) != -1L
                if (!inserted) return // duplicate copy via another path
                val visible = openConversation == from
                touchConversation(from, str("fromName"), text, sentAt, unreadDelta = if (visible) 0 else 1)
                if (!visible) notify(from, str("fromName") ?: "@$from", text)
            }
            "status" -> {
                val ref = str("ref") ?: return
                when (str("status")) {
                    "delivered" -> dao.markDelivered("o:$ref")
                    "failed" -> dao.markFailed("o:$ref", str("reason") ?: "Not delivered")
                }
            }
        }
    }

    private suspend fun touchConversation(handle: String, name: String?, text: String, at: Long, unreadDelta: Int) {
        conversationLock.withLock {
            val existing = dao.getConversation(handle)
            dao.putConversation(
                ConversationEntity(
                    peerHandle = handle,
                    peerName = name ?: existing?.peerName ?: handle,
                    lastText = text.ifEmpty { existing?.lastText.orEmpty() },
                    lastAt = maxOf(at, existing?.lastAt ?: 0),
                    unread = (existing?.unread ?: 0) + unreadDelta,
                )
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(handle: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, handle)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, handle.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(handle.hashCode(), notification)
    }

    companion object {
        const val SERVICE = "chat"
        private const val CHANNEL_ID = "messages"
        private val HANDLE = Regex("^[a-z0-9_]{3,20}$")

        fun normalizeHandle(raw: String) = raw.trim().removePrefix("@").lowercase()

        fun isValidHandle(raw: String) = HANDLE.matches(normalizeHandle(raw))
    }
}
