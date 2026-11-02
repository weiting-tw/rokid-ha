package tw.rokidha.presentation.dashboard

import tw.rokidha.homeassistant.model.HaEntity

/** Short, glanceable value text for one entity (glasses display is small and monochrome). */
object EntityFormatter {
    private val OPENING = setOf("door", "window", "opening", "garage_door")

    fun value(e: HaEntity): String {
        if (e.isUnavailable) return "UNAVAILABLE"
        if (e.isUnknown) return "UNKNOWN"
        return when (e.domain) {
            "sensor" -> listOfNotNull(e.state, e.attr("unit_of_measurement")).joinToString(" ")
            "binary_sensor" -> when (e.attr("device_class")) {
                in OPENING -> if (e.state == "on") "OPEN" else "CLOSED"
                "motion", "occupancy", "presence" -> if (e.state == "on") "DETECTED" else "CLEAR"
                "moisture" -> if (e.state == "on") "WET" else "DRY"
                "smoke", "gas", "safety", "problem" -> if (e.state == "on") "ALERT" else "OK"
                else -> e.state.uppercase()
            }
            "climate" -> listOfNotNull(e.state.uppercase(), e.attr("temperature")?.let { "$it°" }).joinToString(" ")
            "script" -> if (e.state == "on") "RUNNING" else "READY"
            "scene", "button" -> "READY"
            else -> e.state.uppercase()
        }
    }

    fun line(e: HaEntity, maxName: Int = 16): String {
        val name = e.friendlyName.let { if (it.length > maxName) it.take(maxName - 1) + "…" else it }
        return "$name: ${value(e)}"
    }
}
