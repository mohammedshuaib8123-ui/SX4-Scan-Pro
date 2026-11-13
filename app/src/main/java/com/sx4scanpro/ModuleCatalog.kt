package com.sx4scanpro

/** What is KNOWN ABOUT a module from documents, as opposed to what this app has observed (that is [ModuleStatusModel]). */
internal enum class ModuleCapability { FITMENT, ADDRESS_AND_PROTOCOL, DTC_READ, DATA_LIST }

internal enum class KnowledgeLevel {
    /** Mentioned in the global SX4 workshop manual. Not specific to the Indian 2012 diesel, and never a reachability claim. */
    DOCUMENTED_GLOBAL_MANUAL,
    /** No source has been consulted for this item. Nothing may be assumed. */
    UNVERIFIED
}

internal data class ModuleKnowledge(val module: ModuleId, val capability: ModuleCapability, val level: KnowledgeLevel, val source: String)

/**
 * Static, source-attributed knowledge for future multi-module work. It carries NO addresses, protocols, PIDs or commands, because none have
 * been verified for this car; those can only come from an owner-supplied capture. Every item needs vehicle confirmation, and nothing here
 * can raise a [ModuleStatus]: documented is not advertised, reachable, decoded or verified.
 */
internal object ModuleCatalog {
    private const val MANUAL = "Global SX4 workshop manual (BCM and P/S control module sections); not checked against the Indian 2012 ZDi"
    private const val NONE = "no source consulted"

    val entries: List<ModuleKnowledge> = listOf(
        // BCM: the manual describes DTC reading, a data list and an output test with the SUZUKI scan tool at the DLC.
        ModuleKnowledge(ModuleId.BCM, ModuleCapability.DTC_READ, KnowledgeLevel.DOCUMENTED_GLOBAL_MANUAL, MANUAL),
        ModuleKnowledge(ModuleId.BCM, ModuleCapability.DATA_LIST, KnowledgeLevel.DOCUMENTED_GLOBAL_MANUAL, MANUAL),
        ModuleKnowledge(ModuleId.BCM, ModuleCapability.FITMENT, KnowledgeLevel.UNVERIFIED, NONE),
        ModuleKnowledge(ModuleId.BCM, ModuleCapability.ADDRESS_AND_PROTOCOL, KnowledgeLevel.UNVERIFIED, NONE),
        // EPS: the manual lists a power steering control module with a communication check at the DLC. Steering type on this car is only listed as "Power".
        ModuleKnowledge(ModuleId.EPS, ModuleCapability.FITMENT, KnowledgeLevel.UNVERIFIED, "Manual lists the steering type only as \"Power\"; hydraulic or electric is not confirmed"),
        ModuleKnowledge(ModuleId.EPS, ModuleCapability.ADDRESS_AND_PROTOCOL, KnowledgeLevel.UNVERIFIED, NONE),
        ModuleKnowledge(ModuleId.EPS, ModuleCapability.DTC_READ, KnowledgeLevel.UNVERIFIED, NONE),
        ModuleKnowledge(ModuleId.EPS, ModuleCapability.DATA_LIST, KnowledgeLevel.UNVERIFIED, NONE)
    ) + listOf(ModuleId.ABS, ModuleId.CLUSTER, ModuleId.AIRBAG).flatMap { m ->
        ModuleCapability.values().map { ModuleKnowledge(m, it, KnowledgeLevel.UNVERIFIED, NONE) }
    }

    fun forModule(id: ModuleId): List<ModuleKnowledge> = entries.filter { it.module == id }

    /** One honest sentence for the screen. */
    fun summary(id: ModuleId): String {
        val documented = forModule(id).filter { it.level == KnowledgeLevel.DOCUMENTED_GLOBAL_MANUAL }
        return if (documented.isEmpty()) "No documentation has been checked for this module."
        else "The global workshop manual mentions " + documented.joinToString(" and ") { it.capability.name.lowercase().replace('_', ' ') } +
            " for it; not confirmed for this car. Address and protocol are unknown."
    }
}
