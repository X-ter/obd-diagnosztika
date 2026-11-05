package hu.obddiag.core.ident

import hu.obddiag.core.ObdClient
import hu.obddiag.core.mfr.MfrDatabase
import hu.obddiag.core.mfr.MfrDetection
import hu.obddiag.core.mfr.MfrIdentification
import hu.obddiag.core.mfr.MfrIdentifier
import hu.obddiag.core.mfr.MfrSession
import kotlinx.coroutines.CancellationException

/** A felismerés lépései (a naplóban és a jelentésben). */
enum class Step(val label: String) {
    PROTOCOL("Protokoll"), VIN("VIN"), ECU("Szabványos ECU-azonosítás"), BRAND("Márka / csoport"),
    SOURCE("Adatforrás"), PROFILE("Profilválasztás"), PASSIVE("Passzív CAN"), RESULT("Eredmény")
}

/** Időbélyeges, lépésenkénti napló: minden döntés a kiolvasott adatokkal indokolva. */
class RecognitionLog(private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val time: Long, val step: Step, val message: String)

    private val entries = mutableListOf<Entry>()
    val all: List<Entry> get() = synchronized(entries) { entries.toList() }

    fun add(step: Step, message: String) = synchronized(entries) { entries += Entry(clock(), step, message) }
}

/**
 * Az adatok eredete. A passzív (a buszon magától keringő, nem lekérdezett) CAN-üzenetek SOHA nem keverednek
 * az aktívan lekérdezett diagnosztikai paraméterekkel: más a megbízhatóságuk, az elérhetőségük és a jelentésük.
 */
enum class DataOrigin(val label: String) {
    STANDARD_ACTIVE("Szabványos OBD (SAE J1979), lekérdezett"),
    MANUFACTURER_ACTIVE("Gyártói diagnosztika (UDS 22 / KWP 21), lekérdezett"),
    CALCULATED("Számított (lekérdezett értékekből)"),
    PASSIVE_BROADCAST("Passzív CAN-üzenet (csak hallgatás)")
}

/** Egy adatforrás eredménye egy járműre. */
data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val ran: Boolean,
    /** miért futott / miért nem futott */
    val reason: String,
    /** a gyártói profilmotor (DID/LID-definíciók) azonosítási eredménye, ha a forrás ilyet ad */
    val identification: MfrIdentification? = null
)

/**
 * Integrálható gyártói adatforrás. A forrás megmondja, mely gyártói csoportokra van adata; a felismerő csak
 * akkor futtatja, ha a jármű csoportja (VIN-ből vagy a felhasználó kifejezett megadásából) ebbe esik.
 * A forrás a vezérlőt kizárólag pontos azonosító-egyezés alapján választhatja ki; több jelölt vagy ismeretlen
 * azonosító esetén nem választ.
 */
interface ProfileSource {
    val id: String
    val name: String
    val license: String
    val groups: Set<BrandGroup>
    suspend fun match(client: ObdClient, ctx: RecognitionContext, log: RecognitionLog): SourceResult
}

/** Amit a felismerés a forrásoknak átad. */
data class RecognitionContext(val vin: VinInfo?, val group: BrandGroup?, val groupFromUser: Boolean, val ecus: List<EcuIdentity>)

enum class SupportLevel(val label: String) {
    /** legalább egy vezérlőhöz pontosan illeszkedő gyártói profil van */
    MANUFACTURER("Szabványos OBD + gyártói profil a pontosan azonosított vezérlőkre"),
    /** csak a szabványos OBD-II adatok – nincs (vagy nem egyértelmű) gyártói profil */
    STANDARD_ONLY("Csak szabványos OBD-II (korlátozott támogatás)"),
    /** a márka nem állapítható meg; a felhasználótól kell adat a gyártói forrásokhoz */
    NEEDS_INPUT("További járműinformáció szükséges")
}

data class VehicleRecognition(
    val protocol: String?,
    val respondingEcus: List<String>,
    val vin: VinInfo?,
    val vinRaw: String?,
    val vinSource: String?,
    val group: BrandGroup?,
    val groupFromUser: Boolean,
    val ecus: List<EcuIdentity>,
    val sources: List<SourceResult>,
    val support: SupportLevel,
    val log: List<RecognitionLog.Entry>
) {
    /** A gyártói profilmotor eredménye (az első forrásé, amely azonosított). */
    val manufacturer: MfrIdentification? get() = sources.firstOrNull { it.identification?.identified?.isNotEmpty() == true }?.identification
        ?: sources.firstOrNull { it.identification != null }?.identification

    fun report(): String = buildString {
        append("== Jármű ==\n")
        append("Protokoll: ").append(protocol ?: "–").append(" | válaszoló ECU-k: ").append(respondingEcus.joinToString().ifEmpty { "–" }).append('\n')
        append("VIN: ").append(vin?.vin ?: vinRaw ?: "nem olvasható").append(vinSource?.let { " ($it)" } ?: "").append('\n')
        vin?.let {
            append("WMI: ").append(it.wmi).append(" → ").append(it.manufacturer ?: "ismeretlen gyártó").append(" | csoport: ").append(it.group.label).append('\n')
            append("Modellév (10. karakter): ").append(it.modelYears.joinToString(" vagy ").ifEmpty { "–" })
                .append(" | ellenőrző karakter: ").append(if (it.checkDigitValid) "érvényes" else "nem érvényes (Európában nem kötelező)").append('\n')
        }
        append("Csoport: ").append(group?.label ?: "nem megállapítható").append(if (groupFromUser) " (felhasználói megadás)" else "").append('\n')
        append("Támogatás: ").append(support.label).append('\n')
        append("\n== Szabványos ECU-azonosítók ==\n")
        if (ecus.isEmpty()) append("–\n")
        for (e in ecus) {
            append(e.address).append(e.requestId?.let { " (kérés: %03X)".format(it) } ?: "").append(": ")
            val parts = buildList {
                e.ecuName?.let { add("név=$it") }
                if (e.calibrationIds.isNotEmpty()) add("CALID=" + e.calibrationIds.joinToString("/"))
                if (e.cvns.isNotEmpty()) add("CVN=" + e.cvns.joinToString("/"))
                e.identifiers.forEach { (k, v) -> add("$k=$v") }
                e.diagProtocol?.let { add("olvasva: $it") }
            }
            append(parts.joinToString(", ").ifEmpty { "nincs azonosító" }).append('\n')
        }
        append("\n== Adatforrások ==\n")
        for (s in sources) append(s.sourceName).append(": ").append(if (s.ran) "FUTOTT" else "NEM FUTOTT").append(" – ").append(s.reason).append('\n')
        append("\n== Felismerési napló ==\n")
        val t0 = log.firstOrNull()?.time ?: 0L
        for (e in log) append("+%5d ms [%s] %s\n".format(e.time - t0, e.step.label, e.message))
    }
}

/**
 * Univerzális jármű- és ECU-felismerés:
 *  1. protokoll (az adapter által felismert), válaszoló ECU-k
 *  2. VIN (Mode 09 / 02; ha nincs, UDS 22 F190 a szabványos ECU-któl)
 *  3. WMI → gyártói csoport (vagy a felhasználó kifejezett megadása; találgatás nincs)
 *  4. szabványos ECU-azonosítók (Mode 09 CALID/CVN/név, UDS F1xx, KWP 1A)
 *  5. gyártói adatforrások – csak a csoportjukba eső járműnél, pontos azonosító-egyezéssel
 * Minden lépés naplózva; minden művelet olvasó.
 */
class VehicleRecognizer(private val client: ObdClient, private val sources: List<ProfileSource>, private val log: RecognitionLog = RecognitionLog()) {

    suspend fun recognize(userGroup: BrandGroup? = null, onStage: (String) -> Unit = {}): VehicleRecognition {
        val info = client.vehicleInfo
        onStage("protocol")
        log.add(Step.PROTOCOL, if (info == null) "Nincs járműkapcsolat" else
            "Az adapter által felismert protokoll: ${info.protocol.displayName} (ATDPN); válaszoló ECU-k a 0100-ra: ${info.ecus.joinToString().ifEmpty { "–" }}")

        onStage("vin")
        var vinRaw = runCatching { client.readVin() }.getOrNull()
        var vinSource: String? = vinRaw?.let { "Mode 09 / 02" }
        log.add(Step.VIN, if (vinRaw != null) "Mode 09 / 02 → $vinRaw" else "Mode 09 / 02: nincs érvényes VIN a válaszban")

        onStage("ecu")
        val ecus = StandardEcuIdentifier(client, log).identify()
        if (vinRaw == null) {
            ecus.firstNotNullOfOrNull { e -> e.identifiers["vin"]?.takeIf { VinDecoder.isValidFormat(it) }?.let { e.address to it } }?.let { (a, v) ->
                vinRaw = v; vinSource = "UDS 22 F190 / KWP 1A 90 ($a)"
                log.add(Step.VIN, "VIN a vezérlő azonosítójából: $v ($a)")
            }
        }
        val vin = vinRaw?.let { VinDecoder.decode(it) }
        if (vinRaw != null && vin == null) log.add(Step.VIN, "A kiolvasott VIN formailag érvénytelen ($vinRaw) – nem használjuk")
        vin?.let {
            log.add(Step.VIN, "WMI ${it.wmi} → ${it.manufacturer ?: "nincs a WMI-táblában"}; csoport: ${it.group.label}; " +
                "modellév-kód '${it.vin[9]}' → ${it.modelYears.joinToString(" vagy ").ifEmpty { "–" }}; ellenőrző karakter: ${if (it.checkDigitValid) "érvényes" else "nem érvényes / nem használt"}")
        }

        onStage("brand")
        val vinGroup = vin?.group?.takeIf { it != BrandGroup.OTHER }
        val group: BrandGroup?
        val fromUser: Boolean
        when {
            vinGroup != null && userGroup != null && userGroup != vinGroup -> {
                group = vinGroup; fromUser = false
                log.add(Step.BRAND, "A felhasználó által megadott csoport (${userGroup.label}) eltér a VIN szerintitől – a VIN-t használjuk: ${vinGroup.label}")
            }
            vinGroup != null -> { group = vinGroup; fromUser = false; log.add(Step.BRAND, "Csoport a VIN WMI-ből: ${vinGroup.label}") }
            userGroup != null -> { group = userGroup; fromUser = true; log.add(Step.BRAND, "Csoport a felhasználó kifejezett megadásából: ${userGroup.label} (VIN nem azonosította)") }
            else -> { group = null; fromUser = false; log.add(Step.BRAND, "A gyártói csoport nem állapítható meg (nincs VIN vagy ismeretlen WMI) – gyártói forrás nem fut, további járműinformáció kell") }
        }

        val ctx = RecognitionContext(vin, group, fromUser, ecus)
        val results = mutableListOf<SourceResult>()
        for (src in sources) {
            onStage("source:" + src.id)
            if (group == null) {
                results += SourceResult(src.id, src.name, false, "a jármű gyártói csoportja nem ismert")
                continue
            }
            if (group !in src.groups) {
                results += SourceResult(src.id, src.name, false, "a forrás nem tartalmaz adatot erre a csoportra (${group.label})")
                log.add(Step.SOURCE, "${src.name}: kihagyva – csoport ${group.name} nincs a forrás csoportjai között (${src.groups.joinToString { it.name }})")
                continue
            }
            log.add(Step.SOURCE, "${src.name}: aktiválva – csoport ${group.name}${if (fromUser) " (felhasználói megadás)" else " (VIN ${vin?.wmi})"}")
            results += try {
                src.match(client, ctx, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(Step.SOURCE, "${src.name}: hiba – ${e.message ?: e.javaClass.simpleName}")
                SourceResult(src.id, src.name, true, "hiba: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        log.add(Step.PASSIVE, "Passzív CAN-figyelés: nem fut. Az adapter kérés–válasz módban dolgozik; a passzívan keringő üzeneteket " +
            "az app nem keveri a lekérdezett diagnosztikai paraméterekkel (a diagnosztikai aljzaton a gateway jellemzően nem is engedi át őket).")

        val anyIdentified = results.any { it.identification?.identified?.isNotEmpty() == true }
        val support = when {
            anyIdentified -> SupportLevel.MANUFACTURER
            group == null -> SupportLevel.NEEDS_INPUT
            else -> SupportLevel.STANDARD_ONLY
        }
        log.add(Step.RESULT, "Támogatási szint: ${support.label}")
        return VehicleRecognition(info?.protocol?.displayName, info?.ecus.orEmpty(), vin, vinRaw, vinSource, group, fromUser, ecus, results, support, log.all)
    }
}

/**
 * Gyártói profilforrás egy DBMUXEv-formátumú adatbázisból (pl. PSA-RE). A címzést, az azonosító kérést,
 * a cikkszám helyét és a szoftverváltozatokat az adatbázis írja le; a kód nem tartalmaz márka- vagy
 * vezérlőspecifikus feltételezést.
 */
class DbmuxevProfileSource(
    override val id: String,
    override val name: String,
    override val license: String,
    override val groups: Set<BrandGroup>,
    private val database: suspend () -> MfrDatabase,
    private val sessionFactory: (ObdClient) -> MfrSession,
    private val onIdentified: suspend (MfrIdentification, MfrSession, MfrDatabase) -> Unit = { _, _, _ -> }
) : ProfileSource {

    override suspend fun match(client: ObdClient, ctx: RecognitionContext, log: RecognitionLog): SourceResult {
        val db = database()
        log.add(Step.SOURCE, "$name: ${db.ecus.size} vezérlőmodell, ${db.addresses.keys.map { it.first to it.second }.distinct().size} diagnosztikai cím (forrás: ${db.source.name} ${db.source.commit.take(8)})")
        val session = sessionFactory(client)
        val res = MfrIdentifier(db, session).identify()
        try { session.exclusive { closeSessions() } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        res.error?.let { log.add(Step.SOURCE, "$name: $it") }
        res.trace.forEach { log.add(Step.SOURCE, it) }
        for (d in res.detections) {
            val addr = "%03X→%03X".format(d.requestId, d.responseId)
            when (d) {
                is MfrDetection.Identified -> log.add(Step.PROFILE,
                    "$addr: KIVÁLASZTVA ${d.ecu.key}, változat ${d.variant}. Indok: a(z) ${d.ecu.ident.request} válasz ${d.ecu.ident.byteOffset}–${d.ecu.ident.byteOffset + d.ecu.ident.byteLength - 1}. adatbájtja " +
                    "(BCD) = ${d.partNumber}; ez a cikkszám az adatbázisban egyetlen modell egyetlen változatához tartozik" +
                    (if (d.ecu.safetyCritical) "; biztonságkritikus vezérlő – csak egyszeri, kézi olvasás" else ""))
                is MfrDetection.Ambiguous -> log.add(Step.PROFILE,
                    "$addr: NEM VÁLASZTUNK – a ${d.partNumber} cikkszám több modellhez illik (${d.candidates.joinToString()}); korlátozott támogatás, nincs gyártói érték")
                is MfrDetection.Unknown -> log.add(Step.PROFILE,
                    "$addr: NEM VÁLASZTUNK – a vezérlő válaszolt, de a kiolvasott azonosító(k) (${d.partNumbers.joinToString().ifEmpty { "–" }}) nincs(enek) az adatbázisban; korlátozott támogatás")
            }
        }
        if (res.identified.isNotEmpty()) onIdentified(res, session, db)
        val reason = when {
            res.error != null -> res.error!!
            res.detections.isEmpty() -> "egyetlen ismert címen sem válaszolt vezérlő"
            else -> "${res.identified.size} pontosan azonosított, ${res.detections.size - res.identified.size} nem egyértelmű vagy ismeretlen vezérlő"
        }
        return SourceResult(id, name, true, reason, res)
    }
}
