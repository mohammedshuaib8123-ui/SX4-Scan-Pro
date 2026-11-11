package com.sx4scanpro

/** How one Mode 01 support-bitmap request ended. Only [OK] says anything about which PIDs the ECU supports. */
internal enum class BlockKind { OK, NO_DATA, TIMEOUT, NEGATIVE, PARSE_ERROR, ADAPTER_ERROR }

internal data class BlockRead(val base: Int, val kind: BlockKind, val mask: Long?, val raw: String, val elm: ElmKind) {
    val ok: Boolean get() = kind == BlockKind.OK && mask != null
    val pids: Set<Int> get() = if (ok) ObdPidSupport.pidsOf(base, mask!!) else emptySet()
    /** The block's last bit is the next block's own PID: it says whether the next bitmap exists. */
    val hasNext: Boolean get() = ok && base < ObdPidSupport.LAST_BLOCK && (mask!! and 1L) == 1L
}

internal object Mode01Blocks {
    private val negative = Regex("7F01[0-9A-F]{2}")

    fun command(base: Int) = "01%02X".format(base)

    fun classify(base: Int, res: ElmResult): BlockRead {
        val compact = ElmReply.stripBanners(res.raw)
        val mask = if (res.kind == ElmKind.DATA) ObdPidSupport.parseBlock(compact, base) else null
        val kind = when {
            mask != null -> BlockKind.OK
            res.timedOut || res.kind == ElmKind.EMPTY -> BlockKind.TIMEOUT
            res.kind == ElmKind.NO_DATA -> BlockKind.NO_DATA
            res.kind == ElmKind.DATA -> if (negative.containsMatchIn(compact)) BlockKind.NEGATIVE else BlockKind.PARSE_ERROR
            else -> BlockKind.ADAPTER_ERROR
        }
        return BlockRead(base, kind, mask, res.raw, res.kind)
    }

    /** What a failed block is recorded as. None of these is evidence that a PID is unsupported. */
    fun evidenceKind(kind: BlockKind): EvidenceKind = when (kind) {
        BlockKind.TIMEOUT -> EvidenceKind.TIMEOUT
        BlockKind.NEGATIVE -> EvidenceKind.NEGATIVE_RESPONSE
        BlockKind.PARSE_ERROR -> EvidenceKind.DECODE_FAILED
        else -> EvidenceKind.NO_DATA
    }
}

/** Walks the Mode 01 support bitmaps 0100, 0120 ... 01E0, following each block's "next block" bit. One command at a time. */
internal class Mode01Walker(private val send: (String, Long) -> ElmResult) {
    fun readBlock(base: Int, timeoutMs: Long): BlockRead = Mode01Blocks.classify(base, send(Mode01Blocks.command(base), timeoutMs))

    /** The blocks after [from], until one is not advertised, one fails, or the last block is read. */
    fun follow(from: BlockRead, blockTimeoutMs: Long): List<BlockRead> {
        val out = ArrayList<BlockRead>()
        var cur = from
        while (cur.hasNext) { cur = readBlock(cur.base + 0x20, blockTimeoutMs); out += cur }
        return out
    }

    fun walk(firstTimeoutMs: Long, blockTimeoutMs: Long): List<BlockRead> {
        val first = readBlock(0, firstTimeoutMs)
        return listOf(first) + follow(first, blockTimeoutMs)
    }
}

internal data class HeaderRestore(val restored: Boolean, val usedReinit: Boolean)

/**
 * Header state of the adapter during discovery. ATH1 is used only to see which ECU answers; [restore] puts headers back
 * to ATH0, clears any CAN receive filter and proves the link with a valid 0100. ATZ is never routine: the
 * [reinit] fallback runs only when a custom header (ATSH) was applied and ATH0 alone did not bring the link back.
 */
internal class AdapterHeaders(
    private val send: (String, Long) -> ElmResult,
    private val framing: Framing,
    private val reinit: (() -> Unit)? = null
) {
    var customHeaderApplied: Boolean = false
        private set

    fun enableHeaders(): Boolean = ElmReply.compact(send("ATH1", COMMAND_MS).raw).contains("OK")

    fun applyCustomHeader(hex: String): Boolean {
        val ok = ElmReply.compact(send("ATSH$hex", COMMAND_MS).raw).contains("OK")
        if (ok) customHeaderApplied = true
        return ok
    }

    fun restore(): HeaderRestore {
        var ok = tryRestore() || tryRestore()
        var usedReinit = false
        if (!ok && customHeaderApplied && reinit != null) { reinit.invoke(); usedReinit = true; ok = verify() }
        if (ok) customHeaderApplied = false
        return HeaderRestore(ok, usedReinit)
    }

    private fun tryRestore(): Boolean {
        send("ATH0", COMMAND_MS)
        if (framing != Framing.KLINE) send("ATCRA", COMMAND_MS)   // ATCRA is a CAN command; harmless "?" on unknown framing
        return verify()
    }

    /** A valid 0100 answer that starts with the response itself, i.e. without header bytes. */
    private fun verify(): Boolean {
        val r = send("0100", VERIFY_MS)
        if (r.kind != ElmKind.DATA) return false
        return ElmReply.stripBanners(r.raw).let { it.startsWith("4100") && ObdPidSupport.parseBlock(it, 0) != null }
    }

    companion object { const val COMMAND_MS = 3_000L; const val VERIFY_MS = 6_000L }
}

internal data class DiscoveryRun(
    val reads: List<BlockRead>,
    val ecuHeader: String?,
    val ecuAddress: String?,
    val headerRestore: HeaderRestore?,
    val complete: Boolean
) {
    /** Every PID the ECU listed, including the block PIDs 0x20, 0x40 ... that point at the next bitmap. */
    val advertised: Set<Int> get() = reads.filter { it.ok }.flatMap { it.pids }.toSet()
}

/** One Mode 01 discovery pass. Read-only: the only commands sent are 01xx requests and the header commands above. */
internal class PidDiscovery(
    private val send: (String, Long) -> ElmResult,
    private val framing: Framing,
    private val reinit: (() -> Unit)? = null,
    /** Runs the header window with the adapter held, so no other command can see headers switched on. */
    private val exclusive: (() -> Unit) -> Unit = { it() },
    private val timeoutMs: Long = 6_000L
) {
    fun run(captureAddress: Boolean = true): DiscoveryRun {
        val walker = Mode01Walker(send)
        var first: BlockRead? = null
        var header: String? = null
        var restore: HeaderRestore? = null
        if (captureAddress) {
            val headers = AdapterHeaders(send, framing, reinit)
            exclusive {
                try {
                    headers.enableHeaders()
                    val r = walker.readBlock(0, timeoutMs)
                    first = r
                    header = headerOf(r)
                } finally {
                    restore = headers.restore()
                }
            }
        } else {
            first = walker.readBlock(0, timeoutMs)
        }
        val f = first!!
        val healthy = restore?.restored ?: true
        val reads = listOf(f) + if (healthy) walker.follow(f, timeoutMs) else emptyList()
        return DiscoveryRun(reads, header, ecuAddressOf(header), restore, healthy && reads.all { it.ok })
    }

    companion object {
        /** The bytes in front of the response when headers were on, or null when there were none. */
        fun headerOf(read: BlockRead): String? {
            if (!read.ok) return null
            val compact = ElmReply.stripBanners(read.raw)
            val idx = compact.indexOf("4100")
            return if (idx > 0) compact.substring(0, idx) else null
        }

        /** Responding ECU: the source byte of a K-line header (format, target, source), or the CAN id. */
        fun ecuAddressOf(header: String?): String? = when (header?.length) {
            null, 0 -> null
            6 -> header.substring(4)
            5 -> header.substring(0, 3)
            10 -> header.substring(6, 8)
            else -> header
        }
    }
}
