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

                    val rowIds = intArrayOf(R.id.poi_row_0, R.id.poi_row_1, R.id.poi_row_2, R.id.poi_row_3, R.id.poi_row_4)
                    val nameIds = intArrayOf(R.id.poi_name_0, R.id.poi_name_1, R.id.poi_name_2, R.id.poi_name_3, R.id.poi_name_4)
                    val distIds = intArrayOf(R.id.poi_distance_0, R.id.poi_distance_1, R.id.poi_distance_2, R.id.poi_distance_3, R.id.poi_distance_4)

                    if (items.isNotEmpty()) {
                        // Hide placeholder, show POI rows
                        views.setViewVisibility(R.id.poi_placeholder, android.view.View.GONE)
                        for (i in rowIds.indices) {
                            if (i < items.size) {
                                val item = items[i]
                                views.setViewVisibility(rowIds[i], android.view.View.VISIBLE)
                                views.setTextViewText(nameIds[i], "${item.icon} ${item.name}")
                                views.setTextViewText(distIds[i], item.distance)
                            } else {
                                views.setViewVisibility(rowIds[i], android.view.View.GONE)
                            }
                        }
                    } else {
                        // Hide all POI rows, show placeholder
                        for (rowId in rowIds) {
                            views.setViewVisibility(rowId, android.view.View.GONE)
                        }
                        views.setViewVisibility(R.id.poi_placeholder, android.view.View.VISIBLE)
                        val message = when (state) {
                            DisplayState.NO_ROUTE -> "No route loaded"
                            else -> "--"
                        }
                        views.setTextViewText(R.id.poi_placeholder, message)
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