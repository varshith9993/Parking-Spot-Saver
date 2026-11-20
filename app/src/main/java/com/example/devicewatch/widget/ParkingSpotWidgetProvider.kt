package com.example.devicewatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.devicewatch.MainActivity
import com.example.devicewatch.R
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.RelativeTimeFormatter
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingStorage

/**
 * Home screen AppWidget displaying the active parking spot with one-tap Save Spot and Navigate actions.
 */
class ParkingSpotWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        @JvmStatic
        fun updateAllWidgets(context: Context) {
            try {
                val appCtx = context.applicationContext
                val manager = AppWidgetManager.getInstance(appCtx) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(appCtx, ParkingSpotWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(appCtx, manager, id)
                }
            } catch (_: Exception) {
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val storage = ParkingStorage(context)
            val record = storage.getActiveRecord()
            val views = RemoteViews(context.packageName, R.layout.widget_parking_spot)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_HOME, true)
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPending)

            val saveIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_WIDGET_SAVE_SPOT, true)
            }
            val savePending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 2,
                saveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetSaveSpot, savePending)

            if (record == null) {
                views.setTextViewText(R.id.tvWidgetTitle, context.getString(R.string.widget_no_spot_title))
                views.setTextViewText(R.id.tvWidgetSubtitle, context.getString(R.string.widget_no_spot_subtitle))
                views.setViewVisibility(R.id.tvWidgetTime, View.GONE)
                views.setViewVisibility(R.id.btnWidgetNavigate, View.GONE)
            } else {
                val title = record.placeLabel?.takeIf { it.isNotBlank() }
                    ?: record.address?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.saved_spot_badge)
                val subtitle = if (!record.placeLabel.isNullOrBlank() && !record.address.isNullOrBlank()) {
                    record.address
                } else {
                    NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
                }

                views.setTextViewText(R.id.tvWidgetTitle, title)
                views.setTextViewText(R.id.tvWidgetSubtitle, subtitle)

                if (record.savedTimestamp > 0L) {
                    views.setTextViewText(R.id.tvWidgetTime, RelativeTimeFormatter.format(record.savedTimestamp))
                    views.setViewVisibility(R.id.tvWidgetTime, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.tvWidgetTime, View.GONE)
                }

                val navIntent = NavigationManager.buildBestDirectionsIntent(
                    context,
                    record.toParkingSpot(),
                    storage.getNavigationTravelMode()
                )
                if (navIntent != null) {
                    val navPending = PendingIntent.getActivity(
                        context,
                        appWidgetId * 10 + 3,
                        navIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setViewVisibility(R.id.btnWidgetNavigate, View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.btnWidgetNavigate, navPending)
                } else {
                    views.setViewVisibility(R.id.btnWidgetNavigate, View.GONE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
