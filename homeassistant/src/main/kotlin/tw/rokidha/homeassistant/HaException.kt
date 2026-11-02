package tw.rokidha.homeassistant

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Errors with a user-facing explanation. Messages never contain credentials. */
sealed class HaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Short text suitable for the phone UI. */
    open val userMessage: String get() = message ?: javaClass.simpleName

    class Unauthorized : HaException("Access token was rejected (HTTP 401). It may be invalid, expired or revoked.")
    class Forbidden : HaException("The Home Assistant user is not allowed to do this (HTTP 403).")
    class AuthInvalid(detail: String?) :
        HaException("Access token was rejected by the WebSocket API" + (detail?.let { ": $it" } ?: "."))
    class Unreachable(cause: Throwable) :
        HaException("Cannot reach Home Assistant (${cause.javaClass.simpleName}). Check the URL, Wi-Fi/VPN and that HA is running.", cause)
    class Tls(cause: Throwable) :
        HaException("Secure connection failed (${cause.javaClass.simpleName}). The HTTPS certificate is not trusted by Android.", cause)
    class Http(val code: Int) : HaException("Home Assistant returned HTTP $code.")
    class Protocol(detail: String) : HaException("Unexpected response from Home Assistant: $detail")
    class ConnectionLost : HaException("Connection to Home Assistant was lost.")
    class NotConnected : HaException("Not connected to Home Assistant.")
    class CommandFailed(val code: String?, detail: String?) : HaException("Home Assistant refused the command: ${code ?: ""} ${detail ?: ""}".trim())
    class NotAllowed(reason: String) : HaException("Blocked by allowlist: $reason")

    /** True for errors where retrying with the same credentials cannot help. */
    val isCredentialProblem: Boolean get() = this is Unauthorized || this is AuthInvalid

    companion object {
        fun fromIo(e: IOException): HaException = when (e) {
            is SSLException -> Tls(e)
            is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException -> Unreachable(e)
            else -> Unreachable(e)
        }
    }
}
