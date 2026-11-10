package com.sx4scanpro

/** Decodes ISO 15031-5 / SAE J1979 Mode 01 supported-PID bitmaps (blocks 0x00, 0x20, ... 0xE0). */
internal object ObdPidSupport {
    const val LAST_BLOCK = 0xE0

    /** Chains through the blocks in [modeResponse]; a block is read only while the previous block advertises it. */
    fun decode(modeResponse: String): Set<Int> {
        val response = modeResponse.trim().uppercase().filterNot { it.isWhitespace() }
        val supported = linkedSetOf<Int>()
        var base = 0
        while (base <= LAST_BLOCK) {
            if (base != 0 && base !in supported) break
            val mask = parseBlock(response, base) ?: break
            supported.addAll(pidsOf(base, mask))
            base += 0x20
        }
        return supported
    }

    /** The 32-bit bitmap of the block starting at [base] ("41" + base byte + 4 data bytes), or null when absent or malformed. */
    fun parseBlock(compactResponse: String, base: Int): Long? {
        val prefix = "41%02X".format(base)
        val index = compactResponse.indexOf(prefix)
        if (index < 0) return null
        val bitmap = compactResponse.substring(index + prefix.length).take(8)
        if (bitmap.length != 8 || bitmap.any { it !in "0123456789ABCDEF" }) return null
        return bitmap.toLongOrNull(16)
    }

    /** PIDs base+1 .. base+0x20 whose bit is set. The last bit is the next block's own PID. */
    fun pidsOf(base: Int, mask: Long): Set<Int> {
        val out = linkedSetOf<Int>()
        for (offset in 0..31) if (((mask shr (31 - offset)) and 1L) == 1L) out.add(base + 1 + offset)
        return out
    }
}
