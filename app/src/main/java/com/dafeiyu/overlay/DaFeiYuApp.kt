package com.dafeiyu.overlay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.dafeiyu.overlay.data.ConfigStore

class DaFeiYuApp : Application() {

    lateinit var configStore: ConfigStore
        private set

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "大肥魚懸浮服務",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持大肥魚懸浮球在背景穩定運行"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "dafeiyu_overlay_channel"
    }
}
