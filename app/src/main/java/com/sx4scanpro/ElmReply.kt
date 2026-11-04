package com.sx4scanpro

/** What an ELM327 reply means, decided from the text alone. Nothing here talks to hardware. */
internal enum class ElmKind {
    DATA, OK, NO_DATA, UNABLE_TO_CONNECT, BUS_BUSY, BUS_ERROR, CAN_ERROR, DATA_ERROR,
    BUFFER_FULL, STOPPED, UNKNOWN_COMMAND, ACTIVITY_MONITOR, LOW_VOLTAGE_RESET, EMPTY
}

internal object ElmReply {
    private val banners = listOf("SEARCHING...", "BUSINIT:OK", "BUSINIT:...OK", "BUSINIT:", "BUSINIT...")

    /** Upper-cases and removes whitespace so "NO DATA" and "NODATA" compare equal. */
    fun compact(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }

    /** Removes the informational banners the adapter prints before a real answer. */
    fun stripBanners(raw: String): String {
        var s = compact(raw)
        banners.forEach { s = s.replace(it, "") }
        return s
    }

    fun classify(raw: String): ElmKind {
        val s = stripBanners(raw)
        return when {
            s.isEmpty() -> ElmKind.EMPTY
            s == "OK" -> ElmKind.OK
            s.contains("UNABLETOCONNECT") -> ElmKind.UNABLE_TO_CONNECT
            s.contains("NODATA") -> ElmKind.NO_DATA
            s.contains("BUSBUSY") -> ElmKind.BUS_BUSY
            s.contains("BUSERROR") || s.contains("FBERROR") -> ElmKind.BUS_ERROR
            s.contains("CANERROR") -> ElmKind.CAN_ERROR
            s.contains("DATAERROR") || s.contains("<DATAERROR") -> ElmKind.DATA_ERROR
            s.contains("BUFFERFULL") -> ElmKind.BUFFER_FULL
            s.contains("STOPPED") -> ElmKind.STOPPED
            s.contains("ACTMONITOR") -> ElmKind.ACTIVITY_MONITOR
            s.contains("LVRESET") -> ElmKind.LOW_VOLTAGE_RESET
            s == "?" || s.endsWith("?") && s.length <= 3 -> ElmKind.UNKNOWN_COMMAND
            else -> ElmKind.DATA
        }
    }

    /** True when the adapter answered but the ECU did not, which is the state recovery has to handle. */
    fun isEcuSilent(kind: ElmKind) = kind == ElmKind.NO_DATA || kind == ElmKind.UNABLE_TO_CONNECT ||
        kind == ElmKind.BUS_ERROR || kind == ElmKind.CAN_ERROR || kind == ElmKind.BUS_BUSY
}

/** One adapter transaction: what was asked, what came back, how long it took. */
internal data class ElmResult(val command: String, val raw: String, val kind: ElmKind, val millis: Long, val timedOut: Boolean = false)
