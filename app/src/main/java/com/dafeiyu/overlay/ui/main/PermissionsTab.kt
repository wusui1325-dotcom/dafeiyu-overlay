package com.dafeiyu.overlay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.system.PermissionHelper
import com.dafeiyu.overlay.system.PermissionSnapshot
import com.dafeiyu.overlay.system.ShizukuHelper
import com.dafeiyu.overlay.system.ShizukuStatus
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPermissionRow
import com.dafeiyu.overlay.ui.components.MonoSectionHeader

@Composable
fun PermissionsTab(
    permissions: PermissionSnapshot,
    onRefresh: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestAudio: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Security,
                title = "系統權限中心",
                subtitle = "懸浮窗與無障礙為核心必備；其餘權限用於強化背景穩定性"
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonoOutlineButton(
                text = "重新偵測權限狀態",
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            )
        }

        MonoPermissionRow(
            icon = Icons.Rounded.Layers,
            title = "顯示在其他應用程式上層",
            subtitle = "必要權限：繪製大肥魚懸浮球、模式選單、快捷數量窗與座標標記",
            granted = permissions.overlayGranted,
            actionIcon = Icons.Rounded.Settings,
            actionLabel = if (permissions.overlayGranted) "設定" else "授權",
            onActionClick = { PermissionHelper.openOverlaySettings(context) }
        )

        MonoPermissionRow(
            icon = Icons.Rounded.AccessibilityNew,
            title = "無障礙輔助服務",
            subtitle = "必要權限：免錄屏彈窗截圖 (Android 11+)、自動點擊選項與填入輸入框",
            granted = permissions.accessibilityGranted,
            actionIcon = Icons.Rounded.Settings,
            actionLabel = if (permissions.accessibilityGranted) "管理" else "開啟",
            onActionClick = { PermissionHelper.openAccessibilitySettings(context) }
        )

        MonoPermissionRow(
            icon = Icons.Rounded.Notifications,
            title = "通知權限",
            subtitle = "前景服務通知，提供一鍵「顯示/隱藏懸浮球」與「停止」按鈕",
            granted = permissions.notificationGranted,
            actionIcon = Icons.Rounded.Notifications,
            actionLabel = if (permissions.notificationGranted) "設定" else "允許",
            onActionClick = {
                if (permissions.notificationGranted) {
                    PermissionHelper.openNotificationSettings(context)
                } else {
                    onRequestNotification()
                }
            }
        )

        MonoPermissionRow(
            icon = Icons.Rounded.BatteryChargingFull,
            title = "背景運行・忽略電池優化",
            subtitle = "避免系統在切換其他應用時凍結或關閉大肥魚懸浮服務",
            granted = permissions.batteryOptimizationIgnored,
            actionIcon = Icons.Rounded.BatteryChargingFull,
            actionLabel = if (permissions.batteryOptimizationIgnored) "已豁免" else "豁免",
            onActionClick = { PermissionHelper.openBatteryOptimizationSettings(context) }
        )

        MonoPermissionRow(
            icon = Icons.Rounded.Mic,
            title = "麥克風語音權限 (選用)",
            subtitle = "啟用「語音指揮螢幕」功能，開口即可讓 AI 代理人操作畫面",
            granted = permissions.recordAudioGranted,
            actionIcon = Icons.Rounded.Mic,
            actionLabel = if (permissions.recordAudioGranted) "已允許" else "授予",
            onActionClick = onRequestAudio
        )

        MonoPermissionRow(
            icon = Icons.Rounded.Terminal,
            title = "Shizuku (選用進階支援)",
            subtitle = "若部分應用阻擋無障礙輸入，可透過 Shizuku 提供 Shell 備援輸入",
            granted = permissions.shizukuStatus == ShizukuStatus.READY,
            statusLabel = permissions.shizukuStatus.label,
            actionIcon = Icons.Rounded.Terminal,
            actionLabel = if (permissions.shizukuStatus == ShizukuStatus.READY) "已就緒" else "請求",
            onActionClick = {
                ShizukuHelper.requestPermission()
                onRefresh()
            }
        )

        Spacer(modifier = Modifier.height(80.dp))
    }
}
