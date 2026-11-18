package com.sx4scanpro

/**
 * What is printed on the engine ECU's label, as read by the owner from photographs on 2026-11-18. This is a record of a physical label,
 * NOT something the ECU reported, so it is kept apart from the scanned [EcuProfile] and is always shown with that wording. It never
 * feeds module status, addressing or any command.
 */
internal object OwnerEcuLabel {
    const val READ_ON = "2026-11-18"
    const val HEADING = "Label on the ECU (read by the owner from photographs on $READ_ON; not reported by the ECU):"

    /** Each value is as printed. Items marked "unconfirmed" were read by Claude from the photographs and not confirmed by the owner. */
    val fields: List<Pair<String, String>> = listOf(
        "ECU type" to "MJD 6S3.S1 (Magneti Marelli MJD family)",
        "Suzuki part numbers" to "33920M55KD0 and 33920-55KD",
        "Engine / variant line" to "Z13DT MT P74 BSIV",
        "Hardware code (unconfirmed)" to "BC.0105383.B"
    )

    const val NOT_RECORDED = "One more code line on the label was unreadable in the photographs and is not recorded."

    fun lines(): List<String> = buildList {
        add(HEADING)
        fields.forEach { (k, v) -> add("  $k: $v") }
        add("  $NOT_RECORDED")
    }
}
