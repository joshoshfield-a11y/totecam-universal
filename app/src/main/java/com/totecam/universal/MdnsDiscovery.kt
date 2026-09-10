package com.totecam.universal

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Minimal mDNS listener: finds cameras advertising _rtsp._tcp / _onvif._tcp / etc. */
object MdnsDiscovery {

    data class MdnsService(val name: String, val serviceType: String, val host: String, val port: Int)

    private val SERVICES = listOf("_rtsp._tcp.local", "_onvif._tcp.local", "_axis-video._tcp.local", "_mjpeg._tcp.local")

    fun discover(timeoutMs: Int = 3000): List<MdnsService> {
        val results = LinkedHashMap<String, MdnsService>()
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 1000
            val query = buildQuery()
            socket.send(DatagramPacket(query, query.size, InetSocketAddress("224.0.0.251", 5353)))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    parseResponse(p.data.copyOf(p.length), results)
                } catch (e: java.net.SocketTimeoutException) {
                    // keep listening until deadline
                }
            }
            socket.close()
        } catch (e: Exception) {
            // mDNS is best-effort
        }
        return results.values.toList()
    }

    private fun buildQuery(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun writeName(name: String) {
            for (label in name.split(".")) {
                val b = label.toByteArray()
                out.write(b.size); out.write(b)
            }
            out.write(0)
        }
        w16(0x1234)
        w16(0x0000)
        w16(SERVICES.size)
        w16(0); w16(0); w16(0)
        for (s in SERVICES) { writeName(s); w16(12); w16(1) }
        return out.toByteArray()
    }

    private fun parseName(data: ByteArray, off: Int, depth: Int = 0): Pair<String, Int> {
        if (depth > 10) return "" to off
        val labels = mutableListOf<String>()
        var o = off
        var jumped = false
        var nextAfterJump = off
        while (o < data.size) {
            val len = data[o].toInt() and 0xFF
            if (len == 0) { if (!jumped) nextAfterJump = o + 1; break }
            if (len and 0xC0 == 0xC0) {
                if (o + 1 >= data.size) break
                val ptr = ((len and 0x3F) shl 8) or (data[o + 1].toInt() and 0xFF)
                if (!jumped) nextAfterJump = o + 2
                jumped = true
                val (name, _) = parseName(data, ptr, depth + 1)
                if (name.isNotEmpty()) labels.add(name)
                break
            }
            if (o + 1 + len > data.size) break
            labels.add(String(data, o + 1, len, Charsets.UTF_8))
            o += 1 + len
            if (!jumped) nextAfterJump = o
        }
        return labels.joinToString(".") to nextAfterJump
    }

    private fun parseResponse(data: ByteArray, out: LinkedHashMap<String, MdnsService>) {
        try {
            if (data.size < 12) return
            fun r16(o: Int) = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
            val qd = r16(4); val an = r16(6); val ns = r16(8); val ar = r16(10)
            var o = 12
            repeat(qd) {
                val (_, nx) = parseName(data, o)
                o = nx + 4
            }
            val srvList = mutableListOf<Triple<String, Int, String>>() // target, port, ownerName
            val aMap = mutableMapOf<String, String>()

            fun parseRecords(count: Int) {
                repeat(count) {
                    if (o >= data.size) return
                    val (recName, nx) = parseName(data, o)
                    o = nx
                    if (o + 10 > data.size) return
                    val type = r16(o)
                    val rdlen = r16(o + 8)
                    val rdataOff = o + 10
                    o = rdataOff + rdlen
                    when (type) {
                        33 -> { // SRV
                            if (rdataOff + 7 <= data.size) {
                                val port = r16(rdataOff + 4)
                                val (target, _) = parseName(data, rdataOff + 6)
                                srvList.add(Triple(target.trim('.'), port, recName))
                            }
                        }
                        1 -> { // A
                            if (rdataOff + 4 <= data.size) {
                                val ip = "${data[rdataOff].toInt() and 0xFF}.${data[rdataOff + 1].toInt() and 0xFF}." +
                                         "${data[rdataOff + 2].toInt() and 0xFF}.${data[rdataOff + 3].toInt() and 0xFF}"
                                aMap[recName.trim('.')] = ip
                            }
                        }
                    }
                }
            }
            parseRecords(an); parseRecords(ns); parseRecords(ar)

            for ((target, port, owner) in srvList) {
                val ip = aMap[target]
                if (ip == null || ip.isEmpty()) continue
                val friendly = owner.substringBefore("._").trim()
                val svcType = when {
                    owner.contains("_rtsp") -> "RTSP"
                    owner.contains("_onvif") -> "ONVIF"
                    owner.contains("_mjpeg") -> "MJPEG"
                    else -> "RTSP"
                }
                out.putIfAbsent("$ip:$port", MdnsService(friendly.ifEmpty { owner }, svcType, ip, port))
            }
        } catch (e: Exception) {
            // ignore malformed packets
        }
    }
}
