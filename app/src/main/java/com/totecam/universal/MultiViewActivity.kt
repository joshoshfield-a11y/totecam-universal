package com.totecam.universal

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

class MultiViewActivity : AppCompatActivity() {
    private val players = mutableListOf<ExoPlayer>()
    private val mjs = mutableListOf<MjpegStream>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi)
        val ids = intent.getStringArrayListExtra("ids") ?: return finish()
        val cells = listOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3)
        for ((i, id) in ids.withIndex()) {
            if (i >= 4) break
            val entry = CameraStore.byId(this, id) ?: continue
            val cell = findViewById<FrameLayout>(cells[i])
            when (entry.type) {
                CameraEntry.TYPE_RTSP -> {
                    val pv = PlayerView(this)
                    pv.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    cell.addView(pv)
                    val p = ExoPlayer.Builder(this).build()
                    players.add(p)
                    pv.player = p
                    p.setMediaSource(RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(android.net.Uri.parse(entry.effectiveUrl()))))
                    p.prepare()
                    p.playWhenReady = true
                }
                CameraEntry.TYPE_MJPEG -> {
                    val iv = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                    cell.addView(iv)
                    val s = MjpegStream(entry.url, entry.username, entry.password, { b -> iv.setImageBitmap(b) }, {})
                    mjs.add(s)
                    s.start()
                }
            }
            val label = TextView(this).apply {
                text = entry.name
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x88000000.toInt())
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            cell.addView(label)
        }
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it.release() }
        players.clear()
        mjs.forEach { it.stop() }
        mjs.clear()
    }
}
