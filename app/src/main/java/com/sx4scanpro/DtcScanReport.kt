package com.sx4scanpro

/** Separates a valid empty DTC response from unsupported/no-data and malformed replies. */
data class DtcScanReport(
    val codes: List<TroubleCodeResult>,
    val completedModes: List<String>,
    val noDataModes: List<String>,
    val malformedModes: List<String>
) {
    val hasValidResponse: Boolean get() = completedModes.isNotEmpty()
    val hasIssues: Boolean get() = noDataModes.isNotEmpty() || malformedModes.isNotEmpty()
}

data class DtcModeReply(val mode: String, val label: String, val response: String)

object DtcScanReportParser {
    fun parse(replies: List<DtcModeReply>): DtcScanReport {
        val codes = mutableListOf<TroubleCodeResult>()
        val completed = mutableListOf<String>()
        val noData = mutableListOf<String>()
        val malformed = mutableListOf<String>()
        for (reply in replies) {
            val prefix = when (reply.mode) {
                "03" -> "43"
                "07" -> "47"
                "0A" -> "4A"
                else -> { malformed += reply.mode; continue }
            }
            val normalized = reply.response.trim().uppercase().filterNot { it.isWhitespace() }
            when {
                normalized == "NODATA" || normalized == "?" || normalized.isEmpty() ->
                    noData += reply.mode
                normalized.startsWith(prefix) -> {
                    val decoded = ObdTroubleCodeDecoder.decode(normalized, reply.mode)
                    val payload = normalized.removePrefix(prefix)
                    if (payload.isNotEmpty() && payload.length % 4 == 0 &&
                        payload.all { it in "0123456789ABCDEF" }) {
                        completed += reply.mode
                        codes += decoded.map { TroubleCodeResult(reply.label, it) }
                    } else malformed += reply.mode
                }
                else -> malformed += reply.mode
            }
        }
        return DtcScanReport(codes.distinct(), completed, noData, malformed)
    }
}
