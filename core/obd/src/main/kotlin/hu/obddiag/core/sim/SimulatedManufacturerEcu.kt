package hu.obddiag.core.sim

import hu.obddiag.core.mfr.MfrEcu
import hu.obddiag.core.mfr.MfrParam
import hu.obddiag.core.mfr.MfrProtocol
import hu.obddiag.core.mfr.MfrType
import kotlin.math.roundToLong
import kotlin.math.sin

/** Egy szimulált, fizikailag címzett vezérlő a szimulátorban. */
interface SimEcu {
    val requestId: Int
    val responseId: Int
    /** Válasz egy kérésre (szolgáltatásbájttól), vagy null = nincs válasz. */
    fun answer(req: List<Int>): List<Int>?
}

/**
 * Egy adatbázisbeli vezérlőmodellből GENERÁLT szimulált vezérlő: az azonosító kérésre a megadott cikkszámot
 * adja vissza az adatbázis szerinti helyen, az azonosítóira a paraméterleírásból képzett (tartományon belüli,
 * időben változó) adatot. Semmilyen konkrét jármű vagy vezérlő nincs beégetve – bármelyik modell szimulálható.
 */
class SimulatedManufacturerEcu(
    val ecu: MfrEcu,
    val variant: String,
    val partNumber: String,
    private val clock: () -> Long = System::currentTimeMillis
) : SimEcu {
    override val requestId get() = ecu.requestId
    override val responseId get() = ecu.responseId
    private val uds = ecu.protocol == MfrProtocol.UDS
    @Volatile private var dtcStored = true

    /** Csak olyan cikkszám szimulálható, amely BCD-ként pontosan a megadott hosszon ábrázolható. */
    init {
        require(partNumber.length == ecu.ident.byteLength * 2 && partNumber.all { it in '0'..'9' || it in 'A'..'F' }) {
            "A cikkszám ($partNumber) nem ábrázolható ${ecu.ident.byteLength} bájton BCD-ként"
        }
    }

    private fun t() = (clock() % 600_000L) / 1000.0

    override fun answer(req: List<Int>): List<Int>? {
        if (req.isEmpty()) return null
        val hex = req.joinToString("") { "%02X".format(it) }
        if (hex == ecu.ident.request) return listOf(req[0] + 0x40) + req.drop(1) + identPayload()
        return when (req[0]) {
            0x81 -> listOf(0xC1, 0xD0, 0x8F)
            0x82 -> listOf(0xC2)
            0x10 -> listOf(0x50) + req.drop(1)
            0x3E -> listOf(0x7E) + req.drop(1).take(1)
            0x21, 0x22 -> {
                val wantService = if (uds) 0x22 else 0x21
                if (req[0] != wantService) return listOf(0x7F, req[0], 0x11)
                val id = if (uds) (req.getOrElse(1) { 0 } shl 8) or req.getOrElse(2) { 0 } else req.getOrElse(1) { 0 }
                val item = ecu.items.firstOrNull { it.id == id } ?: return listOf(0x7F, req[0], 0x31)
                val params = item.paramsFor(variant) ?: return listOf(0x7F, req[0], 0x31)
                listOf(req[0] + 0x40) + req.drop(1) + payload(item.length, params)
            }
            0x17 -> listOf(0x57) + (dtcCodes().let { c -> listOf(c.size) + c.flatMap { listOf(it shr 8, it and 0xFF, 0xE0) } })
            0x19 -> if (req.getOrNull(1) == 0x02) listOf(0x59, 0x02, 0xFF) + dtcCodes().flatMap { listOf(it shr 8, it and 0xFF, 0x00, 0x09) }
                    else listOf(0x7F, 0x19, 0x12)
            0x14 -> { dtcStored = false; listOf(0x54) + req.drop(1) }
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    /** Egy tárolt hibakód (az adatbázis első ismert kódja), amíg nem törlik. */
    private fun dtcCodes(): List<Int> = if (dtcStored) ecu.dtcInfo.keys.sorted().take(1) else emptyList()

    private fun identPayload(): List<Int> {
        val off = ecu.ident.byteOffset
        val out = MutableList(maxOf(off + ecu.ident.byteLength, 24)) { 0x00 }
        partNumber.chunked(2).forEachIndexed { i, b -> out[off + i] = b.toInt(16) }
        return out
    }

    private fun payload(length: Int?, params: List<MfrParam>): List<Int> {
        val bits = params.maxOfOrNull { it.start + it.length } ?: 8
        val bytes = IntArray(maxOf(length ?: 0, (bits + 7) / 8))
        params.forEachIndexed { i, p -> encode(bytes, p, i) }
        return bytes.toList()
    }

    private fun encode(bytes: IntArray, p: MfrParam, i: Int) {
        val maxRaw = if (p.length >= 63) Long.MAX_VALUE else (1L shl p.length) - 1
        val phase = 0.5 + 0.2 * sin(t() / 4 + i)
        val raw: Long = when (p.type) {
            MfrType.BOOL -> if (sin(t() / 7 + i) > 0) 1 else 0
            MfrType.ENUM -> p.values.keys.minOrNull() ?: 0
            MfrType.UINT, MfrType.SINT -> {
                val lo = p.min; val hi = p.max
                val target = if (lo != null && hi != null) lo + (hi - lo) * phase else p.offset + p.factor * maxRaw * 0.25 * phase
                var r = if (p.factor == 0.0) 0L else ((target - p.offset) / p.factor).roundToLong()
                if (p.type == MfrType.SINT) { r = r.coerceIn(-(maxRaw / 2) - 1, maxRaw / 2); if (r < 0) r += maxRaw + 1 }
                else r = r.coerceIn(0, maxRaw)
                if (r in p.invalid) r = (r + 1).coerceAtMost(maxRaw)
                r
            }
            else -> return // BCD / STR / BYTES / FLOAT: nulla bájtok (a felület érvénytelenként kezeli)
        }
        if (p.littleEndian && p.start % 8 == 0 && p.length % 8 == 0) {
            for (k in 0 until p.length / 8) bytes.setSafe(p.start / 8 + k, ((raw shr (8 * k)) and 0xFF).toInt())
            return
        }
        for (b in 0 until p.length) {
            val bit = ((raw shr (p.length - 1 - b)) and 1L).toInt()
            val pos = p.start + b
            if (pos / 8 >= bytes.size) return
            val mask = 1 shl (7 - pos % 8)
            bytes[pos / 8] = if (bit == 1) bytes[pos / 8] or mask else bytes[pos / 8] and mask.inv()
        }
    }

    private fun IntArray.setSafe(i: Int, v: Int) { if (i in indices) this[i] = v }

    companion object {
        /**
         * Szimulálható vezérlők kiválasztása egy adatbázisból: csak egyértelműen azonosítható cikkszám, amely
         * BCD-ként ábrázolható. A [pick] (pl. véletlen) dönti el, melyik – így a szimuláció sem egy konkrét
         * vezérlőre épül.
         */
        fun candidates(ecus: List<MfrEcu>): List<Triple<MfrEcu, String, String>> = ecus
            // 11 bites címek, a szabványos OBD-címeken (7DF, 7E0–7EF) kívül – azokat a szimulált szabványos ECU foglalja
            .filter { it.requestId <= 0x7FF && it.responseId <= 0x7FF && it.requestId !in 0x7DF..0x7EF && it.responseId !in 0x7DF..0x7EF }
            .flatMap { e ->
            e.variants.flatMap { (v, parts) -> parts.map { Triple(e, v, it) } }
                .filter { (_, _, p) -> p.length == e.ident.byteLength * 2 && p.all { it in '0'..'9' || it in 'A'..'F' } && p !in e.ambiguousParts }
        }
    }
}
