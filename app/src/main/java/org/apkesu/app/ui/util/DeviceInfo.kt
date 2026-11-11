package org.apkesu.app.ui.util

import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * 轻量级设备 / Root 环境探测工具。
 * 所有耗时探测都通过 shell 命令完成，调用方需自行放到后台线程。
 */
object DeviceInfo {

    fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            manufacturer.isNotEmpty() && model.isNotEmpty() &&
                model.lowercase().startsWith(manufacturer.lowercase()) -> model
            manufacturer.isNotEmpty() && model.isNotEmpty() -> "$manufacturer $model"
            model.isNotEmpty() -> model
            else -> Build.DEVICE.orEmpty()
        }.ifEmpty { "未知设备" }
    }

    fun installedAppCount(context: Context): Int = try {
        context.packageManager.getInstalledPackages(0).size
    } catch (_: Throwable) {
        0
    }

    fun moduleCount(): Int? {
        val out = runShell("su -c 'ls -1 /data/adb/modules'", 5)
        if (out == null) return null
        val count = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.count()
        return count
    }

    fun selinuxMode(): String {
        val viaSu = runShell("su -c 'getenforce'", 3)?.trim()?.takeIf { it.isNotEmpty() }
        if (viaSu != null) return viaSu

        val enforce = readFile("/sys/fs/selinux/enforce")?.trim()
        return when (enforce) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else -> "未知"
        }
    }

    fun zygiskStatus(): String {
        val viaSu = runShell(
            "su -c 'test -d /data/adb/modules/zygisk -o -d /data/adb/modules/zygisksu -o -d /data/adb/zygisk && echo enabled'",
            3
        )?.trim()
        if (viaSu == "enabled") return "已启用"
        return "未检测到"
    }

    fun rootFramework(): String {
        val viaSu = runShell(
            "su -c 'if [ -d /data/adb/ksu ]; then echo KernelSU; elif [ -d /data/adb/apd ] || [ -f /data/adb/apd ]; then echo APatch; elif [ -d /data/adb/magisk ]; then echo Magisk; else echo none; fi'",
            4
        )?.trim()?.lineSequence()?.map { it.trim() }?.lastOrNull()?.trim()
        if (!viaSu.isNullOrEmpty() && viaSu != "none") return viaSu

        if (runShell("su -c 'id'", 2) != null) return "已 Root"
        if (fileExists("/data/adb/ksu")) return "KernelSU"
        if (fileExists("/data/adb/apd")) return "APatch"
        if (fileExists("/data/adb/magisk")) return "Magisk"
        return "未检测到"
    }

    fun susfsSupported(): Boolean {
        val viaSu = runShell(
            "su -c 'cat /proc/modules | grep -qi susfs && echo yes; test -d /sys/module/susfs && echo yes; test -f /data/adb/ksu/bin/ksu_susfs && echo yes'",
            4
        )?.trim()
        return viaSu?.contains("yes") == true
    }

    fun susfsVersion(): String? = runShell("su -c '/data/adb/ksu/bin/ksu_susfs show version'", 4)
        ?.trim()?.takeIf { it.isNotEmpty() }

    /** 探测 ksu_susfs 二进制，兼容不同 susfs4ksu 版本与越狱环境。 */
    fun ksuSusfsBin(): String? {
        val candidates = listOf(
            "/data/adb/ksu/bin/ksu_susfs",
            "/data/adb/ksu/susfs4ksu/ksu_susfs",
            "/data/adb/modules/susfs4ksu/ksu_susfs",
            "/data/adb/susfs4ksu/ksu_susfs",
        )
        val checks = candidates.joinToString("; ") { "[ -x $it ] && echo $it" }
        val viaSu = runShell(
            "su -c '$checks; command -v ksu_susfs'",
            4
        )?.trim()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        return viaSu ?: candidates.firstOrNull { fileExists(it) }
    }

    fun susfsEnabledFeatures(): String? = ksuSusfsBin()?.let { bin ->
        runShell("su -c '$bin show enabled_features'", 4)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun hasRoot(): Boolean = runShell("su -c 'id -u'", 3)?.trim() == "0"

    /**
     * 以 root shell 执行脚本。脚本通过 stdin 喂给 su，避免引号转义问题，
     * 同时兼容 KernelSU / APatch / Magisk 等实现（越狱模式同样适用）。
     */
    fun execRoot(script: String, timeoutSeconds: Long = 15): Boolean = try {
        val process = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write((script.trimEnd() + "\n").toByteArray()) }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Throwable) {
        false
    }

    private fun fileExists(path: String): Boolean = try {
        java.io.File(path).exists()
    } catch (_: Throwable) {
        false
    }

    private fun readFile(path: String): String? = try {
        java.io.File(path).takeIf { it.exists() && it.canRead() }?.readText()
    } catch (_: Throwable) {
        null
    }

    private fun runShell(command: String, timeoutSeconds: Long): String? = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().readText()
        }
    } catch (_: Throwable) {
        null
    }
}
