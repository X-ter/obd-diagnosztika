package hu.obddiag.core.elm

/**
 * Az ELM327 nyers szöveges válaszainak értelmezése.
 *
 * A munkamenet bekapcsolt fejlécekkel (ATH1) és szóközökkel (ATS1) dolgozik, mert így
 * azonosítható, melyik ECU válaszolt, és a CAN többkeretes (ISO-TP) üzenetek is
 * megbízhatóan összerakhatók. Fejlécek nélküli válaszokat is kezel tartalékként.
 */
object ElmResponseParser {

    private val ERROR_PATTERNS: List<Pair<String, ElmErrorKind>> = listOf(
        "UNABLE TO CONNECT" to ElmErrorKind.UNABLE_TO_CONNECT,
        "CAN ERROR" to ElmErrorKind.CAN_ERROR,
        "BUS ERROR" to ElmErrorKind.BUS_ERROR,
        "BUSINIT: ...ERROR" to ElmErrorKind.BUS_ERROR,
        "BUS INIT: ...ERROR" to ElmErrorKind.BUS_ERROR,
        "BUS BUSY" to ElmErrorKind.BUS_BUSY,
        "BUFFER FULL" to ElmErrorKind.BUFFER_FULL,
        "DATA ERROR" to ElmErrorKind.DATA_ERROR,
        "<DATA ERROR" to ElmErrorKind.DATA_ERROR,
        "FB ERROR" to ElmErrorKind.FB_ERROR,
        "LV RESET" to ElmErrorKind.LV_RESET,
        "STOPPED" to ElmErrorKind.STOPPED,
        "ACT ALERT" to ElmErrorKind.INTERNAL_ERROR,
        "LP ALERT" to ElmErrorKind.INTERNAL_ERROR,
    )

    private val NOISE_PREFIXES = listOf("SEARCHING", "BUS INIT", "BUSINIT")

    /** Egy válasz sorokra bontása: a prompt, a visszhang és a zajsorok eltávolítása. */
    fun cleanLines(raw: String, command: String? = null): List<String> {
        val echo = command?.replace(" ", "")?.uppercase()
        return raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim().replace('\u0000', ' ').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line ->
                val up = line.uppercase()
                NOISE_PREFIXES.any { up.startsWith(it) && !up.contains("ERROR") }
            }
            .filterNot { echo != null && it.replace(" ", "").uppercase() == echo }
            .map { line ->
                // Egyes klónok a SEARCHING... után ugyanabba a sorba írják az adatot.
                val up = line.uppercase()
                if (up.startsWith("SEARCHING...")) line.substring("SEARCHING...".length).trim() else line
            }
            .filter { it.isNotEmpty() }
    }

    /** OBD (nem AT) parancs válaszának teljes értelmezése. */
    fun parseObd(
        raw: String,
        command: String?,
        protocol: ObdProtocol?,
        headersEnabled: Boolean = true
    ): ElmResult {
        val lines = cleanLines(raw, command)
        if (lines.isEmpty()) return ElmResult.NoData(raw)

        val joined = lines.joinToString(" ").uppercase()
        if (lines.all { it.uppercase().replace(" ", "") == "NODATA" }) return ElmResult.NoData(raw)
        if (lines.size == 1 && lines[0].trim() == "?") return ElmResult.Error(ElmErrorKind.UNKNOWN_COMMAND, raw)
        ERROR_PATTERNS.firstOrNull { joined.contains(it.first) }?.let { (_, kind) ->
            // Ha a hiba mellett mégis érkezett érvényes adat, azt használjuk.
            val dataLines = lines.filter { isHexLine(it) }
            if (dataLines.isEmpty()) return ElmResult.Error(kind, raw)
        }
        if (joined.matches(Regex("ERR\\d+.*"))) return ElmResult.Error(ElmErrorKind.INTERNAL_ERROR, raw)

        val dataLines = lines.filter { isHexLine(it) || isIndexedLine(it) }
        if (dataLines.isEmpty()) {
            return if (joined.contains("NO DATA")) ElmResult.NoData(raw) else ElmResult.Error(ElmErrorKind.MALFORMED, raw)
        }

        val messages = try {
            if (headersEnabled) parseWithHeaders(dataLines, protocol) else parseWithoutHeaders(dataLines)
        } catch (e: IllegalArgumentException) {
            return ElmResult.Error(ElmErrorKind.MALFORMED, raw)
        }

        val negatives = mutableListOf<NegativeResponse>()
        val positives = mutableListOf<EcuMessage>()
        for (m in messages) {
            if (m.data.isEmpty()) continue
            if ((m.data[0].toInt() and 0xFF) == 0x7F) {
                val service = if (m.data.size > 1) m.data[1].toInt() and 0xFF else 0
                val nrc = if (m.data.size > 2) m.data[2].toInt() and 0xFF else 0
                if (nrc != 0x78) negatives += NegativeResponse(m.ecu, service, nrc)
            } else {
                positives += m
            }
        }
        if (positives.isEmpty() && negatives.isEmpty()) return ElmResult.NoData(raw)
        return ElmResult.Data(positives, negatives, raw)
    }

    /** AT parancsok szöveges válasza. */
    fun parseText(raw: String, command: String?): ElmResult {
        val lines = cleanLines(raw, command)
        if (lines.isEmpty()) return ElmResult.Text("", raw)
        if (lines.last().trim() == "?") return ElmResult.Error(ElmErrorKind.UNKNOWN_COMMAND, raw)
        return ElmResult.Text(lines.joinToString("\n"), raw)
    }

    private fun isHexLine(line: String): Boolean {
        val s = line.replace(" ", "")
        return s.isNotEmpty() && s.all { it.isHexDigitChar() }
    }

    private fun isIndexedLine(line: String): Boolean = Regex("^[0-9A-Fa-f]:\\s*[0-9A-Fa-f ]+$").matches(line.trim())

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    private fun tokenize(line: String, protocol: ObdProtocol?): List<String> {
        val trimmed = line.trim().uppercase()
        if (trimmed.contains(' ')) return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Szóközök nélküli (ATS0) válasz: az esetleges 3 karakteres CAN fejléc külön kezelése.
        val result = mutableListOf<String>()
        var rest = trimmed
        val isCan11 = protocol?.canHeaderBytes == 11 || (protocol == null && rest.length % 2 == 1)
        if (isCan11 && rest.length % 2 == 1) {
            result += rest.substring(0, 3)
            rest = rest.substring(3)
        }
        require(rest.length % 2 == 0) { "páratlan hosszúságú hexa sor" }
        rest.chunked(2).forEach { result += it }
        return result
    }

    private enum class HeaderMode { CAN11, CAN29, LEGACY }

    private fun detectMode(tokens: List<String>, protocol: ObdProtocol?): HeaderMode = when {
        protocol != null && protocol != ObdProtocol.AUTO && protocol.isCan ->
            if (protocol.canHeaderBytes == 29) HeaderMode.CAN29 else HeaderMode.CAN11
        protocol != null && protocol != ObdProtocol.AUTO -> HeaderMode.LEGACY
        tokens.first().length == 3 -> HeaderMode.CAN11
        tokens.size >= 5 && tokens[0] == "18" && (tokens[1] == "DA" || tokens[1] == "DB") -> HeaderMode.CAN29
        else -> HeaderMode.LEGACY
    }

    private fun hexToInt(t: String): Int {
        require(t.length == 2 && t.all { it.isHexDigitChar() }) { "nem hexa bájt: $t" }
        return t.toInt(16)
    }

    private class IsoTpAssembly(val ecu: String) {
        var expected = -1
        val buffer = ArrayList<Byte>()
        val complete get() = expected >= 0 && buffer.size >= expected
    }

    private fun parseWithHeaders(lines: List<String>, protocol: ObdProtocol?): List<EcuMessage> {
        val out = mutableListOf<EcuMessage>()
        val assemblies = LinkedHashMap<String, IsoTpAssembly>()

        for (line in lines) {
            if (isIndexedLine(line)) continue
            val tokens = tokenize(line, protocol)
            if (tokens.isEmpty()) continue
            when (detectMode(tokens, protocol)) {
                HeaderMode.CAN11, HeaderMode.CAN29 -> {
                    val isCan11 = tokens.first().length == 3
                    val headerLen = if (isCan11) 1 else 4
                    if (tokens.size <= headerLen) continue
                    val ecu = if (isCan11) tokens[0] else tokens.subList(0, 4).joinToString("")
                    val bytes = tokens.drop(headerLen).map { hexToInt(it) }
                    val pci = bytes[0]
                    when (pci shr 4) {
                        0x0 -> {
                            val len = pci and 0x0F
                            val payload = bytes.drop(1).take(len)
                            if (payload.isNotEmpty()) out += EcuMessage(ecu, payload.map { it.toByte() }.toByteArray())
                        }
                        0x1 -> {
                            if (bytes.size < 2) continue
                            val len = ((pci and 0x0F) shl 8) or bytes[1]
                            val a = IsoTpAssembly(ecu).also { it.expected = len }
                            bytes.drop(2).forEach { a.buffer += it.toByte() }
                            assemblies[ecu] = a
                        }
                        0x2 -> {
                            val a = assemblies[ecu] ?: continue
                            bytes.drop(1).forEach { a.buffer += it.toByte() }
                        }
                        else -> Unit // 0x3: flow control, nem tartalmaz adatot
                    }
                    assemblies[ecu]?.let { a ->
                        if (a.complete) {
                            out += EcuMessage(ecu, a.buffer.take(a.expected).toByteArray())
                            assemblies.remove(ecu)
                        }
                    }
                }
                HeaderMode.LEGACY -> {
                    // 3 bájtos fejléc (prioritás, cél, forrás) + adat + ellenőrzőösszeg
                    if (tokens.size < 5) continue
                    val ecu = tokens[2]
                    val data = tokens.subList(3, tokens.size - 1).map { hexToInt(it).toByte() }
                    if (data.isNotEmpty()) out += EcuMessage(ecu, data.toByteArray())
                }
            }
        }
        // Hiányos többkeretes üzenet: a beérkezett részt adjuk vissza, a dekóderek eldöntik, hogy elég-e.
        assemblies.values.filter { it.buffer.isNotEmpty() }.forEach { out += EcuMessage(it.ecu, it.buffer.toByteArray()) }
        return out
    }

    private fun parseWithoutHeaders(lines: List<String>): List<EcuMessage> {
        val out = mutableListOf<EcuMessage>()
        val indexed = lines.filter { isIndexedLine(it) }
        if (indexed.isNotEmpty()) {
            val lengthLine = lines.firstOrNull { !isIndexedLine(it) && it.replace(" ", "").length == 3 }
            val expected = lengthLine?.replace(" ", "")?.toIntOrNull(16)
            val bytes = indexed.flatMap { l ->
                l.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { hexToInt(it).toByte() }
            }
            out += EcuMessage("?", (if (expected != null) bytes.take(expected) else bytes).toByteArray())
            return out
        }
        for (line in lines) {
            val tokens = tokenize(line, null)
            out += EcuMessage("?", tokens.map { hexToInt(it).toByte() }.toByteArray())
        }
        return out
    }
}
