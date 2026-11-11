package org.apkesu.app.ui.util

import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * 只统计用户自行安装的第三方应用，排除系统预置软件（含被用户更新过的系统应用）。
     * 系统应用带有 FLAG_SYSTEM 标记，过滤掉即可得到「已安装应用」数量。
     */
    fun installedAppCount(context: Context): Int = try {
        context.packageManager.getInstalledPackages(0).count { pkg ->
            val flags = pkg.applicationInfo?.flags ?: 0
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
        }
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

    /** 首页「Root框架」：优先返回一次完整探测得到的真实品牌。 */
    @Volatile
    private var lastEnv: RootEnv? = null

    fun rootFramework(): String {
        lastEnv?.let { if (it.working) return it.framework }
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

    fun susfsSupported(): Boolean = detectRoot(null).susfs

    fun susfsVersion(): String? = detectRoot(null).susfsVersion.takeIf { it.isNotEmpty() }

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

    // ---------------------------------------------------------------- Root 环境探测

    /** 互斥的单一真实工作模式：LKM / GKI / LKM 越狱。 */
    enum class WorkMode(val label: String, val accent: Boolean = false) {
        LKM("LKM 工作模式"),
        GKI("GKI 工作模式"),
        JAILBREAK_LKM("LKM 越狱工作模式", accent = true),
        UNKNOWN("工作模式未知"),
    }

    /** SUSFS 部署状态，决定能否即时下发路径。 */
    enum class SusfsState(val label: String) {
        KERNEL_OK("已启用（内核已打补丁）"),
        TOOL_ONLY("未生效：内核未带 SUSFS 补丁"),
        KERNEL_NOTOOL("内核已支持，缺少 ksu_susfs 工具"),
        NONE("未检测到（内核与用户态模块均未部署）"),
    }

    data class RootEnv(
        val working: Boolean,
        val framework: String,
        val frameworkVersion: String,
        val kernel: String,
        val kernelFull: String,
        val hookType: String,
        val zygisk: String,
        val selinux: String,
        val fingerprint: String,
        val superuser: String,
        val modules: String,
        /** 互斥单一工作模式，不再并列显示多个标签。 */
        val workMode: WorkMode,
        val susfs: Boolean,
        val susfsVersion: String,
        val susfsState: SusfsState,
        val susfsBin: String,
        val susfsFeatures: String,
        val susfsVariant: String,
        /** 越狱模式 / permissive：此时严禁写入或刷写任何分区，否则会破坏验证启动链导致格机。 */
        val jailbreak: Boolean = false,
        val permissive: Boolean = false,
    )

    /**
     * 运行 assets/susfs_probe.sh 采集真实 Root 环境。
     * 结果缓存，供首页与 SUSFS 页共用；force=true 时强制重新采集。
     *
     * 说明：内核级精确状态只有 Root 管理器自身能读取，
     * 「工作模式（LKM/GKI/自定义/越狱模式）」与「钩子类型」为基于环境特征的启发式推断。
     */
    fun detectRoot(context: Context?, force: Boolean = false): RootEnv {
        if (!force) lastEnv?.let { return it }

        val script = try {
            context?.assets?.open("susfs_probe.sh")?.bufferedReader()?.use { it.readText() }
        } catch (_: Throwable) {
            null
        }

        val map = HashMap<String, String>()
        if (!script.isNullOrEmpty()) {
            execRootCapture(script)?.lineSequence()?.forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    if (key.isNotEmpty() && key.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
                        map[key] = line.substring(idx + 1).trim()
                    }
                }
            }
        }

        fun v(key: String, fallback: String = "未知"): String =
            map[key]?.takeIf { it.isNotEmpty() } ?: fallback

        val working = map["SU"] == "0" || (map.isEmpty() && hasRoot())

        // 越狱模式（运行时动态加载 LKM 的临时 root）与 permissive SELinux：
        // 这两种状态下改动任何分区都会破坏 AVB / dm-verity 验证启动链，导致无法开机或格机。
        // 因此 App 在这两种状态下必须显示警示，并且只写 /data/adb/ 数据区、不做内核下发。
        val jailbreak = map["JAILBREAK"] == "1"
        val permissive = map["PERMISSIVE"] == "1" ||
            map["SELINUX"]?.equals("Permissive", ignoreCase = true) == true ||
            selinuxMode().equals("Permissive", ignoreCase = true)

        // 互斥的单一工作模式：脚本按 越狱 > LKM > GKI 优先级只输出一个 MODE
        val workMode = when (map["MODE"]) {
            "JAILBREAK_LKM" -> WorkMode.JAILBREAK_LKM
            "LKM" -> WorkMode.LKM
            "GKI" -> WorkMode.GKI
            else -> WorkMode.UNKNOWN
        }
        val susfsState = when (map["SUSFSSTATE"]) {
            "KERNEL_OK" -> SusfsState.KERNEL_OK
            "TOOL_ONLY" -> SusfsState.TOOL_ONLY
            "KERNEL_NOTOOL" -> SusfsState.KERNEL_NOTOOL
            else -> SusfsState.NONE
        }

        val env = RootEnv(
            working = working,
            framework = v("FW", if (working) "已 Root" else "未检测到"),
            frameworkVersion = v("FWVER", "—"),
            kernel = v("KERNEL"),
            kernelFull = v("KERNELFULL"),
            hookType = v("HOOK"),
            zygisk = v("ZYGISK", "未检测到"),
            selinux = v("SELINUX", selinuxMode()),
            fingerprint = v("FP"),
            superuser = v("SUPERUSER", "—"),
            modules = v("MODULES", "—"),
            workMode = workMode,
            susfs = map["SUSFS"] == "1",
            susfsVersion = v("SUSFSVER", ""),
            susfsState = susfsState,
            susfsBin = v("SUSBIN", ""),
            susfsFeatures = v("SUSFSFEAT", ""),
            susfsVariant = v("SUSFSVAR", ""),
            jailbreak = jailbreak,
            permissive = permissive,
        )
        lastEnv = env
        return env
    }

    // ---------------------------------------------------------------- shell 执行

    /**
     * 以 root shell 执行脚本并捕获 stdout。脚本通过 stdin 喂给 su，避免引号转义问题，
     * 同时兼容 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk。
     * 越狱模式（permissive + 运行时加载 LKM 的临时 root）下同样可用。
     */
    fun execRootCapture(script: String, timeoutSeconds: Long = 12): String? = try {
        val process = ProcessBuilder("su").start()
        process.outputStream.use { it.write((script.trimEnd() + "\n").toByteArray()) }
        val captured = AtomicReference("")
        val reader = Thread {
            captured.set(try {
                process.inputStream.bufferedReader().readText()
            } catch (_: Throwable) {
                ""
            })
        }
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1500)
        captured.get().takeIf { finished }
    } catch (_: Throwable) {
        null
    }

    /** 以 root shell 执行脚本（不关心输出）。 */
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
