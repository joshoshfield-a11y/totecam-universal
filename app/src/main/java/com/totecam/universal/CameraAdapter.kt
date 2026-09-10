package com.totecam.universal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CameraAdapter(
    private val items: MutableList<CameraEntry>,
    private val onOpen: (CameraEntry) -> Unit,
    private val onDelete: (CameraEntry) -> Unit,
    private val onEdit: (CameraEntry) -> Unit
) : RecyclerView.Adapter<CameraAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.itemName)
        val badge: TextView = v.findViewById(R.id.itemBadge)
        val sub: TextView = v.findViewById(R.id.itemSub)
        val del: Button = v.findViewById(R.id.itemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_camera, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.name
        h.badge.text = e.type
        h.sub.text = e.url.ifEmpty { e.note }
        h.itemView.setOnClickListener { onOpen(e) }
        h.itemView.setOnLongClickListener { onEdit(e); true }
        h.del.setOnClickListener { onDelete(e) }
    }

    fun submit(new: List<CameraEntry>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }
}
