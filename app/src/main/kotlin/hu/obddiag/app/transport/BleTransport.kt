package hu.obddiag.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportException
import hu.obddiag.core.transport.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Bluetooth Low Energy OBD adapter (pl. Vgate iCar Pro BLE, OBDLink CX, Veepeak BLE).
 *
 * Az adapterek egy „soros port” jellegű GATT-szolgáltatást kínálnak: egy értesítő (notify) jellemzőn
 * érkezik a válasz, és egy írható jellemzőre küldjük a parancsot. Ismert UUID-párokat próbálunk, utána
 * általános keresés jön.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val address: String, private val name: String?) : ObdTransport {
    override val type = TransportType.BLUETOOTH_LE
    override val description: String get() = "BLE: ${name ?: address}"

    @Volatile private var open = false
    override val isConnected: Boolean get() = open

    private var channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> get() = channel

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val writeMutex = Mutex()

    private var ready = CompletableDeferred<Unit>()
    private var writeAck: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                open = false
                channel.close()
                if (!ready.isCompleted) ready.completeExceptionally(TransportException("A BLE-kapcsolat megszakadt (állapot: $status)"))
                g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(TransportException("A BLE-szolgáltatások felderítése nem sikerült ($status)"))
                return
            }
            val pair = selectCharacteristics(g)
            if (pair == null) {
                ready.completeExceptionally(TransportException("Az eszköz nem kínál ismert OBD soros szolgáltatást"))
                return
            }
            notifyChar = pair.first
            writeChar = pair.second
            g.setCharacteristicNotification(pair.first, true)
            val cccd = pair.first.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = if ((pair.first.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            writeDescriptorCompat(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD && !ready.isCompleted) {
                if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
                else ready.completeExceptionally(TransportException("Az értesítések bekapcsolása nem sikerült ($status)"))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            channel.trySend(value.copyOf())
        }

        @Deprecated("Android 12 és régebbi rendszereken ez a változat hívódik")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { channel.trySend(it.copyOf()) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private fun selectCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((service, notify, write) in KNOWN) {
            val s = g.getService(service) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            return n to w
        }
        // Általános keresés: ugyanabban a (nem szabványos) szolgáltatásban egy értesítő és egy írható jellemző
        for (s in g.services) {
            if (s.uuid.toString().lowercase().let { it.startsWith("00001800") || it.startsWith("00001801") || it.startsWith("0000180a") }) continue
            val n = s.characteristics.firstOrNull { (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 }
            val w = s.characteristics.firstOrNull { (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }
            if (n != null && w != null) return n to w
        }
        return null
    }

    override suspend fun connect() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("A készülék nem támogatja a Bluetooth-t")
        if (!adapter.isEnabled) throw TransportException("A Bluetooth ki van kapcsolva")
        val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            throw TransportException("Érvénytelen BLE-cím: $address", e)
        }
        channel = Channel(Channel.UNLIMITED)
        ready = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
            open = true
        } catch (e: TimeoutCancellationException) {
            close()
            throw TransportException("A BLE-adapter nem válaszolt időben", e)
        } catch (e: TransportException) {
            close()
            throw e
        }
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw TransportException("Nincs BLE-kapcsolat")
        val c = writeChar ?: throw TransportException("Nincs írható jellemző")
        val noResponse = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        writeMutex.withLock {
            for (chunk in data.toList().chunked(chunkSize)) {
                val bytes = chunk.toByteArray()
                val ack = CompletableDeferred<Boolean>().also { writeAck = it }
                val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val started = writeCompat(g, c, bytes, type)
                if (!started) throw TransportException("BLE-írás nem indult el")
                if (noResponse) delay(5) else {
                    val ok = try { withTimeout(2_000) { ack.await() } } catch (e: TimeoutCancellationException) { false }
                    if (!ok) throw TransportException("BLE-írás sikertelen")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = bytes
            g.writeCharacteristic(c)
        }

    override suspend fun close() {
        open = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        channel.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun u(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        /** Ismert szolgáltatás / értesítő / írható jellemző hármasok. */
        val KNOWN: List<Triple<UUID, UUID, UUID>> = listOf(
            Triple(u("fff0"), u("fff1"), u("fff2")),
            Triple(u("ffe0"), u("ffe1"), u("ffe1")),
            Triple(u("18f0"), u("2af0"), u("2af1")),
        )

        /** A BLE-keresésnél ezek alapján jelöljük meg a valószínű OBD-adaptereket. */
        val LIKELY_SERVICE_UUIDS: Set<UUID> = setOf(u("fff0"), u("ffe0"), u("18f0"))
    }
}
