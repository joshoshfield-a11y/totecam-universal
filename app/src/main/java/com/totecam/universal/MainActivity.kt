package com.totecam.universal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: CameraAdapter
    private val exec = Executors.newSingleThreadExecutor()
    private var pendingEntry: CameraEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        adapter = CameraAdapter(
            mutableListOf(),
            onOpen = { openPlayer(it) },
            onDelete = { CameraStore.remove(this, it); refresh() },
            onEdit = { showAddDialog(it) }
        )
        val rv = findViewById<RecyclerView>(R.id.cameraList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog(null) }
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnGrid).setOnClickListener { openGrid() }
        refresh()
    }

    private fun refresh() {
        val cams = CameraStore.load(this)
        adapter.submit(cams)
        findViewById<TextView>(R.id.emptyText).visibility = if (cams.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openPlayer(entry: CameraEntry) {
        if (entry.type == CameraEntry.TYPE_DEVICE || entry.type == CameraEntry.TYPE_USB) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingEntry = entry
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
                return
            }
        }
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("entry", entry)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingEntry?.let { openPlayer(it) }
            pendingEntry = null
        }
    }

    private fun openGrid() {
        val ipCams = CameraStore.load(this).filter { it.type == CameraEntry.TYPE_RTSP || it.type == CameraEntry.TYPE_MJPEG }
        if (ipCams.isEmpty()) { toast("Add or scan for cameras first"); return }
        val intent = Intent(this, MultiViewActivity::class.java)
        intent.putStringArrayListExtra("ids", ArrayList(ipCams.take(4).map { it.id }))
        startActivity(intent)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showAddDialog(existing: CameraEntry?) {
        val view = layoutInflater.inflate(R.layout.dialog_add, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)
        val spinner = view.findViewById<Spinner>(R.id.spinnerType)
        val types = listOf(CameraEntry.TYPE_RTSP, CameraEntry.TYPE_MJPEG, CameraEntry.TYPE_USB, CameraEntry.TYPE_DEVICE)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        existing?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            etUser.setText(it.username)
            etPass.setText(it.password)
            spinner.setSelection(types.indexOf(it.type).coerceAtLeast(0))
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add camera" else "Edit camera")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val e = existing ?: CameraEntry()
                e.name = etName.text.toString().ifEmpty { "Camera" }
                e.type = spinner.selectedItem as String
                e.url = etUrl.text.toString().trim()
                e.username = etUser.text.toString()
                e.password = etPass.text.toString()
                if (e.type == CameraEntry.TYPE_DEVICE || e.type == CameraEntry.TYPE_USB) e.url = ""
                CameraStore.save(this, e)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startScan() {
        val view = layoutInflater.inflate(R.layout.dialog_scan, null)
        val etUser = view.findViewById<EditText>(R.id.etScanUser)
        val etPass = view.findViewById<EditText>(R.id.etScanPass)
        val status = view.findViewById<TextView>(R.id.scanStatus)
        val btn = view.findViewById<Button>(R.id.btnStartScan)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Scan network for cameras")
            .setView(view)
            .setNegativeButton("Close", null)
            .create()
        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = "Starting…"
            exec.execute {
                val found = Discovery.scan(this, etUser.text.toString(), etPass.text.toString()) { msg ->
                    runOnUiThread { status.text = msg }
                }
                runOnUiThread {
                    var added = 0
                    val existing = CameraStore.load(this)
                    for (f in found) {
                        val dup = existing.any { it.url.isNotEmpty() && it.url == f.url }
                        if (!dup) { CameraStore.save(this, f); added++ }
                    }
                    refresh()
                    status.text = "Done. $added new camera(s) added."
                    btn.isEnabled = true
                    if (added > 0) toast("Found $added camera(s)")
                }
            }
        }
        dlg.show()
    }
}
