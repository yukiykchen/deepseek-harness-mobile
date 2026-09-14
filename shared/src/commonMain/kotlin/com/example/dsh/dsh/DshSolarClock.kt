package com.example.dsh.dsh

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether it is after sunset for the device's clock, so the app can follow daylight
 * without asking for a location permission.
 *
 * The app has no location source, so latitude is a constant ([DEFAULT_LATITUDE]) and
 * local solar noon is taken to be clock noon: the device's UTC offset already places
 * the user within roughly half an hour of their true meridian, and the equation of time
 * adds at most a quarter hour on top. That is far inside the tolerance of "switch the
 * theme around dusk", and the Appearance modal says so rather than implying GPS
 * precision. The seasonal swing is real — the sunset hour moves by about two hours
 * between solstices — which is the part of the behaviour worth having.
 */
internal object DshSolarClock {

    /** A mid-northern latitude, chosen so the seasonal swing is representative. */
    const val DEFAULT_LATITUDE = 35.0

    /** Sun centre at −0.833°, the civil definition of sunrise and sunset. */
    private const val SUNRISE_ZENITH_DEGREES = 90.833

    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_MINUTE = 60_000L

    fun isNight(
        epochMillis: Long,
        utcOffsetMinutes: Int,
        latitudeDeg: Double = DEFAULT_LATITUDE,
    ): Boolean {
        val localMillis = epochMillis + utcOffsetMinutes * MS_PER_MINUTE
        val days = floorDiv(localMillis, MS_PER_DAY)
        val hour = (localMillis - days * MS_PER_DAY).toDouble() / 3_600_000.0
        val daylight = daylightHalfLength(dayOfYear(days), latitudeDeg)
            ?: return latitudeDeg >= 0.0 == isNorthernWinter(dayOfYear(days))
        return hour < 12.0 - daylight || hour >= 12.0 + daylight
    }

    /**
     * Half the length of the day in hours, or null inside a polar day or night where the
     * sun does not cross the horizon at all.
     */
    private fun daylightHalfLength(dayOfYear: Int, latitudeDeg: Double): Double? {
        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)
        val declination = 0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.001480 * sin(3 * gamma)
        val latitude = latitudeDeg * PI / 180.0
        val cosHourAngle = (cos(SUNRISE_ZENITH_DEGREES * PI / 180.0) - sin(latitude) * sin(declination)) /
            (cos(latitude) * cos(declination))
        if (cosHourAngle >= 1.0 || cosHourAngle <= -1.0) return null
        return acos(cosHourAngle) * 12.0 / PI
    }

    private fun isNorthernWinter(dayOfYear: Int): Boolean = dayOfYear < 80 || dayOfYear > 266

    /**
     * Day of the year from days since the epoch. This is Hinnant's civil-from-days with
     * only the day-of-year kept; the declination it feeds is smooth enough that the leap
     * -year offset does not matter.
     */
    private fun dayOfYear(days: Long): Int {
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        // Days since 1 March, which is day 60 of a common year.
        val sinceMarch = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        return ((sinceMarch + 59) % 365 + 1).toInt()
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val q = value / divisor
        return if (value % divisor != 0L && (value xor divisor) < 0) q - 1 else q
    }
}
