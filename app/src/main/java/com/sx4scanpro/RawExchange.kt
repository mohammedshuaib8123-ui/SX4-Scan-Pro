package com.sx4scanpro

import java.io.File

/**
 * One adapter transaction exactly as it happened. [rawText] is the reply as received (CR/LF and banners kept, the final
 * `>` prompt excluded), never trimmed or compacted, so undecoded responses are kept without loss.
 * [ecuAddress] stays null until headers are captured during a discovery run.
 */
internal data class RawExchange(
    val seq: Long, val session: String, val tMs: Long, val command: String, val rawText: String, val status: String,
    val rttMs: Long, val protocol: String, val ecuAddress: String? = null, val error: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "seq" to seq, "session" to session, "tMs" to tMs, "command" to command, "raw" to rawText, "status" to status,
        "rttMs" to rttMs, "protocol" to protocol, "ecu" to ecuAddress, "error" to error
    )

    companion object {
        const val STATUS_TIMEOUT = "TIMEOUT"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_BLOCKED = "BLOCKED"

        /** Status for a completed reply: the adapter-level classification of what came back. */
        fun statusOf(raw: String): String = ElmReply.classify(raw).name

        fun fromMap(m: Map<*, *>): RawExchange? {
            return RawExchange(
                seq = m["seq"] as? Long ?: return null, session = m["session"] as? String ?: "", tMs = m["tMs"] as? Long ?: return null,
                command = m["command"] as? String ?: return null, rawText = m["raw"] as? String ?: "", status = m["status"] as? String ?: return null,
                rttMs = m["rttMs"] as? Long ?: 0L, protocol = m["protocol"] as? String ?: "", ecuAddress = m["ecu"] as? String, error = m["error"] as? String
            )
        }
    }
}

/**
 * Bounded append-only capture (one JSON object per line) in app storage. Rotates by size and keeps the newest [keep] files.
 * Writing never throws, so capture cannot break a drive; a line that cannot be parsed back is skipped, not repaired.
 */
internal class CaptureStore(private val dir: File, private val maxBytes: Long = 1_500_000L, private val keep: Int = 4) {
    private val name = "capture"
    private var seq = -1L

    private fun current() = File(dir, "$name.jsonl")

    @Synchronized
    private fun nextSeq(): Long {
        if (seq < 0) seq = (readAll().maxOfOrNull { it.seq } ?: 0L)
        return ++seq
    }

    /** Records one exchange and returns it with its sequence number, or null if it could not be written. */
    @Synchronized
    fun record(session: String, tMs: Long, command: String, rawText: String, status: String, rttMs: Long, protocol: String, ecu: String? = null, error: String? = null): RawExchange? =
        runCatching {
            dir.mkdirs()
            if (current().exists() && current().length() >= maxBytes) rotate()
            val ex = RawExchange(nextSeq(), session, tMs, command, rawText, status, rttMs, protocol, ecu, error)
            current().appendText(MiniJson.write(ex.toMap(), -1) + "\n")
            ex
        }.getOrNull()

    private fun rotate() {
        File(dir, "$name.${keep - 1}.jsonl").delete()
        for (i in keep - 2 downTo 1) File(dir, "$name.$i.jsonl").renameTo(File(dir, "$name.${i + 1}.jsonl"))
        current().renameTo(File(dir, "$name.1.jsonl"))
    }

    /** Everything kept, oldest first. */
    @Synchronized
    fun readAll(): List<RawExchange> = runCatching {
        val files = (keep - 1 downTo 1).map { File(dir, "$name.$it.jsonl") } + current()
        files.filter { it.exists() }.flatMap { f -> f.readLines().mapNotNull { (MiniJson.parse(it) as? Map<*, *>)?.let(RawExchange::fromMap) } }
    }.getOrDefault(emptyList())

    /** The kept capture files that exist, oldest first (for export; read-only). */
    @Synchronized
    fun files(): List<File> = ((keep - 1 downTo 1).map { File(dir, "$name.$it.jsonl") } + current()).filter { it.exists() }

    @Synchronized
    fun clear() { runCatching { dir.listFiles()?.forEach { it.delete() } }; seq = -1L }
}
