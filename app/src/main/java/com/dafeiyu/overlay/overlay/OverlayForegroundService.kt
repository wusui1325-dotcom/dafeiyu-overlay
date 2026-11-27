package com.dafeiyu.overlay.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dafeiyu.overlay.DaFeiYuApp
import com.dafeiyu.overlay.MainActivity
import com.dafeiyu.overlay.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayForegroundService : Service() {

    private var controller: FloatingOverlayController? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val app = application as DaFeiYuApp
        controller = FloatingOverlayController(
            context = this,
            configStore = app.configStore,
            historyStore = app.historyStore,
            onStopServiceRequested = { stopSelf() }
        ).also { it.start() }
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_VISIBILITY -> {
                controller?.toggleOrbVisibility()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayForegroundService::class.java).setAction(ACTION_TOGGLE_VISIBILITY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, DaFeiYuApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fish)
            .setContentTitle("大肥魚懸浮助手運行中")
            .setContentText("點擊懸浮球啟動輸入類或選擇類流程")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_visibility_off, "顯示/隱藏", toggleIntent)
            .addAction(R.drawable.ic_close, "停止", stopIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2026
        const val ACTION_STOP = "com.dafeiyu.overlay.ACTION_STOP"
        const val ACTION_TOGGLE_VISIBILITY = "com.dafeiyu.overlay.ACTION_TOGGLE_VISIBILITY"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OverlayForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayForegroundService::class.java))
        }
    }
}
