package com.example.dsh.dsh

/**
 * How one inventory row reads to a user. `pluginInventory/list` reports Loader
 * enablement and the root-fiber phase as two independent facts; this collapses them
 * into the single status the badges and the status filter share. A failure outranks
 * everything else, and a disabled entry has no meaningful phase.
 */
internal enum class DshPluginStatus(val label: String) {
    FAILED("Failed to start"),
    ACTIVE("Running"),
    LOADING("Loading"),
    UNLOADING("Unloading"),
    PENDING("Waiting for dependencies"),
    IDLE("Not running"),
    DISABLED("Disabled"),
}

/** One status filter chip; a null [status] is the unfiltered chip. */
internal data class DshPluginStatusChip(
    val status: DshPluginStatus?,
    val label: String,
    val count: Int,
)

/**
 * Read-only view logic over `pluginInventory/list`. The Host exposes no mutation
 * capability, so nothing here writes: it only decides what one row says.
 */
internal object DshPluginCatalog {
    /**
     * The inventory carries a phase and no failure text — upstream
     * `PluginInventoryEntry` has no message field — so a failed row is explained by
     * naming what the Host could not start.
     */
    const val FAILURE_NOTE: String =
        "The Host's plugin inventory is read-only and reports the lifecycle phase only, " +
            "with no failure message. The cause is in the Host's own log."

    fun status(entry: DshPluginEntry): DshPluginStatus = when {
        entry.fiberPhase == "failed" -> DshPluginStatus.FAILED
        !entry.enabled -> DshPluginStatus.DISABLED
        entry.fiberPhase == "active" -> DshPluginStatus.ACTIVE
        entry.fiberPhase == "loading" -> DshPluginStatus.LOADING
        entry.fiberPhase == "unloading" -> DshPluginStatus.UNLOADING
        entry.fiberPhase == "pending" -> DshPluginStatus.PENDING
        else -> DshPluginStatus.IDLE
    }

    /** Row title: the module specifier without its scope and Host prefixes. */
    fun shortModuleName(moduleName: String): String {
        val unscoped = if (moduleName.startsWith("@")) moduleName.substringAfter('/') else moduleName
        val trimmed = unscoped.removePrefix("cordis:").removePrefix("cordis-plugin-")
        return if (trimmed.startsWith("dsh-")) {
            trimmed.removePrefix("dsh-").removePrefix("host-").removePrefix("client-")
        } else {
            trimmed
        }
    }

    /** Search covers the full module specifier and the Loader entry id, not the short title. */
    fun matches(entry: DshPluginEntry, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return entry.moduleName.lowercase().contains(needle) || entry.entryId.lowercase().contains(needle)
    }

    /** Rows the list shows: failures first, otherwise the Loader's own order. */
    fun visible(rows: List<DshPluginEntry>, query: String, status: DshPluginStatus?): List<DshPluginEntry> {
        val kept = rows.filter { (status == null || status(it) == status) && matches(it, query) }
        return kept.filter { status(it) == DshPluginStatus.FAILED } +
            kept.filter { status(it) != DshPluginStatus.FAILED }
    }

    /** One chip per status the inventory actually contains, so no chip filters to nothing. */
    fun statusChips(rows: List<DshPluginEntry>): List<DshPluginStatusChip> =
        listOf(DshPluginStatusChip(null, "All", rows.size)) +
            DshPluginStatus.values().mapNotNull { status ->
                val count = rows.count { status(it) == status }
                if (count == 0) null else DshPluginStatusChip(status, status.label, count)
            }

    fun presetName(preset: DshAgentPresetPlugins): String = preset.name?.ifEmpty { null } ?: preset.id

    /**
     * Agent presets that compose a module, by module name. Presets — not the Loader's
     * own entries — are where a deployment mounts its model-facing plugins, so a
     * globally disabled module can still be live for a session.
     */
    fun presetsEnablingModule(inventory: DshPluginInventory): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        inventory.agentPresets.orEmpty().forEach { preset ->
            val name = presetName(preset)
            preset.rows.forEach { row ->
                if (row.enabled != "true") return@forEach
                val names = found.getOrPut(row.moduleName) { mutableListOf() }
                if (name !in names) names += name
            }
        }
        return found
    }

    /** Preset compositions the Host could not read — the only failure text the inventory carries. */
    fun brokenPresets(inventory: DshPluginInventory): List<String> =
        inventory.agentPresets.orEmpty().mapNotNull { preset ->
            preset.broken?.let { "${presetName(preset)}: $it" }
        }

    /** Requirement item 4: what the Host's configuration says about this row. */
    fun configurationSummary(entry: DshPluginEntry, presets: List<String>): String = when {
        entry.enabled -> "Enabled in the Host's plugin loader"
        presets.isNotEmpty() -> "Disabled globally; provided per session by ${presets.joinToString(", ")}"
        else -> "Disabled in the Host's plugin loader"
    }

    /** Requirement item 5: the readable summary for a failed row. */
    fun failureSummary(entry: DshPluginEntry): String =
        "The Host loaded module ${entry.moduleName} for entry ${entry.entryId}, " +
            "but its root fiber ended in phase \"failed\", so the plugin is not serving."
}

/** Readable copy for a failed `pluginInventory/list` request (Task 5 criterion 4). */
internal fun dshPluginErrorMessage(error: DshRpcError?): String = when {
    error == null -> "The Host did not answer."
    error.code == "cancelled" || error.code == "generation-cancelled" ->
        "The connection to the Host dropped before the plugin list arrived. Reconnect and try again."
    error.code.startsWith("transport-") -> "The Host could not be reached (${error.code})."
    error.code == "bad-response" -> "The Host answered the plugin list in a shape this app does not understand."
    else -> error.message.ifEmpty { error.code }
}
