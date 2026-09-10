package com.totecam.universal

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var mjpeg: MjpegStream? = null
    private var usbCamera: android.hardware.camera2.CameraDevice? = null
    private var usbSession: android.hardware.camera2.CameraCaptureSession? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val entry = intent.getSerializableExtra("entry") as? CameraEntry ?: return finish()
        setContentView(R.layout.activity_player)
        title = entry.name
        val hint = findViewById<TextView>(R.id.playerHint)
        val pv = findViewById<PlayerView>(R.id.playerView)
        val mj = findViewById<ImageView>(R.id.mjpegView)
        val cx = findViewById<PreviewView>(R.id.previewView)
        val uv = findViewById<TextureView>(R.id.usbView)

        when (entry.type) {
            CameraEntry.TYPE_RTSP -> {
                pv.visibility = View.VISIBLE
                hint.text = "Connecting to ${entry.url} …"
                val p = ExoPlayer.Builder(this).build()
                player = p
                pv.player = p
                val src = RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(android.net.Uri.parse(entry.effectiveUrl())))
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) hint.text = ""
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        hint.text = "Stream error (${error.errorCodeName}).\nCheck URL/login.\n${entry.url}"
                    }
                })
                p.setMediaSource(src)
                p.prepare()
                p.playWhenReady = true
            }
            CameraEntry.TYPE_MJPEG -> {
                mj.visibility = View.VISIBLE
                hint.text = "Connecting…"
                val s = MjpegStream(
                    entry.url, entry.username, entry.password,
                    onFrame = { bmp: Bitmap -> mj.setImageBitmap(bmp); hint.text = "" },
                    onError = { e -> hint.text = "MJPEG error: $e" }
                )
                mjpeg = s
                s.start()
            }
            CameraEntry.TYPE_DEVICE -> {
                cx.visibility = View.VISIBLE
                startDeviceCamera(cx, hint)
            }
            CameraEntry.TYPE_USB -> {
                uv.visibility = View.VISIBLE
                startUsbCamera(uv, hint)
            }
        }
    }

    private fun startDeviceCamera(cx: PreviewView, hint: TextView) {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(cx.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    hint.text = ""
                } catch (e: Exception) {
                    hint.text = "Camera error: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            hint.text = "Camera error: ${e.message}"
        }
    }

    private fun startUsbCamera(uv: TextureView, hint: TextView) {
        val mgr = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        var camId: String? = null
        try {
            for (id in mgr.cameraIdList) {
                val ch = mgr.getCameraCharacteristics(id)
                if (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL) { camId = id; break }
            }
        } catch (e: Exception) {}
        if (camId == null) {
            hint.text = "No USB camera visible to the system.\n\nMany phones don't expose USB webcams to apps natively — the built-in UVC driver arrives in v1.1."
            return
        }
        hint.text = "Opening USB camera…"
        uv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openUsb(mgr, camId, st, hint) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeUsb(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        uv.surfaceTexture?.let { openUsb(mgr, camId, it, hint) }
    }

    private fun openUsb(mgr: android.hardware.camera2.CameraManager, id: String, st: SurfaceTexture, hint: TextView) {
        if (usbCamera != null) return
        st.setDefaultBufferSize(1280, 720)
        val surface = Surface(st)
        try {
            mgr.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(c: android.hardware.camera2.CameraDevice) {
                    usbCamera = c
                    try {
                        c.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                usbSession = session
                                try {
                                    val req = c.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(surface)
                                    session.setRepeatingRequest(req.build(), null, uiHandler)
                                    hint.text = ""
                                } catch (e: Exception) { hint.text = "Preview failed: ${e.message}" }
                            }
                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                hint.text = "USB camera configuration failed"
                            }
                        }, uiHandler)
                    } catch (e: Exception) { hint.text = "Session error: ${e.message}" }
                }
                override fun onDisconnected(c: android.hardware.camera2.CameraDevice) {
                    c.close(); usbCamera = null; hint.text = "USB camera disconnected"
                }
                override fun onError(c: android.hardware.camera2.CameraDevice, error: Int) {
                    c.close(); usbCamera = null; hint.text = "USB camera error $error"
                }
            }, uiHandler)
        } catch (e: SecurityException) {
            hint.text = "Camera permission needed"
        } catch (e: Exception) {
            hint.text = "USB open failed: ${e.message}"
        }
    }

    private fun closeUsb() {
        try { usbSession?.close() } catch (_: Exception) {}
        try { usbCamera?.close() } catch (_: Exception) {}
        usbSession = null
        usbCamera = null
    }

    override fun onStop() {
        super.onStop()
        player?.release(); player = null
        mjpeg?.stop(); mjpeg = null
        closeUsb()
    }
}
