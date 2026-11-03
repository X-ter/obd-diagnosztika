package hu.obddiag.core.transport

import kotlinx.coroutines.channels.ReceiveChannel

/** Az adapterhez vezető fizikai kapcsolat típusa. */
enum class TransportType { BLUETOOTH_CLASSIC, BLUETOOTH_LE, WIFI, SIMULATION }

/**
 * Bájtfolyam-alapú kapcsolat egy ELM327-kompatibilis adapterhez.
 *
 * A megvalósítás saját olvasószálat futtat, és a beérkező bájtokat az [input] csatornára teszi.
 * Így az olvasás időtúllépéssel megszakítható, akkor is, ha az alatta lévő socket blokkoló.
 */
interface ObdTransport {
    val type: TransportType

    /** Rövid, emberi olvasásra szánt azonosító (eszköznév, IP-cím…). */
    val description: String

    val isConnected: Boolean

    /** A beérkező adatok csatornája. Minden [connect] után új csatorna jöhet létre. */
    val input: ReceiveChannel<ByteArray>

    /** Felépíti a kapcsolatot; hiba esetén [TransportException]-t dob. */
    suspend fun connect()

    suspend fun write(data: ByteArray)

    suspend fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
