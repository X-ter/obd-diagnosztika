package hu.obddiag.app.data

import hu.obddiag.core.connection.ConnectionManager
import hu.obddiag.core.pid.Sample
import hu.obddiag.core.pid.DerivedSignals
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.poll.PidHealth
import hu.obddiag.core.poll.PidPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Egy jel aktuális értéke a munkamenet minimumával és maximumával. */
data class LiveValue(
    val signalId: String,
    val value: Double,
    val timestampMs: Long,
    val min: Double,
    val max: Double,
    val simulated: Boolean
)

/** Idősor-gyűrűpuffer a grafikonokhoz (időbélyeg ms, érték). */
class SeriesBuffer(private val capacity: Int) {
    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var start = 0
    var size = 0
        private set

    @Synchronized fun add(t: Long, v: Double) {
        val idx = (start + size) % capacity
        times[idx] = t; values[idx] = v
        if (size < capacity) size++ else start = (start + 1) % capacity
    }

    @Synchronized fun snapshot(sinceMs: Long = Long.MIN_VALUE): Pair<LongArray, DoubleArray> {
        val ts = ArrayList<Long>(size); val vs = ArrayList<Double>(size)
        for (i in 0 until size) {
            val idx = (start + i) % capacity
            if (times[idx] >= sinceMs) { ts += times[idx]; vs += values[idx] }
        }
        return ts.toLongArray() to vs.toDoubleArray()
    }

    @Synchronized fun clear() { start = 0; size = 0 }
}

/**
 * Az élő adatok központi tárolója.
 *
 * A képernyők (és a naplózás) „feliratkoznak” a nekik szükséges jelekre; a lekérdezési sor ezek
 * uniójából áll össze, így minden PID csak egyszer kerül lekérdezésre. Ha senki sem figyel,
 * a lekérdezés leáll (akkumulátorkímélés).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataRepository(
    private val scope: CoroutineScope,
    private val connection: ConnectionManager
) {
    private val subscriptions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** A szüneteltetés okai (pl. "background", "terminal"); a lekérdezés csak akkor fut, ha nincs ok. */
    private val pauseReasons = MutableStateFlow<Set<String>>(emptySet())
    private val paused = MutableStateFlow(false)

    private val _values = MutableStateFlow<Map<String, LiveValue>>(emptyMap())
    val values: StateFlow<Map<String, LiveValue>> = _values.asStateFlow()

    private val _states = MutableStateFlow<Map<String, String>>(emptyMap())
    val states: StateFlow<Map<String, String>> = _states.asStateFlow()

    private val _health = MutableStateFlow<Map<Int, PidHealth>>(emptyMap())
    val health: StateFlow<Map<Int, PidHealth>> = _health.asStateFlow()

    private val _cycleRate = MutableStateFlow(0.0)
    val cycleRate: StateFlow<Double> = _cycleRate.asStateFlow()

    /** Minden új mintacsomag (a naplózó ezt rögzíti). */
    private val _samples = MutableSharedFlow<List<Sample>>(extraBufferCapacity = 128)
    val samples: SharedFlow<List<Sample>> = _samples.asSharedFlow()

    /** Növekvő számláló: a grafikonok ennek változásakor rajzolnak újra. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val history = HashMap<String, SeriesBuffer>()
    private var pollJob: Job? = null
    private var runningPoller: PidPoller? = null

    @Volatile var minCycleIntervalMs: Long = 0
        set(value) { field = value; connection.poller.value?.minCycleIntervalMs = value }

    init {
        // A lekérdező indítása/leállítása az aktív poller és a feliratkozások szerint
        scope.launch {
            combine(connection.poller, subscriptions, paused) { poller, subs, isPaused ->
                Triple(poller, subs.values.flatten().toSet(), isPaused)
            }.collectLatest { (poller, signals, isPaused) ->
                updatePolling(poller, signals, isPaused)
            }
        }
        scope.launch {
            connection.poller.flatMapLatest { it?.samples ?: emptyFlow() }.collect { onSamples(it) }
        }
        scope.launch {
            connection.poller.flatMapLatest { it?.states ?: flowOf(emptyMap()) }.collect { _states.value = it }
        }
        scope.launch {
            connection.poller.flatMapLatest { it?.health ?: flowOf(emptyMap()) }.collect { _health.value = it }
        }
        scope.launch {
            connection.poller.flatMapLatest { it?.cycleRate ?: flowOf(0.0) }.collect { _cycleRate.value = it }
        }
        scope.launch {
            // Új kapcsolatnál a korábbi értékek már nem érvényesek
            connection.poller.collect { resetValues() }
        }
    }

    private fun updatePolling(poller: PidPoller?, signals: Set<String>, isPaused: Boolean) {
        val pids = signals.flatMap { StandardPids.requiredPids(it) }.toSet()
        poller?.setRequestedPids(pids)
        poller?.minCycleIntervalMs = minCycleIntervalMs
        val shouldRun = poller != null && pids.isNotEmpty() && !isPaused
        if (shouldRun && (pollJob?.isActive != true || runningPoller !== poller)) {
            pollJob?.cancel()
            runningPoller = poller
            pollJob = scope.launch { poller!!.run() }
        } else if (!shouldRun) {
            pollJob?.cancel()
            pollJob = null
            runningPoller = null
        }
    }

    private fun onSamples(batch: List<Sample>) {
        val cur = HashMap(_values.value)
        for (s in batch) {
            val prev = cur[s.signalId]
            cur[s.signalId] = LiveValue(
                s.signalId, s.value, s.timestampMs,
                min = prev?.let { minOf(it.min, s.value) } ?: s.value,
                max = prev?.let { maxOf(it.max, s.value) } ?: s.value,
                simulated = s.simulated
            )
            synchronized(history) { history.getOrPut(s.signalId) { SeriesBuffer(HISTORY_CAPACITY) } }.add(s.timestampMs, s.value)
        }
        // Számított értékek (teljesítmény, fogyasztás, túlnyomás…) a frissen mért bemenetekből
        val ts = batch.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
        val simulated = batch.any { it.simulated }
        val derived = DerivedSignals.computeAll(
            cur.mapValues { (_, v) -> v.value to v.timestampMs }, batch.map { it.signalId }.toSet(), ts,
            connection.client.value?.compressionIgnition
        ).map { (id, v) -> Sample(id, v, ts, simulated) }
        for (s in derived) {
            val prev = cur[s.signalId]
            cur[s.signalId] = LiveValue(s.signalId, s.value, s.timestampMs,
                prev?.let { minOf(it.min, s.value) } ?: s.value, prev?.let { maxOf(it.max, s.value) } ?: s.value, s.simulated)
            synchronized(history) { history.getOrPut(s.signalId) { SeriesBuffer(HISTORY_CAPACITY) } }.add(s.timestampMs, s.value)
        }
        _values.value = cur
        _tick.value = _tick.value + 1
        _samples.tryEmit(if (derived.isEmpty()) batch else batch + derived)
    }

    fun history(signalId: String): SeriesBuffer? = synchronized(history) { history[signalId] }

    fun resetMinMax() {
        _values.value = _values.value.mapValues { (_, v) -> v.copy(min = v.value, max = v.value) }
    }

    private fun resetValues() {
        _values.value = emptyMap()
        _states.value = emptyMap()
        synchronized(history) { history.values.forEach { it.clear() } }
    }

    /** Feliratkozás jelekre egy azonosítóval; ugyanazzal az azonosítóval felülírható. */
    fun subscribe(owner: String, signals: Set<String>) {
        subscriptions.value = subscriptions.value + (owner to signals)
    }

    fun unsubscribe(owner: String) {
        subscriptions.value = subscriptions.value - owner
    }

    /** Szüneteltetés egy adott okból (háttérbe kerülés, szakértői terminál…); minden ok feloldása után folytatódik. */
    fun setPaused(reason: String, p: Boolean) {
        pauseReasons.value = if (p) pauseReasons.value + reason else pauseReasons.value - reason
        paused.value = pauseReasons.value.isNotEmpty()
    }

    companion object {
        /** Jelenként ~10 perc 5 Hz-en. */
        const val HISTORY_CAPACITY = 3_000
    }
}
