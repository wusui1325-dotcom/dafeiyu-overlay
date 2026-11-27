package com.dafeiyu.overlay

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.overlay.overlay.OverlayForegroundService
import com.dafeiyu.overlay.system.PermissionHelper
import com.dafeiyu.overlay.system.ShizukuHelper
import com.dafeiyu.overlay.ui.main.MainScreen
import com.dafeiyu.overlay.ui.onboarding.OnboardingScreen
import com.dafeiyu.overlay.ui.theme.DaFeiYuTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private var onPermissionChangedCallback: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        onPermissionChangedCallback?.invoke()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == ShizukuHelper.REQUEST_CODE) {
            onPermissionChangedCallback?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

        val app = application as DaFeiYuApp
        val configStore = app.configStore
        val historyStore = app.historyStore

        setContent {
            val config by configStore.config.collectAsState()
            val overlayRunning by OverlayForegroundService.isRunning.collectAsState()
            var permissions by remember { mutableStateOf(PermissionHelper.snapshot(this)) }
            var showOnboarding by remember { mutableStateOf(!config.onboardingCompleted) }

            val refreshPermissions = {
                permissions = PermissionHelper.snapshot(this)
            }
            onPermissionChangedCallback = refreshPermissions

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        refreshPermissions()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val requestNotification = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    refreshPermissions()
                }
            }

            val toggleOverlay = {
                if (overlayRunning) {
                    OverlayForegroundService.stop(this)
                } else if (!PermissionHelper.hasOverlayPermission(this)) {
                    Toast.makeText(this, "請先授予「懸浮視窗」權限", Toast.LENGTH_SHORT).show()
                    PermissionHelper.openOverlaySettings(this)
                } else {
                    OverlayForegroundService.start(this)
                }
            }

            DaFeiYuTheme(darkTheme = config.appDarkTheme) {
                if (showOnboarding) {
                    OnboardingScreen(
                        config = config,
                        configStore = configStore,
                        permissions = permissions,
                        onRequestNotification = requestNotification,
                        onFinish = { launchOverlay ->
                            configStore.update { it.copy(onboardingCompleted = true) }
                            showOnboarding = false
                            if (launchOverlay && PermissionHelper.hasOverlayPermission(this)) {
                                OverlayForegroundService.start(this)
                            }
                        }
                    )
                } else {
                    MainScreen(
                        config = config,
                        configStore = configStore,
                        historyStore = historyStore,
                        permissions = permissions,
                        overlayRunning = overlayRunning,
                        onToggleOverlay = toggleOverlay,
                        onStartOnboarding = { showOnboarding = true },
                        onRefreshPermissions = refreshPermissions,
                        onRequestNotification = requestNotification
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }
}
