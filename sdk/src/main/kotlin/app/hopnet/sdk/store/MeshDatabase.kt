package app.hopnet.sdk.store

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
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeCodec
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.store.EnvelopeStore
import app.hopnet.core.store.StoredEnvelope

@Entity(tableName = "envelopes", indices = [Index("expiresAt"), Index("type")])
internal class EnvelopeEntity(
    @PrimaryKey val msgId: String,
    val type: Int,
    val isLocal: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val storedAt: Long,
    val bytes: ByteArray,
)

@Entity(tableName = "seen")
internal class SeenEntity(@PrimaryKey val msgId: String, val seenAt: Long)

@Entity(tableName = "receipts")
internal class ReceiptEntity(@PrimaryKey val targetId: String, val expiresAt: Long)

@Dao
internal interface MeshDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeen(seen: SeenEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEnvelope(envelope: EnvelopeEntity)

    @Query("SELECT isLocal FROM envelopes WHERE msgId = :msgId")
    suspend fun isLocal(msgId: String): Boolean?

    @Query("SELECT * FROM envelopes WHERE msgId = :msgId")
    suspend fun get(msgId: String): EnvelopeEntity?

    @Query("DELETE FROM envelopes WHERE msgId = :msgId")
    suspend fun deleteEnvelope(msgId: String)

    @Query(
        "SELECT * FROM envelopes WHERE expiresAt > :now " +
            "ORDER BY CASE WHEN type = 4 THEN 0 WHEN type = 3 THEN 1 WHEN isLocal THEN 2 ELSE 3 END, createdAt DESC LIMIT :limit"
    )
    suspend fun live(now: Long, limit: Int): List<EnvelopeEntity>

    @Query("SELECT * FROM envelopes WHERE type = :type AND expiresAt > :now")
    suspend fun liveOfType(type: Int, now: Long): List<EnvelopeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReceipt(receipt: ReceiptEntity)

    @Query("SELECT COUNT(*) FROM receipts WHERE targetId = :targetId")
    suspend fun receiptCount(targetId: String): Int

    @Query("SELECT COUNT(*) FROM envelopes")
    suspend fun count(): Int

    @Query("DELETE FROM envelopes WHERE expiresAt <= :now")
    suspend fun deleteExpiredEnvelopes(now: Long)

    @Query("DELETE FROM receipts WHERE expiresAt <= :now")
    suspend fun deleteExpiredReceipts(now: Long)

    @Query("DELETE FROM seen WHERE seenAt < :before")
    suspend fun deleteSeenBefore(before: Long)

    /** Broadcasts (type 4) are kept: at most one per sender, and they may be a call for help. */
    @Query("DELETE FROM envelopes WHERE msgId IN (SELECT msgId FROM envelopes WHERE isLocal = 0 AND type != 4 ORDER BY storedAt ASC LIMIT :n)")
    suspend fun evictOldestRelayed(n: Int)

    @Query("DELETE FROM envelopes")
    suspend fun clearEnvelopes()

    @Query("DELETE FROM seen")
    suspend fun clearSeen()

    @Query("DELETE FROM receipts")
    suspend fun clearReceipts()
}

@Database(entities = [EnvelopeEntity::class, SeenEntity::class, ReceiptEntity::class], version = 1, exportSchema = false)
internal abstract class MeshDatabase : RoomDatabase() {
    abstract fun dao(): MeshDao

    companion object {
        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "hopnet_mesh.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** Store-and-forward state on disk, so carried messages survive app restarts and reboots. */
internal class RoomEnvelopeStore(db: MeshDatabase) : EnvelopeStore {
    private val dao = db.dao()
    private val seenCache = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 5_000
    }

    override suspend fun markSeen(msgId: String, now: Long): Boolean {
        synchronized(seenCache) { if (seenCache.containsKey(msgId)) return false }
        val inserted = dao.insertSeen(SeenEntity(msgId, now)) != -1L
        synchronized(seenCache) { seenCache[msgId] = Unit }
        return inserted
    }

    override suspend fun put(envelope: Envelope, local: Boolean, now: Long) {
        val wasLocal = dao.isLocal(envelope.msgId) == true
        dao.putEnvelope(
            EnvelopeEntity(
                msgId = envelope.msgId,
                type = envelope.type.code,
                isLocal = local || wasLocal,
                createdAt = envelope.createdAt,
                expiresAt = envelope.expiresAt,
                storedAt = now,
                bytes = EnvelopeCodec.encode(envelope),
            )
        )
    }

    override suspend fun remove(msgId: String) = dao.deleteEnvelope(msgId)

    override suspend fun isLocal(msgId: String) = dao.isLocal(msgId) == true

    override suspend fun get(msgId: String) = dao.get(msgId)?.toStored()

    override suspend fun live(now: Long, limit: Int) = dao.live(now, limit).mapNotNull { it.toStored() }

    override suspend fun liveOfType(type: EnvelopeType, now: Long) = dao.liveOfType(type.code, now).mapNotNull { it.toStored() }

    override suspend fun addReceipt(targetId: String, expiresAt: Long) = dao.putReceipt(ReceiptEntity(targetId, expiresAt))

    override suspend fun hasReceipt(targetId: String) = dao.receiptCount(targetId) > 0

    override suspend fun count() = dao.count()

    override suspend fun housekeeping(now: Long, maxEnvelopes: Int, seenTtlMs: Long) {
        dao.deleteExpiredEnvelopes(now)
        dao.deleteExpiredReceipts(now)
        dao.deleteSeenBefore(now - seenTtlMs)
        val overflow = dao.count() - maxEnvelopes
        if (overflow > 0) dao.evictOldestRelayed(overflow)
    }

    override suspend fun clear() {
        dao.clearEnvelopes()
        dao.clearSeen()
        dao.clearReceipts()
        synchronized(seenCache) { seenCache.clear() }
    }

    private fun EnvelopeEntity.toStored() = EnvelopeCodec.decodeOrNull(bytes)?.let { StoredEnvelope(it, isLocal) }
}
