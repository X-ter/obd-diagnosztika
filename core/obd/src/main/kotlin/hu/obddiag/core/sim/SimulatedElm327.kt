package hu.obddiag.core.sim

import hu.obddiag.core.dtc.DtcParser
import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportException
import hu.obddiag.core.transport.TransportType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SZIMULÁLT ELM327 adapter egy CAN (11 bit, 500 kbaud) protokollú, benzines jármű modelljével.
 *
 * Kizárólag a felület kipróbálására és az automatikus tesztekre szolgál. Az alkalmazás az
 * ebből származó minden adatot „SZIMULÁLT” jelöléssel mutat.
 */
class SimulatedElm327(
    private val responseDelayMs: Long = 25,
    private val clock: () -> Long = System::currentTimeMillis
) : ObdTransport {

    override val type = TransportType.SIMULATION
    override val description = "Szimulált ELM327 (demó jármű)"

    @Volatile private var connected = false
    override val isConnected: Boolean get() = connected

    private var channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> get() = channel

    private val startTime = clock()
    private var echo = true
    private var headers = false
    private var spaces = true
    private var protocolSet = false
    /** Az ATSH-val beállított kérésfejléc; 7DF = funkcionális OBD-kérés. */
    private var txHeader = "7DF"
    private var respHeader = "7E8"

    var storedDtcs = mutableListOf("P0301", "P0420")
    var pendingDtcs = mutableListOf("P0171")
    var permanentDtcs = mutableListOf("P0420")
    var milOn = true

    /** Teszteléshez: ha igaz, a következő parancsra nem érkezik válasz. */
    @Volatile var dropNextResponse = false

    private val supported = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F,
        0x20, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x40, 0x42, 0x43, 0x44, 0x45, 0x46, 0x49, 0x4C, 0x51, 0x5C,
        0x5E, 0x60, 0x61, 0x62, 0x63, 0x67, 0x68, 0x6C, 0x7F, 0x80, 0x8E, 0x9D, 0xA0, 0xA4, 0xA6
    )

    override suspend fun connect() {
        channel = Channel(Channel.UNLIMITED)
        connected = true
    }

    override suspend fun close() {
        connected = false
        channel.close()
    }

    /** Teszteléshez: a kapcsolat váratlan megszakadása. */
    fun simulateConnectionLoss() {
        connected = false
        channel.close()
    }

    private val lineBuffer = StringBuilder()

    override suspend fun write(data: ByteArray) {
        if (!connected) throw TransportException("A szimulált kapcsolat zárva")
        lineBuffer.append(String(data, Charsets.US_ASCII))
        while (true) {
            val idx = lineBuffer.indexOf("\r")
            if (idx < 0) break
            val cmd = lineBuffer.substring(0, idx).trim().uppercase().replace(" ", "")
            lineBuffer.delete(0, idx + 1)
            if (responseDelayMs > 0) delay(responseDelayMs)
            if (dropNextResponse) {
                dropNextResponse = false
                continue
            }
            val body = respond(cmd)
            val out = buildString {
                if (echo) append(cmd).append('\r')
                append(body.joinToString("\r")).append("\r\r>")
            }
            channel.trySend(out.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun t(): Double = (clock() - startTime) / 1000.0

    /** Egyszerű motormodell: alapjárat, gyorsítás, utazósebesség ciklusban. */
    private data class EngineState(val rpm: Double, val speed: Double, val load: Double, val throttle: Double,
                                   val coolant: Double, val map: Double, val maf: Double)

    private fun engine(): EngineState {
        val time = t()
        val phase = (time % 60.0) / 60.0
        val drive = when {
            phase < 0.15 -> 0.0
            phase < 0.45 -> (phase - 0.15) / 0.30
            phase < 0.80 -> 1.0
            else -> 1.0 - (phase - 0.80) / 0.20
        }
        val wobble = sin(time * 2 * PI / 3.7) * 0.05
        val rpm = 780 + drive * 1900 + wobble * 400 + (if (phase in 0.2..0.4) 900 * sin((phase - 0.2) / 0.2 * PI) else 0.0)
        val speed = max(0.0, drive * 92 + wobble * 6)
        val throttle = 14 + drive * 22 + (if (phase in 0.15..0.45) 25.0 else 0.0) + wobble * 20
        val load = 18 + throttle * 0.9
        val coolant = (20 + time * 1.2).coerceAtMost(89.0) + sin(time / 9) * 1.5
        val map = 30 + load * 0.7
        val maf = rpm / 1000.0 * 2.4 * (load / 40.0)
        return EngineState(rpm, speed, load, throttle, coolant, map, maf)
    }

    private fun hex(vararg bytes: Int) = bytes.joinToString(if (spaces) " " else "") { "%02X".format(it and 0xFF) }

    /** Egy ECU-üzenet keretezése (fejléc + PCI + adat), szükség esetén több keretre bontva. */
    private fun frames(payload: List<Int>): List<String> {
        val header = respHeader
        fun line(bytes: List<Int>): String {
            val body = hex(*bytes.toIntArray())
            return if (headers) "$header${if (spaces) " " else ""}$body" else body
        }
        if (payload.size <= 7) {
            return listOf(line(if (headers) listOf(payload.size) + payload else payload))
        }
        if (!headers) {
            // Fejlécek nélkül az ELM a sorszámozott formát használja
            val out = mutableListOf("%03X".format(payload.size))
            val first = payload.take(6)
            out += "0: " + hex(*first.toIntArray())
            payload.drop(6).chunked(7).forEachIndexed { i, c -> out += "${(i + 1) % 16}: " + hex(*c.toIntArray()) }
            return out
        }
        val out = mutableListOf<String>()
        out += line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
        payload.drop(6).chunked(7).forEachIndexed { i, chunk ->
            out += line(listOf(0x20 or ((i + 1) % 16)) + chunk + List(7 - chunk.size) { 0x00 })
        }
        return out
    }

    private fun respond(cmd: String): List<String> {
        if (cmd.isEmpty()) return listOf("?")
        if (cmd.startsWith("AT")) return respondAt(cmd.substring(2))
        if (cmd.startsWith("ST")) return listOf("?")
        if (!cmd.all { it.isDigit() || it in 'A'..'F' }) return listOf("?")
        if (txHeader == PSA_ENGINE_RQ) {
            respHeader = "688"
            val r = psaEngine(cmd) ?: return listOf("NO DATA")
            return frames(r)
        }
        if (txHeader != "7DF" && txHeader != "7E0") return listOf("NO DATA")
        if (cmd.length >= 2 && cmd.substring(0, 2).toInt(16) !in 0x01..0x0A) return listOf("NO DATA")
        respHeader = "7E8"
        val search = if (!protocolSet) listOf("SEARCHING...") else emptyList()
        protocolSet = true
        // A válaszszám-utótag ("010C1") levágása
        val core = if (cmd.length % 2 == 1) cmd.dropLast(1) else cmd
        val bytes = core.chunked(2).map { it.toInt(16) }
        val mode = bytes[0]
        val response: List<Int>? = when (mode) {
            0x01 -> mode01(bytes.drop(1))
            0x02 -> mode02(bytes.drop(1))
            0x03 -> dtcResponse(0x43, storedDtcs)
            0x07 -> dtcResponse(0x47, pendingDtcs)
            0x0A -> dtcResponse(0x4A, permanentDtcs)
            0x04 -> { storedDtcs.clear(); pendingDtcs.clear(); milOn = false; listOf(0x44) }
            0x06 -> mode06(bytes.drop(1))
            0x09 -> mode09(bytes.getOrNull(1) ?: -1)
            else -> listOf(0x7F, mode, 0x11)
        }
        // Valódi ELM327-viselkedés: a válaszszám-utótag a CAN-kereteket számolja, ezért egy több keretes
        // választ az adapter az első N keret után levág.
        val countHint = if (cmd.length % 2 == 1) cmd.last().digitToIntOrNull(16) else null
        val lines = response?.let { frames(it) }?.let { if (countHint != null && headers) it.take(countHint) else it }
        return search + (lines ?: listOf("NO DATA"))
    }

    private fun respondAt(c: String): List<String> = when {
        c == "Z" || c == "WS" -> { echo = true; headers = false; spaces = true; protocolSet = false; txHeader = "7DF"; listOf("", "ELM327 v1.5") }
        c == "E0" -> { echo = false; listOf("OK") }
        c == "E1" -> { echo = true; listOf("OK") }
        c == "H1" -> { headers = true; listOf("OK") }
        c == "H0" -> { headers = false; listOf("OK") }
        c == "S0" -> { spaces = false; listOf("OK") }
        c == "S1" -> { spaces = true; listOf("OK") }
        c == "I" -> listOf("ELM327 v1.5")
        c == "@1" -> listOf("OBD-II SZIMULÁTOR")
        c == "RV" -> listOf("%.1fV".format(java.util.Locale.US, 12.6 + sin(t() / 5) * 0.4 + if (engine().rpm > 600) 1.6 else 0.0))
        c.startsWith("SH") && c.length == 5 -> { txHeader = c.substring(2); listOf("OK") }
        c.startsWith("CRA") -> listOf("OK")
        c.startsWith("FCSH") || c.startsWith("FCSD") || c.startsWith("FCSM") -> listOf("OK")
        c == "DPN" -> listOf("A6")
        c == "DP" -> listOf("AUTO, ISO 15765-4 (CAN 11/500)")
        c.startsWith("SP") -> { protocolSet = c.endsWith("6"); listOf("OK") }
        c.startsWith("L") || c.startsWith("AT") || c.startsWith("ST") || c == "D" -> listOf("OK")
        else -> listOf("?")
    }

    private fun supportMask(base: Int): List<Int> {
        val mask = IntArray(4)
        for (p in supported) {
            if (p in (base + 1)..(base + 0x20)) {
                val idx = p - base - 1
                mask[idx / 8] = mask[idx / 8] or (1 shl (7 - idx % 8))
            }
        }
        return mask.toList()
    }

    private fun pidData(pid: Int): List<Int>? {
        val e = engine()
        fun u16(v: Double): List<Int> { val x = v.roundToInt().coerceIn(0, 65535); return listOf(x shr 8, x and 0xFF) }
        fun pct(v: Double) = (v * 255 / 100).roundToInt().coerceIn(0, 255)
        return when (pid) {
            0x00, 0x20, 0x40, 0x60, 0x80, 0xA0 -> supportMask(pid)
            0x01 -> listOf((if (milOn) 0x80 else 0) or storedDtcs.size, 0x07, 0x65, 0x04)
            0x03 -> listOf(if (e.coolant < 60) 0x01 else 0x02, 0x00)
            0x04 -> listOf(pct(e.load))
            0x05 -> listOf((e.coolant + 40).roundToInt())
            0x06 -> listOf((128 + sin(t() * 1.3) * 6).roundToInt())
            0x07 -> listOf(128 + 9)
            0x0B -> listOf(e.map.roundToInt())
            0x0C -> u16(e.rpm * 4)
            0x0D -> listOf(e.speed.roundToInt())
            0x0E -> listOf(((8 + e.load / 10) + 64).times(2).roundToInt())
            0x0F -> listOf((24 + 40 + sin(t() / 20) * 3).roundToInt())
            0x10 -> u16(e.maf * 100)
            0x11 -> listOf(pct(e.throttle))
            0x13 -> listOf(0x03)
            0x15 -> listOf(((0.45 + sin(t() * 6) * 0.4) * 200).roundToInt(), 0x80 + 3)
            0x1C -> listOf(0x06)
            0x1F -> u16(t())
            0x21 -> u16(if (milOn) 132.0 else 0.0)
            0x2F -> listOf(pct(63 - t() / 600))
            0x30 -> listOf(12)
            0x31 -> u16(840.0)
            0x33 -> listOf(101)
            0x3C -> u16((420 + e.load * 3 + 40) * 10)
            0x42 -> u16((13.9 + sin(t() / 7) * 0.2) * 1000)
            0x43 -> u16(e.load * 255 / 100)
            0x44 -> u16(1.0 * 32768)
            0x45 -> listOf(pct(e.throttle * 0.8))
            0x46 -> listOf(22 + 40)
            0x49 -> listOf(pct(e.throttle))
            0x4C -> listOf(pct(e.throttle))
            0x51 -> listOf(0x01)
            0x5C -> listOf((e.coolant * 0.95 + 40).roundToInt())
            0x5E -> u16(fuelLph(e) * 20)
            0x61 -> listOf((e.load * 0.95 + 125).roundToInt())
            0x62 -> listOf((e.load * 0.9 + 125).roundToInt())
            0x63 -> u16(320.0)
            0x67 -> listOf(0x01, (e.coolant + 40).roundToInt(), 0x00)
            0x68 -> listOf(0x01, 24 + 40, 0, 0, 0, 0, 0)
            0x6C -> listOf(0x03, pct(e.throttle), pct(e.throttle - 0.5))
                .let { it + listOf(0, 0) }
            0x7F -> listOf(0x03) + u32(4_512_345L + t().toLong()) + u32(1_204_000L) + u32(0)
            0x8E -> listOf(125 + 8)
            0x9D -> u16(fuelLph(e) * 745.0 / 3600.0 / 0.02) + u16(fuelLph(e) * 745.0 / 3600.0 / 0.02)
            0xA4 -> {
                val gear = gearFor(e.speed)
                val ratio = listOf(0.0, 3.6, 2.1, 1.4, 1.0, 0.8, 0.65)[gear]
                listOf(0x03, gear shl 4) + u16(ratio * 1000)
            }
            0xA6 -> u32(1_534_870L + (t() * e.speed / 360.0).toLong())
            else -> null
        }
    }

    private fun u32(v: Long): List<Int> = listOf((v shr 24).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF)
    private fun fuelLph(e: EngineState) = e.maf * 3600.0 / 14.7 / 745.0
    private fun gearFor(speed: Double) = when { speed < 2 -> 0; speed < 18 -> 1; speed < 35 -> 2; speed < 55 -> 3; speed < 75 -> 4; speed < 90 -> 5; else -> 6 }

    private fun mode01(pids: List<Int>): List<Int>? {
        if (pids.isEmpty()) return null
        val out = mutableListOf(0x41)
        for (p in pids.take(6)) {
            if (p !in supported && p != 0x00) continue
            val d = pidData(p) ?: continue
            out += p
            out += d
        }
        return if (out.size == 1) null else out
    }

    private fun mode02(args: List<Int>): List<Int>? {
        val pid = args.firstOrNull() ?: return null
        if (storedDtcs.isEmpty()) return null
        return when (pid) {
            0x00 -> listOf(0x42, 0x00, 0x00, 0x7E, 0x3F, 0x80, 0x00) // 02–07, 0B–11
            0x02 -> listOf(0x42, 0x02, 0x00) + DtcParser.encode(storedDtcs.first()).toList()
            0x03 -> listOf(0x42, 0x03, 0x00, 0x02, 0x00)
            0x04 -> listOf(0x42, 0x04, 0x00, 0x6E)
            0x05 -> listOf(0x42, 0x05, 0x00, 0x7B)
            0x06 -> listOf(0x42, 0x06, 0x00, 0x86)
            0x07 -> listOf(0x42, 0x07, 0x00, 0x8C)
            0x0B -> listOf(0x42, 0x0B, 0x00, 0x3A)
            0x0C -> listOf(0x42, 0x0C, 0x00, 0x0C, 0x80)
            0x0D -> listOf(0x42, 0x0D, 0x00, 0x00)
            0x0E -> listOf(0x42, 0x0E, 0x00, 0x8E)
            0x0F -> listOf(0x42, 0x0F, 0x00, 0x3E)
            0x10 -> listOf(0x42, 0x10, 0x00, 0x01, 0x2C)
            0x11 -> listOf(0x42, 0x11, 0x00, 0x26)
            else -> null
        }
    }

    private fun dtcResponse(service: Int, codes: List<String>): List<Int> {
        val out = mutableListOf(service, codes.size)
        codes.forEach { val (a, b) = DtcParser.encode(it); out += a; out += b }
        return out
    }

    private fun mode06(args: List<Int>): List<Int>? = when (args.firstOrNull()) {
        0x00 -> listOf(0x46, 0x00, 0xC0, 0x00, 0x00, 0x01) // MID 01, 02, 20
        0x20 -> listOf(0x46, 0x20, 0x80, 0x00, 0x00, 0x00) // MID 21
        0x01 -> listOf(0x46, 0x01, 0x01, 0x0A, 0x02, 0xE4, 0x02, 0x58, 0xFF, 0xFF)
        0x02 -> listOf(0x46, 0x02, 0x05, 0x10, 0x00, 0x4B, 0x00, 0x00, 0x00, 0xC8)
        0x21 -> listOf(0x46, 0x21, 0x80, 0x01, 0x00, 0x8C, 0x00, 0x00, 0x00, 0x64)
        else -> null
    }

    private fun mode09(info: Int): List<Int>? {
        fun ascii(s: String) = s.map { it.code }
        return when (info) {
            0x00 -> listOf(0x49, 0x00, 0x54, 0x40, 0x00, 0x00) // 02, 04, 06, 0A
            0x02 -> listOf(0x49, 0x02, 0x01) + ascii(SIM_VIN)
            0x04 -> listOf(0x49, 0x04, 0x01) + ascii("SIMCAL0000000001")
            0x06 -> listOf(0x49, 0x06, 0x01, 0x1A, 0x2B, 0x3C, 0x4D)
            0x0A -> listOf(0x49, 0x0A, 0x01) + ascii("ECM-EngineControl").let { it + List(20 - it.size) { 0 } }
            else -> null
        }
    }

    /**
     * SZIMULÁLT PSA motorvezérlő a PSA-RE leírása szerint (CMM_MG1CS042, 1.2 PureTech, „B78_V1” változat, cikkszám 932130).
     * Csak néhány élő DID-re válaszol; minden másra „requestOutOfRange” (7F 22 31), mint egy valódi vezérlő.
     */
    private fun psaEngine(cmd: String): List<Int>? {
        val b = cmd.chunked(2).map { it.toInt(16) }
        val e = engine()
        fun u16(v: Double): List<Int> { val x = v.roundToInt().coerceIn(0, 65535); return listOf(x shr 8, x and 0xFF) }
        fun s16(v: Double): List<Int> { val x = v.roundToInt().coerceIn(-32768, 32767) and 0xFFFF; return listOf(x shr 8, x and 0xFF) }
        if (b.isEmpty()) return null
        return when (b[0]) {
            0x22 -> {
                if (b.size != 3) return listOf(0x7F, 0x22, 0x13)
                val did = (b[1] shl 8) or b[2]
                val data: List<Int>? = when (did) {
                    0xF0FE -> List(21) { 0x00 } + listOf(0x93, 0x21, 0x30) + List(4) { 0x00 }
                    0xD400 -> u16(e.rpm)
                    0xD40A -> s16(e.coolant)
                    0xD429 -> s16((1.2 + e.rpm / 1500.0) * 1000)            // olajnyomás, 0,001 bar/bit
                    0xD464 -> u16((1.0 + e.rpm / 1600.0) * 1000)            // előírt olajnyomás
                    0xD47D -> u16((e.coolant * 1.05 + 273.15) / 0.0234375) // olajhőmérséklet
                    0xD47E -> u16((1013.0 + e.load * 4) / 0.078125)        // mért töltőnyomás, mbar
                    0xD651 -> listOf(if (e.coolant > 60) 0x01 else 0x00)    // hűtővízszivattyú állapota (enum)
                    else -> null
                }
                if (data == null) listOf(0x7F, 0x22, 0x31) else listOf(0x62, b[1], b[2]) + data
            }
            0x3E -> listOf(0x7E, 0x00)
            // gyártói hibakódok: egy szimulált tárolt kód (P0562 – alacsony tápfeszültség), amíg nem törlik
            0x19 -> if (b.getOrNull(1) == 0x02) listOf(0x59, 0x02, 0xFF) + (if (psaDtcStored) listOf(0x05, 0x62, 0x16, 0x08) else emptyList())
                    else listOf(0x7F, 0x19, 0x12)
            0x14 -> { psaDtcStored = false; listOf(0x54) }
            else -> listOf(0x7F, b[0], 0x11)
        }
    }

    @Volatile private var psaDtcStored = true

    companion object {
        const val PSA_ENGINE_RQ = "6A8"
        /** Szándékosan felismerhetően fiktív alvázszám. */
        const val SIM_VIN = "SMLT0BD2026000001"
    }
}
