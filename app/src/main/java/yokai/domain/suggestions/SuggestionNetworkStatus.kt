package yokai.domain.suggestions

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SuggestionNetworkStatus {
    fun isOnline(): Boolean
    fun onlineChanges(): Flow<Boolean> = flowOf(isOnline())
}

object AlwaysOnlineSuggestionNetworkStatus : SuggestionNetworkStatus {
    override fun isOnline(): Boolean = true
}

class TransientSuggestionNetworkException(
    cause: Throwable,
) : IOException(cause.message, cause)

fun Throwable.isTransientSuggestionNetworkFailure(isOnline: Boolean): Boolean {
    if (!isOnline) return true
    // Per-host DNS failures (UnknownHostException, "unable to resolve host" IOException)
    // are NOT transient — the device has working network, just THAT hostname returned no
    // address (NXDOMAIN, ISP DNS filter, or extension's domain dead). Classifying as
    // transient would propagate through the chunk's coroutineScope and cancel sibling
    // source fetches in the same chunk, even though they could succeed.
    return causeChain().any { throwable ->
        throwable is ConnectException ||
            throwable is NoRouteToHostException ||
            throwable is SocketException ||
            // SocketTimeoutException extends InterruptedIOException, not SocketException,
            // so it has to be checked explicitly. A tower hand-off or transient Wi-Fi
            // stall is the typical trigger; misclassifying it as a source error advances
            // the section batch as if the source had run dry.
            throwable is SocketTimeoutException ||
            // SSL failures during a network drop look like normal IOException to the
            // caller, but the section should pause for resume rather than advance.
            throwable is SSLException ||
            (
                throwable is IOException &&
                    throwable.message.orEmpty().lowercase().let { message ->
                        message.contains("connection reset") ||
                            message.contains("network is unreachable") ||
                            message.contains("no route to host") ||
                            message.contains("software caused connection abort") ||
                            message.contains("failed to connect")
                    }
                )
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
