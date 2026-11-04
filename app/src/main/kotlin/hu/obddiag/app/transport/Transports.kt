package hu.obddiag.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportException
import hu.obddiag.core.transport.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/** A kiválasztott adapter leírása (a beállításokban is így tároljuk). */
sealed class AdapterTarget {
    abstract val label: String

    data class Classic(val address: String, val name: String?) : AdapterTarget() {
        override val label get() = name ?: address
    }
    data class Ble(val address: String, val name: String?) : AdapterTarget() {
        override val label get() = name ?: address
    }
    data class Wifi(val host: String, val port: Int) : AdapterTarget() {
        override val label get() = "$host:$port"
    }
    data object Simulation : AdapterTarget() {
        override val label get() = "Szimuláció"
    }
}

/**
 * Közös alap a stream-alapú (Bluetooth RFCOMM, TCP) kapcsolatokhoz: külön olvasószál tölti a csatornát.
 */
abstract class StreamTransport : ObdTransport {
    @Volatile protected var open = false
    override val isConnected: Boolean get() = open
    private var channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> get() = channel
    protected var output: OutputStream? = null

    protected fun startReader(stream: InputStream, name: String) {
        channel = Channel(Channel.UNLIMITED)
        open = true
        val ch = channel
        thread(name = name, isDaemon = true) {
            val buf = ByteArray(1024)
            try {
                while (open) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) ch.trySend(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                open = false
                ch.close()
            }
        }
    }

    /** A kapcsolat azonnali lezárása (az elakadt, blokkoló írás feloldásához). */
    protected abstract fun forceClose()

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportException("Nincs kapcsolat")
        // Írás-őr: ha a Bluetooth/Wi-Fi kapcsolat elakad, a blokkoló írás akár 30 s-ig is tarthat.
        // Ilyenkor lezárjuk a kapcsolatot, így az automatikus újracsatlakozás azonnal elindulhat.
        val watchdog = java.util.Timer("write-watchdog", true)
        var timedOut = false
        watchdog.schedule(object : java.util.TimerTask() {
            override fun run() { timedOut = true; open = false; try { forceClose() } catch (_: Exception) { } }
        }, WRITE_TIMEOUT_MS)
        try {
            out.write(data)
            out.flush()
        } catch (e: Exception) {
            open = false
            throw TransportException(if (timedOut) "Az adapter nem fogadja az adatot (írási időtúllépés)" else "Írási hiba: ${e.message}", e)
        } finally {
            watchdog.cancel()
        }
        if (timedOut) throw TransportException("Az adapter nem fogadja az adatot (írási időtúllépés)")
    }

    companion object {
        const val WRITE_TIMEOUT_MS = 4_000L
    }
}

/** Bluetooth Classic (SPP / RFCOMM) – a legtöbb ELM327 klón és az OBDLink adapterek. */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(private val context: Context, private val address: String, private val name: String?) : StreamTransport() {
    override val type = TransportType.BLUETOOTH_CLASSIC
    override val description: String get() = "Bluetooth: ${name ?: address}"
    private var socket: BluetoothSocket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("A készülék nem támogatja a Bluetooth-t")
        if (!adapter.isEnabled) throw TransportException("A Bluetooth ki van kapcsolva")
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportException("Érvénytelen Bluetooth-cím: $address", e)
        }
        try { adapter.cancelDiscovery() } catch (_: Exception) { }

        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // Tartalék: egyes klónok SDP-rekord nélkül, közvetlenül az 1-es csatornán érhetők el.
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket }
        )
        var last: Exception? = null
        for (create in attempts) {
            val s = try { create() } catch (e: Exception) { last = e; continue }
            try {
                s.connect()
                socket = s
                output = s.outputStream
                startReader(s.inputStream, "bt-reader")
                return@withContext
            } catch (e: Exception) {
                last = e
                try { s.close() } catch (_: Exception) { }
            }
        }
        throw TransportException(
            "Nem sikerült Bluetooth-kapcsolatot létesíteni. Ellenőrizd, hogy az adapter be van-e dugva és párosítva van-e.",
            last
        )
    }

    override fun forceClose() { socket?.close() }

    override suspend fun close() = withContext(Dispatchers.IO) {
        open = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Wi-Fi adapter (TCP). A kapcsolatot kifejezetten a Wi-Fi hálózaton nyitjuk meg: az OBD adapterek
 * hálózatán nincs internet, ezért az Android egyébként a mobilhálózatot használná.
 */
class WifiTransport(private val context: Context, private val host: String, private val port: Int) : StreamTransport() {
    override val type = TransportType.WIFI
    override val description: String get() = "Wi-Fi: $host:$port"
    private var socket: Socket? = null

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val network = wifiNetwork()
        val s = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (e: Exception) {
            Socket()
        }
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            val hint = if (network == null) " A telefon nem csatlakozik Wi-Fi hálózathoz." else ""
            throw TransportException("Nem érhető el a(z) $host:$port cím.$hint (${e.message})", e)
        }
        socket = s
        output = s.getOutputStream()
        startReader(s.getInputStream(), "wifi-reader")
    }

    override fun forceClose() { socket?.close() }

    override suspend fun close() = withContext(Dispatchers.IO) {
        open = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        output = null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
