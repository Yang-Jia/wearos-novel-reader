package com.wear.novelreader.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object IpAddress {

    fun lanUrls(context: Context, port: Int): List<String> {
        val ips = LinkedHashSet<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks
                .filter { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                .sortedByDescending { it == cm.activeNetwork }
            for (network in wifi) {
                addLinkAddresses(cm.getLinkProperties(network), ips)
            }
            if (ips.isEmpty() && isWifiConnected(context)) {
                addLinkAddresses(cm.getLinkProperties(cm.activeNetwork), ips)
            }
        } catch (_: Exception) {
        }
        return ips.map { "http://$it:$port" }
    }

    private fun addLinkAddresses(properties: LinkProperties?, ips: MutableSet<String>) {
        properties?.linkAddresses?.forEach { address ->
            val ip = address.address
            if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) {
                ip.hostAddress?.let(ips::add)
            }
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }
}
