package yokai.domain.suggestions

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionNetworkStatusTest {

    @Test
    fun `offline state makes any source exception transient`() {
        assertTrue(IOException("source failed").isTransientSuggestionNetworkFailure(isOnline = false))
    }

    @Test
    fun `connection reset is transient even before network callback catches up`() {
        assertTrue(SocketException("Connection reset").isTransientSuggestionNetworkFailure(isOnline = true))
    }

    @Test
    fun `unknown host is transient`() {
        assertTrue(UnknownHostException("Unable to resolve host").isTransientSuggestionNetworkFailure(isOnline = true))
    }

    @Test
    fun `ordinary parser exception is source specific`() {
        assertFalse(IllegalStateException("missing selector").isTransientSuggestionNetworkFailure(isOnline = true))
    }

    @Test
    fun `socket timeout while online is transient not source-specific`() {
        // Regression: when a tower hand-off or flaky Wi-Fi drops a request mid-flight,
        // OkHttp surfaces SocketTimeoutException with a "timeout" message. Without
        // classifying this as transient the affected section gets advanced as if its
        // sources had truly run dry, producing the thin-results bug.
        assertTrue(SocketTimeoutException("timeout").isTransientSuggestionNetworkFailure(isOnline = true))
    }

    @Test
    fun `SSL handshake failure during network drop is transient`() {
        // Cell tower swaps and captive portals frequently break TLS mid-handshake.
        // Classify these as transient so the section pauses for resume rather than
        // marking the source as exhausted.
        assertTrue(
            SSLHandshakeException("Connection closed by peer")
                .isTransientSuggestionNetworkFailure(isOnline = true),
        )
    }
}
