package hu.obddiag.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val make: String,
    val model: String,
    val year: Int?,
    val engine: String = "",
    val engineCode: String = "",
    val vin: String? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayName: String get() = listOfNotNull(make.ifBlank { null }, model.ifBlank { null }, year?.toString()).joinToString(" ")
}

/**
 * Egy hibakód-olvasás eredménye. A kódokat "KÓD:státusz:ECU" formában, pontosvesszővel elválasztva tároljuk
 * (pl. "P0301:STORED:7E8;P0171:PENDING:7E8").
 */
@Entity(
    tableName = "diagnostic_sessions",
    foreignKeys = [ForeignKey(entity = VehicleEntity::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("vehicleId"), Index("timestampMs")]
)
data class DiagnosticSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    /** UTC epoch ms */
    val timestampMs: Long,
    val codes: String,
    val storedCount: Int,
    val pendingCount: Int,
    val permanentCount: Int,
    val milOn: Boolean?,
    val protocol: String?,
    val vin: String?,
    val cleared: Boolean = false,
    val simulated: Boolean
)

@Entity(
    tableName = "log_sessions",
    foreignKeys = [ForeignKey(entity = VehicleEntity::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("vehicleId")]
)
data class LogSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val name: String,
    /** UTC epoch ms */
    val startedAt: Long,
    val endedAt: Long? = null,
    /** A rögzített jelek azonosítói vesszővel elválasztva (pl. "01.0C,01.0D"). */
    val signalIds: String,
    val sampleCount: Int = 0,
    val simulated: Boolean,
    /** A minták mindig SI alapegységben kerülnek tárolásra; ez a mező az egységeket rögzíti ("01.0C=1/min;…"). */
    val units: String = ""
)

@Entity(
    tableName = "log_samples",
    foreignKeys = [ForeignKey(entity = LogSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "timestampMs"])]
)
data class LogSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val signalId: String,
    /** UTC epoch ms */
    val timestampMs: Long,
    val value: Double
)

/** Műszerfalprofil: a widgetek elrendezése JSON-ban. */
@Entity(tableName = "dashboards")
data class DashboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val layoutJson: String,
    val sortOrder: Int = 0
)
