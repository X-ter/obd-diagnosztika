package hu.obddiag.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: Long): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE vin = :vin LIMIT 1")
    suspend fun findByVin(vin: String): VehicleEntity?

    @Upsert
    suspend fun upsert(vehicle: VehicleEntity): Long

    @Delete
    suspend fun delete(vehicle: VehicleEntity)
}

@Dao
interface DiagnosticSessionDao {
    @Query("SELECT * FROM diagnostic_sessions ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticSessionEntity>>

    @Query("SELECT * FROM diagnostic_sessions WHERE vehicleId = :vehicleId ORDER BY timestampMs DESC")
    fun observeForVehicle(vehicleId: Long): Flow<List<DiagnosticSessionEntity>>

    @Insert
    suspend fun insert(session: DiagnosticSessionEntity): Long

    @Query("DELETE FROM diagnostic_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log_sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<LogSessionEntity>>

    @Query("SELECT * FROM log_sessions WHERE id = :id")
    suspend fun getSession(id: Long): LogSessionEntity?

    @Insert
    suspend fun insertSession(session: LogSessionEntity): Long

    @Update
    suspend fun updateSession(session: LogSessionEntity)

    @Query("UPDATE log_sessions SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM log_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertSamples(samples: List<LogSampleEntity>)

    @Query("SELECT * FROM log_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC, id ASC")
    suspend fun samples(sessionId: Long): List<LogSampleEntity>

    @Query("SELECT COUNT(*) FROM log_samples WHERE sessionId = :sessionId")
    suspend fun sampleCount(sessionId: Long): Int
}

@Dao
interface DashboardDao {
    @Query("SELECT * FROM dashboards ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<DashboardEntity>>

    @Query("SELECT COUNT(*) FROM dashboards")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(d: DashboardEntity): Long

    @Query("DELETE FROM dashboards WHERE id = :id")
    suspend fun delete(id: Long)
}
