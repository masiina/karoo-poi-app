package com.karoopoi.extension

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.karoopoi.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

abstract class PoiListDataType(
    extensionId: String,
    typeId: String,
    private val logTag: String,
    private val displayItems: StateFlow<List<PoiDisplayItem>>,
    private val displayState: StateFlow<DisplayState>
) : DataTypeImpl(extensionId, typeId) {

    private var scope: CoroutineScope? = null

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(logTag, "startView called")
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope!!.launch {
            combine(displayState, displayItems) { state, items -> Pair(state, items) }
                .collect { (state, items) ->
                Log.d(logTag, "Updating view: state=$state, items=${items.size}")
                val views = RemoteViews(context.packageName, R.layout.remote_views_poi_list)
                views.removeAllViews(R.id.poi_list_container)
                when {
                    items.isNotEmpty() -> {
                        for (item in items) {
                            val row = RemoteViews(context.packageName, R.layout.remote_views_poi_row)
                            row.setInt(R.id.poi_name, "setGravity", android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
                            row.setViewVisibility(R.id.poi_distance, android.view.View.VISIBLE)
                            row.setTextViewText(R.id.poi_name, "${item.icon} ${item.name}")
                            row.setTextViewText(R.id.poi_distance, item.distance)
                            views.addView(R.id.poi_list_container, row)
                        }
                    }
                    state == DisplayState.NO_ROUTE -> {
                        val placeholder = RemoteViews(context.packageName, R.layout.remote_views_poi_row)
                        placeholder.setInt(R.id.poi_name, "setGravity", android.view.Gravity.CENTER)
                        placeholder.setTextViewText(R.id.poi_name, "No route loaded")
                        placeholder.setViewVisibility(R.id.poi_distance, android.view.View.GONE)
                        views.addView(R.id.poi_list_container, placeholder)
                    }
                    state == DisplayState.LOADED -> {
                        val placeholder = RemoteViews(context.packageName, R.layout.remote_views_poi_row)
                        placeholder.setInt(R.id.poi_name, "setGravity", android.view.Gravity.CENTER)
                        placeholder.setTextViewText(R.id.poi_name, "GPS locating...")
                        placeholder.setViewVisibility(R.id.poi_distance, android.view.View.GONE)
                        views.addView(R.id.poi_list_container, placeholder)
                    }
                    else -> {
                        val placeholder = RemoteViews(context.packageName, R.layout.remote_views_poi_row)
                        placeholder.setInt(R.id.poi_name, "setGravity", android.view.Gravity.CENTER)
                        placeholder.setTextViewText(R.id.poi_name, "--")
                        placeholder.setViewVisibility(R.id.poi_distance, android.view.View.GONE)
                        views.addView(R.id.poi_list_container, placeholder)
                    }
                }
                emitter.updateView(views)
            }
        }
        emitter.setCancellable {
            Log.d(logTag, "stopView called")
            scope?.cancel()
            scope = null
        }
    }
}