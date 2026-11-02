package tw.rokidha.homeassistant.config

/**
 * Home Assistant Long-Lived Access Token.
 *
 * [toString] is redacted so the token cannot leak through logs, string templates,
 * data-class `toString()` or crash messages. Read [value] only where it is sent to HA.
 */
class AccessToken private constructor(val value: String) {

    override fun toString(): String = "AccessToken(<redacted>)"
    override fun equals(other: Any?): Boolean = other is AccessToken && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Accepts what users typically paste: surrounding whitespace and an optional
         * "Bearer " prefix are removed. Returns null for blank or whitespace-containing input.
         */
        fun parse(input: String): AccessToken? {
            val t = input.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            return AccessToken(t)
        }
    }
}
