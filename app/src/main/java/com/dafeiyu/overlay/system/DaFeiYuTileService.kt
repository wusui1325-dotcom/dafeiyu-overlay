package com.dafeiyu.overlay.system

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dafeiyu.overlay.MainActivity
import com.dafeiyu.overlay.overlay.OverlayForegroundService

class DaFeiYuTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val running = OverlayForegroundService.isRunning.value
        if (running) {
            OverlayForegroundService.stop(this)
            updateTile(false)
        } else {
            if (!PermissionHelper.hasOverlayPermission(this)) {
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }
            OverlayForegroundService.start(this)
            updateTile(true)
        }
    }

    private fun syncTileState() {
        updateTile(OverlayForegroundService.isRunning.value)
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "大肥魚"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "懸浮運行中" else "點擊啟動"
        }
        tile.updateTile()
    }
}
