package com.example.devicewatch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.ItemPlaceSearchResultBinding
import com.example.devicewatch.location.PlaceSearchResult
import com.example.devicewatch.location.SearchResultKind

internal class PlaceSearchAdapter(
    private val onPlaceClicked: (PlaceSearchResult) -> Unit
) : RecyclerView.Adapter<PlaceSearchAdapter.PlaceViewHolder>() {

    private var items: List<PlaceSearchResult> = emptyList()

    fun submitResults(next: List<PlaceSearchResult>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(items[position], isLast = position == items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class PlaceViewHolder(
        private val binding: ItemPlaceSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlaceSearchResult, isLast: Boolean) {
            val ctx = binding.root.context
            val (iconRes, tintRes) = when (item.kind) {
                SearchResultKind.COORDINATE -> R.drawable.ic_my_location to R.color.ps_accent_blue
                SearchResultKind.POSTCODE -> R.drawable.ic_search to R.color.ps_primary
                SearchResultKind.SAVED_SPOT -> R.drawable.ic_bookmark_filled to R.color.ps_primary
                SearchResultKind.HISTORY_SPOT -> R.drawable.ic_nav_history to R.color.ps_text_secondary
                SearchResultKind.PLACE -> R.drawable.ic_pin_drop to R.color.ps_primary
            }
            binding.ivPlaceResultIcon.setImageResource(iconRes)
            binding.ivPlaceResultIcon.setColorFilter(ContextCompat.getColor(ctx, tintRes))

            binding.tvPlaceTitle.text = item.title
            if (item.subtitle.isBlank()) {
                binding.tvPlaceSubtitle.visibility = View.GONE
            } else {
                binding.tvPlaceSubtitle.visibility = View.VISIBLE
                binding.tvPlaceSubtitle.text = item.subtitle
            }
            binding.viewPlaceDivider.visibility = if (isLast) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { onPlaceClicked(item) }
        }
    }
}
