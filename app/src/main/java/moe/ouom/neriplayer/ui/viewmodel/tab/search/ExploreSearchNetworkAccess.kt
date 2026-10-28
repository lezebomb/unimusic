package moe.ouom.neriplayer.ui.viewmodel.tab.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import moe.ouom.neriplayer.common.search.SearchSourceException
import moe.ouom.neriplayer.common.search.SearchSourceFailure

/** Reject only known offline state. Missing service/permission/capabilities must not block a usable network. */
internal fun requireExploreSearchNetwork(context: Context) {
    val available = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        val network = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching true
        // Validation may be blocked on an otherwise usable VPN/corporate network; do not require it.
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
    if (!available) throw SearchSourceException(SearchSourceFailure.NETWORK)
}
