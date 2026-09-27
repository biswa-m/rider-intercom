package com.bmxt.riderintercom.intercom.audio

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        result += address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
            // Return an empty list if the network interfaces are unavailable.
        }

        return result.distinct()
    }

    fun getPrimaryLocalIpv4(): String? =
        getLocalIpv4Addresses()
            .firstOrNull {
                it.startsWith("192.168.") ||
                    it.startsWith("10.") ||
                    it.matches(Regex("""172\.(1[6-9]|2\d|3[0-1])\..*"""))
            }
            ?: getLocalIpv4Addresses().firstOrNull()
}
