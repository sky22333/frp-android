package com.sky22333.frpandroid.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpLog
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpRuntimeState
import com.sky22333.frpandroid.core.frp.FrpType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class FrpProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: FrpType,
    val toml: String,
    val autoStart: Boolean,
    val updatedAt: Long,
) {
    fun toModel(): FrpProfile = FrpProfile(id, name, type, toml, autoStart, updatedAt)
}

@Entity(tableName = "runtime_states")
data class FrpRuntimeStateEntity(
    @PrimaryKey val id: String,
    val type: FrpType,
    val state: FrpInstanceStatus,
    val lastError: String?,
    val desiredRunning: Boolean = false,
) {
    fun toModel(): FrpRuntimeState = FrpRuntimeState(id, type, state, lastError)
}

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["time"]),
        Index(value = ["level"]),
        Index(value = ["type"]),
        Index(value = ["instanceId"]),
    ],
)
data class FrpLogEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val instanceId: String,
    val type: String,
    val level: String,
    val message: String,
    val time: Long,
) {
    fun toModel(): FrpLog = FrpLog(
        instanceId = instanceId,
        type = type,
        level = level,
        message = message,
        time = time,
        uid = uid,
    )
}

class FrpTypeConverters {
    @TypeConverter fun typeToString(type: FrpType): String = type.name
    @TypeConverter fun stringToType(value: String): FrpType = FrpType.valueOf(value)
    @TypeConverter fun statusToString(status: FrpInstanceStatus): String = status.name
    @TypeConverter fun stringToStatus(value: String): FrpInstanceStatus = FrpInstanceStatus.valueOf(value)
}

@Dao
interface FrpDao {
    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC")
    fun observeProfiles(): Flow<List<FrpProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): FrpProfileEntity?

    @Query("SELECT * FROM profiles WHERE autoStart = 1")
    suspend fun getAutoStartProfiles(): List<FrpProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: FrpProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("SELECT * FROM runtime_states")
    fun observeRuntimeStates(): Flow<List<FrpRuntimeStateEntity>>

    @Query("SELECT * FROM runtime_states")
    suspend fun getRuntimeStates(): List<FrpRuntimeStateEntity>

    @Query("SELECT * FROM runtime_states WHERE desiredRunning = 1")
    suspend fun getDesiredRunningStates(): List<FrpRuntimeStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntimeState(state: FrpRuntimeStateEntity)

    @Query("DELETE FROM runtime_states WHERE id = :id")
    suspend fun deleteRuntimeState(id: String)

    @Query(
        """
        SELECT * FROM logs
        WHERE (:instanceId IS NULL OR instanceId = :instanceId)
        AND (:type IS NULL OR type = :type)
        AND (:level IS NULL OR level = :level)
        AND (:keyword IS NULL OR message LIKE '%' || :keyword || '%')
        ORDER BY time DESC
        LIMIT :limit
        """,
    )
    fun observeLogs(
        instanceId: String?,
        type: String?,
        level: String?,
        keyword: String?,
        limit: Int,
    ): Flow<List<FrpLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLogs(logs: List<FrpLogEntity>)

    @Query(
        """
        DELETE FROM logs
        WHERE uid <= COALESCE(
            (
                SELECT uid FROM logs
                ORDER BY uid DESC
                LIMIT 1 OFFSET :maxCount
            ),
            -1
        )
        """,
    )
    suspend fun trimLogs(maxCount: Int)

    @Query("DELETE FROM logs WHERE time < :olderThan")
    suspend fun deleteLogsOlderThan(olderThan: Long)

    @Query("DELETE FROM logs")
    suspend fun clearLogs()
}

@Database(
    entities = [FrpProfileEntity::class, FrpRuntimeStateEntity::class, FrpLogEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(FrpTypeConverters::class)
abstract class FrpDatabase : RoomDatabase() {
    abstract fun frpDao(): FrpDao

    companion object {
        @Volatile private var instance: FrpDatabase? = null

        fun get(context: Context): FrpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrpDatabase::class.java,
                    "frp-android.db",
                ).build().also { instance = it }
            }
    }
}

fun FrpProfile.toEntity(): FrpProfileEntity =
    FrpProfileEntity(id, name, type, toml, autoStart, updatedAt)

fun FrpRuntimeState.toEntity(desiredRunning: Boolean = false): FrpRuntimeStateEntity =
    FrpRuntimeStateEntity(id, type, state, lastError, desiredRunning)

fun FrpLog.toEntity(): FrpLogEntity =
    FrpLogEntity(uid = uid, instanceId = instanceId, type = type, level = level, message = message, time = time)
