package tw.rokidha.homeassistant.connection

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff with ±[jitter] randomisation: 1 s, 2 s, 4 s … capped at [maxMs]. */
class Backoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val factor: Double = 2.0,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** [attempt] starts at 1 for the first retry. */
    fun delayFor(attempt: Int): Long {
        val base = min(maxMs.toDouble(), initialMs * factor.pow((attempt - 1).coerceAtLeast(0)))
        val spread = base * jitter
        return (base + random.nextDouble(-spread, spread + 1e-9)).toLong().coerceIn(0, maxMs)
    }
}
