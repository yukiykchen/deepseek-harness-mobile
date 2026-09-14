package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshSolarClockTest {
    /** 2026-06-21, the northern solstice, 00:00 UTC. */
    private val solsticeJune = 1_782_000_000_000L

    /** 2026-12-21, the southern solstice, 00:00 UTC. */
    private val solsticeDecember = 1_797_811_200_000L

    private fun atLocalHour(dayStartUtc: Long, hour: Int, offsetMinutes: Int): Long =
        dayStartUtc + hour * 3_600_000L - offsetMinutes * 60_000L

    @Test
    fun middayIsDayAndMidnightIsNight() {
        listOf(solsticeJune, solsticeDecember).forEach { day ->
            assertFalse(DshSolarClock.isNight(atLocalHour(day, 12, 0), 0), "noon must be day")
            assertTrue(DshSolarClock.isNight(atLocalHour(day, 0, 0), 0), "midnight must be night")
        }
    }

    /** The whole point of the bonus: the boundary moves with the season. */
    @Test
    fun theEveningBoundaryMovesWithTheSeason() {
        // Sunset at 35°N runs from about 19:10 at the June solstice to about 16:50 at the
        // December one, so 19:00 falls on opposite sides of dusk in the two seasons.
        assertFalse(DshSolarClock.isNight(atLocalHour(solsticeJune, 19, 0), 0), "19:00 in midsummer is daylight")
        assertTrue(DshSolarClock.isNight(atLocalHour(solsticeDecember, 19, 0), 0), "19:00 in midwinter is night")
    }

    @Test
    fun theOffsetShiftsTheBoundaryWithTheUser() {
        // The same instant is 13:00 for a user at +60 and 01:00 for one at -660.
        val instant = atLocalHour(solsticeJune, 13, 60)
        assertFalse(DshSolarClock.isNight(instant, 60))
        assertTrue(DshSolarClock.isNight(instant, -660))
    }

    @Test
    fun theSouthernHemisphereIsInverted() {
        val southern = -35.0
        assertTrue(DshSolarClock.isNight(atLocalHour(solsticeJune, 19, 0), 0, southern))
        assertFalse(DshSolarClock.isNight(atLocalHour(solsticeDecember, 19, 0), 0, southern))
    }

    @Test
    fun polarDayAndPolarNightDoNotThrow() {
        assertFalse(DshSolarClock.isNight(atLocalHour(solsticeJune, 23, 0), 0, 80.0), "polar day")
        assertTrue(DshSolarClock.isNight(atLocalHour(solsticeDecember, 12, 0), 0, 80.0), "polar night")
    }

    @Test
    fun theDefaultLatitudeIsTheDocumentedOne() {
        assertEquals(35.0, DshSolarClock.DEFAULT_LATITUDE)
    }
}
