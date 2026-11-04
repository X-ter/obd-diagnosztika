package hu.obddiag.core.mfr

/** Egy gyártói paraméter dekódolt értéke. */
sealed class MfrValue {
    data class Num(val value: Double) : MfrValue()
    data class Label(val text: MfrText, val raw: Long) : MfrValue()
    data class Flag(val on: Boolean) : MfrValue()
    data class Text(val text: String) : MfrValue()
    /** Az ECU „nincs adat” értéket küldött, vagy a válasz túl rövid. */
    data object Invalid : MfrValue()
}

/** DBMUXEv szerinti dekódolás: big-endian bitszámozás, nyers × factor + offset, érvénytelen értékek kiszűrése. */
object MfrDecoder {

    /** Nyers bitek kiolvasása; a payload a szolgáltatás- és azonosítóbájtok UTÁNI adat. */
    fun raw(payload: ByteArray, start: Int, length: Int, littleEndian: Boolean = false): Long? {
        if (length <= 0 || length > 64 || start < 0 || start + length > payload.size * 8) return null
        if (littleEndian && start % 8 == 0 && length % 8 == 0) {
            var v = 0L
            val first = start / 8
            for (i in (length / 8 - 1) downTo 0) v = (v shl 8) or (payload[first + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (b in start until start + length) {
            val bit = (payload[b / 8].toInt() shr (7 - b % 8)) and 1
            v = (v shl 1) or bit.toLong()
        }
        return v
    }

    fun decode(p: MfrParam, payload: ByteArray): MfrValue {
        when (p.type) {
            MfrType.STR -> {
                val bytes = slice(payload, p) ?: return MfrValue.Invalid
                val s = bytes.filter { it in 0x20..0x7E }.map { it.toInt().toChar() }.joinToString("").trim()
                return if (s.isEmpty()) MfrValue.Invalid else MfrValue.Text(s)
            }
            MfrType.BYTES -> {
                val bytes = slice(payload, p) ?: return MfrValue.Invalid
                return MfrValue.Text(bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            }
            MfrType.BCD -> {
                val bytes = slice(payload, p) ?: return MfrValue.Invalid
                return MfrValue.Text(bcd(bytes))
            }
            else -> Unit
        }
        val r = raw(payload, p.start, p.length, p.littleEndian) ?: return MfrValue.Invalid
        if (r in p.invalid) return MfrValue.Invalid
        return when (p.type) {
            MfrType.BOOL -> p.values[r]?.let { MfrValue.Label(it, r) } ?: MfrValue.Flag(r != 0L)
            MfrType.ENUM -> p.values[r]?.let { MfrValue.Label(it, r) } ?: scaled(p, r)
            MfrType.FLOAT -> {
                val d = if (p.length == 32) java.lang.Float.intBitsToFloat(r.toInt()).toDouble() else java.lang.Double.longBitsToDouble(r)
                if (d.isFinite()) MfrValue.Num(d * p.factor + p.offset) else MfrValue.Invalid
            }
            else -> scaled(p, r)
        }
    }

    private fun scaled(p: MfrParam, r: Long): MfrValue {
        val v = if (p.type == MfrType.SINT && p.length < 64 && (r shr (p.length - 1)) and 1L == 1L) r - (1L shl p.length) else r
        return MfrValue.Num(v * p.factor + p.offset)
    }

    private fun slice(payload: ByteArray, p: MfrParam): ByteArray? {
        if (p.start % 8 != 0) return null
        val from = p.start / 8
        if (from >= payload.size) return null
        val to = minOf(payload.size, from + (p.length + 7) / 8)
        return payload.copyOfRange(from, to)
    }

    fun bcd(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
            append("0123456789ABCDEF"[b.toInt() and 0xF])
        }
    }
}
