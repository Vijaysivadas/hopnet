package app.hopnet.services.findme

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

object FindMeStatus {
    const val LOST = "lost"
    const val FOUND = "found"
}

/** Someone else's Find Me alert, as last heard over the mesh. One row per person (their address). */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val address: String,
    val msgId: String,
    val caseId: String,
    val name: String,
    val handle: String?,
    val status: String,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    val fixAt: Long?,
    val note: String?,
    val battery: Int?,
    /** Sender's clock; only compared with the same sender's other updates. */
    val sentAt: Long,
    val expiresAt: Long,
    /** Our clock, when this update reached us. */
    val receivedAt: Long,
    val hops: Int,
    val path: String,
)

/** This phone's own alert (at most one, id 0). The location is the last one we sent. */
@Entity(tableName = "my_alert")
data class MyAlertEntity(
    @PrimaryKey val id: Int = 0,
    val caseId: String,
    val status: String,
    val note: String,
    val startedAt: Long,
    val lastSentAt: Long = 0,
    val updates: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float? = null,
    val fixAt: Long? = null,
    val relayPeers: Int = 0,
    val viaUplink: Boolean = false,
    val policeAt: Long? = null,
    val policeHops: Int? = null,
    val policePath: String? = null,
)

val AlertEntity.hasFix: Boolean get() = lat != null && lon != null

val MyAlertEntity.hasFix: Boolean get() = lat != null && lon != null

/** Every broadcast we sent, so SDK progress updates (by msgId) can be matched to our alert. */
@Entity(tableName = "my_sends", indices = [Index("caseId")])
data class MySendEntity(@PrimaryKey val msgId: String, val caseId: String, val sentAt: Long)

@Dao
interface FindMeDao {
    @Query("SELECT * FROM alerts ORDER BY CASE WHEN status = 'lost' THEN 0 ELSE 1 END, receivedAt DESC")
    fun alerts(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE address = :address")
    suspend fun alert(address: String): AlertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlert(alert: AlertEntity)

    @Query("DELETE FROM alerts WHERE expiresAt <= :now")
    suspend fun deleteExpiredAlerts(now: Long)

    @Query("SELECT * FROM my_alert WHERE id = 0")
    fun myAlert(): Flow<MyAlertEntity?>

    @Query("SELECT * FROM my_alert WHERE id = 0")
    suspend fun getMyAlert(): MyAlertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMyAlert(alert: MyAlertEntity)

    @Query("DELETE FROM my_alert")
    suspend fun clearMyAlert()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSend(send: MySendEntity)

    @Query("SELECT caseId FROM my_sends WHERE msgId = :msgId")
    suspend fun caseOfSend(msgId: String): String?

    @Query("DELETE FROM my_sends WHERE sentAt < :before")
    suspend fun deleteSendsBefore(before: Long)

    @Query(
        "UPDATE my_alert SET status = :status, lastSentAt = :at, updates = updates + 1, lat = :lat, lon = :lon, " +
            "accuracy = :accuracy, fixAt = :fixAt WHERE caseId = :caseId"
    )
    suspend fun markSent(caseId: String, status: String, at: Long, lat: Double?, lon: Double?, accuracy: Float?, fixAt: Long?)

    @Query("UPDATE my_alert SET relayPeers = MAX(relayPeers, :peers), viaUplink = (viaUplink OR :viaUplink) WHERE caseId = :caseId")
    suspend fun markForwarded(caseId: String, peers: Int, viaUplink: Boolean)

    @Query("UPDATE my_alert SET policeAt = :at, policeHops = :hops, policePath = :path WHERE caseId = :caseId AND policeAt IS NULL")
    suspend fun markPolice(caseId: String, at: Long, hops: Int, path: String)
}

@Database(entities = [AlertEntity::class, MyAlertEntity::class, MySendEntity::class], version = 1, exportSchema = false)
abstract class FindMeDatabase : RoomDatabase() {
    abstract fun dao(): FindMeDao

    companion object {
        fun create(context: Context): FindMeDatabase =
            Room.databaseBuilder(context, FindMeDatabase::class.java, "findme.db").build()
    }
}
