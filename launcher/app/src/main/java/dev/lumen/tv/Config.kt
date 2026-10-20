package dev.lumen.tv

import dev.lumen.tv.data.BusStop
import dev.lumen.tv.data.MetroStation
import dev.lumen.tv.data.Team

/** User-tweakable settings. Will move to a settings screen / phone remote later. */
object Config {
    const val CITY = "Valencia"
    const val LATITUDE = 39.4699
    const val LONGITUDE = -0.3763

    /** AEMET warning zone (as named in the MeteoAlarm feed). Valencia city is in the north coast zone. */
    const val ALERT_AREA = "Litoral norte de Valencia"

    /**
     * Metrovalencia stations (ids from metrovalencia.es). The first one is shown on the home screen.
     *
     * `walk` is the minutes from the front door to the platform, measured door to entrance on foot
     * at 4,5 km/h plus a minute and a half for stairs and corridors. Trains leaving sooner than
     * that are hidden, which is the whole point: the near station is around the corner and the far one is a
     * different kind of trip, and a dashboard that shows both as "2 min" is lying about one of them.
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

    /** Teams on the screensaver: TheSportsDB id for fixtures/results, ESPN id for live scores where ESPN covers it. */
    val TEAMS = listOf(
        Team("Valencia CF", sportsDbId = 133725, espnPath = "soccer/esp.1", espnId = 94),
        Team("Levante UD", sportsDbId = 133732, espnPath = "soccer/esp.1", espnId = 1538),
        Team("Valencia Basket", sportsDbId = 135097, espnPath = "basketball/euroleague", espnId = 126418),
        Team("Valencia Basket Fem.", sportsDbId = 144026),
    )

    /** Night mode for the screensaver, from NIGHT_START to NIGHT_END (hours, local time). */
    const val NIGHT_START = 0
    const val NIGHT_END = 7

    /** Apps in the top row, in this order: package names, or "package/Activity" for Lumen's own apps. */
    val FAVORITES = listOf(
        "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream",
        "com.google.android.youtube.tv",
        "com.stremio.one",
        "com.spotify.tv.android",
        "dev.lumen.tv/dev.lumen.tv.radio.RadioActivity",
    )

    /** Other apps that go with Lumen's own in "Todas las apps", right after them. */
    val HOUSE_APPS = listOf(
        "com.ionitech.airscreen", // AirPlay receiver: mirroring from iPhones (Android phones use Cast)
    )
}
