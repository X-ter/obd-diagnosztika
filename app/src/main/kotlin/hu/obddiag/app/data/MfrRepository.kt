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
    data class Running(val done: Int, val total: Int) : MfrState()
    data class Done(val result: MfrIdentification) : MfrState()
    data class Failed(val message: String) : MfrState()
}

/**
 * A beépített Peugeot-adatbázis (PSA-RE alapján, Apache-2.0) betöltése és a vezérlők azonosítása.
 * Az adatbázis csak az első használatkor töltődik be (kb. 2,5 MB tömörítve).
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
            context.assets.open(ASSET).use { GZIPInputStream(it).bufferedReader(Charsets.UTF_8).readText() }
        }.let { json -> withContext(Dispatchers.Default) { MfrDatabase.fromJson(json) } }.also { db = it }
    }

    suspend fun identify(): MfrIdentification? {
        if (_state.value is MfrState.Running || _state.value is MfrState.Loading) return null
        _state.value = MfrState.Loading
        return try {
            val d = database()
            _state.value = MfrState.Running(0, d.addresses.size)
            val r = connection.identifyManufacturer(d) { done, total -> _state.value = MfrState.Running(done, total) }
            _state.value = if (r.error != null && r.detections.isEmpty()) MfrState.Failed(r.error!!) else MfrState.Done(r)
            autoIdentify = true
            r
        } catch (e: Exception) {
            _state.value = MfrState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
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
