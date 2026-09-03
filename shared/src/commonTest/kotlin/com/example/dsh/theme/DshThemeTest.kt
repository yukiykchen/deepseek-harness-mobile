package com.example.dsh.theme

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshThemeTest {
    @BeforeTest
    fun reset() = DshTheme.resetForTest()

    @Test
    fun bootstrapAppliesStoredPreferenceAndSystemState() {
        assertTrue(DshTheme.bootstrap("dark", systemDark = false))
        assertEquals(DshThemePreference.DARK, DshTheme.snapshot.preference)
        assertTrue(DshTheme.snapshot.isDark)
        assertEquals(1, DshTheme.snapshot.revision)
    }

    @Test
    fun bootstrapWithInvalidValueFallsBackToSystem() {
        DshTheme.bootstrap("blue", systemDark = true)
        assertEquals(DshThemePreference.SYSTEM, DshTheme.snapshot.preference)
        assertTrue(DshTheme.snapshot.isDark)
    }

    @Test
    fun repeatedBootstrapWithSameValuesDoesNotBumpRevision() {
        DshTheme.bootstrap("system", systemDark = false)
        val before = DshTheme.snapshot.revision
        assertFalse(DshTheme.bootstrap("system", systemDark = false))
        assertEquals(before, DshTheme.snapshot.revision)
    }

    @Test
    fun systemChangeDoesNotAffectLockedPreference() {
        DshTheme.setPreference(DshThemePreference.LIGHT)
        val before = DshTheme.snapshot.revision
        assertFalse(DshTheme.updateSystemDark(true))
        assertEquals(before, DshTheme.snapshot.revision)
        assertFalse(DshTheme.snapshot.isDark)
        assertEquals(DshThemePreference.LIGHT, DshTheme.snapshot.preference)
    }

    @Test
    fun systemChangeUpdatesSystemPreference() {
        DshTheme.setPreference(DshThemePreference.SYSTEM)
        val before = DshTheme.snapshot.revision
        assertTrue(DshTheme.updateSystemDark(true))
        assertEquals(before + 1, DshTheme.snapshot.revision)
        assertTrue(DshTheme.snapshot.isDark)
    }

    @Test
    fun sameSystemValueDoesNotBroadcastTwice() {
        DshTheme.setPreference(DshThemePreference.SYSTEM)
        assertTrue(DshTheme.updateSystemDark(true))
        val before = DshTheme.snapshot.revision
        assertFalse(DshTheme.updateSystemDark(true))
        assertEquals(before, DshTheme.snapshot.revision)
    }

    @Test
    fun switchingBackToSystemUsesLatestSystemState() {
        DshTheme.bootstrap("light", systemDark = false)
        DshTheme.updateSystemDark(true)
        assertFalse(DshTheme.snapshot.isDark)
        assertTrue(DshTheme.setPreference(DshThemePreference.SYSTEM))
        assertTrue(DshTheme.snapshot.isDark)
    }

    @Test
    fun snapshotTokensMatchResolvedScheme() {
        DshTheme.setPreference(DshThemePreference.DARK)
        assertEquals(DshThemeTokens.DARK, DshTheme.snapshot.tokens)
        assertEquals(DshCodeColors.DARK, DshTheme.snapshot.codeColors)
        DshTheme.setPreference(DshThemePreference.LIGHT)
        assertEquals(DshThemeTokens.LIGHT, DshTheme.snapshot.tokens)
        assertEquals(DshCodeColors.LIGHT, DshTheme.snapshot.codeColors)
    }
}
