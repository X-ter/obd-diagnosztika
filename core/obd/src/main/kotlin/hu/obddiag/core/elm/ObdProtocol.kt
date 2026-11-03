package hu.obddiag.core.elm

/** ELM327 protokollszámok (ATSPx / ATDPN). */
enum class ObdProtocol(val code: Char, val displayName: String, val isCan: Boolean, val canHeaderBytes: Int) {
    AUTO('0', "Automatikus", false, 0),
    SAE_J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false, 0),
    SAE_J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false, 0),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", false, 0),
    ISO_14230_4_KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false, 0),
    ISO_14230_4_KWP_FAST('5', "ISO 14230-4 KWP (gyors init)", false, 0),
    ISO_15765_4_CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, 11),
    ISO_15765_4_CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, 29),
    ISO_15765_4_CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, 11),
    ISO_15765_4_CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, 29),
    SAE_J1939_CAN('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true, 29),
    USER1_CAN('B', "USER1 CAN", true, 11),
    USER2_CAN('C', "USER2 CAN", true, 11);

    companion object {
        /** ATDPN válasz értelmezése, pl. "A6" (automatikusan választott 6-os) vagy "6". */
        fun fromDpn(response: String): ObdProtocol? {
            val s = response.trim().uppercase().lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return null
            // "A6" = automatikusan választott 6-os protokoll; az egyetlen "A" viszont a J1939.
            val c = (if (s.length >= 2 && s[0] == 'A') s[1] else s.firstOrNull()) ?: return null
            return entries.firstOrNull { it.code == c && it != AUTO }
        }

        fun fromCode(c: Char): ObdProtocol? = entries.firstOrNull { it.code == c.uppercaseChar() }
    }
}
