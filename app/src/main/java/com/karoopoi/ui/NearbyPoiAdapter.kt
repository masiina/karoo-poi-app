package com.karoopoi.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karoopoi.R
import com.karoopoi.geo.GeoUtils
import kotlin.math.roundToInt

data class NearbyPoi(
    val name: String,
    val distance: Double,
    val bearing: Double,
    val compassDir: String,
    val category: String,
    val lat: Double,
    val lon: Double
)

class NearbyPoiAdapter(
    private val onNavigate: (NearbyPoi) -> Unit
) : RecyclerView.Adapter<NearbyPoiAdapter.ViewHolder>() {

    private val items = mutableListOf<NearbyPoi>()

    fun submitList(newItems: List<NearbyPoi>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.nearby_poi_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onNavigate)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.row_icon)
        private val name: TextView = itemView.findViewById(R.id.row_name)
        private val distance: TextView = itemView.findViewById(R.id.row_distance)
        private val direction: TextView = itemView.findViewById(R.id.row_direction)
        private val navigateButton: Button = itemView.findViewById(R.id.row_navigate)

        fun bind(poi: NearbyPoi, onNavigate: (NearbyPoi) -> Unit) {
            name.text = poi.name
            distance.text = formatDistance(poi.distance)
            direction.text = "${bearingToArrow(poi.bearing)} ${poi.compassDir}"
            icon.text = categoryEmoji(poi.category)
            navigateButton.setOnClickListener { onNavigate(poi) }
        }

        private fun formatDistance(meters: Double): String {
            return if (meters >= 1000) {
                String.format("%.1fkm", meters / 1000)
            } else {
                "${meters.roundToInt()}m"
            }
        }

        private fun bearingToArrow(bearing: Double): String {
            return when (GeoUtils.compassDirection(bearing)) {
                "N" -> "↑"
                "NE" -> "↗"
                "E" -> "→"
                "SE" -> "↘"
                "S" -> "↓"
                "SW" -> "↙"
                "W" -> "←"
                "NW" -> "↖"
                else -> "↑"
            }
        }

        private fun categoryEmoji(category: String): String {
            return when (category) {
                "swimming", "beach" -> "🏖"
                "supermarket", "convenience" -> "🏪"
                "viewpoint" -> "⛰"
                else -> "📍"
            }
        }
    }
}
