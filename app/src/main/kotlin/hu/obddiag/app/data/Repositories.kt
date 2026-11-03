package hu.obddiag.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import hu.obddiag.core.ClearResult
import hu.obddiag.core.DtcReadResult
import hu.obddiag.core.FreezeFrame
import hu.obddiag.core.connection.ConnectionManager
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.dtc.Dtc
import hu.obddiag.core.dtc.DtcStatus
import hu.obddiag.core.pid.Sample
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.data.DiagnosticSessionEntity
import hu.obddiag.data.LogSampleEntity
import hu.obddiag.data.LogSessionEntity
import hu.obddiag.data.ObdDatabase
import hu.obddiag.data.VehicleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Egy teljes hibakód-olvasás eredménye (mindhárom típus). */
data class DtcScan(
    val stored: List<Dtc>,
    val pending: List<Dtc>,
    val permanent: List<Dtc>,
    val milOn: Boolean?,
    val notSupported: Set<DtcStatus>,
    val timestampMs: Long,
    val simulated: Boolean
) {
    val all: List<Dtc> get() = stored + pending + permanent
}

class NotConnectedException : Exception("Nincs járműkapcsolat")

class DiagnosticsRepository(
    private val db: ObdDatabase,
    private val connection: ConnectionManager,
    private val settings: SettingsRepository
) {
    private val _lastScan = MutableStateFlow<DtcScan?>(null)
    val lastScan: StateFlow<DtcScan?> = _lastScan.asStateFlow()

    fun recentSessions(limit: Int = 10) = db.diagnosticSessions().observeRecent(limit)

    private fun client() = connection.client.value?.takeIf { connection.state.value is ConnectionState.Connected }
        ?: throw NotConnectedException()

    suspend fun scan(): DtcScan {
        val c = client()
        val notSupported = mutableSetOf<DtcStatus>()
        fun codes(r: DtcReadResult, status: DtcStatus): List<Dtc> = when (r) {
            is DtcReadResult.Success -> r.codes
            is DtcReadResult.NotSupported -> { notSupported += status; emptyList() }
            is DtcReadResult.Failure -> throw IllegalStateException(r.kind.userMessage)
        }
        val stored = codes(c.readDtcs(DtcStatus.STORED), DtcStatus.STORED)
        val pending = codes(c.readDtcs(DtcStatus.PENDING), DtcStatus.PENDING)
        val permanent = try { codes(c.readDtcs(DtcStatus.PERMANENT), DtcStatus.PERMANENT) } catch (e: IllegalStateException) {
            // A Mode 0A-t sok régebbi (2010 előtti) jármű nem ismeri.
            notSupported += DtcStatus.PERMANENT; emptyList()
        }
        val readiness = c.readReadiness()
        val scan = DtcScan(stored, pending, permanent, readiness?.milOn, notSupported, System.currentTimeMillis(), connection.isSimulated)
        _lastScan.value = scan
        save(scan, cleared = false)
        return scan
    }

    suspend fun clear(): ClearResult {
        val r = client().clearDtcs()
        if (r is ClearResult.Success) {
            _lastScan.value?.let { save(it.copy(timestampMs = System.currentTimeMillis()), cleared = true) }
        }
        return r
    }

    suspend fun freezeFrame(): FreezeFrame? = client().readFreezeFrame()

    private suspend fun save(scan: DtcScan, cleared: Boolean) {
        val s = connection.state.value as? ConnectionState.Connected
        db.diagnosticSessions().insert(
            DiagnosticSessionEntity(
                vehicleId = settings.current().activeVehicleId,
                timestampMs = scan.timestampMs,
                codes = scan.all.joinToString(";") { "${it.code}:${it.status.name}:${it.ecu}" },
                storedCount = scan.stored.size,
                pendingCount = scan.pending.size,
                permanentCount = scan.permanent.size,
                milOn = scan.milOn,
                protocol = s?.vehicle?.protocol?.displayName,
                vin = null,
                cleared = cleared,
                simulated = scan.simulated
            )
        )
    }
}

class VehicleRepository(private val db: ObdDatabase, private val settings: SettingsRepository) {
    val vehicles = db.vehicles().observeAll()
    suspend fun save(v: VehicleEntity): Long {
        val id = db.vehicles().upsert(v)
        return if (id == -1L) v.id else id
    }
    suspend fun delete(v: VehicleEntity) {
        db.vehicles().delete(v)
        if (settings.current().activeVehicleId == v.id) settings.setActiveVehicle(null)
    }
    suspend fun get(id: Long) = db.vehicles().get(id)

    /** A kiolvasott VIN alapján megkeresi vagy létrehozza a járműprofilt, és aktívvá teszi. */
    suspend fun attachVin(vin: String): VehicleEntity {
        val existing = db.vehicles().findByVin(vin)
        val v = existing ?: run {
            val active = settings.current().activeVehicleId?.let { db.vehicles().get(it) }
            if (active != null && active.vin.isNullOrBlank()) active.copy(vin = vin)
            else VehicleEntity(make = VinInfo.manufacturer(vin) ?: "", model = "", year = VinInfo.modelYear(vin), vin = vin)
        }
        val id = save(v)
        settings.setActiveVehicle(id)
        return v.copy(id = id)
    }
}

/** Egyszerű VIN-értelmezés (WMI gyártó, 10. karakter modellév) – csak javaslat a profil kitöltéséhez. */
object VinInfo {
    private val WMI = mapOf(
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "WAU" to "Audi", "WBA" to "BMW", "WBS" to "BMW",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "WF0" to "Ford", "WOL" to "Opel", "W0L" to "Opel",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "VR3" to "Peugeot", "TMB" to "Škoda", "VSS" to "SEAT",
        "ZFA" to "Fiat", "JTD" to "Toyota", "JT2" to "Toyota", "SB1" to "Toyota", "JHM" to "Honda", "JN1" to "Nissan",
        "SJN" to "Nissan", "JMZ" to "Mazda", "JS2" to "Suzuki", "TSM" to "Suzuki", "KMH" to "Hyundai", "TMA" to "Hyundai",
        "KNA" to "Kia", "U5Y" to "Kia", "YV1" to "Volvo", "SAL" to "Land Rover", "SAJ" to "Jaguar", "WP0" to "Porsche",
        "UU1" to "Dacia", "1FA" to "Ford", "1G1" to "Chevrolet", "5YJ" to "Tesla", "LRW" to "Tesla", "XW8" to "Volkswagen"
    )

    fun manufacturer(vin: String): String? = WMI[vin.take(3).uppercase()]

    fun modelYear(vin: String): Int? {
        if (vin.length != 17) return null
        val codes = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val idx = codes.indexOf(vin[9].uppercaseChar())
        if (idx < 0) return null
        // A ciklus 30 évente ismétlődik; a jelenhez legközelebbi, nem jövőbeli évet választjuk.
        val now = java.time.Year.now().value + 1
        var year = 1980 + idx
        while (year + 30 <= now) year += 30
        return year
    }
}

/** Élő adatok rögzítése munkamenetekbe (Room), kötegelt írással. */
class LoggingRepository(
    private val scope: CoroutineScope,
    private val db: ObdDatabase,
    private val live: LiveDataRepository,
    private val connection: ConnectionManager,
    private val settings: SettingsRepository
) {
    data class Recording(val sessionId: Long, val name: String, val signals: Set<String>, val startedAt: Long, val samples: Int)

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    val sessions = db.logs().observeSessions()
    private var job: Job? = null
    private val lock = Mutex()

    suspend fun start(name: String, signals: Set<String>): Long = lock.withLock {
        stopLocked()
        val now = System.currentTimeMillis()
        val units = signals.joinToString(";") { id -> "$id=${StandardPids.signal(id)?.unit?.symbol ?: ""}" }
        val id = db.logs().insertSession(
            LogSessionEntity(vehicleId = settings.current().activeVehicleId, name = name, startedAt = now,
                signalIds = signals.joinToString(","), simulated = connection.isSimulated, units = units)
        )
        _recording.value = Recording(id, name, signals, now, 0)
        live.subscribe(OWNER, signals)
        job = scope.launch {
            val buffer = ArrayList<LogSampleEntity>()
            var lastFlush = System.currentTimeMillis()
            try {
            live.samples.collect { batch ->
                batch.filter { it.signalId in signals }.forEach { s: Sample ->
                    buffer += LogSampleEntity(sessionId = id, signalId = s.signalId, timestampMs = s.timestampMs, value = s.value)
                }
                val t = System.currentTimeMillis()
                if (buffer.size >= 200 || (buffer.isNotEmpty() && t - lastFlush > 2_000)) {
                    val toWrite = ArrayList(buffer); buffer.clear(); lastFlush = t
                    db.logs().insertSamples(toWrite)
                    _recording.value = _recording.value?.let { it.copy(samples = it.samples + toWrite.size) }
                }
            }
            } finally {
                // A leállításkor pufferben maradt mintákat is kiírjuk.
                if (buffer.isNotEmpty()) withContext(NonCancellable) { db.logs().insertSamples(ArrayList(buffer)) }
            }
        }
        id
    }

    suspend fun stop() = lock.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val rec = _recording.value ?: return
        job?.cancelAndJoin()
        job = null
        live.unsubscribe(OWNER)
        val count = db.logs().sampleCount(rec.sessionId)
        db.logs().getSession(rec.sessionId)?.let {
            db.logs().updateSession(it.copy(endedAt = System.currentTimeMillis(), sampleCount = count))
        }
        _recording.value = null
    }

    suspend fun rename(id: Long, name: String) = db.logs().rename(id, name)
    suspend fun delete(id: Long) = db.logs().deleteSession(id)
    suspend fun session(id: Long) = db.logs().getSession(id)
    suspend fun samples(id: Long) = db.logs().samples(id)

    companion object { const val OWNER = "logging" }
}

/** CSV-export és megosztás. Az időbélyegek UTC ISO 8601 formában és epoch ms-ban is szerepelnek. */
object CsvExporter {
    private val iso = DateTimeFormatter.ISO_INSTANT
    private val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun esc(s: String) = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Hosszú (normalizált) formátum: egy sor = egy minta. Ez minden jelnél egyértelmű. */
    fun samplesCsv(rows: List<Triple<Long, String, Double>>, nameOf: (String) -> String, simulated: Boolean, title: String): String = buildString {
        append("# ").append(esc(title)).append('\n')
        append("# Exportálva: ").append(iso.format(Instant.now())).append(" | Időzóna (local_time): ").append(ZoneId.systemDefault().id).append('\n')
        if (simulated) append("# FIGYELEM: SZIMULÁLT ADATOK – nem valós járműből származnak\n")
        append("timestamp_utc,epoch_ms,local_time,signal_id,signal_name,value,unit\n")
        for ((t, id, v) in rows) {
            val unit = StandardPids.signal(id)?.unit?.symbol ?: ""
            append(iso.format(Instant.ofEpochMilli(t))).append(',').append(t).append(',')
                .append(local.format(Instant.ofEpochMilli(t))).append(',').append(id).append(',')
                .append(esc(nameOf(id))).append(',').append(v.toString()).append(',').append(esc(unit)).append('\n')
        }
    }

    suspend fun share(context: Context, fileName: String, content: String, mime: String = "text/csv", chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, fileName).apply { writeText(content, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun fileStamp(): String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
}
