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
 * "Now playing" in Quick Settings: the live stream count from the last background check, and a tap opens Now playing on
 * All servers. While the device is locked it shows the name only, never the count.
 */
class NowPlayingTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        AlertsRuntime.surfacePreferences(this).tileAdded = true
        ServerCheckScheduler.reconcile(this)
        ServerCheckScheduler.checkNow(this)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        AlertsRuntime.surfacePreferences(this).tileAdded = false
        ServerCheckScheduler.reconcile(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        val now = System.currentTimeMillis()
        val records = AlertsRuntime.statusStore(this).records()
        // The tile is on screen: if the last check is old, ask for a new one (the check itself is rate-limited by WorkManager).
        val lastCheck = records.values.mapNotNull { it.status.checkedAt }.maxOrNull()
        if (lastCheck == null || now - lastCheck > REFRESH_AFTER_MILLIS) ServerCheckScheduler.checkNow(this)
        render(TileModels.map(records, now))
    }

    private fun render(model: TileModel) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_bloem)
        val subtitle: String?
        when {
            isLocked -> { tile.state = Tile.STATE_INACTIVE; subtitle = null }
            model is TileModel.Streams && model.count > 0 -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = resources.getQuantityString(R.plurals.tile_streams, model.count, model.count)
            }
            model is TileModel.Streams -> { tile.state = Tile.STATE_INACTIVE; subtitle = getString(R.string.tile_nothing_playing) }
            else -> { tile.state = Tile.STATE_INACTIVE; subtitle = getString(R.string.tile_no_data) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Asks for the device to be unlocked first, then opens the app, which asks for the app lock if it is on.
        unlockAndRun {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLink.buildAll("now-playing")), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                // Only reached below Android 14, where the Intent form is the supported one.
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
