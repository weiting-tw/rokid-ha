package tw.rokidha.homeassistant.connection

sealed interface HaConnectionState {
    /** No URL/token stored. */
    data object NotConfigured : HaConnectionState

    /** Configured, but the user disconnected or the client is not started. */
    data object Stopped : HaConnectionState

    data class Connecting(val attempt: Int) : HaConnectionState

    data class Connected(val haVersion: String?, val sinceMs: Long) : HaConnectionState

    /** Temporary failure; will retry automatically at [nextRetryAtMs]. */
    data class Retrying(val reason: String, val attempt: Int, val nextRetryAtMs: Long) : HaConnectionState

    /** Token invalid, expired or revoked. No automatic retry: the user must enter a new token. */
    data class CredentialsRejected(val reason: String) : HaConnectionState

    val isConnected: Boolean get() = this is Connected
}
