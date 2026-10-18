package app.forkcast.core.domain

/**
 * Barcode normalization (SPEC §4.4). Every barcode is stored and compared as a zero-padded
 * GTIN-14 string, so UPC-A "016000275287", EAN-13 "0016000275287" and FDC's "00016000275287"
 * all match. UPC-E is expanded to UPC-A first.
 */
object Gtin {

    /** GTIN-14 for a scanned or typed code, or null if it isn't a valid EAN-8/UPC-E/UPC-A/EAN-13/GTIN-14. */
    fun normalize(raw: String): String? {
        val digits = raw.trim().filter { !it.isWhitespace() && it != '-' }
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val expanded = when (digits.length) {
            6, 7 -> null // UPC-E without number system / check digit isn't safe to expand
            8 -> if (digits[0] == '0' || digits[0] == '1') upcEToUpcA(digits)?.takeIf { hasValidCheckDigit(it) } ?: digits else digits
            12, 13, 14 -> digits
            else -> null
        } ?: return null
        if (!hasValidCheckDigit(expanded)) return null
        return expanded.padStart(14, '0')
    }

    /**
     * An 8-digit code could be EAN-8 or UPC-E; scanners report which format they saw.
     * Use this when the scanner says UPC-E.
     */
    fun normalizeUpcE(upcE: String): String? = upcEToUpcA(upcE)?.let { normalize(it) }

    /** Forms worth trying against a database that may store other lengths (14, 13, 12 digits). */
    fun lookupVariants(gtin14: String): List<String> = buildList {
        add(gtin14)
        if (gtin14.startsWith("0")) add(gtin14.substring(1))
        if (gtin14.startsWith("00")) add(gtin14.substring(2))
        if (gtin14.startsWith("000000")) add(gtin14.substring(6)) // EAN-8
    }

    fun hasValidCheckDigit(code: String): Boolean {
        if (code.length < 8 || !code.all { it.isDigit() }) return false
        val body = code.dropLast(1)
        var sum = 0
        // From the right, digits alternate weights 3, 1, 3, 1...
        body.reversed().forEachIndexed { i, c -> sum += (c - '0') * if (i % 2 == 0) 3 else 1 }
        val check = (10 - sum % 10) % 10
        return check == code.last() - '0'
    }

    /** Standard UPC-E → UPC-A expansion (8-digit UPC-E with number system and check digit). */
    fun upcEToUpcA(upcE: String): String? {
        if (upcE.length != 8 || !upcE.all { it.isDigit() }) return null
        val ns = upcE[0]
        if (ns != '0' && ns != '1') return null
        val d = upcE.substring(1, 7)
        val check = upcE[7]
        val manufacturerAndProduct = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return "$ns$manufacturerAndProduct$check"
    }
}
