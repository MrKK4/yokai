package yokai.data.suggestions

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import eu.kanade.tachiyomi.util.system.connectivityManager
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import yokai.domain.suggestions.SuggestionNetworkStatus

class AndroidSuggestionNetworkStatus(
    private val context: Context,
) : SuggestionNetworkStatus {

    override fun isOnline(): Boolean = context.isOnline()

    override fun onlineChanges(): Flow<Boolean> =
        callbackFlow {
            trySend(isOnline())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(isOnline())
                }

                override fun onLost(network: Network) {
                    trySend(isOnline())
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(isOnline())
                }
            }
            context.connectivityManager.registerDefaultNetworkCallback(callback)
            awaitClose {
                runCatching {
                    context.connectivityManager.unregisterNetworkCallback(callback)
                }
            }
        }.distinctUntilChanged()
}
