package com.framecaddy.app

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class FrameItem(val bitmap: Bitmap, val timestampMs: Long)

class FrameAdapter(
    private val onFrameClick: (Long) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FrameAdapter.VH>() {

    private val frames = mutableListOf<FrameItem>()
    private val selected = mutableSetOf<Int>()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.frameImage)
        val label: TextView = view.findViewById(R.id.frameLabel)
        val check: CheckBox = view.findViewById(R.id.frameCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false))

    override fun getItemCount() = frames.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame = frames[position]
        holder.image.setImageBitmap(frame.bitmap)
        holder.label.text = "${frame.timestampMs}ms"
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = position in selected
        holder.itemView.setOnClickListener { onFrameClick(frame.timestampMs) }
        holder.itemView.setOnLongClickListener { toggle(position); true }
        holder.check.setOnClickListener { toggle(position) }
    }

    private fun toggle(pos: Int) {
        if (pos in selected) selected.remove(pos) else selected.add(pos)
        notifyItemChanged(pos)
        onSelectionChanged(selected.size)
    }

    fun setFrames(list: List<FrameItem>) {
        frames.clear(); frames.addAll(list); selected.clear()
        notifyDataSetChanged(); onSelectionChanged(0)
    }

    fun clearFrames() {
        frames.clear(); selected.clear()
        notifyDataSetChanged(); onSelectionChanged(0)
    }

    fun getSelectedFrames(): List<FrameItem> = selected.sorted().map { frames[it] }
}
