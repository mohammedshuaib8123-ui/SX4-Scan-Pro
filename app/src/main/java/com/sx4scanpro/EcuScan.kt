package com.sx4scanpro

/** Facts about the adapter and bus gathered at connect time (ATI, ATRV, ATDP). Empty text means the adapter did not answer. */
internal data class AdapterFacts(val name: String, val id: String, val voltage: String, val protocol: String)

/**
 * Read-only identity scan: Mode 09 support list, then VIN, calibration IDs, CVNs and ECU name, but only the ones the ECU
 * advertised. Only service 09 reads and the adapter's own queries are used; nothing is written, cleared or reset.
 */
internal object EcuScan {
    private val ITEMS = listOf(0x02, 0x04, 0x06, 0x0A)

    fun run(port: ElmPort, facts: AdapterFacts, mode01: Set<Int>, appVersion: String, nowMs: Long, onStep: (String) -> Unit = {}): EcuProfile {
        val framing = Framing.fromProtocolText(facts.protocol)
        fun ask(pid: Int): Mode09Value {
            onStep("Reading 09%02X".format(pid))
            val r = port.sendLines("09%02X".format(pid), 12_000L)
            return if (r.timedOut) Mode09Value(pid, ScanStatus.ADAPTER_ERROR, note = "timed out")
            else Mode09Decoder.decode(pid, r.raw, framing)
        }
        fun toField(v: Mode09Value) = ProfileField(v.status, v.items, v.rawHex, v.note)

        val support = ask(0x00)
        val notes = mutableListOf<String>()
        if (framing == Framing.UNKNOWN) notes += "Protocol was not reported by the adapter, so multi-frame replies were decoded with K-line rules only."
        val advertised: Set<Int>? = if (support.status == ScanStatus.READ) support.items.mapNotNull { it.toIntOrNull(16) }.toSet() else null
        if (advertised == null) notes += "The ECU did not report its Mode 09 support list, so no Mode 09 items were requested."
        val got = ITEMS.associateWith { pid ->
            when {
                advertised == null -> ProfileField(ScanStatus.NOT_ATTEMPTED, note = "Mode 09 support list unavailable")
                pid !in advertised -> ProfileField(ScanStatus.NOT_ADVERTISED)
                else -> toField(ask(pid))
            }
        }
        return EcuProfile(
            capturedAtMs = nowMs, appVersion = appVersion, adapterName = facts.name, adapterId = facts.id,
            adapterVoltage = facts.voltage, protocol = facts.protocol, framing = framing,
            mode01Supported = mode01.sorted(), mode09Supported = toField(support),
            vin = got.getValue(0x02), calIds = got.getValue(0x04), cvns = got.getValue(0x06), ecuName = got.getValue(0x0A),
            notes = notes
        )
    }
}
