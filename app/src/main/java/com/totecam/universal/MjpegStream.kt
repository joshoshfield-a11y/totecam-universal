package com.totecam.universal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Reads a multipart/x-mixed-replace MJPEG stream and posts frames to the UI. */
class MjpegStream(
    private val url: String,
    private val username: String,
    private val password: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 0
                if (username.isNotEmpty()) {
                    val cred = android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $cred")
                }
                conn.connect()
                val contentType = conn.contentType ?: ""
                var boundary = "boundary"
                val m = Regex("boundary=([^;\\s]+)").find(contentType)
                if (m != null) boundary = m.groupValues[1].trim('"')
                val boundaryBytes = ("--" + boundary).toByteArray()
                val input = BufferedInputStream(conn.inputStream)
                val accum = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                var headerSkipped = false

                while (running) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    accum.write(chunk, 0, n)
                    var data = accum.toByteArray()

                    while (running) {
                        if (!headerSkipped) {
                            val bIdx = indexOf(data, boundaryBytes)
                            if (bIdx < 0) {
                                accum.reset()
                                if (data.size > 65536) accum.write(data, data.size - boundaryBytes.size, boundaryBytes.size)
                                break
                            }
                            val hdrEnd = findHeaderEnd(data, bIdx + boundaryBytes.size)
                            if (hdrEnd < 0) {
                                accum.reset()
                                accum.write(data, bIdx, data.size - bIdx)
                                break
                            }
                            headerSkipped = true
                            accum.reset()
                            accum.write(data, hdrEnd, data.size - hdrEnd)
                            data = accum.toByteArray()
                        }

                        val next = indexOf(data, boundaryBytes)
                        if (next < 0) {
                            accum.reset()
                            accum.write(data)
                            break
                        }

                        val jpeg = data.copyOfRange(0, next)
                        val trimmed = if (jpeg.size >= 2 && jpeg[jpeg.size - 2] == 0x0D.toByte() && jpeg[jpeg.size - 1] == 0x0A.toByte())
                            jpeg.copyOfRange(0, jpeg.size - 2) else jpeg
                        if (trimmed.size > 100) {
                            val bmp = BitmapFactory.decodeByteArray(trimmed, 0, trimmed.size)
                            if (bmp != null) ui.post { if (running) onFrame(bmp) }
                        }

                        headerSkipped = false
                        accum.reset()
                        accum.write(data, next, data.size - next)
                        data = accum.toByteArray()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (running) ui.post { onError(e.message ?: "MJPEG stream error") }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun findHeaderEnd(data: ByteArray, from: Int): Int {
        if (data.size < 4) return -1
        var i = from
        while (i <= data.size - 4) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()) return i + 4
            i++
        }
        return -1
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }
}
