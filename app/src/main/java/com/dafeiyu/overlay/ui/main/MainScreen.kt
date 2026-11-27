package com.dafeiyu.overlay.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.system.PermissionSnapshot

private data class NavItem(
    val label: String,
    val headerTitle: String,
    val icon: ImageVector
)

@Composable
fun MainScreen(
    config: AppConfig,
    configStore: ConfigStore,
    permissions: PermissionSnapshot,
    overlayRunning: Boolean,
    onToggleOverlay: () -> Unit,
    onStartOnboarding: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestNotification: () -> Unit
) {
    val navItems = remember {
        listOf(
            NavItem("首頁", "控制中心", Icons.Rounded.Home),
            NavItem("懸浮", "懸浮外觀與行為", Icons.Rounded.Layers),
            NavItem("資料", "儲存資料", Icons.Rounded.ContactPage),
            NavItem("模型", "DeepSeek 模型與參數", Icons.Rounded.AutoAwesome),
            NavItem("權限", "系統權限中心", Icons.Rounded.Security)
        )
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Streamlined Monochrome Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
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
                    Column {
                        Text(
                            text = "大肥魚 DaFeiYu",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = navItems[selectedTab].headerTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Light / Dark Monochrome Toggle Pill with Icon
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable {
                            configStore.update { it.copy(appDarkTheme = !it.appDarkTheme) }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (config.appDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (config.appDarkTheme) "亮白" else "純黑",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> HomeTab(
                        config = config,
                        permissions = permissions,
                        overlayRunning = overlayRunning,
                        onToggleOverlay = onToggleOverlay,
                        onStartOnboarding = onStartOnboarding,
                        onJumpToPermissions = { selectedTab = 4 },
                        onJumpToModel = { selectedTab = 3 }
                    )
                    1 -> OverlaySettingsTab(
                        config = config,
                        configStore = configStore
                    )
                    2 -> DataVaultTab(
                        config = config,
                        configStore = configStore
                    )
                    3 -> ModelSettingsTab(
                        config = config,
                        configStore = configStore
                    )
                    4 -> PermissionsTab(
                        permissions = permissions,
                        onRefresh = onRefreshPermissions,
                        onRequestNotification = onRequestNotification
                    )
                }
            }
        }

        // Floating Streamlined Monochrome Pill Bottom Navigation Bar (5 Tabs)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            navItems.forEachIndexed { index, item ->
                val selected = selectedTab == index
                val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(bg)
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.headerTitle,
                        tint = fg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = fg,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
