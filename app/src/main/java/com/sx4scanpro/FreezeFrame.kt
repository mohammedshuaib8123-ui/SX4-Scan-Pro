package com.sx4scanpro

/**
 * Mode 02 freeze frame: the sensor values the ECU stored at the moment a fault was set.
 * Request "02<pid>00", reply "42<pid>00<data>". Only an exact reply is decoded; otherwise the
 * result is null and the screen says the ECU returned nothing.
 */
internal object FreezeFrame {
    /** PIDs worth asking for, if the ECU advertises them in Mode 01. */
    val PIDS = listOf(0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x11, 0x0F, 0x10, 0x2C)

    fun command(pid: Int): String = String.format("02%02X00", pid)

    data class Entry(val name: String, val value: String, val unit: String)

    fun decode(spec: PidSpec, response: String): Entry? {
        val r = response.uppercase().filterNot { it.isWhitespace() }
        val prefix = "42" + String.format("%02X", spec.pid) + "00"
        if (!r.startsWith(prefix)) return null
        // Reuse the Mode 01 decoder by rewriting the verified header.
        val v = PidCatalog.decode(spec, "41" + String.format("%02X", spec.pid) + r.removePrefix(prefix)) ?: return null
        return Entry(spec.name, PidCatalog.format(spec, v), spec.unit)
    }
}
