package com.totecam.universal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Minimal RTSP DESCRIBE handshake used to validate stream paths and credentials. */
object RtspProbe {
    enum class Status { OK, AUTH_REQUIRED, NOT_FOUND, ERROR }

    val COMMON_PATHS = listOf(
        "/", "/11", "/12", "/live/ch00_0", "/live/ch01_0",
        "/h264/ch1/main/av_stream", "/h264/ch1/sub/av_stream",
        "/stream1", "/stream2", "/live.sdp", "/media/video1", "/av0_0",
        "/cam/realmonitor?channel=1&subtype=0",
        "/user=admin&password=&channel=1&stream=0.sdp",
        "/mpeg4/ch01/main/av_stream"
    )

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun authHeader(method: String, uri: String, user: String, pass: String, challenge: String?): String {
        if (challenge == null) return ""
        return if (challenge.startsWith("Digest", ignoreCase = true)) {
            val realm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1) ?: ""
            val nonce = Regex("nonce=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1) ?: ""
            val qop = Regex("qop=\"?([^\",]*)\"?").find(challenge)?.groupValues?.get(1)
            val ha1 = md5("$user:$realm:$pass")
            val ha2 = md5("$method:$uri")
            if (qop != null && qop.isNotEmpty()) {
                val cnonce = md5(System.nanoTime().toString())
                val resp = md5("$ha1:$nonce:00000001:$cnonce:$qop:$ha2")
                "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\", qop=$qop, nc=00000001, cnonce=\"$cnonce\""
            } else {
                val resp = md5("$ha1:$nonce:$ha2")
                "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\""
            }
        } else {
            val cred = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
            "Basic $cred"
        }
    }

    fun describe(host: String, port: Int, path: String, user: String, pass: String, timeoutMs: Int = 3500): Status {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val uri = "rtsp://$host:$port$path"
            var cseq = 1

            fun sendRequest(authorization: String?) {
                val sb = StringBuilder()
                sb.append("DESCRIBE $uri RTSP/1.0\r\n")
                sb.append("CSeq: ${cseq++}\r\n")
                sb.append("Accept: application/sdp\r\n")
                sb.append("User-Agent: ToteCam/1.0\r\n")
                if (authorization != null) sb.append("Authorization: $authorization\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())
                out.flush()
            }

            fun readResponse(): Pair<Int, String?> {
                var status = 0
                var challenge: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("RTSP/")) {
                        status = line.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
                    } else if (line.startsWith("WWW-Authenticate:", ignoreCase = true)) {
                        challenge = line.substringAfter(':').trim()
                    }
                }
                return status to challenge
            }

            sendRequest(null)
            val first = readResponse()
            var status = first.first
            if (status == 401 && user.isNotEmpty()) {
                sendRequest(authHeader("DESCRIBE", uri, user, pass, first.second))
                status = readResponse().first
            }
            when (status) {
                200 -> Status.OK
                401 -> Status.AUTH_REQUIRED
                404 -> Status.NOT_FOUND
                else -> Status.ERROR
            }
        } catch (e: Exception) {
            Status.ERROR
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun findWorkingPath(host: String, port: Int, user: String, pass: String): Pair<Status, String?> {
        for (path in COMMON_PATHS) {
            when (describe(host, port, path, user, pass)) {
                Status.OK -> return Status.OK to path
                Status.AUTH_REQUIRED -> return Status.AUTH_REQUIRED to path
                else -> {}
            }
        }
        return Status.ERROR to null
    }
}
