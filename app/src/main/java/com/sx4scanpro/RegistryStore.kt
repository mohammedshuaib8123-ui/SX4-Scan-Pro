package com.sx4scanpro

import java.io.File

internal data class RegistryLoad(val registry: ParameterRegistry, val issues: List<String>)

/**
 * Versioned JSON persistence for [ParameterRegistry]. Built-in definitions always come from the app (PidCatalog and the
 * Mode 09 decoders); the file holds capability states, evidence and any definitions the app did not ship. Only state and
 * evidence are stored: no sensor readings. Loading never throws and keeps every entry that is still valid.
 */
internal class RegistryStore(private val file: File) {
    companion object {
        const val SCHEMA = 1
        private val knownStates = ParamState.values().map { it.name }.toSet()
    }

    fun toJson(r: ParameterRegistry, savedAtMs: Long): String {
        val builtIn = ParameterRegistry.withBuiltInDefinitions().all().associateBy { it.id }
        val extra = r.all().filter { builtIn[it.id] != it }
        val recs = r.records().values.filter { it.state != ParamState.UNTESTED || it.evidence.isNotEmpty() }
        return MiniJson.write(linkedMapOf(
            "schema" to SCHEMA.toLong(), "savedAtMs" to savedAtMs,
            "definitions" to extra.map { d ->
                mapOf("id" to d.id, "service" to d.service, "identifier" to d.identifier, "name" to d.name, "description" to d.description,
                    "dataType" to d.dataType.name, "unit" to d.unit, "hasDecoder" to d.hasDecoder, "source" to d.source)
            },
            "records" to recs.map { c ->
                mapOf("id" to c.id, "state" to c.state.name, "lastVerifiedMs" to c.lastVerifiedMs, "lastChangeMs" to c.lastChangeMs,
                    "evidence" to c.evidence.map { e -> mapOf("tMs" to e.tMs, "kind" to e.kind.name, "note" to e.note, "rawSeq" to e.rawSeq) })
            }
        ))
    }

    /** Parses stored text. A bad entry is skipped and reported; a bad or newer file yields the built-in defaults and an issue. */
    fun fromJson(text: String): RegistryLoad {
        val reg = ParameterRegistry.withBuiltInDefinitions()
        val issues = mutableListOf<String>()
        val root = MiniJson.parse(text) as? Map<*, *> ?: return RegistryLoad(reg, listOf("stored registry is not valid JSON; started empty"))
        val schema = root["schema"] as? Long ?: return RegistryLoad(reg, listOf("stored registry has no schema version; started empty"))
        if (schema > SCHEMA) return RegistryLoad(reg, listOf("stored registry is from a newer app version (schema $schema); started empty and left the file untouched"))
        if (schema < 1) return RegistryLoad(reg, listOf("stored registry schema $schema is not supported; started empty"))
        (root["definitions"] as? List<*>)?.forEachIndexed { i, d ->
            val m = d as? Map<*, *>
            val def = m?.let {
                runCatching {
                    ParamDef(it["id"] as String, it["service"] as String, it["identifier"] as String, it["name"] as String, it["description"] as? String ?: "",
                        ParamDataType.valueOf(it["dataType"] as String), it["unit"] as? String ?: "", it["hasDecoder"] as? Boolean ?: false, it["source"] as? String ?: "")
                }.getOrNull()
            }
            if (def == null || !reg.register(def)) issues += "definition #$i skipped (invalid or conflicts with a built-in definition)"
        }
        (root["records"] as? List<*>)?.forEachIndexed { i, r ->
            val m = r as? Map<*, *>
            val rec = m?.let { runCatching { parseRecord(it) }.getOrNull() }
            if (rec == null || !reg.restore(rec)) issues += "record #$i skipped (invalid or unknown parameter)"
        }
        return RegistryLoad(reg, issues)
    }

    private fun parseRecord(m: Map<*, *>): ParamRecord {
        val state = m["state"] as String
        require(state in knownStates)
        val ev = (m["evidence"] as? List<*>).orEmpty().mapNotNull { e ->
            (e as? Map<*, *>)?.let { runCatching { Evidence(it["tMs"] as Long, EvidenceKind.valueOf(it["kind"] as String), it["note"] as? String ?: "", it["rawSeq"] as? Long) }.getOrNull() }
        }
        return ParamRecord(m["id"] as String, ParamState.valueOf(state), m["lastVerifiedMs"] as? Long, m["lastChangeMs"] as? Long, ev)
    }

    /** Loads from disk. Missing file: fresh built-in registry. Unreadable file: copied to `.corrupt` for inspection, then fresh. Never throws. */
    fun load(): RegistryLoad = try {
        if (!file.exists()) RegistryLoad(ParameterRegistry.withBuiltInDefinitions(), emptyList())
        else {
            val text = file.readText()
            val res = fromJson(text)
            if (res.issues.any { it.startsWith("stored registry") }) runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
            res
        }
    } catch (e: Exception) {
        RegistryLoad(ParameterRegistry.withBuiltInDefinitions(), listOf("could not read stored registry (${e.javaClass.simpleName}); started empty"))
    }

    /** Atomic save: write a temporary file, then rename over the old one. Returns false (and keeps the old file) on failure. */
    fun save(r: ParameterRegistry, nowMs: Long = System.currentTimeMillis()): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(r, nowMs))
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        true
    }.getOrDefault(false)
}
