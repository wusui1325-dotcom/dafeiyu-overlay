package com.dafeiyu.overlay.system

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuStatus(val label: String) {
    NOT_RUNNING("未啟動"),
    NO_PERMISSION("未授權"),
    READY("已就緒")
}

object ShizukuHelper {

    const val REQUEST_CODE = 1042

    fun getStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.READY
            } else {
                ShizukuStatus.NO_PERMISSION
            }
        } catch (_: Throwable) {
            ShizukuStatus.NOT_RUNNING
        }
    }

    fun isReady(): Boolean = getStatus() == ShizukuStatus.READY

    fun requestPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Executes a shell command via Shizuku if authorized (reflection-safe for API 13+).
     */
    fun runShellCommand(command: String): Boolean {
        if (!isReady()) return false
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            val exitCode = process?.waitFor() ?: -1
            exitCode == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        return runShellCommand("input tap ${x.toInt()} ${y.toInt()}")
    }

    fun inputText(text: String): Boolean {
        // Basic ASCII safe shell input; non-ASCII should prefer Accessibility / Clipboard paste
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(" ", "%s")
        return runShellCommand("input text \"$escaped\"")
    }
}
