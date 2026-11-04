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
     * Metrovalencia stations and EMT bus stops. They live in [HomeStops], outside this file, because
     * together with the walking times they say where the TV is. The real ones go in
     * `src/stops-local/` (gitignored); without that folder the build uses `src/stops-example/`.
     */
    val METRO_STATIONS = HomeStops.METRO_STATIONS
    val BUS_STOPS = HomeStops.BUS_STOPS

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
