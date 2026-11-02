package tw.rokidha.presentation.interaction

import tw.rokidha.domain.glasses.GlassesInput

/**
 * Drops inputs that must not trigger anything:
 * - the same event delivered twice (same source + sequence);
 * - a second event of the same kind from the same source within [debounceMs] (bounce, or one
 *   physical press reported through two SDK callbacks);
 * - events older than [maxAgeMs] when processed (e.g. buffered while the app was frozen or the
 *   link was reconnecting) — a stale input never executes anything.
 */
class InputGate(
    private val clock: () -> Long,
    private val debounceMs: Long = 400,
    private val maxAgeMs: Long = 2_000,
) {
    sealed interface Verdict {
        data object Accept : Verdict
        data class Drop(val reason: String) : Verdict
    }

    private val lastSequence = HashMap<GlassesInput.Source, Long>()
    private val lastAccepted = HashMap<Pair<GlassesInput.Source, GlassesInput.Kind>, Long>()

    @Synchronized
    fun check(e: GlassesInput): Verdict {
        val now = clock()
        if (now - e.receivedAtMs > maxAgeMs) return Verdict.Drop("stale input (${now - e.receivedAtMs} ms old)")
        val prevSeq = lastSequence[e.source]
        if (prevSeq != null && e.sequence <= prevSeq) return Verdict.Drop("duplicate delivery #${e.sequence}")
        lastSequence[e.source] = e.sequence
        val key = e.source to e.kind
        val prev = lastAccepted[key]
        if (prev != null && e.receivedAtMs - prev < debounceMs) return Verdict.Drop("debounced (${e.receivedAtMs - prev} ms)")
        lastAccepted[key] = e.receivedAtMs
        return Verdict.Accept
    }

    @Synchronized
    fun reset() {
        lastAccepted.clear()
    }
}
