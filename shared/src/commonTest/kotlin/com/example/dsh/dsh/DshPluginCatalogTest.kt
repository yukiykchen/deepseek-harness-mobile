package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshPluginCatalogTest {

    private fun entry(
        entryId: String = "tool-fs",
        moduleName: String = "@deepseek-ai/dsh-tool-fs",
        enabled: Boolean = true,
        fiberPhase: String? = "active",
    ) = DshPluginEntry(entryId, moduleName, enabled, fiberPhase)

    /** The mock Host's `fixtures/plugin-inventory.json` shape, one row per status. */
    private val rows = listOf(
        entry(),
        entry("tool-bash", "@deepseek-ai/dsh-tool-bash"),
        entry("mcp-memory", "@deepseek-ai/dsh-mcp-client", fiberPhase = "failed"),
        entry("lsp", "@deepseek-ai/dsh-lsp", enabled = false, fiberPhase = null),
        entry("session-title-llm", "@deepseek-ai/dsh-session-title-llm", fiberPhase = "loading"),
        entry("telemetry-otel", "@deepseek-ai/dsh-session-telemetry-otel", fiberPhase = "pending"),
        entry("compaction-basic", "@deepseek-ai/dsh-compaction-basic", fiberPhase = "unloading"),
    )

    @Test
    fun statusCollapsesEnablementAndPhase() {
        assertEquals(DshPluginStatus.ACTIVE, DshPluginCatalog.status(entry()))
        assertEquals(DshPluginStatus.LOADING, DshPluginCatalog.status(entry(fiberPhase = "loading")))
        assertEquals(DshPluginStatus.PENDING, DshPluginCatalog.status(entry(fiberPhase = "pending")))
        assertEquals(DshPluginStatus.UNLOADING, DshPluginCatalog.status(entry(fiberPhase = "unloading")))
        // No live root fiber, but the Loader still has it enabled.
        assertEquals(DshPluginStatus.IDLE, DshPluginCatalog.status(entry(fiberPhase = null)))
        assertEquals(DshPluginStatus.DISABLED, DshPluginCatalog.status(entry(enabled = false, fiberPhase = null)))
        // A failure outranks enablement, the way the Host's own client reads it.
        assertEquals(
            DshPluginStatus.FAILED,
            DshPluginCatalog.status(entry(enabled = false, fiberPhase = "failed")),
        )
    }

    @Test
    fun shortModuleNameDropsScopeAndHostPrefixes() {
        assertEquals("tool-fs", DshPluginCatalog.shortModuleName("@deepseek-ai/dsh-tool-fs"))
        assertEquals("connection", DshPluginCatalog.shortModuleName("@deepseek-ai/dsh-client-connection"))
        assertEquals("plugin-inventory", DshPluginCatalog.shortModuleName("@deepseek-ai/dsh-host-plugin-inventory"))
        assertEquals("scan-remote", DshPluginCatalog.shortModuleName("@yukiykchen/dsh-scan-remote"))
        assertEquals("loader", DshPluginCatalog.shortModuleName("cordis-plugin-loader"))
        assertEquals("local", DshPluginCatalog.shortModuleName("cordis:local"))
    }

    @Test
    fun searchCoversModuleAndEntryIdCaseInsensitively() {
        assertTrue(DshPluginCatalog.matches(entry(), ""))
        assertTrue(DshPluginCatalog.matches(entry(), "  "))
        assertTrue(DshPluginCatalog.matches(entry(), "TOOL-FS"))
        assertTrue(DshPluginCatalog.matches(entry("mcp-memory", "@deepseek-ai/dsh-mcp-client"), "memory"))
        assertTrue(DshPluginCatalog.matches(entry("mcp-memory", "@deepseek-ai/dsh-mcp-client"), "mcp-client"))
        assertFalse(DshPluginCatalog.matches(entry(), "lsp"))
    }

    @Test
    fun visibleListsFailuresFirstAndHonoursBothFilters() {
        val all = DshPluginCatalog.visible(rows, "", null)
        assertEquals(rows.size, all.size)
        assertEquals("mcp-memory", all.first().entryId)

        val failedOnly = DshPluginCatalog.visible(rows, "", DshPluginStatus.FAILED)
        assertEquals(listOf("mcp-memory"), failedOnly.map { it.entryId })

        val searched = DshPluginCatalog.visible(rows, "tool", null)
        assertEquals(listOf("tool-fs", "tool-bash"), searched.map { it.entryId })

        // Search and status intersect rather than replace one another.
        assertTrue(DshPluginCatalog.visible(rows, "tool", DshPluginStatus.FAILED).isEmpty())
    }

    @Test
    fun statusChipsCoverOnlyStatusesPresent() {
        val chips = DshPluginCatalog.statusChips(rows)
        assertEquals(null, chips.first().status)
        assertEquals(rows.size, chips.first().count)
        assertEquals(
            listOf(
                DshPluginStatus.FAILED,
                DshPluginStatus.ACTIVE,
                DshPluginStatus.LOADING,
                DshPluginStatus.UNLOADING,
                DshPluginStatus.PENDING,
                DshPluginStatus.DISABLED,
            ),
            chips.drop(1).map { it.status },
        )
        assertEquals(2, chips.first { it.status == DshPluginStatus.ACTIVE }.count)
        assertTrue(chips.none { it.status == DshPluginStatus.IDLE })
    }

    @Test
    fun configurationSummaryNamesThePresetsThatProvideADisabledModule() {
        val inventory = DshPluginInventory(
            entries = rows,
            agentPresets = listOf(
                DshAgentPresetPlugins(
                    id = "default",
                    trust = "system",
                    name = "Default",
                    isDefault = true,
                    broken = null,
                    rows = listOf(
                        DshAgentPresetPluginRow("lsp", "@deepseek-ai/dsh-lsp", "true", null, "active"),
                        DshAgentPresetPluginRow(null, "@deepseek-ai/dsh-tool-web", "conditional", "!process.env.NO_WEB", null),
                    ),
                ),
                DshAgentPresetPlugins(
                    id = "reviewer",
                    trust = "user",
                    name = null,
                    isDefault = false,
                    broken = "presets/reviewer.yml: unknown plugin @acme/review-tools",
                    rows = emptyList(),
                ),
            ),
        )
        val byModule = DshPluginCatalog.presetsEnablingModule(inventory)
        assertEquals(listOf("Default"), byModule["@deepseek-ai/dsh-lsp"])
        // A conditional row is not an enablement the Host can confirm.
        assertTrue(byModule["@deepseek-ai/dsh-tool-web"] == null)

        val disabled = entry("lsp", "@deepseek-ai/dsh-lsp", enabled = false, fiberPhase = null)
        assertEquals(
            "Disabled globally; provided per session by Default",
            DshPluginCatalog.configurationSummary(disabled, listOf("Default")),
        )
        assertEquals(
            "Disabled in the Host's plugin loader",
            DshPluginCatalog.configurationSummary(disabled, emptyList()),
        )
        assertEquals(
            "Enabled in the Host's plugin loader",
            DshPluginCatalog.configurationSummary(entry(), emptyList()),
        )

        // A preset with no display name falls back to its id, and its `broken` text is
        // the only failure message the inventory carries.
        assertEquals(
            listOf("reviewer: presets/reviewer.yml: unknown plugin @acme/review-tools"),
            DshPluginCatalog.brokenPresets(inventory),
        )
    }

    @Test
    fun failureSummaryNamesTheModuleAndEntryTheHostCouldNotStart() {
        val failed = entry("mcp-memory", "@deepseek-ai/dsh-mcp-client", fiberPhase = "failed")
        val summary = DshPluginCatalog.failureSummary(failed)
        assertTrue(summary.contains("@deepseek-ai/dsh-mcp-client"))
        assertTrue(summary.contains("mcp-memory"))
        assertTrue(summary.contains("failed"))
        // The inventory has no message field, so the UI has to say so.
        assertTrue(DshPluginCatalog.FAILURE_NOTE.contains("no failure message"))
    }

    @Test
    fun requestFailuresReadAsPlainEnglish() {
        assertEquals("The Host did not answer.", dshPluginErrorMessage(null))
        assertTrue(dshPluginErrorMessage(DshRpcError("transport-500", "boom")).contains("transport-500"))
        assertTrue(
            dshPluginErrorMessage(DshRpcError("generation-cancelled", "x")).contains("Reconnect"),
        )
        assertEquals("nope", dshPluginErrorMessage(DshRpcError("pluginInventory/denied", "nope")))
        assertEquals(
            "pluginInventory/denied",
            dshPluginErrorMessage(DshRpcError("pluginInventory/denied", "")),
        )
    }
}
