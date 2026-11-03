package hu.obddiag.core.poll

import hu.obddiag.core.ObdClient
import hu.obddiag.core.elm.ElmErrorKind
import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.pid.PidCategory
import hu.obddiag.core.pid.PidDecoder
import hu.obddiag.core.pid.Sample
import hu.obddiag.core.pid.StandardPids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Egy PID lekérdezési állapota. */
enum class PidHealth { OK, NOT_SUPPORTED, NO_RESPONSE }

/**
 * Élő adatok lekérdezési ütemezője.
 *
 * - Minden PID csak egyszer kerül a sorba, akárhány képernyő kéri (duplikáció szűrése).
 * - CAN-en legfeljebb 6 PID-et kérdez le egy kérésben, ha a jármű ezt támogatja.
 * - A lassan változó értékeket (hőmérsékletek, szintek) ritkábban kérdezi le.
 * - A többszöri NO DATA után a PID-et nem támogatottnak jelöli, és nem kérdezi le tovább,
 *   így nem jelenik meg hamis érték, és nem lassítja a többi lekérdezést.
 */
class PidPoller(
    private val client: ObdClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val simulated: Boolean = false
) {
    private val _samples = MutableSharedFlow<List<Sample>>(extraBufferCapacity = 64)
    val samples: SharedFlow<List<Sample>> = _samples.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, String>>(emptyMap())
    val states: StateFlow<Map<String, String>> = _states.asStateFlow()

    private val _health = MutableStateFlow<Map<Int, PidHealth>>(emptyMap())
    val health: StateFlow<Map<Int, PidHealth>> = _health.asStateFlow()

    /** Teljes körök másodpercenként (a felületen kijelezve). */
    private val _cycleRate = MutableStateFlow(0.0)
    val cycleRate: StateFlow<Double> = _cycleRate.asStateFlow()

    @Volatile private var requested: Set<Int> = emptySet()
    private val failures = HashMap<Int, Int>()
    private val lastPolled = HashMap<Int, Long>()

    /** A kért minimális körszünet (ms). 0 = amilyen gyorsan az adapter engedi. */
    @Volatile var minCycleIntervalMs: Long = 0

    /** Egymás után sikertelen körök (kapcsolatvesztés felismerésére). */
    @Volatile var consecutiveFailedCycles = 0
        private set

    fun setRequestedPids(pids: Set<Int>) {
        requested = pids
    }

    fun resetHealth() {
        failures.clear()
        _health.value = emptyMap()
    }

    private fun slowIntervalMs(pid: Int): Long {
        val def = StandardPids.pid(pid) ?: return 0
        val cats = def.signals.map { it.category }.toSet()
        return when {
            pid in setOf(0x2F, 0x30, 0x31, 0x21, 0x4D, 0x4E, 0x1C, 0x51, 0x13, 0x01, 0x03, 0x63) -> 5_000
            cats.contains(PidCategory.TEMPERATURE) -> 2_000
            cats.contains(PidCategory.EXHAUST) && pid in 0x3C..0x3F -> 2_000
            else -> 0
        }
    }

    /** A lekérdezési kör: addig fut, amíg a hívó coroutine-t meg nem szakítják. */
    suspend fun run() {
        while (coroutineContext.isActive) {
            val started = clock()
            val pids = requested.filter { _health.value[it] != PidHealth.NOT_SUPPORTED && StandardPids.pid(it) != null }
                .filter { p -> client.supportedPids.isEmpty() || p in client.supportedPids }
            if (pids.isEmpty()) {
                delay(250)
                continue
            }
            val now = clock()
            val due = pids.filter { p -> now - (lastPolled[p] ?: 0L) >= slowIntervalMs(p) }
            val groups = if (client.multiPidSupported) due.chunked(MULTI_PID_GROUP) else due.map { listOf(it) }
            var anySuccess = false
            var anyAttempt = false
            for (group in groups) {
                if (!coroutineContext.isActive) return
                anyAttempt = true
                val result = safeRead(group)
                if (group.size > 1 && (result !is ElmResult.Data || result.messages.isEmpty())) {
                    // A csoportos kérés nem sikerült: ebben a körben egyenként kérdezzük le a PID-eket.
                    for (p in group) {
                        if (handleResult(listOf(p), safeRead(listOf(p)))) anySuccess = true
                    }
                } else if (handleResult(group, result)) anySuccess = true
            }
            consecutiveFailedCycles = if (anySuccess || !anyAttempt) 0 else consecutiveFailedCycles + 1
            val elapsed = clock() - started
            if (elapsed > 0) _cycleRate.value = 1000.0 / elapsed
            val wait = minCycleIntervalMs - elapsed
            delay(if (wait > 0) wait else 10)
        }
    }

    private suspend fun safeRead(group: List<Int>): ElmResult = try {
        client.readPids(group)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ElmResult.Error(ElmErrorKind.TIMEOUT, e.message ?: "")
    }

    private suspend fun handleResult(group: List<Int>, result: ElmResult): Boolean {
        val ts = clock()
        group.forEach { lastPolled[it] = ts }
        when (result) {
            is ElmResult.Data -> {
                val decoded = PidDecoder.decode(result.messages)
                // Több ECU válasza esetén az elsőként érkező (általában a motorvezérlő, 7E8) értéke számít.
                val byPid = decoded.groupBy { it.pid }.mapValues { it.value.first() }
                val samples = mutableListOf<Sample>()
                val states = HashMap(_states.value)
                for (pid in group) {
                    val d = byPid[pid]
                    if (d == null) {
                        markFailure(pid)
                        continue
                    }
                    failures[pid] = 0
                    setHealth(pid, PidHealth.OK)
                    d.values.forEach { (id, v) -> samples += Sample(id, v, ts, simulated) }
                    states.putAll(d.states)
                }
                if (samples.isNotEmpty()) _samples.emit(samples)
                _states.value = states
                return byPid.isNotEmpty()
            }
            is ElmResult.NoData -> {
                group.forEach { markFailure(it) }
                return false
            }
            is ElmResult.Error -> {
                if (result.kind == ElmErrorKind.UNKNOWN_COMMAND) group.forEach { setHealth(it, PidHealth.NOT_SUPPORTED) }
                else group.forEach { setHealth(it, PidHealth.NO_RESPONSE) }
                return false
            }
            is ElmResult.Text -> return false
        }
    }

    private fun markFailure(pid: Int) {
        val n = (failures[pid] ?: 0) + 1
        failures[pid] = n
        setHealth(pid, if (n >= MAX_FAILURES) PidHealth.NOT_SUPPORTED else PidHealth.NO_RESPONSE)
    }

    private fun setHealth(pid: Int, h: PidHealth) {
        if (_health.value[pid] != h) _health.value = _health.value + (pid to h)
    }

    companion object {
        const val MULTI_PID_GROUP = 6
        const val MAX_FAILURES = 3
    }
}
