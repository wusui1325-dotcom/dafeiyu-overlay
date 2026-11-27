package com.dafeiyu.overlay.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Translate
import com.dafeiyu.overlay.ui.web.WebTranslateActivity
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.BuildConfig
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.HistoryStore
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.network.ReleaseInfo
import com.dafeiyu.overlay.system.PermissionSnapshot
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPrimaryButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeTab(
    config: AppConfig,
    historyStore: HistoryStore,
    permissions: PermissionSnapshot,
    overlayRunning: Boolean,
    onToggleOverlay: () -> Unit,
    onStartOnboarding: () -> Unit,
    onJumpToPermissions: () -> Unit,
    onJumpToModel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by historyStore.entries.collectAsState()

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var latestRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
                        text = if (overlayRunning) "點擊喚起選單・長按執行「${config.orbLongPressAction.label}」"
                        else "v${BuildConfig.VERSION_NAME}・無後端 BYOK 直連 DeepSeek",
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

            Spacer(modifier = Modifier.height(8.dp))

            MonoOutlineButton(
                text = "DeepSeek 網頁全文精譯（支援雙語對照）",
                icon = Icons.Rounded.Translate,
                onClick = { WebTranslateActivity.launch(context) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoOutlineButton(
                    text = "引導設置",
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
                } else {
                    MonoOutlineButton(
                        text = if (checkingUpdate) "檢查中…" else "檢查更新",
                        icon = Icons.Rounded.SystemUpdate,
                        enabled = !checkingUpdate,
                        onClick = {
                            checkingUpdate = true
                            updateMessage = null
                            scope.launch {
                                when (val res = DeepSeekClient.checkLatestRelease()) {
                                    is ApiResult.Success -> {
                                        latestRelease = res.data
                                        val currentTag = "v${BuildConfig.VERSION_NAME}"
                                        updateMessage = if (res.data.tagName == currentTag) {
                                            "目前已是最新版本 ($currentTag)"
                                        } else {
                                            "發現新版本：${res.data.tagName}（目前 $currentTag）"
                                        }
                                    }
                                    is ApiResult.Error -> {
                                        updateMessage = res.message
                                    }
                                }
                                checkingUpdate = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (updateMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = updateMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    val rel = latestRelease
                    if (rel != null && rel.tagName != "v${BuildConfig.VERSION_NAME}") {
                        Spacer(modifier = Modifier.width(8.dp))
                        MonoChipButton(
                            text = "下載 APK",
                            icon = Icons.Rounded.Download,
                            selected = true,
                            onClick = {
                                val target = rel.apkUrl ?: rel.htmlUrl
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                    }
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
                icon = Icons.Rounded.ContactPage,
                label = "儲存資料卡片",
                value = if (config.envCards.isEmpty()) "尚未建立" else "${config.envCards.size} 張本機加密",
                modifier = Modifier.weight(1f)
            )
            StatusMetricPill(
                icon = Icons.Rounded.Layers,
                label = "核心權限",
                value = if (permissions.coreReady) "全部就緒" else "待授權",
                modifier = Modifier.weight(1f)
            )
        }

        // Recent Execution History Card
        MonoCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MonoSectionHeader(
                        icon = Icons.Rounded.History,
                        title = "最近執行紀錄",
                        subtitle = if (history.isEmpty()) "完成任務後自動於本機紀錄（僅保留佔位符）"
                        else "本機保留最近 ${history.size} 筆紀錄"
                    )
                }
                if (history.isNotEmpty()) {
                    MonoChipButton(
                        text = "清除",
                        icon = Icons.Rounded.DeleteOutline,
                        selected = false,
                        onClick = { historyStore.clear() }
                    )
                }
            }

            if (history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.take(6).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = entry.mode,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val latencyTag = if (entry.latencyMs > 0) " · ${entry.latencyMs}ms" else ""
                                    Text(
                                        text = "${timeFmt.format(Date(entry.timestampMs))}$latencyTag",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = entry.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            MonoChipButton(
                                text = "複製",
                                icon = Icons.Rounded.ContentCopy,
                                selected = false,
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText(entry.mode, entry.summary))
                                    Toast.makeText(context, "已複製紀錄內容", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
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
                text = "1. 點擊懸浮球選擇「輸入類」，手動點擊目標輸入框\n" +
                    "2. 自動隱藏懸浮窗截圖發送 DeepSeek，支援 [卡片名字] 本機零洩漏替換\n" +
                    "3. 自動聚焦並填入精簡答案",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            MonoSectionHeader(
                icon = Icons.Rounded.Checklist,
                title = "選擇類・大肥魚流程",
                subtitle = "適用於是非題、單選題與多選題"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. 快捷小窗加減或一鍵選取 2 / 4 選項數量\n" +
                    "2. 確定後立即隱藏懸浮窗並行截圖分析，同時由上往下、由左往右標記選項\n" +
                    "3. 模型回傳單／多選編號後自動點擊對應座標",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            MonoSectionHeader(
                icon = Icons.Rounded.Autorenew,
                title = "全自動迴圈 (實驗性)",
                subtitle = "逐步多道問題・支援自動收合鍵盤與長表單自動下滑"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "每輪自動執行：判斷繼續 → 截圖辨識 → 由上往下左至右完成單項並收合鍵盤 → 複查本頁剩餘項目 → 點擊繼續／下一題（或自動下滑）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            MonoSectionHeader(
                icon = Icons.Rounded.Lightbulb,
                title = "快速看圖問答・長按快捷",
                subtitle = "不自動點擊，直接浮出黑白可捲動解答卡"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "長按懸浮球 0.5 秒可直接觸發自訂快捷動作（預設為快速看圖問答），即時解析螢幕重點並支援一鍵複製。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(86.dp))
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
