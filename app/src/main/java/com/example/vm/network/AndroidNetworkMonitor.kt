package com.example.vm.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostNetworkType(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular Data"),
    ETHERNET("Ethernet"),
    OFFLINE("Offline / Disconnected")
}

data class HostNetworkInfo(
    val isConnected: Boolean = false,
    val type: HostNetworkType = HostNetworkType.OFFLINE,
    val hasInternetCapability: Boolean = false
)

/**
 * AndroidNetworkMonitor utilizes Android System ConnectivityManager to track
 * host Wi-Fi/Cellular connectivity state changes dynamically and notify the VM networking layer.
 */
class AndroidNetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkInfo = MutableStateFlow(getCurrentNetworkInfo())
    val networkInfo: StateFlow<HostNetworkInfo> = _networkInfo.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring() {
        if (connectivityManager == null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkInfo.value = getCurrentNetworkInfo()
            }

            override fun onLost(network: Network) {
                _networkInfo.value = getCurrentNetworkInfo()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                _networkInfo.value = getCurrentNetworkInfo()
            }
        }

        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // Permission or system limitation handled safely
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        networkCallback = null
    }

    fun getCurrentNetworkInfo(): HostNetworkInfo {
        val cm = connectivityManager ?: return HostNetworkInfo()
        val activeNetwork = cm.activeNetwork ?: return HostNetworkInfo()
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return HostNetworkInfo()

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> HostNetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> HostNetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> HostNetworkType.ETHERNET
            else -> HostNetworkType.OFFLINE
        }

        return HostNetworkInfo(
            isConnected = hasInternet,
            type = type,
            hasInternetCapability = hasInternet
        )
    }
}
