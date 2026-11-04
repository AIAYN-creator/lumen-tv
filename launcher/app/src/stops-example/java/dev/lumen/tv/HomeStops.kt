package dev.lumen.tv

import dev.lumen.tv.data.BusStop
import dev.lumen.tv.data.MetroStation

/** Example stops, so the project builds out of the box. Copy this file to src/stops-local/ and put yours in. Read through [Config.METRO_STATIONS] and [Config.BUS_STOPS]. */
object HomeStops {
    /**
     * Metrovalencia stations (ids from metrovalencia.es). The first one is shown on the home screen.
     *
     * `walk` is the minutes from the front door to the platform, measured door to entrance on foot
     * at 4,5 km/h plus a minute and a half for stairs and corridors. Trains leaving sooner than
     * that are hidden, which is the whole point: a station around the corner and one across the
     * neighbourhood are different trips, and a dashboard that shows both as "2 min" is lying about one.
     */
    val METRO_STATIONS = listOf(
        MetroStation(1, "Xàtiva", walk = 6),
        MetroStation(2, "Colón", walk = 14),
    )

    /**
     * EMT bus stops (stop numbers as printed at the stop), each filtered to one line.
     *
     * Their `walk` stays 0, so nothing is filtered: the EMT feed only ever lists one or two buses
     * of a line, so hiding the imminent one would usually leave an empty card instead of a useful
     * one. The metro feed looks seven or eight trains ahead, which is what makes filtering work there.
     */
    val BUS_STOPS = listOf(
        BusStop(line = "4", stopId = 1, name = "Plaza del Ayuntamiento"),
        BusStop(line = "7", stopId = 2, name = "Plaza de la Reina"),
    )
}
