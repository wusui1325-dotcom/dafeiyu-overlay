package com.dafeiyu.overlay.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.data.ReasoningEffort
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.system.PermissionHelper
import com.dafeiyu.overlay.system.PermissionSnapshot
import com.dafeiyu.overlay.system.ShizukuHelper
import com.dafeiyu.overlay.system.ShizukuStatus
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPermissionRow
import com.dafeiyu.overlay.ui.components.MonoPrimaryButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import com.dafeiyu.overlay.ui.components.MonoSliderRow
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    config: AppConfig,
    configStore: ConfigStore,
    permissions: PermissionSnapshot,
    onRequestNotification: () -> Unit,
    onFinish: (launchOverlay: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val totalSteps = 4

    var showApiKey by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // Top Step Indicator Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fish),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "大肥魚・初始化引導",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "${step + 1} / $totalSteps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Streamlined progress pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(totalSteps) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (step) {
                0 -> {
                    Text(
                        text = "無後端・BYOK 懸浮輔助",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "直接連線您的 DeepSeek API，不經過任何第三方伺服器。隨時在其他應用上喚起「大肥魚」流程，自動截圖分析並填入或點選答案。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonoCard {
                        MonoSectionHeader(
                            icon = Icons.Rounded.Edit,
                            title = "輸入類流程",
                            subtitle = "點選輸入框 → 隱藏懸浮窗截圖 → DeepSeek 精簡回傳 → 直接輸入"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        MonoSectionHeader(
                            icon = Icons.Rounded.Checklist,
                            title = "選擇類流程",
                            subtitle = "設定選項數量 (快捷 2 / 4) → 同時截圖分析並依序標記選項 → 自動單選/多選點擊"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        MonoSectionHeader(
                            icon = Icons.Rounded.Security,
                            title = "純本地加密儲存",
                            subtitle = "API Key 使用 Android Keystore 本地加密保存，完全開源透明"
                        )
                    }
                }

                1 -> {
                    Text(
                        text = "設定 DeepSeek BYOK 金鑰",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "輸入您的 DeepSeek API Key，支援官方端點或任何 OpenAI 相容閘道。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonoCard {
                        MonoSectionHeader(
                            icon = Icons.Rounded.Key,
                            title = "API 憑證與端點",
                            subtitle = "預設直連 https://api.deepseek.com"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = config.apiKey,
                            onValueChange = { v -> configStore.update { it.copy(apiKey = v.trim()) } },
                            label = { Text("DeepSeek API Key (sk-...)") },
                            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = config.baseUrl,
                            onValueChange = { v -> configStore.update { it.copy(baseUrl = v) } },
                            label = { Text("API Base URL") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        MonoOutlineButton(
                            text = if (testing) "驗證連線中…" else "測試連線並拉取模型",
                            icon = Icons.Rounded.Refresh,
                            enabled = !testing && config.apiKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                testing = true
                                testStatus = null
                                scope.launch {
                                    when (val res = DeepSeekClient.fetchModels(config.apiKey, config.baseUrl)) {
                                        is ApiResult.Success -> {
                                            configStore.update { it.copy(cachedModels = res.data) }
                                            testStatus = "連線成功！已取得 ${res.data.size} 個可用模型。"
                                        }
                                        is ApiResult.Error -> {
                                            testStatus = res.message
                                        }
                                    }
                                    testing = false
                                }
                            }
                        )
                        if (testStatus != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = testStatus.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                2 -> {
                    Text(
                        text = "模型與生成參數",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "建議使用 deepseek-flash（原生支援視覺截圖輸入）搭配較低 Temperature，讓填答更穩定精準。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonoCard {
                        MonoSectionHeader(
                            icon = Icons.Rounded.AutoAwesome,
                            title = "選擇模型型號",
                            subtitle = "目前：${config.modelId}"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            config.cachedModels.forEach { model ->
                                MonoChipButton(
                                    text = model,
                                    icon = Icons.Rounded.AutoAwesome,
                                    selected = config.modelId == model,
                                    onClick = { configStore.update { it.copy(modelId = model) } }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        MonoSliderRow(
                            icon = Icons.Rounded.Thermostat,
                            title = "Temperature (溫度)",
                            valueLabel = String.format(Locale.US, "%.2f", config.temperature),
                            value = config.temperature,
                            valueRange = 0f..2f,
                            steps = 39,
                            onValueChange = { v -> configStore.update { it.copy(temperature = v) } }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MonoSectionHeader(
                            icon = Icons.Rounded.Tune,
                            title = "思考強度 (Reasoning Effort)",
                            subtitle = "所有檔位皆強制關閉深度思考 (thinking: disabled)"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReasoningEffort.entries.forEach { effort ->
                                MonoChipButton(
                                    text = effort.label,
                                    icon = Icons.Rounded.Tune,
                                    selected = config.reasoningEffort == effort,
                                    onClick = { configStore.update { it.copy(reasoningEffort = effort) } }
                                )
                            }
                        }
                    }
                }

                3 -> {
                    Text(
                        text = "必要權限配置",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "授予懸浮窗與無障礙權限後即可立即啟動大肥魚流程；通知、電池與 Shizuku 可依需求啟用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonoPermissionRow(
                        icon = Icons.Rounded.Layers,
                        title = "懸浮視窗權限",
                        subtitle = "在其他應用上方顯示大肥魚懸浮球與快捷選單",
                        granted = permissions.overlayGranted,
                        actionIcon = Icons.Rounded.Settings,
                        actionLabel = if (permissions.overlayGranted) "管理" else "前往授權",
                        onActionClick = { PermissionHelper.openOverlaySettings(context) }
                    )
                    MonoPermissionRow(
                        icon = Icons.Rounded.AccessibilityNew,
                        title = "無障礙服務",
                        subtitle = "免彈窗截圖、模擬點擊選項與自動輸入文字",
                        granted = permissions.accessibilityGranted,
                        actionIcon = Icons.Rounded.Settings,
                        actionLabel = if (permissions.accessibilityGranted) "管理" else "前往開啟",
                        onActionClick = { PermissionHelper.openAccessibilitySettings(context) }
                    )
                    MonoPermissionRow(
                        icon = Icons.Rounded.Notifications,
                        title = "通知權限",
                        subtitle = "顯示前景常駐通知以快速切換隱藏或停止",
                        granted = permissions.notificationGranted,
                        actionIcon = Icons.Rounded.Notifications,
                        actionLabel = if (permissions.notificationGranted) "已允許" else "授予",
                        onActionClick = onRequestNotification
                    )
                    MonoPermissionRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "忽略電池優化",
                        subtitle = "防止背景運行懸浮服務被系統回收",
                        granted = permissions.batteryOptimizationIgnored,
                        actionIcon = Icons.Rounded.BatteryChargingFull,
                        actionLabel = if (permissions.batteryOptimizationIgnored) "已豁免" else "設定",
                        onActionClick = { PermissionHelper.openBatteryOptimizationSettings(context) }
                    )
                    MonoPermissionRow(
                        icon = Icons.Rounded.Terminal,
                        title = "Shizuku (選用)",
                        subtitle = "進階 Shell 輸入備援，不開啟亦可完整使用",
                        granted = permissions.shizukuStatus == ShizukuStatus.READY,
                        statusLabel = permissions.shizukuStatus.label,
                        actionIcon = Icons.Rounded.Terminal,
                        actionLabel = "授權",
                        onActionClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bottom Navigation Buttons (Every button has an icon)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                MonoOutlineButton(
                    text = "上一步",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = { step-- },
                    modifier = Modifier.weight(1f)
                )
            } else {
                MonoOutlineButton(
                    text = "略過引導",
                    icon = Icons.Rounded.Check,
                    onClick = { onFinish(false) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (step < totalSteps - 1) {
                MonoPrimaryButton(
                    text = "下一步",
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = { step++ },
                    modifier = Modifier.weight(1f)
                )
            } else {
                MonoPrimaryButton(
                    text = if (permissions.overlayGranted) "完成並啟動" else "完成設定",
                    icon = if (permissions.overlayGranted) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                    onClick = { onFinish(permissions.overlayGranted) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
