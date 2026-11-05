package hu.obddiag.core

import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.mfr.MfrDatabase
import hu.obddiag.core.mfr.MfrEcu
import hu.obddiag.core.sim.SimEcu
import hu.obddiag.core.sim.SimulatedElm327
import hu.obddiag.core.sim.SimulatedManufacturerEcu
import hu.obddiag.core.transport.ObdTransport
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Közös tesztsegédek. A tesztek SEMMILYEN konkrét járművet, motort vagy vezérlőt nem égetnek be: a szimulált
 * vezérlőket az adatbázisból, véletlenszám-generátorral (rögzített maggal, hogy hiba esetén reprodukálható
 * legyen) választják, így a felismerési logika bármely modellre ugyanúgy kell működjön.
 */
object TestSupport {
    val db: MfrDatabase by lazy {
        val f = listOf("app/src/main/assets/mfr/psa.dat", "../../app/src/main/assets/mfr/psa.dat",
            "/home/claude/repo/app/src/main/assets/mfr/psa.dat").map(::File).first { it.exists() }
        MfrDatabase.fromLines { n -> MfrDatabase.lineOf({ GZIPInputStream(f.inputStream()).reader() }, n) }
    }

    /** Minden szimulálható (egyértelmű, BCD-ként ábrázolható cikkszámú) vezérlő-változat. */
    val candidates by lazy { SimulatedManufacturerEcu.candidates(db.ecus) }

    /** Van-e a változatnak legalább [n] numerikus élő paramétere. */
    fun hasNumericLive(e: MfrEcu, v: String, n: Int = 1) =
        e.items.filter { it.kind == "live" }.sumOf { i -> i.paramsFor(v)?.count { it.isNumeric } ?: 0 } >= n

    fun pick(rnd: Random, variantFilter: (MfrEcu, String) -> Boolean = { _, _ -> true }, filter: (MfrEcu) -> Boolean = { true }): SimulatedManufacturerEcu {
        val list = candidates.filter { filter(it.first) && variantFilter(it.first, it.second) }
        check(list.isNotEmpty()) { "nincs a feltételnek megfelelő vezérlő az adatbázisban" }
        val (e, v, p) = list[rnd.nextInt(list.size)]
        return SimulatedManufacturerEcu(e, v, p)
    }

    /** Különböző címeken lévő vezérlők (egy címen egy szimulált vezérlő lehet). */
    fun pickDistinct(rnd: Random, n: Int, filter: (MfrEcu) -> Boolean = { true }): List<SimulatedManufacturerEcu> {
        val out = mutableListOf<SimulatedManufacturerEcu>()
        var guard = 0
        while (out.size < n && guard++ < 1000) {
            val s = pick(rnd, filter = filter)
            if (out.none { it.requestId == s.requestId || it.responseId == s.responseId }) out += s
        }
        return out
    }

    /** Egy olyan, formailag érvényes cikkszám, amely egyetlen vezérlőmodellnél sem szerepel. */
    fun unknownPart(ecu: MfrEcu, rnd: Random): String {
        val all = db.ecus.flatMap { it.variants.values.flatten() }.toSet()
        while (true) {
            val p = (1..ecu.ident.byteLength * 2).map { "0123456789"[rnd.nextInt(10)] }.joinToString("")
            if (p !in all) return p
        }
    }

    /** Átadja a parancsokat a szimulátornak, és rögzíti őket (a biztonsági ellenőrzésekhez). */
    class Recording(private val inner: ObdTransport) : ObdTransport by inner {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun write(data: ByteArray) {
            sent += String(data).trim()
            inner.write(data)
        }
    }

    suspend fun simClient(ecus: List<SimEcu> = emptyList(), vin: String? = SimulatedElm327.SIM_VIN, delayMs: Long = 0): Pair<ObdClient, Recording> {
        val t = Recording(SimulatedElm327(responseDelayMs = delayMs, manufacturerEcus = { ecus }, vin = vin))
        t.connect()
        val c = ObdClient(ElmSession(t)).apply { initializeAdapter(); connectVehicle(); discoverSupported(0x01); probeOptimizations() }
        return c to t
    }

    /** Csak olvasó diagnosztikai kérések (a felismerés és a lekérdezés során ennél több nem mehet ki). */
    val READ_ONLY = listOf(
        Regex("^0[1235679A][0-9A-F]*$"), Regex("^22[0-9A-F]{4}$"), Regex("^21[0-9A-F]{2}$"), Regex("^1A[0-9A-F]{2}$"),
        Regex("^3E(00|01|80)?$"), Regex("^8[12]$"), Regex("^10(C0|81|01)$"), Regex("^17FF00$"), Regex("^1902(FF|09|08)$")
    )

    fun nonReadOnly(sent: List<String>): List<String> =
        sent.filter { !it.startsWith("AT") && !it.startsWith("ST") && it.isNotBlank() }
            .map { it.replace(" ", "").uppercase() }
            .filter { cmd -> READ_ONLY.none { it.matches(cmd) } && !(cmd.length % 2 == 1 && READ_ONLY.any { r -> r.matches(cmd.dropLast(1)) }) }
}
