package com.totecam.universal

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.DatagramPacket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object Discovery {
    private const val MULTI_ADDR = "239.255.255.250"
    private const val ONVIF_PORT = 3702
    private val RTSP_PORTS = listOf(554, 8554, 10554, 5540)
    private val HTTP_PORTS = listOf(80, 81, 8000, 8080)

    private val MJPEG_PATHS = listOf(
        "/videostream.cgi", "/video.mjpeg", "/mjpeg.cgi", "/cam.mjpeg",
        "/video.cgi", "/live", "/stream", "/img/video.mjpeg"
    )

    fun scan(ctx: android.content.Context, user: String, pass: String, tryDefaults: Boolean,
             onStatus: (String) -> Unit): List<CameraEntry> {
        val byHost = LinkedHashMap<String, CameraEntry>()
        onvifDiscover(byHost, user, pass, tryDefaults, onStatus)
        mdnsDiscover(byHost, user, pass, tryDefaults, onStatus)
        subnetScan(byHost, user, pass, tryDefaults, onStatus)
        return byHost.values.toList()
    }

    // ---------------------------------------------------------------- ONVIF

    private fun onvifDiscover(byHost: MutableMap<String, CameraEntry>, user: String, pass: String,
                              tryDefaults: Boolean, onStatus: (String) -> Unit) {
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
            socket.send(DatagramPacket(probe.toByteArray(), probe.length, InetSocketAddress(MULTI_ADDR, ONVIF_PORT)))
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
                        if (!seen.add(host) || byHost.containsKey(host)) continue
                        onStatus("ONVIF device at $host")
                        val (rtsp, cred) = tryGetStreamUri(addr, user, pass, tryDefaults)
                        if (rtsp != null) {
                            byHost[host] = CameraEntry(
                                name = "ONVIF @ $host", type = CameraEntry.TYPE_RTSP, url = rtsp,
                                username = cred?.first ?: "", password = cred?.second ?: "")
                        } else {
                            byHost[host] = CameraEntry(
                                name = "ONVIF @ $host (login?)", type = CameraEntry.TYPE_RTSP,
                                url = "rtsp://$host:554/",
                                note = "ONVIF found at $addr but stream URI needs auth — edit and add login")
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

    private fun wsseHeader(user: String, pass: String): String {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonceB64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
        val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val digestBytes = java.security.MessageDigest.getInstance("SHA-1")
            .digest(nonce + created.toByteArray(Charsets.UTF_8) + pass.toByteArray(Charsets.UTF_8))
        val digest = android.util.Base64.encodeToString(digestBytes, android.util.Base64.NO_WRAP)
        return "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
               "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
               "<wsse:UsernameToken><wsse:Username>$user</wsse:Username>" +
               "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</wsse:Password>" +
               "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">$nonceB64</wsse:Nonce>" +
               "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    /** Returns (rtspUrl, credsUsed?) — tries anonymous, user creds, then factory defaults. */
    private fun tryGetStreamUri(deviceUrl: String, user: String, pass: String,
                                tryDefaults: Boolean): Pair<String?, Pair<String, String>?> {
        val profilesBody = "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        fun uriBody(token: String) = "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
            "<trt:StreamSetup><tt:Stream xmlns:tt=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</tt:Stream>" +
            "<tt:Transport xmlns:tt=\"http://www.onvif.org/ver10/schema\"><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
            "<trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri>"
        fun fetch(header: String?): String? {
            val p = soap(deviceUrl, profilesBody, header)
            val token = Regex("""token="([^"]+)"""").find(p)?.groupValues?.get(1) ?: return null
            val u = soap(deviceUrl, uriBody(token), header)
            return Regex("""<(?:\w+:)?Uri[^>]*>(rtsp://[^<]+)</(?:\w+:)?Uri>""").find(u)?.groupValues?.get(1)
        }
        try { fetch(null)?.let { return it to null } } catch (e: Exception) {}
        val creds = mutableListOf<Pair<String, String>>()
        if (user.isNotEmpty()) creds.add(user to pass)
        if (tryDefaults) creds.addAll(RtspProbe.DEFAULT_CREDENTIALS)
        for (c in creds) {
            try { fetch(wsseHeader(c.first, c.second))?.let { return it to c } } catch (e: Exception) {}
        }
        return null to null
    }

    private fun soap(url: String, body: String, wsse: String? = null): String {
        val envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
            (if (wsse != null) "<s:Header>$wsse</s:Header>" else "") +
            "<s:Body>$body</s:Body></s:Envelope>"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        conn.outputStream.use { it.write(envelope.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    }

    // ---------------------------------------------------------------- mDNS

    private fun mdnsDiscover(byHost: MutableMap<String, CameraEntry>, user: String, pass: String,
                             tryDefaults: Boolean, onStatus: (String) -> Unit) {
        onStatus("Listening for mDNS camera advertisements…")
        val services = MdnsDiscovery.discover(3000)
        for (svc in services) {
            if (byHost.containsKey(svc.host)) continue
            onStatus("mDNS: ${svc.name} (${svc.serviceType}) at ${svc.host}:${svc.port}")
            when (svc.serviceType) {
                "ONVIF" -> {
                    val (rtsp, cred) = tryGetStreamUri(
                        "http://${svc.host}:${svc.port}/onvif/device_service", user, pass, tryDefaults)
                    if (rtsp != null) {
                        byHost[svc.host] = CameraEntry(name = svc.name, type = CameraEntry.TYPE_RTSP, url = rtsp,
                            username = cred?.first ?: "", password = cred?.second ?: "")
                    } else {
                        probeRtspForHost(byHost, svc.host, 554, svc.name, user, pass, tryDefaults)
                    }
                }
                "MJPEG" -> byHost[svc.host] = CameraEntry(
                    name = svc.name, type = CameraEntry.TYPE_MJPEG,
                    url = "http://${svc.host}:${svc.port}/",
                    note = "mDNS-advertised MJPEG service — edit path if the stream 404s")
                else -> probeRtspForHost(byHost, svc.host, svc.port, svc.name, user, pass, tryDefaults)
            }
        }
    }

    private fun probeRtspForHost(byHost: MutableMap<String, CameraEntry>, host: String, port: Int,
                                 name: String, user: String, pass: String, tryDefaults: Boolean) {
        val r = RtspProbe.probe(host, port, user, pass, tryDefaults)
        when (r.status) {
            RtspProbe.Status.OK -> byHost[host] = CameraEntry(
                name = name, type = CameraEntry.TYPE_RTSP,
                url = "rtsp://$host:$port${r.path}", username = r.username, password = r.password)
            RtspProbe.Status.AUTH_REQUIRED -> byHost[host] = CameraEntry(
                name = "$name (login needed)", type = CameraEntry.TYPE_RTSP, url = "rtsp://$host:$port/",
                note = "Camera requires auth — edit and add username/password")
            else -> {}
        }
    }

    // ---------------------------------------------------------------- subnet scan

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

    private fun basic(user: String, pass: String) =
        "Basic " + android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)

    private fun tryMjpegUrl(url: String, cred: Pair<String, String>?): Int {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = true
            if (cred != null) conn.setRequestProperty("Authorization", basic(cred.first, cred.second))
            conn.connect()
            val code = conn.responseCode
            val ct = (conn.contentType ?: "").toLowerCase()
            conn.disconnect()
            when {
                code == 200 && (ct.contains("mixed-replace") || ct.startsWith("image") || ct.contains("multipart")) -> 1
                code == 401 -> 0
                else -> -1
            }
        } catch (e: Exception) { -1 }
    }

    private fun probeMjpeg(host: String, ports: List<Int>, user: String, pass: String,
                           tryDefaults: Boolean): CameraEntry? {
        val creds = mutableListOf<Pair<String, String>>()
        if (user.isNotEmpty()) creds.add(user to pass)
        if (tryDefaults) creds.addAll(RtspProbe.DEFAULT_CREDENTIALS)
        for (port in ports.sorted()) {
            for (path in MJPEG_PATHS) {
                val url = "http://$host:$port$path"
                when (tryMjpegUrl(url, null)) {
                    1 -> return CameraEntry(name = "MJPEG @ $host", type = CameraEntry.TYPE_MJPEG, url = url)
                    0 -> for (c in creds) {
                        if (tryMjpegUrl(url, c) == 1)
                            return CameraEntry(name = "MJPEG @ $host", type = CameraEntry.TYPE_MJPEG,
                                url = url, username = c.first, password = c.second)
                    }
                    else -> {}
                }
            }
        }
        return null
    }

    private fun subnetScan(byHost: MutableMap<String, CameraEntry>, user: String, pass: String,
                           tryDefaults: Boolean, onStatus: (String) -> Unit) {
        val ip = localIp()
        if (ip == null) { onStatus("No local network — connect to Wi-Fi and retry"); return }
        val base = ip.substring(0, ip.lastIndexOf('.') + 1)
        onStatus("Scanning ${base}0/24 (RTSP 554/8554/10554/5540 + HTTP 80/81/8000/8080)…")
        val openPorts = ConcurrentHashMap<String, MutableList<Int>>()
        val allPorts = RTSP_PORTS + HTTP_PORTS
        val pool = Executors.newFixedThreadPool(64)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            val host = base + i
            pool.submit {
                try {
                    for (port in allPorts) {
                        try {
                            Socket().use { s -> s.connect(InetSocketAddress(host, port), 200)
                                openPorts.computeIfAbsent(host) { Collections.synchronizedList(mutableListOf()) }.add(port) }
                        } catch (e: Exception) {}
                    }
                } finally { latch.countDown() }
            }
        }
        latch.await()
        pool.shutdown()
        onStatus("Found ${openPorts.size} host(s) with camera ports — identifying…")
        for ((host, ports) in openPorts.toSortedMap()) {
            if (byHost.containsKey(host)) continue
            val rtspPort = ports.firstOrNull { it in RTSP_PORTS }
            if (rtspPort != null) {
                onStatus("Probing RTSP $host:$rtspPort …")
                val r = RtspProbe.probe(host, rtspPort, user, pass, tryDefaults)
                when (r.status) {
                    RtspProbe.Status.OK -> {
                        byHost[host] = CameraEntry(name = "Cam @ $host", type = CameraEntry.TYPE_RTSP,
                            url = "rtsp://$host:$rtspPort${r.path}", username = r.username, password = r.password)
                        continue
                    }
                    RtspProbe.Status.AUTH_REQUIRED -> {
                        byHost[host] = CameraEntry(name = "Cam @ $host (login needed)", type = CameraEntry.TYPE_RTSP,
                            url = "rtsp://$host:$rtspPort/", note = "Camera requires auth — edit and add username/password")
                        continue
                    }
                    else -> {}
                }
            }
            val httpPorts = ports.filter { it in HTTP_PORTS }
            if (httpPorts.isNotEmpty()) {
                onStatus("Probing HTTP/MJPEG $host …")
                val e = probeMjpeg(host, httpPorts, user, pass, tryDefaults)
                if (e != null) byHost[host] = e
            }
        }
    }
}
