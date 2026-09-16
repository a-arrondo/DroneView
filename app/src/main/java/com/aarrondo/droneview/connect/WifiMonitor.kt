package com.aarrondo.droneview.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WifiMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnectedToDrone = MutableStateFlow(false)
    val isConnectedToDrone: StateFlow<Boolean> = _isConnectedToDrone

    private val _droneNetwork = MutableStateFlow<Network?>(null)
    val droneNetwork: StateFlow<Network?> = _droneNetwork

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    fun start() {
        if (callback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    clearDroneNetwork(network)
                    return
                }
                val linkProperties = connectivityManager.getLinkProperties(network)
                updateDroneNetwork(network, linkProperties)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateDroneNetwork(network, linkProperties)
            }

            override fun onLost(network: Network) {
                clearDroneNetwork(network)
            }
        }

        callback = cb
        connectivityManager.registerNetworkCallback(request, cb)
    }

    fun stop() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        _isConnectedToDrone.value = false
        _droneNetwork.value = null
        unbindProcess()
    }

    private fun updateDroneNetwork(network: Network, linkProperties: LinkProperties?) {
        if (!gatewayMatchesDrone(linkProperties)) {
            Log.d(TAG, "wifi net=$network not drone (routes=${linkProperties?.routes}, " +
                "dhcp=${linkProperties?.dhcpServerAddress?.hostAddress}, " +
                "addrs=${linkProperties?.linkAddresses})")
            clearDroneNetwork(network)
            return
        }
        Log.i(TAG, "drone network detected: $network, binding process")
        _isConnectedToDrone.value = true
        _droneNetwork.value = network
        bindProcess(network)
    }

    private fun clearDroneNetwork(network: Network) {
        if (_droneNetwork.value == network) {
            _droneNetwork.value = null
        }
        if (_droneNetwork.value == null) {
            _isConnectedToDrone.value = false
        }
        if (boundNetwork == network) {
            unbindProcess()
        }
    }

    private fun bindProcess(network: Network) {
        if (boundNetwork == network) return
        try {
            val ok = connectivityManager.bindProcessToNetwork(network)
            Log.i(TAG, "bindProcessToNetwork($network) -> $ok")
            boundNetwork = network
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcess() {
        if (boundNetwork == null) return
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (e: Exception) {
            Log.w(TAG, "unbindProcess failed", e)
        }
        boundNetwork = null
    }

    companion object {
        private const val TAG = "DroneView"
    }

    private fun gatewayMatchesDrone(linkProperties: LinkProperties?): Boolean {
        if (linkProperties == null) return false

        if (linkProperties.routes.any { it.gateway?.hostAddress == IcommConsts.DRONE_HOST }) {
            return true
        }
        if (linkProperties.dhcpServerAddress?.hostAddress == IcommConsts.DRONE_HOST) {
            return true
        }
        if (linkProperties.linkAddresses.any {
                it.address?.hostAddress?.startsWith("192.168.0.") == true
            }
        ) {
            return true
        }

        return false
    }
}
