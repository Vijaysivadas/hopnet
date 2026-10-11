package app.hopnet.services.info

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
import app.hopnet.core.client.HopnetJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Progress of a question, in order. Like chat ticks, a state only moves forward. */
object InfoState {
    const val QUEUED = 0     // stored here, nobody to hand it to yet
    const val RELAYED = 1    // handed to nearby phones, or uploaded directly
    const val AT_SERVER = 2  // server receipt came back: it's reading the internet
    const val ANSWERED = 3
    const val FAILED = 8
}

/**
 * One question and, once it comes back, its answer card. id is "o:<msgId>" for questions asked on
 * this phone and "p:<msgId>" for alerts the server pushed on its own (watches).
 * Reader pages are rows too (tool "read"), grouped by [linkKey] and ordered by [part].
 */
@Entity(tableName = "cards", indices = [Index("linkKey"), Index("askedAt")])
data class InfoCardEntity(
    @PrimaryKey val id: String,
    val tool: String,
    /** What the person asked, e.g. "Weather · Aluva". */
    val label: String,
    /** The request, so it can be asked again. */
    val paramsJson: String,
    val askedAt: Long,
    val state: Int,
    val relayPeers: Int = 0,
    val viaUplink: Boolean = false,
    val upHops: Int? = null,
    val upPath: String? = null,
    val answeredAt: Long? = null,
    val ok: Boolean = true,
    val title: String? = null,
    val body: String? = null,
    val itemsJson: String? = null,
    val source: String? = null,
    val url: String? = null,
    val level: String? = null,
    val ai: Boolean = false,
    val part: Int? = null,
    val parts: Int? = null,
    val linkKey: String? = null,
    /** When the server fetched it (its clock). */
    val fetchedAt: Long? = null,
    /** What the server pulled from the internet to answer. */
    val bytesFetched: Long? = null,
    val cached: Boolean = false,
    /** What the answer took on every hop of the mesh. */
    val wireBytes: Int? = null,
    val downHops: Int? = null,
    val downPath: String? = null,
    val watchUntil: Long? = null,
    val pushed: Boolean = false,
    val seen: Boolean = true,
    val failReason: String? = null,
)

/** One line of an answer card: a headline, a place, an alert, a search result. */
data class InfoItem(
    val title: String,
    val detail: String?,
    val meta: String?,
    val key: String?,
    val level: String?,
    val lat: Double?,
    val lon: Double?,
)

val InfoCardEntity.items: List<InfoItem>
    get() = itemsJson?.let { raw ->
        runCatching {
            HopnetJson.parseToJsonElement(raw).jsonArray.map { el ->
                val o = el.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                InfoItem(s("t").orEmpty(), s("s"), s("m"), s("k"), s("lvl"), o["lat"]?.jsonPrimitive?.doubleOrNull, o["lon"]?.jsonPrimitive?.doubleOrNull)
            }
        }.getOrNull()
    } ?: emptyList()

val InfoCardEntity.params: JsonObject
    get() = runCatching { HopnetJson.parseToJsonElement(paramsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))

val InfoCardEntity.pending: Boolean get() = state < InfoState.ANSWERED

@Dao
interface InfoDao {
    @Query("SELECT * FROM cards WHERE tool != 'read' ORDER BY askedAt DESC LIMIT 100")
    fun feed(): Flow<List<InfoCardEntity>>

    @Query("SELECT * FROM cards WHERE tool = 'read' AND (linkKey = :key OR id = :firstId) ORDER BY part, askedAt")
    fun reader(key: String, firstId: String): Flow<List<InfoCardEntity>>

    @Query("SELECT COUNT(*) FROM cards WHERE pushed = 1 AND seen = 0")
    fun unseenCount(): Flow<Int>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun get(id: String): InfoCardEntity?

    @Query("SELECT * FROM cards WHERE tool = 'read' AND linkKey = :key AND part = :part AND state != 8 LIMIT 1")
    suspend fun readerPart(key: String, part: Int): InfoCardEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: InfoCardEntity): Long

    @Query(
        "UPDATE cards SET relayPeers = MAX(relayPeers, :peers), viaUplink = (viaUplink OR :viaUplink), " +
            "state = CASE WHEN state < 1 THEN 1 ELSE state END WHERE id = :id AND state < 3"
    )
    suspend fun markForwarded(id: String, peers: Int, viaUplink: Boolean)

    @Query("UPDATE cards SET state = 2, upHops = :hops, upPath = :path WHERE id = :id AND state < 2")
    suspend fun markAtServer(id: String, hops: Int, path: String)

    @Query("UPDATE cards SET state = 8, failReason = :reason WHERE id = :id AND state < 3")
    suspend fun markFailed(id: String, reason: String)

    @Query(
        "UPDATE cards SET state = 3, answeredAt = :at, ok = :ok, title = :title, body = :body, itemsJson = :items, " +
            "source = :source, url = :url, level = :level, ai = :ai, part = COALESCE(:part, part), parts = :parts, " +
            "linkKey = COALESCE(:key, linkKey), fetchedAt = :fetchedAt, bytesFetched = :bytes, cached = :cached, " +
            "wireBytes = :wire, downHops = :downHops, downPath = :downPath, watchUntil = :watchUntil WHERE id = :id"
    )
    suspend fun answer(
        id: String, at: Long, ok: Boolean, title: String, body: String?, items: String?, source: String?, url: String?,
        level: String?, ai: Boolean, part: Int?, parts: Int?, key: String?, fetchedAt: Long?, bytes: Long?, cached: Boolean,
        wire: Int?, downHops: Int, downPath: String, watchUntil: Long?,
    ): Int

    @Query("UPDATE cards SET seen = 1 WHERE seen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM cards WHERE state >= 3 OR state = 8")
    suspend fun clearFinished()

    @Query("DELETE FROM cards WHERE askedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Database(entities = [InfoCardEntity::class], version = 1, exportSchema = false)
abstract class InfoDatabase : RoomDatabase() {
    abstract fun dao(): InfoDao

    companion object {
        fun create(context: Context): InfoDatabase =
            Room.databaseBuilder(context, InfoDatabase::class.java, "info.db").build()
    }
}
