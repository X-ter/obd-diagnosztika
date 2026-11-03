package hu.obddiag.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VehicleEntity::class, DiagnosticSessionEntity::class, LogSessionEntity::class, LogSampleEntity::class, DashboardEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ObdDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun diagnosticSessions(): DiagnosticSessionDao
    abstract fun logs(): LogDao
    abstract fun dashboards(): DashboardDao

    companion object {
        fun create(context: Context): ObdDatabase =
            Room.databaseBuilder(context.applicationContext, ObdDatabase::class.java, "obd_diag.db").build()
    }
}
