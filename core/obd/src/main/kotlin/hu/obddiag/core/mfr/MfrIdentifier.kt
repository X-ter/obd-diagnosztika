package hu.obddiag.core.mfr

import kotlinx.coroutines.CancellationException

/** Egy diagnosztikai címen talált vezérlő azonosításának eredménye. */
sealed class MfrDetection {
    abstract val requestId: Int
    abstract val responseId: Int
    abstract val nodes: List<String>

    /** Pontos egyezés: a cikkszám egyetlen vezérlőmodell egyetlen szoftverváltozatához tartozik. */
    data class Identified(override val requestId: Int, override val responseId: Int, override val nodes: List<String>,
                          val ecu: MfrEcu, val variant: String, val partNumber: String) : MfrDetection()

    /** A vezérlő válaszolt, de a cikkszám több modellhez is illik – nem választunk automatikusan. */
    data class Ambiguous(override val requestId: Int, override val responseId: Int, override val nodes: List<String>,
                         val partNumber: String, val candidates: List<String>) : MfrDetection()

    /** A vezérlő válaszolt, de a cikkszáma nincs az adatbázisban – értéket nem mutatunk (nem tippelünk). */
    data class Unknown(override val requestId: Int, override val responseId: Int, override val nodes: List<String>,
                       val partNumbers: List<String>) : MfrDetection()
}

data class MfrIdentification(
    val detections: List<MfrDetection>,
    val probedAddresses: Int,
    val flowControlSupported: Boolean,
    val error: String? = null,
    /** Részletes napló: címenként a küldött azonosító kérések és a válaszok (hibakereséshez, megosztható). */
    val trace: List<String> = emptyList()
) {
    val identified: List<MfrDetection.Identified> get() = detections.filterIsInstance<MfrDetection.Identified>()
}

/**
 * A jármű vezérlőinek felderítése: minden ismert címen elküldi az adatbázis szerinti (csak olvasó) azonosító kérést,
 * kiolvassa a cikkszámot, és pontos egyezés esetén kiválasztja a vezérlőmodellt és a szoftverváltozatot.
 */
class MfrIdentifier(private val db: MfrDatabase, private val session: MfrSession) {

    private val trace = mutableListOf<String>()

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    suspend fun identify(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): MfrIdentification {
        if (!session.protocolSupported) {
            return MfrIdentification(emptyList(), 0, true, "A gyártói diagnosztika 11 bites CAN protokollt igényel (a jármű protokollja ettől eltér).")
        }
        val detections = mutableListOf<MfrDetection>()
        trace.clear()
        val addresses = db.addresses.entries.groupBy({ it.key.first to it.key.second }, { it.value }).entries.toList()
        var done = 0
        for ((addr, ecuLists) in addresses) {
            val ecus = ecuLists.flatten()
            try {
                detect(addr.first, addr.second, ecus)?.let { detections += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MfrSafetyException) {
                throw e
            } catch (_: Exception) {
            }
            onProgress(++done, addresses.size)
        }
        return MfrIdentification(detections, addresses.size, session.flowControlSupported, trace = trace.toList())
    }

    private suspend fun detect(rq: Int, rs: Int, ecus: List<MfrEcu>): MfrDetection? = session.exclusive {
        val nodes = ecus.map { it.node }.distinct()
        val parts = mutableListOf<String>()
        val matches = mutableListOf<Triple<MfrEcu, String, String>>()
        var anyAnswer = false
        // Protokollonként (UDS / KWP) és azonosító kérésenként egyszer kérdezünk.
        for ((proto, byProto) in ecus.groupBy { it.protocol }) {
            var first = true
            for ((req, group) in byProto.groupBy { it.ident.request }) {
                // protokollváltáskor (UDS → KWP ugyanazon a címen) mindig új KWP-session kell
                select(group.first(), forceSession = first)
                first = false
                val r = read(group.first(), req)
                trace += "%03X→%03X %s %s: %s".format(rq, rs, if (proto == MfrProtocol.UDS) "UDS" else "KWP", req, when (r) {
                    is MfrReadResult.Ok -> "OK " + hex(r.payload)
                    is MfrReadResult.Negative -> "NRC 0x%02X".format(r.nrc)
                    MfrReadResult.NoResponse -> "nincs válasz"
                    is MfrReadResult.Failed -> "hiba: " + r.reason
                })
                if (r is MfrReadResult.NoResponse && !anyAnswer) continue
                if (r !is MfrReadResult.Ok) { if (r is MfrReadResult.Negative) anyAnswer = true; continue }
                anyAnswer = true
                for (ecu in group) {
                    val off = ecu.ident.byteOffset
                    if (off + ecu.ident.byteLength > r.payload.size) continue
                    val part = MfrDecoder.bcd(r.payload.copyOfRange(off, off + ecu.ident.byteLength))
                    parts += part
                    ecu.variantForPart(part)?.let { matches += Triple(ecu, it, part) }
                    if (part in ecu.ambiguousParts) matches += Triple(ecu, "", part)
                }
            }
            if (proto == MfrProtocol.UDS && anyAnswer && matches.isNotEmpty()) break
        }
        if (anyAnswer) trace += "%03X: kiolvasott cikkszámok: %s; egyezés: %s".format(rq, parts.distinct().joinToString().ifEmpty { "–" },
            matches.joinToString { "${it.first.key} (${it.second.ifEmpty { "nem egyértelmű" }})" }.ifEmpty { "nincs" })
        when {
            !anyAnswer -> null
            matches.size == 1 && matches[0].second.isNotEmpty() ->
                MfrDetection.Identified(rq, rs, nodes, matches[0].first, matches[0].second, matches[0].third)
            matches.isNotEmpty() ->
                MfrDetection.Ambiguous(rq, rs, nodes, matches.first().third, matches.map { "${it.first.node}/${it.first.model}" }.distinct())
            else -> MfrDetection.Unknown(rq, rs, nodes, parts.distinct())
        }
    }
}
