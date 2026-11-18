package com.example.devicewatch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.ItemParkingPhotoThumbBinding
import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.storage.ParkingPhotoManager

class ParkingPhotoAdapter(
    private val photoManager: ParkingPhotoManager,
    private val onPhotoClick: (index: Int, item: ParkingPhotoItem) -> Unit,
    private val onRemoveClick: ((index: Int, item: ParkingPhotoItem) -> Unit)? = null
) : RecyclerView.Adapter<ParkingPhotoAdapter.PhotoViewHolder>() {

    private var items: List<ParkingPhotoItem> = emptyList()

    fun submitList(newItems: List<ParkingPhotoItem>) {
        items = newItems.toList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemParkingPhotoThumbBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class PhotoViewHolder(
        private val binding: ItemParkingPhotoThumbBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ParkingPhotoItem, position: Int) {
            binding.ivPhotoThumb.tag = item.filePath
            binding.ivPhotoThumb.setImageDrawable(null)
            binding.tvPhotoThumbMissing.visibility = View.GONE

            photoManager.loadSampledBitmap(item.filePath, THUMB_REQ_SIZE, THUMB_REQ_SIZE) { bitmap ->
                if (binding.ivPhotoThumb.tag != item.filePath) return@loadSampledBitmap
                if (bitmap != null) {
                    binding.ivPhotoThumb.setImageBitmap(bitmap)
                    binding.tvPhotoThumbMissing.visibility = View.GONE
                } else {
                    binding.ivPhotoThumb.setImageDrawable(null)
                    binding.tvPhotoThumbMissing.visibility = View.VISIBLE
                }
            }

            binding.root.setOnClickListener {
                val adapterPos = bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION && adapterPos < items.size) {
                    onPhotoClick(adapterPos, items[adapterPos])
                }
            }

            val removeHandler = onRemoveClick
            if (removeHandler != null) {
                binding.btnRemovePhotoThumb.visibility = View.VISIBLE
                binding.btnRemovePhotoThumb.setOnClickListener {
                    val adapterPos = bindingAdapterPosition
                    if (adapterPos != RecyclerView.NO_POSITION && adapterPos < items.size) {
                        removeHandler(adapterPos, items[adapterPos])
                    }
                }
            } else {
                binding.btnRemovePhotoThumb.visibility = View.GONE
                binding.btnRemovePhotoThumb.setOnClickListener(null)
            }
        }
    }

    companion object {
        private const val THUMB_REQ_SIZE = 320
    }
}
