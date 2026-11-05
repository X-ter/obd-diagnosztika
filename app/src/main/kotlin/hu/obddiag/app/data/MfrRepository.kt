package hu.obddiag.app.data

import android.content.Context
import hu.obddiag.core.connection.ConnectionManager
import hu.obddiag.core.mfr.MfrDatabase
import hu.obddiag.core.mfr.MfrIdentification
import hu.obddiag.core.mfr.MfrSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/** A gyártói (PSA) diagnosztika állapota a felület számára. */
sealed class MfrState {
    data object Idle : MfrState()
    data object Loading : MfrState()
    /** [stage]: protocol | vin | ecu | brand | source:<id> */
    data class Running(val stage: String) : MfrState()
    data class Done(val recognition: hu.obddiag.core.ident.VehicleRecognition) : MfrState() {
        /** a gyártói profilmotor eredménye (ha egy forrás futott) */
        val result: MfrIdentification? get() = recognition.manufacturer
    }
    data class Failed(val message: String) : MfrState()
}

/**
 * Univerzális jármű- és ECU-felismerés. A gyártói adatforrások (jelenleg: PSA-RE, Apache-2.0) csak az egyik
 * integrált forrást jelentik; a felismerés VIN, protokoll és szabványos azonosítók alapján dönti el, melyik fut.
 * Az adatbázis csak az első használatkor töltődik be (kb. 3 MB tömörítve); memóriában csak a fejléc és az azonosított vezérlők adatai vannak.
 */
class MfrRepository(private val context: Context, private val connection: ConnectionManager) {
    private val _state = MutableStateFlow<MfrState>(MfrState.Idle)
    val state: StateFlow<MfrState> = _state.asStateFlow()

    @Volatile private var db: MfrDatabase? = null
    private val loadLock = Mutex()

    /** Ha a felhasználó már futtatott azonosítást, újracsatlakozáskor automatikusan megismételjük. */
    @Volatile var autoIdentify = false

    init {
        // A felület magyar; ahol a forrásban nincs magyar szöveg, ott az angol jelenik meg.
        MfrSignals.language = "hu"
    }

    suspend fun database(): MfrDatabase = loadLock.withLock {
        db ?: withContext(Dispatchers.IO) {
            MfrDatabase.fromLines { n ->
                MfrDatabase.lineOf({ GZIPInputStream(context.assets.open(ASSET), 1 shl 16).reader(Charsets.UTF_8) }, n)
            }
        }.also { db = it }
    }

    /** A felhasználó által kifejezetten megadott gyártói csoport (ha a VIN nem azonosít). */
    @Volatile var userGroup: hu.obddiag.core.ident.BrandGroup? = null
        private set

    /** Az integrált gyártói adatforrások. Új forrás itt adható hozzá (ugyanazzal a felismerési logikával). */
    val sources: List<hu.obddiag.core.ident.ProfileSource> by lazy {
        listOf(
            hu.obddiag.core.ident.DbmuxevProfileSource(
                id = "psa-re", name = "PSA-RE (prototux/PSA-RE)", license = "Apache-2.0",
                groups = setOf(hu.obddiag.core.ident.BrandGroup.STELLANTIS_PSA),
                database = { database() },
                sessionFactory = { c -> connection.newMfrSession(c) },
                onIdentified = { res, session, db -> connection.installManufacturer(res, session, db) }
            )
        )
    }

    /** Csoportok, amelyekhez van gyártói adatforrás (a felhasználói megadás választéka). */
    val supportedGroups: List<hu.obddiag.core.ident.BrandGroup> get() = sources.flatMap { it.groups }.distinct()

    suspend fun recognize(group: hu.obddiag.core.ident.BrandGroup? = userGroup): hu.obddiag.core.ident.VehicleRecognition? {
        if (_state.value is MfrState.Running || _state.value is MfrState.Loading) return null
        userGroup = group
        _state.value = MfrState.Running("protocol")
        return try {
            val r = connection.recognizeVehicle(sources, group) { st -> _state.value = MfrState.Running(st) }
            _state.value = if (r == null) MfrState.Failed("Nincs járműkapcsolat") else MfrState.Done(r)
            autoIdentify = true
            r
        } catch (e: Exception) {
            _state.value = MfrState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Régi belépési pont (újracsatlakozás): a teljes felismerést futtatja a korábbi megadással. */
    suspend fun identify() = recognize(userGroup)

    /**
     * Szimulált gyártói vezérlők a demó módhoz: az adatbázisból VÉLETLENSZERŰEN választva (minden kapcsolódáskor
     * más), hogy a szimuláció se egy konkrét vezérlőre épüljön.
     */
    fun simulationEcus(): List<hu.obddiag.core.sim.SimEcu> = try {
        val d = kotlinx.coroutines.runBlocking { database() }
        val cands = hu.obddiag.core.sim.SimulatedManufacturerEcu.candidates(d.ecus)
        val rnd = kotlin.random.Random(System.nanoTime())
        val out = mutableListOf<hu.obddiag.core.sim.SimulatedManufacturerEcu>()
        // egy nem biztonságkritikus, élő adatokkal rendelkező vezérlő + egy tetszőleges másik
        // (véletlen próbálkozás – a vezérlők adatai lustán töltődnek, nem olvassuk be mindet)
        for (i in 0 until 40) {
            val (e, v, p) = cands[rnd.nextInt(cands.size)]
            if (!e.safetyCritical && e.items.count { it.kind == "live" && it.paramsFor(v) != null } >= 2) {
                out += hu.obddiag.core.sim.SimulatedManufacturerEcu(e, v, p); break
            }
        }
        repeat(20) {
            if (out.size >= 2) return@repeat
            val (e, v, p) = cands[rnd.nextInt(cands.size)]
            if (out.none { it.requestId == e.requestId }) out += hu.obddiag.core.sim.SimulatedManufacturerEcu(e, v, p)
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    fun reset() {
        _state.value = MfrState.Idle
    }

    companion object {
        /** gzip-tömörített JSON; a kiterjesztés szándékosan nem .gz, mert azt a fordító kicsomagolná. */
        const val ASSET = "mfr/psa.dat"
        const val LICENSE_ASSET = "mfr/LICENSE-PSA-RE.txt"
    }
}
