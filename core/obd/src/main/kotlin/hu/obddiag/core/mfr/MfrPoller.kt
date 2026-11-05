package hu.obddiag.core.mfr

import hu.obddiag.core.pid.Sample
import hu.obddiag.core.poll.PidHealth
import kotlinx.coroutines.CancellationException

/** Egy gyártói lekérdezési kör eredménye. */
class MfrCycleResult(val samples: List<Sample>, val states: Map<String, String>, val health: Map<String, PidHealth>)

/**
 * Gyártói (PSA) értékek periodikus olvasása. Az élő értékeket minden körben, a konfigurációs és azonosító
 * jellegűeket ritkán olvassa; a vezérlő által nem ismert azonosítókat ritkán próbálja újra.
 *
 * Szöveges értékek kódolása az állapottérképben: "txt:<szöveg>", "bool:1" / "bool:0".
 */
class MfrPoller(
    val registry: MfrRegistry,
    private val session: MfrSession,
    private val simulated: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lastRead = HashMap<String, Long>()
    private val notSupported = HashSet<String>()
    private val failures = HashMap<String, Int>()

    private fun key(ecu: MfrEcu, item: MfrItem) = "%03X:%04X".format(ecu.requestId, item.id)

    private fun intervalMs(item: MfrItem, k: String): Long = when {
        k in notSupported -> RETRY_UNSUPPORTED_MS
        item.kind == "live" -> MIN_LIVE_MS
        else -> SLOW_KIND_MS
    }

    suspend fun cycle(signalIds: Set<String>): MfrCycleResult {
        val plan = registry.plan(signalIds)
        if (plan.isEmpty()) return MfrCycleResult(emptyList(), emptyMap(), emptyMap())
        val now = clock()
        val due = plan.mapValues { (ecu, items) -> items.filter { (item, _) -> now - (lastRead[key(ecu, item)] ?: 0L) >= intervalMs(item, key(ecu, item)) } }
            .filterValues { it.isNotEmpty() }
        if (due.isEmpty()) return MfrCycleResult(emptyList(), emptyMap(), emptyMap())
        val samples = mutableListOf<Sample>()
        val states = HashMap<String, String>()
        val health = HashMap<String, PidHealth>()
        val lang = MfrSignals.language
        session.exclusive {
            for ((ecu, items) in due) {
                try {
                    select(ecu)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                for ((item, sigs) in items) {
                    val k = key(ecu, item)
                    val ts = clock()
                    lastRead[k] = ts
                    when (val r = read(ecu, itemRequest(ecu, item))) {
                        is MfrReadResult.Ok -> {
                            notSupported.remove(k); failures[k] = 0
                            for (s in sigs) {
                                when (val v = MfrDecoder.decode(s.param, r.payload)) {
                                    is MfrValue.Num -> samples += Sample(s.id, v.value * s.unitMultiplier, ts, simulated)
                                    is MfrValue.Label -> states[s.id] = "txt:" + v.text.get(lang)
                                    is MfrValue.Flag -> states[s.id] = if (v.on) "bool:1" else "bool:0"
                                    is MfrValue.Text -> states[s.id] = "txt:" + v.text
                                    MfrValue.Invalid -> { health[s.id] = PidHealth.NO_RESPONSE; continue }
                                }
                                health[s.id] = PidHealth.OK
                            }
                        }
                        is MfrReadResult.Negative -> {
                            val h = if (r.notSupported) { notSupported += k; PidHealth.NOT_SUPPORTED } else PidHealth.NO_RESPONSE
                            sigs.forEach { health[it.id] = h }
                        }
                        else -> {
                            val n = (failures[k] ?: 0) + 1
                            failures[k] = n
                            // nem válaszol: ritkábban próbáljuk újra, de nem jelöljük „nem támogatott”-nak (az csak NRC alapján jár)
                            if (n >= 3) notSupported += k
                            sigs.forEach { health[it.id] = PidHealth.NO_RESPONSE }
                        }
                    }
                }
            }
        }
        return MfrCycleResult(samples, states, health)
    }

    companion object {
        const val SLOW_KIND_MS = 30_000L
        /** egy élő azonosító legfeljebb ilyen gyakran (a busz és a vezérlő kímélése) */
        const val MIN_LIVE_MS = 500L
        const val RETRY_UNSUPPORTED_MS = 60_000L
    }
}
