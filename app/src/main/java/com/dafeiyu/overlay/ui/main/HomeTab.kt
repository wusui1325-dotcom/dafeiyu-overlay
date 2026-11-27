package com.dafeiyu.overlay.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.system.PermissionSnapshot
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPrimaryButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import java.util.Locale

@Composable
fun HomeTab(
    config: AppConfig,
    permissions: PermissionSnapshot,
    overlayRunning: Boolean,
    onToggleOverlay: () -> Unit,
    onStartOnboarding: () -> Unit,
    onJumpToPermissions: () -> Unit,
    onJumpToModel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Launch Card
        MonoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fish),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (overlayRunning) "懸浮助手運行中" else "大肥魚準備就緒",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (overlayRunning) "點擊螢幕上的黑白懸浮球即可喚起流程"
                        else "無後端 BYOK・直連 DeepSeek 視覺模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            MonoPrimaryButton(
                text = if (overlayRunning) "停止懸浮服務" else "啟動大肥魚懸浮球",
                icon = if (overlayRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                onClick = onToggleOverlay,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoOutlineButton(
                    text = "重新引導設置",
                    icon = Icons.Rounded.Explore,
                    onClick = onStartOnboarding,
                    modifier = Modifier.weight(1f)
                )
                if (!permissions.coreReady) {
                    MonoOutlineButton(
                        text = "補齊權限",
                        icon = Icons.Rounded.VerifiedUser,
                        onClick = onJumpToPermissions,
                        modifier = Modifier.weight(1f)
                    )
                } else if (config.apiKey.isBlank()) {
                    MonoOutlineButton(
                        text = "設定金鑰",
                        icon = Icons.Rounded.Key,
                        onClick = onJumpToModel,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Status Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusMetricPill(
                icon = Icons.Rounded.AutoAwesome,
                label = "目前模型",
                value = config.modelId,
                modifier = Modifier.weight(1f)
            )
            StatusMetricPill(
                icon = Icons.Rounded.Thermostat,
                label = "Temperature",
                value = String.format(Locale.US, "%.2f", config.temperature),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusMetricPill(
                icon = Icons.Rounded.Key,
                label = "BYOK 金鑰",
                value = if (config.apiKey.isNotBlank()) "已加密保存" else "尚未填寫",
                modifier = Modifier.weight(1f)
            )
            StatusMetricPill(
                icon = Icons.Rounded.Layers,
                label = "核心權限",
                value = if (permissions.coreReady) "全部就緒" else "待授權",
                modifier = Modifier.weight(1f)
            )
        }

        // Workflow Guide Card
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Edit,
                title = "輸入類・大肥魚流程",
                subtitle = "適用於填空題、簡答題、表單輸入框"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. 點擊懸浮球選擇「輸入類」\n" +
                    "2. 手動點擊一次目標輸入框位置\n" +
                    "3. 自動隱藏懸浮窗並立即截圖發送至 DeepSeek\n" +
                    "4. 模型僅回傳精簡輸入文字，系統自動填入目標位置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MonoSectionHeader(
                icon = Icons.Rounded.Checklist,
                title = "選擇類・大肥魚流程",
                subtitle = "適用於是非題、單選題與多選題"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. 點擊懸浮球選擇「選擇類」，開啟數量快捷小窗\n" +
                    "2. 手動加減或一鍵點選「2 / 4」快捷數量並按確定\n" +
                    "3. 立即隱藏懸浮窗截圖發送 DeepSeek，同時由上往下、由左往右依序點擊各選項座標\n" +
                    "4. 模型回傳單選或多選編號後，系統自動依序點擊答案",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MonoSectionHeader(
                icon = Icons.Rounded.Autorenew,
                title = "全自動迴圈 (實驗性)",
                subtitle = "推薦場景：逐步的多道問題・隨時可點頂部停止鍵中斷"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. 識別畫面是否仍需繼續迴圈\n" +
                    "2. 隱藏懸浮窗截圖，結合 0..1000 歸一化座標與無障礙元件識別題型與位置\n" +
                    "3. 按從上往下、從左往右順序完成一條未完成項目（支援本機加密卡片替換）\n" +
                    "4. 複查本頁是否仍有未完成項目，若有則重複步驟 3 → 4\n" +
                    "5. 尋找並點擊「下一題 / 繼續」按鈕進入下一輪",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun StatusMetricPill(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}
