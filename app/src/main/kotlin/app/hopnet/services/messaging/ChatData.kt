package app.hopnet.services.messaging

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Delivery progress of a message, in order. Statuses only ever move forward. */
object MessageStatus {
    const val QUEUED = 0      // stored on this phone, nobody to hand it to yet
    const val RELAYED = 1     // handed to nearby phone(s) or uploaded directly
    const val AT_SERVER = 2   // server receipt came back
    const val DELIVERED = 3   // recipient's phone confirmed
    const val FAILED = 8
    const val RECEIVED = 9    // incoming message
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerHandle: String,
    val peerName: String,
    val lastText: String,
    val lastAt: Long,
    val unread: Int,
)

/** id is "o:<msgId>" for messages we sent and "i:<sender msgId>" for messages we received. */
@Entity(tableName = "messages", indices = [Index("peerHandle")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val peerHandle: String,
    val outgoing: Boolean,
    val text: String,
    val sentAt: Long,
    val status: Int,
    val relayPeers: Int = 0,
    val viaUplink: Boolean = false,
    val hops: Int? = null,
    val path: String? = null,
    val failReason: String? = null,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerHandle = :handle")
    fun conversation(handle: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE peerHandle = :handle")
    suspend fun getConversation(handle: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putConversation(conversation: ConversationEntity)

    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversations")
    fun unreadTotal(): Flow<Int>

    @Query("UPDATE conversations SET unread = 0 WHERE peerHandle = :handle")
    suspend fun markRead(handle: String)

    @Query("SELECT * FROM messages WHERE peerHandle = :handle ORDER BY sentAt DESC")
    fun messages(handle: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query(
        "UPDATE messages SET relayPeers = MAX(relayPeers, :peers), viaUplink = (viaUplink OR :viaUplink), " +
            "status = CASE WHEN status < 1 THEN 1 ELSE status END WHERE id = :id AND status < 3"
    )
    suspend fun markForwarded(id: String, peers: Int, viaUplink: Boolean)

    @Query("UPDATE messages SET status = 2, hops = :hops, path = :path WHERE id = :id AND status < 2")
    suspend fun markAtServer(id: String, hops: Int, path: String)

    @Query("UPDATE messages SET status = 3 WHERE id = :id AND status < 3")
    suspend fun markDelivered(id: String)

    @Query("UPDATE messages SET status = 8, failReason = :reason WHERE id = :id AND status < 3")
    suspend fun markFailed(id: String, reason: String)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db").build()
    }
}
