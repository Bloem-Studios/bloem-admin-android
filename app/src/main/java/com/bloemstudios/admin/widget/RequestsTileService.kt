package com.bloemstudios.admin.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.links.DeepLink

/**
 * "Requests waiting" in Quick Settings: how many requests wait for approval, from the last background check. A tap opens
 * Requests on All servers. While the device is locked it shows the name only, never the count.
 */
class RequestsTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        AlertsRuntime.surfacePreferences(this).requestsTileAdded = true
        ServerCheckScheduler.reconcile(this)
        ServerCheckScheduler.checkNow(this)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        AlertsRuntime.surfacePreferences(this).requestsTileAdded = false
        ServerCheckScheduler.reconcile(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        val now = System.currentTimeMillis()
        val records = AlertsRuntime.statusStore(this).records()
        val lastCheck = records.values.mapNotNull { it.status.checkedAt }.maxOrNull()
        if (lastCheck == null || now - lastCheck > REFRESH_AFTER_MILLIS) ServerCheckScheduler.checkNow(this)
        render(TileModels.mapRequests(records, now))
    }

    private fun render(model: TileModel) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_requests_label)
        tile.icon = Icon.createWithResource(this, R.drawable.lucide_inbox)
        val subtitle: String?
        when {
            // Like the Now playing tile: the name only, never a count, while the device is locked.
            isLocked -> { tile.state = Tile.STATE_INACTIVE; subtitle = null }
            model is TileModel.Waiting && model.count > 0 -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = resources.getQuantityString(R.plurals.tile_requests_waiting, model.count, model.count)
            }
            model is TileModel.Waiting -> { tile.state = Tile.STATE_INACTIVE; subtitle = getString(R.string.tile_requests_none) }
            else -> { tile.state = Tile.STATE_INACTIVE; subtitle = getString(R.string.tile_no_data) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLink.buildAll("requests")), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }

    private companion object {
        const val REFRESH_AFTER_MILLIS = 5L * 60 * 1000
    }
}
