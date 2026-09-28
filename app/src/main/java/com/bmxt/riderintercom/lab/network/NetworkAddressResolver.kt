package com.bmxt.riderintercom.lab.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddressResolver {
    fun localIpv4Addresses(): List<String> = buildList {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            networkInterface.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) add(address.hostAddress ?: "")
            }
        }
    }.filter { it.isNotBlank() }
}
