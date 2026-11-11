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
            "/data/adb/modules/susfs4ksu/bin/ksu_susfs",
            "/data/adb/susfs4ksu/ksu_susfs",
            "/data/adb/susfs/bin/ksu_susfs",
            "/data/adb/ksu/susfs/ksu_susfs",
            "/system/bin/ksu_susfs",
        )
        val checks = candidates.joinToString("; ") { "[ -x $it ] && echo $it" }
        val viaSu = runShell(
            "su -c '$checks; command -v ksu_susfs; find /data/adb -maxdepth 6 -name ksu_susfs -type f -executable 2>/dev/null | head -n1'",
            5
        )?.trim()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        return viaSu ?: candidates.firstOrNull { fileExists(it) }
    }

    fun susfsEnabledFeatures(): String? = ksuSusfsBin()?.let { bin ->
        runShell("su -c '$bin show enabled_features'", 4)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun hasRoot(): Boolean = runShell("su -c 'id -u'", 3)?.trim() == "0"

    /**
     * 加载 SUSFS 独立内核模块（LKM）：动态 insmod 注入内核，不刷分区、不触碰 boot/system/vendor/vbmeta，
     * 因此越狱模式（临时 LKM root）下同样安全，且「现有 GKI SUSFS 配置与 KernelSU KO 保持不变」。
     * 前提：设备上需存在与当前内核版本匹配的 .ko（通常为 susfs4ksu 模块自带，或用户自备）。
     * 不匹配的 ko 会被内核拒绝（vermagic/KMI 不符），不会强行加载。
     */
    data class ModuleLoadResult(
        val state: String, // loaded / already / no_ko / failed / no_tool
        val path: String,
        val error: String,
        /** 实际采用的加载方式：ksud（KernelSU Next 原生）/ insmod（手动 LKM 加载）。 */
        val method: String = "",
    )

    fun loadSusfsModule(): ModuleLoadResult {
        // 多管理器原生调用：KernelSU Next 优先用其原生 ksud susfs load（按 KMI 加载预编译模块，
        // 并启用 autoload 开机自启）；其余管理器（SukiSU / KernelSU 官方 / SterSU）无 ksud，
        // 回退到手动 insmod 独立 LKM（susfs_guard_lkm.ko 等）。两者都不刷分区、不触碰 GKI 配置。
        val script = """
            KSUD=""
            for K in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd; do
              [ -x "${'$'}K" ] && KSUD="${'$'}K" && break
            done
            [ -z "${'$'}KSUD" ] && KSUD=${'$'}(command -v ksud 2>/dev/null)

            if [ -n "${'$'}KSUD" ]; then
              OUT=${'$'}({ "${'$'}KSUD" susfs load; } 2>&1)
              RC=${'$'}?
              if [ ${'$'}RC -eq 0 ]; then
                "${'$'}KSUD" susfs autoload enable >/dev/null 2>&1
                echo MOD_METHOD=ksud
                echo MOD_STATE=loaded
                echo MOD_PATH=${'$'}(echo "${'$'}OUT" | head -n1)
                echo MOD_ERR=${'$'}OUT
                exit 0
              else
                echo MOD_METHOD=ksud
                echo MOD_STATE=failed
                echo MOD_ERR=${'$'}OUT
                exit 0
              fi
            fi
            echo MOD_METHOD=insmod

            KO=${'$'}$(true)
            for D in /data/adb/modules/susfs4ksu /data/adb/ksu/susfs4ksu /data/adb/susfs4ksu; do
              KO=${'$'}(find "${'$'}D" -maxdepth 1 -name '*.ko' 2>/dev/null | head -n1)
              [ -n "${'$'}KO" ] && break
            done
            [ -z "${'$'}KO" ] && KO=${'$'}(find /data/adb -maxdepth 6 -name '*.ko' 2>/dev/null | grep -iE 'susfs|sui' | head -n1)
            if [ -z "${'$'}KO" ]; then echo MOD_STATE=no_ko; exit 0; fi
            KN=${'$'}(basename "${'$'}KO" .ko)
            echo MOD_PATH="${'$'}KO"
            if grep -q "${'$'}KN" /proc/modules 2>/dev/null; then
              echo MOD_STATE=already
            else
              ERR=${'$'}(mktemp)
              if insmod "${'$'}KO" 2="${'$'}ERR"; then
                echo MOD_STATE=loaded
              else
                echo MOD_STATE=failed
                echo MOD_ERR="${'$'}(head -c 300 "${'$'}ERR" | tr '\n' ' ')"
              fi
              rm -f "${'$'}ERR"
            fi
        """.trimIndent()
        val res = execRootDetail(script, 25)
        if (!res.finished) {
            val detail = res.err.takeIf { it.isNotBlank() }?.trim()
                ?: "su 在 25 秒内无响应（通常是 Root 管理器未授予本应用权限，su 卡住未返回）"
            return ModuleLoadResult("failed", "", "执行失败：$detail。请确认已在 KernelSU / SukiSU / Magisk 的超级用户列表中允许「隐藏应用列表-OSS」", "")
        }
        val out = res.out
        if (out.isBlank()) {
            val detail = res.err.takeIf { it.isNotBlank() }?.trim()
                ?: "su 已执行但无任何输出（可能 su 被拒绝）"
            return ModuleLoadResult("failed", "", "执行失败：$detail", "")
        }
        val map = HashMap<String, String>()
        out.lineSequence().forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) map[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
        return ModuleLoadResult(
            state = map["MOD_STATE"].orEmpty(),
            path = map["MOD_PATH"].orEmpty(),
            error = map["MOD_ERR"].orEmpty(),
            method = map["MOD_METHOD"].orEmpty(),
        )
    }

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

    /** Root 管理器类型，决定 SUSFS 的原生调用入口。 */
    enum class ManagerType {
        SUKISU,   // SukiSU Ultra：内置 SUSFS 管理器，ksu_susfs 在 /data/adb/ksu/bin
        KSU_NEXT, // KernelSU Next：原生 ksud susfs load/features/support/autoload
        KSU,      // KernelSU 官方：需另装 susfs4ksu，统一用 ksu_susfs
        STERSU,   // SterSU：需另装 susfs4ksu，统一用 ksu_susfs
        APATCH,   // APatch：用户态 KPM，无内核 SUSFS
        MAGISK,   // Magisk：不支持 SUSFS
        UNKNOWN,
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
        /** SUSFS 独立内核模块 (.ko) 路径；非空即探测到可用的独立 LKM，可经 insmod 动态加载。 */
        val susfsKo: String,
        /** Root 管理器类型，决定 SUSFS 原生调用入口（ksud / 内置管理器 / susfs4ksu）。 */
        val managerType: ManagerType,
        /** KernelSU Next 的 ksud 二进制路径（空表示不可用）。 */
        val ksudPath: String,
        /** ksud susfs support 输出（内核是否支持 SUSFS 的原生判定）。 */
        val ksudSusfsSupport: String,
        /** ksud susfs features 输出（已启用特性）。 */
        val ksudSusfsFeatures: String,
        /** ksud susfs autoload status 输出（开机自动加载是否启用）。 */
        val ksudAutoload: String,
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
        // 因此 App 在这两种状态下必须显示警示，并禁止一切刷写分区的操作。
        // 注意：被禁止的只有「写分区」；ksu_susfs add_sus_path 是纯内存态 supercall，
        // 不写分区、不破坏验证启动链，越狱模式下同样允许执行（见 SusfsFragment.buildScript）。
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

        // 管理器类型：归一化为 SUKISU / KSU_NEXT / KSU / STERSU / APATCH / MAGISK / UNKNOWN，
        // App 据此选择「从各管理器的 GKI 原生调用 SUSFS」的入口。
        val managerType = when (map["MGRTYPE"]) {
            "SUKISU" -> ManagerType.SUKISU
            "KSU_NEXT" -> ManagerType.KSU_NEXT
            "KSU" -> ManagerType.KSU
            "STERSU" -> ManagerType.STERSU
            "APATCH" -> ManagerType.APATCH
            "MAGISK" -> ManagerType.MAGISK
            else -> ManagerType.UNKNOWN
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
            susfsKo = v("KO", ""),
            managerType = managerType,
            ksudPath = v("KSUD", ""),
            ksudSusfsSupport = v("KSUDSUP", ""),
            ksudSusfsFeatures = v("KSUDFEAT", ""),
            ksudAutoload = v("KSUDAUTO", ""),
            jailbreak = jailbreak,
            permissive = permissive,
        )
        lastEnv = env
        return env
    }

    // ---------------------------------------------------------------- shell 执行

    /**
     * 以 root shell 执行脚本并捕获 stdout。
     *
     * 实现要点（修订 9 · 修复「执行失败，请确认已授予 Root 权限」误报）：
     * - 统一使用 `su -c` 命令式调用，与 hasRoot() 完全一致；脚本经 base64 编码后通过
     *   `echo <b64> | base64 -d | sh` 注入，彻底避免引号转义与交互式 shell 卡死。
     * - 旧的 `ProcessBuilder("su")` + stdin 喂脚本方式在 KernelSU / Magisk 下会让 su
     *   开一个不随 EOF 退出的交互式 shell，waitFor 超时后被强杀返回 null，从而误报
     *   「执行失败」——其实 hasRoot()（同样走 su -c）是成功的。本实现规避该问题。
     * 同时兼容 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk，
     * 越狱模式（permissive + 运行时加载 LKM 的临时 root）下同样可用。
     */
    /**
     * 带诊断信息的 root 执行结果（修订 10）。
     * - finished=false 且 err 含「su 启动失败」：su 二进制不可用或启动被拒；
     * - finished=false 且无该提示：su 在规定时间内无响应（典型为权限未授予导致 su 卡住）；
     * - finished=true 但 out 为空且 err 非空：su 已运行但内部命令报错（如脚本写入失败、Permission denied）。
     */
    data class RootExecResult(
        val finished: Boolean,
        val out: String,
        val err: String,
        val exit: Int,
    )

    /**
     * 以 root shell 执行脚本，分离捕获 stdout / stderr，并返回是否超时。
     *
     * 实现（修订 10 · 去除 base64 依赖 + 规避交互式 shell 卡死）：
     * 1. 把脚本以**纯文本**写入 /data/local/tmp/ 下临时文件（该目录对所有进程可写可读）；
     * 2. 用 `su -c "sh <脚本路径>"` 命令式执行——sh 读完文件即退出，不会像旧的 stdin 交互式
     *    shell 那样卡住导致 waitFor 超时；
     * 3. 不再依赖 base64（部分裁剪过的 root shell 没有 base64 会导致整条 pipeline 失败，
     *    表现为「执行失败」误报）。
     * 兼容 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk，越狱模式同样可用。
     */
    fun execRootDetail(script: String, timeoutSeconds: Long = 12): RootExecResult {
        val tmpDir = java.io.File("/data/local/tmp")
        val scriptFile = java.io.File(tmpDir, "apkesu_exec_${System.nanoTime()}.sh")
        return try {
            if (!tmpDir.exists()) tmpDir.mkdirs()
            scriptFile.writeText(script.trimEnd() + "\n")
            scriptFile.setReadable(true, false)
            scriptFile.setExecutable(true, false)
            val cmd = "su -c \"sh ${scriptFile.absolutePath}\""
            val process = ProcessBuilder("sh", "-c", cmd).start()
            val outS = StringBuilder()
            val errS = StringBuilder()
            val tOut = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { outS.append(it).append('\n') }
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { errS.append(it).append('\n') }
                } catch (_: Throwable) {
                }
            }
            tOut.start(); tErr.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            tOut.join(1500); tErr.join(1500)
            RootExecResult(finished, outS.toString(), errS.toString(), if (finished) process.exitValue() else -1)
        } catch (_: Throwable) {
            RootExecResult(false, "", "su 启动失败，或脚本写入 /data/local/tmp 失败（请确认 su 可用且该目录可写）", -1)
        } finally {
            try {
                scriptFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    /** 兼容旧调用：仅返回 stdout（超时或 su 不可用返回 null）。 */
    fun execRootCapture(script: String, timeoutSeconds: Long = 12): String? =
        execRootDetail(script, timeoutSeconds).takeIf { it.finished }?.out

    /** 以 root shell 执行脚本（不关心输出）。同样走 `su -c "sh <tmpfile>"`。 */
    fun execRoot(script: String, timeoutSeconds: Long = 15): Boolean {
        val r = execRootDetail(script, timeoutSeconds)
        return r.finished && r.exit == 0
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
