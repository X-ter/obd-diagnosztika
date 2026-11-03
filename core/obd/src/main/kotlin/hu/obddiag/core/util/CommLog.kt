package hu.obddiag.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Gyűrűpufferes kommunikációs napló: az adapterrel váltott üzenetek és a hibák, diagnosztikai célra. */
class CommLog(private val capacity: Int = 500, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Direction { TX, RX, ERROR, INFO }

    data class Entry(val id: Long, val timestampMs: Long, val direction: Direction, val text: String)

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private var nextId = 0L
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Ha ki van kapcsolva, csak a hibák és az információs bejegyzések kerülnek a naplóba, a forgalom nem. */
    @Volatile var trafficEnabled: Boolean = true

    fun add(direction: Direction, text: String) {
        if (!trafficEnabled && (direction == Direction.TX || direction == Direction.RX)) return
        synchronized(lock) {
            buffer.addLast(Entry(nextId++, clock(), direction, text))
            while (buffer.size > capacity) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }
}
