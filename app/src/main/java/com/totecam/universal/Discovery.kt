package com.totecam.universal

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.DatagramPacket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object Discovery {
    private const val MULTI_ADDR = "239.255.255.250"
    private const val ONVIF_PORT = 3702

    fun scan(ctx: android.content.Context, user: String, pass: String, onStatus: (String) -> Unit): List<CameraEntry> {
        val found = mutableListOf<CameraEntry>()
        onvifDiscover(found, user, pass, onStatus)
        subnetScan(found, user, pass, onStatus)
        return found.distinctBy { if (it.url.isNotEmpty()) it.url else it.name }
    }

    private fun onvifDiscover(out: MutableList<CameraEntry>, user: String, pass: String, onStatus: (String) -> Unit) {
        try {
            onStatus("Broadcasting ONVIF discovery probe…")
            val msgId = "uuid:" + UUID.randomUUID().toString()
            val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>$msgId</w:MessageID><w:To>urn:docs-oasis-open-org:ws-dd:ns:discovery:2009:01</w:To><w:Action>http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/Probe</w:Action></e:Header>
<e:Body><w:Probe><w:Types>dn:NetworkVideoTransmitter</w:Types></w:Probe></e:Body>
</e:Envelope>"""
            val socket = MulticastSocket()
            socket.soTimeout = 1500
            val target = InetSocketAddress(MULTI_ADDR, ONVIF_PORT)
            socket.send(DatagramPacket(probe.toByteArray(), probe.length, target))
            val seen = mutableSetOf<String>()
            val deadline = System.currentTimeMillis() + 4000
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    val addrs = Regex("""(http://[^\s<"]+)""").findAll(text).map { it.groupValues[1] }.toList()
                    for (addr in addrs) {
                        val host = try { URL(addr).host } catch (e: Exception) { continue }
                        if (!seen.add(host)) continue
                        onStatus("ONVIF device at $host")
                        val rtsp = tryGetStreamUri(addr, user, pass)
                        if (rtsp != null) {
                            out.add(CameraEntry(name = "ONVIF @ $host", type = CameraEntry.TYPE_RTSP, url = rtsp, username = user, password = pass))
                        } else {
                            out.add(CameraEntry(name = "ONVIF @ $host (login?)", type = CameraEntry.TYPE_RTSP, url = "rtsp://$host:554/", note = "ONVIF found at $addr — set login or path if needed"))
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // keep listening until deadline
                }
            }
            socket.close()
        } catch (e: Exception) {
            onStatus("ONVIF probe failed: ${e.message}")
        }
    }

    private fun tryGetStreamUri(deviceUrl: String, user: String, pass: String): String? {
        return try {
            val profilesBody = soap(deviceUrl, """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/></s:Body></s:Envelope>""")
            val token = Regex("""token="([^"]+)"""").find(profilesBody)?.groupValues?.get(1) ?: return null
            val uriBody = soap(deviceUrl, """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl"><trt:StreamSetup><tt:Stream xmlns:tt="http://www.onvif.org/ver10/schema">RTP-Unicast</tt:Stream><tt:Transport xmlns:tt="http://www.onvif.org/ver10/schema"><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri></s:Body></s:Envelope>""")
            Regex("""<(?:\w+:)?Uri[^>]*>(rtsp://[^<]+)</(?:\w+:)?Uri>""").find(uriBody)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun soap(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    }

    private fun localIp(): String? {
        try {
            val enums = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in enums.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun subnetScan(out: MutableList<CameraEntry>, user: String, pass: String, onStatus: (String) -> Unit) {
        val ip = localIp()
        if (ip == null) { onStatus("No local network — connect to Wi-Fi and retry"); return }
        val base = ip.substring(0, ip.lastIndexOf('.') + 1)
        onStatus("Scanning ${base}0/24 for RTSP ports…")
        val openHosts = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(64)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            val host = base + i
            pool.submit {
                try {
                    for (port in listOf(554, 8554)) {
                        try {
                            Socket().use { s -> s.connect(InetSocketAddress(host, port), 200); openHosts.add("$host:$port") }
                        } catch (e: Exception) {}
                    }
                } finally { latch.countDown() }
            }
        }
        latch.await()
        pool.shutdown()
        onStatus("Found ${openHosts.size} open RTSP port(s) — testing camera paths…")
        for (hp in openHosts.sorted()) {
            val host = hp.substringBefore(':')
            val port = hp.substringAfter(':').toInt()
            onStatus("Probing $hp …")
            val (status, path) = RtspProbe.findWorkingPath(host, port, user, pass)
            when (status) {
                RtspProbe.Status.OK ->
                    out.add(CameraEntry(name = "Cam @ $host", type = CameraEntry.TYPE_RTSP, url = "rtsp://$host:$port$path", username = user, password = pass))
                RtspProbe.Status.AUTH_REQUIRED ->
                    out.add(CameraEntry(name = "Cam @ $host (login needed)", type = CameraEntry.TYPE_RTSP, url = "rtsp://$host:$port$path", note = "Camera answered 401 — edit and add username/password"))
                else -> {}
            }
        }
    }
}
