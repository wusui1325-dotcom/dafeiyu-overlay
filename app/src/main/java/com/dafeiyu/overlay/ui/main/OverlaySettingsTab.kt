package com.dafeiyu.overlay.ui.main

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.data.OverlayThemeMode
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import com.dafeiyu.overlay.ui.components.MonoSliderRow
import com.dafeiyu.overlay.ui.components.MonoToggleRow
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlaySettingsTab(
    config: AppConfig,
    configStore: ConfigStore
) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live Monochrome Preview Card
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Contrast,
                title = "懸浮球即時預覽",
                subtitle = "調整參數會同步套用至運行中的懸浮視窗"
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                val isDarkOrb = config.overlayTheme == OverlayThemeMode.DARK ||
                    (config.overlayTheme == OverlayThemeMode.AUTO && config.appDarkTheme)
                val orbBg = if (isDarkOrb) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
                val orbFg = if (isDarkOrb) Color(0xFFFFFFFF) else Color(0xFF0A0A0A)
                val orbBorder = if (isDarkOrb) Color(0xFF2E2E32) else Color(0xFFDCDCE0)

                Row(
                    modifier = Modifier
                        .alpha(config.overlayAlpha)
                        .height(config.overlaySizeDp.dp)
                        .clip(RoundedCornerShape((config.overlaySizeDp / 2 + 4).dp))
                        .background(orbBg)
                        .border(1.dp, orbBorder, RoundedCornerShape((config.overlaySizeDp / 2 + 4).dp))
                        .padding(horizontal = if (config.showPillLabel) 14.dp else (config.overlaySizeDp * 0.25f).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fish),
                        contentDescription = null,
                        tint = orbFg,
                        modifier = Modifier.size((config.overlaySizeDp * 0.48f).dp)
                    )
                    if (config.showPillLabel) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "大肥魚",
                            color = orbFg,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Size, Opacity, Style
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.AspectRatio,
                title = "外觀與尺寸自定義",
                subtitle = "高度自由度控制懸浮球大小、透明度與黑白風格"
            )
            Spacer(modifier = Modifier.height(10.dp))
            MonoSliderRow(
                icon = Icons.Rounded.AspectRatio,
                title = "懸浮大小",
                valueLabel = "${config.overlaySizeDp} dp",
                value = config.overlaySizeDp.toFloat(),
                valueRange = 36f..84f,
                onValueChange = { v -> configStore.update { it.copy(overlaySizeDp = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.Opacity,
                title = "懸浮透明度",
                valueLabel = "${(config.overlayAlpha * 100).roundToInt()}%",
                value = config.overlayAlpha,
                valueRange = 0.2f..1.0f,
                onValueChange = { v -> configStore.update { it.copy(overlayAlpha = v) } }
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverlayThemeMode.entries.forEach { mode ->
                    MonoChipButton(
                        text = mode.label,
                        icon = Icons.Rounded.Contrast,
                        selected = config.overlayTheme == mode,
                        onClick = { configStore.update { it.copy(overlayTheme = mode) } }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            MonoToggleRow(
                icon = Icons.Rounded.Label,
                title = "流線膠囊文字標籤",
                subtitle = "在懸浮圖示旁顯示「大肥魚」字樣",
                checked = config.showPillLabel,
                onCheckedChange = { v -> configStore.update { it.copy(showPillLabel = v) } }
            )
            MonoToggleRow(
                icon = Icons.Rounded.Dock,
                title = "自動貼邊吸附",
                subtitle = "拖曳放開後自動吸附至螢幕左側或右側邊緣",
                checked = config.snapToEdge,
                onCheckedChange = { v -> configStore.update { it.copy(snapToEdge = v) } }
            )
            MonoToggleRow(
                icon = Icons.Rounded.Vibration,
                title = "操作觸覺回饋",
                subtitle = "點擊懸浮按鈕與標記座標時提供微震動",
                checked = config.hapticFeedback,
                onCheckedChange = { v -> configStore.update { it.copy(hapticFeedback = v) } }
            )
        }

        // Position Presets
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.OpenWith,
                title = "懸浮位置管理",
                subtitle = "目前座標：X=${config.overlayPosX}px, Y=${config.overlayPosY}px"
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoChipButton(
                    text = "左上",
                    icon = Icons.Rounded.OpenWith,
                    selected = false,
                    onClick = { configStore.update { it.copy(overlayPosX = 24, overlayPosY = 220) } }
                )
                MonoChipButton(
                    text = "右上",
                    icon = Icons.Rounded.OpenWith,
                    selected = false,
                    onClick = {
                        configStore.update {
                            it.copy(overlayPosX = (dm.widthPixels - 180).coerceAtLeast(24), overlayPosY = 220)
                        }
                    }
                )
                MonoChipButton(
                    text = "左中",
                    icon = Icons.Rounded.OpenWith,
                    selected = false,
                    onClick = {
                        configStore.update {
                            it.copy(overlayPosX = 24, overlayPosY = (dm.heightPixels * 0.45f).toInt())
                        }
                    }
                )
                MonoChipButton(
                    text = "右中",
                    icon = Icons.Rounded.OpenWith,
                    selected = false,
                    onClick = {
                        configStore.update {
                            it.copy(
                                overlayPosX = (dm.widthPixels - 180).coerceAtLeast(24),
                                overlayPosY = (dm.heightPixels * 0.45f).toInt()
                            )
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            MonoOutlineButton(
                text = "重置預設位置與外觀",
                icon = Icons.Rounded.RestartAlt,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    configStore.update {
                        it.copy(
                            overlaySizeDp = 52,
                            overlayAlpha = 0.92f,
                            overlayPosX = 24,
                            overlayPosY = 360,
                            snapToEdge = true,
                            showPillLabel = false
                        )
                    }
                }
            )
        }

        // Execution & Capture Tuning
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Timer,
                title = "截圖與自動點擊微調",
                subtitle = "針對不同裝置幀率與應用反應速度微調延遲"
            )
            Spacer(modifier = Modifier.height(10.dp))
            MonoSliderRow(
                icon = Icons.Rounded.Timer,
                title = "截圖前隱藏懸浮延遲",
                valueLabel = "${config.screenshotHideDelayMs} ms",
                value = config.screenshotHideDelayMs.toFloat(),
                valueRange = 60f..400f,
                onValueChange = { v -> configStore.update { it.copy(screenshotHideDelayMs = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.TouchApp,
                title = "多選連續點擊間隔",
                valueLabel = "${config.multiClickIntervalMs} ms",
                value = config.multiClickIntervalMs.toFloat(),
                valueRange = 100f..1000f,
                onValueChange = { v -> configStore.update { it.copy(multiClickIntervalMs = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.Image,
                title = "截圖最大寬度",
                valueLabel = "${config.imageMaxWidth} px",
                value = config.imageMaxWidth.toFloat(),
                valueRange = 640f..1920f,
                onValueChange = { v -> configStore.update { it.copy(imageMaxWidth = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.Image,
                title = "JPEG 壓縮品質",
                valueLabel = "${config.imageJpegQuality}%",
                value = config.imageJpegQuality.toFloat(),
                valueRange = 50f..95f,
                onValueChange = { v -> configStore.update { it.copy(imageJpegQuality = v.roundToInt()) } }
            )
            MonoToggleRow(
                icon = Icons.Rounded.NotificationsActive,
                title = "顯示結果狀態膠囊",
                subtitle = "完成輸入或點擊後於頂部短暫顯示結果與複製按鈕",
                checked = config.showResultToast,
                onCheckedChange = { v -> configStore.update { it.copy(showResultToast = v) } }
            )
        }

        // Experimental: Full Auto-Loop Tuning
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Autorenew,
                title = "全自動迴圈設定 (實驗性)",
                subtitle = "推薦場景：逐步多道問題・支援跨裝置歸一化座標與元件吸附"
            )
            Spacer(modifier = Modifier.height(10.dp))
            MonoSliderRow(
                icon = Icons.Rounded.Autorenew,
                title = "最大迴圈輪數",
                valueLabel = "${config.autoLoopMaxRounds} 輪",
                value = config.autoLoopMaxRounds.toFloat(),
                valueRange = 1f..50f,
                onValueChange = { v -> configStore.update { it.copy(autoLoopMaxRounds = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.Timer,
                title = "單項完成後等待延遲",
                valueLabel = "${config.autoLoopStepDelayMs} ms",
                value = config.autoLoopStepDelayMs.toFloat(),
                valueRange = 300f..2500f,
                onValueChange = { v -> configStore.update { it.copy(autoLoopStepDelayMs = v.roundToInt()) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.Timer,
                title = "點擊下一題後換頁等待",
                valueLabel = "${config.autoLoopPageDelayMs} ms",
                value = config.autoLoopPageDelayMs.toFloat(),
                valueRange = 500f..4000f,
                onValueChange = { v -> configStore.update { it.copy(autoLoopPageDelayMs = v.roundToInt()) } }
            )
            MonoToggleRow(
                icon = Icons.Rounded.GpsFixed,
                title = "無障礙元件中心精準吸附",
                subtitle = "結合 0..1000 螢幕歸一化座標與無障礙元件邊界，自動適配不同螢幕尺寸",
                checked = config.autoLoopSnapToNode,
                onCheckedChange = { v -> configStore.update { it.copy(autoLoopSnapToNode = v) } }
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
