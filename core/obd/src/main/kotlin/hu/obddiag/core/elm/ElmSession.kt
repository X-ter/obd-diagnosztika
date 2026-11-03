package hu.obddiag.core.elm

import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportException
import hu.obddiag.core.util.CommLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parancs–válasz munkamenet egy ELM327-kompatibilis adapterrel.
 * Egyszerre mindig csak egy parancs fut ([Mutex]); a válasz végét a '>' prompt jelzi.
 */
class ElmSession(
    private val transport: ObdTransport,
    private val log: CommLog? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val pending = StringBuilder()
    @Volatile private var interrupted = false

    /** Hány egymást követő időtúllépés történt. A kapcsolatkezelő ez alapján dönt a kapcsolat elvesztéséről. */
    @Volatile var consecutiveTimeouts: Int = 0
        private set

    @Volatile var lastResponseTimeMs: Long = 0
        private set

    var protocol: ObdProtocol? = null
    var headersEnabled: Boolean = true

    /** Nyers parancs elküldése; a teljes, prompt előtti szöveges választ adja vissza. */
    suspend fun sendRaw(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String = mutex.withLock {
        sendLocked(command, timeoutMs)
    }

    /** AT (vagy ST) parancs, szöveges válasszal. */
    suspend fun at(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResult {
        val raw = try {
            sendRaw(command, timeoutMs)
        } catch (e: TimeoutCancellationException) {
            return ElmResult.Error(ElmErrorKind.TIMEOUT, "")
        }
        return ElmResponseParser.parseText(raw, command)
    }

    /** OBD kérés (pl. "010C"), értelmezett válasszal. */
    suspend fun obd(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResult {
        val raw = try {
            sendRaw(command, timeoutMs)
        } catch (e: TimeoutCancellationException) {
            return ElmResult.Error(ElmErrorKind.TIMEOUT, "")
        }
        val result = ElmResponseParser.parseObd(raw, command, protocol, headersEnabled)
        if (result is ElmResult.Error) log?.add(CommLog.Direction.ERROR, "${command}: ${result.kind.userMessage}")
        return result
    }

    private suspend fun sendLocked(command: String, timeoutMs: Long): String {
        if (!transport.isConnected) throw TransportException("Nincs kapcsolat az adapterrel")
        if (interrupted) {
            // Egy megszakított (pl. leállított lekérdező) parancs válasza még úton lehet: megvárjuk és eldobjuk.
            interrupted = false
            withTimeoutOrNull(RECOVERY_TIMEOUT_MS) { readUntilPrompt() }
            pending.clear()
        }
        drainStale()
        log?.add(CommLog.Direction.TX, command)
        val started = clock()
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        try {
            val response = withTimeout(timeoutMs) { readUntilPrompt() }
            consecutiveTimeouts = 0
            lastResponseTimeMs = clock() - started
            log?.add(CommLog.Direction.RX, response.trim().replace("\r", " | "))
            return response
        } catch (e: TimeoutCancellationException) {
            consecutiveTimeouts++
            log?.add(CommLog.Direction.ERROR, "$command: időtúllépés (${timeoutMs} ms)")
            // A félbemaradt választ el kell dobni, különben a következő parancs kapná meg.
            recoverAfterTimeout()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            interrupted = true
            throw e
        }
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val idx = pending.indexOf(">")
            if (idx >= 0) {
                val response = pending.substring(0, idx)
                pending.delete(0, idx + 1)
                return response
            }
            val chunk = try {
                transport.input.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw TransportException("A kapcsolat megszakadt", e)
            }
            pending.append(String(chunk, Charsets.ISO_8859_1))
            if (pending.length > MAX_BUFFER) pending.delete(0, pending.length - MAX_BUFFER)
        }
    }

    /** Az előző parancsból itt maradt, késve érkező bájtok eldobása. */
    private fun drainStale() {
        while (true) {
            val chunk = transport.input.tryReceive().getOrNull() ?: break
            pending.append(String(chunk, Charsets.ISO_8859_1))
        }
        val idx = pending.lastIndexOf(">")
        if (idx >= 0) pending.delete(0, idx + 1)
    }

    private suspend fun recoverAfterTimeout() {
        pending.clear()
        try {
            // Egy üres kocsivissza megszakítja a folyamatban lévő ELM-műveletet ("STOPPED").
            transport.write("\r".toByteArray(Charsets.US_ASCII))
            withTimeoutOrNull(RECOVERY_TIMEOUT_MS) { readUntilPrompt() }
        } catch (_: Exception) {
        }
        pending.clear()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val RESET_TIMEOUT_MS = 5_000L
        const val SEARCH_TIMEOUT_MS = 15_000L
        private const val RECOVERY_TIMEOUT_MS = 800L
        private const val MAX_BUFFER = 64 * 1024
    }
}
