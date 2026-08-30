package com.vibereading.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

/** WiFi+蜂窝同时在线时，localIpAddresses 的 WiFi 优先排序（WebCompanionService.wifiInterfacePriority）。 */
class WebCompanionServiceTest {

    @Test
    fun `wifi 接口优先于蜂窝与 VPN 接口`() {
        assertEquals(0, WebCompanionService.wifiInterfacePriority("wlan0"))
        assertEquals(0, WebCompanionService.wifiInterfacePriority("wlan1"))
        assertEquals(0, WebCompanionService.wifiInterfacePriority("swlan0"))
        assertEquals(1, WebCompanionService.wifiInterfacePriority("rmnet_data0"))
        assertEquals(1, WebCompanionService.wifiInterfacePriority("tun0"))
        assertEquals(1, WebCompanionService.wifiInterfacePriority("eth0"))
    }

    @Test
    fun `排序后 wlan 网卡地址排在最前且同优先级保持枚举原序`() {
        val entries = listOf(
            "rmnet_data0" to "10.22.33.44",
            "wlan0" to "192.168.1.100",
            "dummy0" to "192.168.49.1",
            "wlan1" to "192.168.1.101"
        )
        val sorted = entries.sortedBy { WebCompanionService.wifiInterfacePriority(it.first) }.map { it.second }
        assertEquals(listOf("192.168.1.100", "192.168.1.101", "10.22.33.44", "192.168.49.1"), sorted)
    }
}
