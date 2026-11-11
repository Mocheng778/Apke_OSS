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
     * 「现有 GKI SUSFS 配置与 KernelSU KO 保持不变」。
     *
     * 修订 15：发行版已内置 susfs4ksu-lkm 全套 KMI 的 .ko（模块 ZIP 的 susfs_ko/，刷入后位于
     * /data/adb/modules/apkesu_zygisk/susfs_ko/）。这里优先按当前内核 KMI（androidNN-X.Y）精确
     * 挑选内置 .ko 加载，不再要求用户另装 susfs4ksu 模块。加载顺序：ksud insmod（KSU 系，处理
     * 签名/vermagic）→ 系统 insmod（带 expose_proc=1 建立 /proc/susfs_* 节点）→ 内置 susfs_insmod
     * 加载器。vermagic/KMI 不符时内核会拒绝，不会强行加载。仅解锁模式（非越狱）下由 UI 触发。
     *
     * @param preferredKo 用户在 UI 手动指定的 .ko 文件名（仅允许 [A-Za-z0-9._-]），空=自动按 KMI 选。
     */
    data class ModuleLoadResult(
        val state: String, // loaded / already / no_ko / failed / no_tool
        val path: String,
        val error: String,
        /** 实际采用的加载方式：ksud-insmod / insmod / susfs_insmod / bundled。 */
        val method: String = "",
        /** 当前内核推导出的 KMI。 */
        val kmi: String = "",
        /** 无精确匹配时，内置目录里可选的 .ko 文件名（逗号分隔）。 */
        val available: String = "",
    )

    fun loadSusfsModule(preferredKo: String = ""): ModuleLoadResult {
        // 防 shell 注入：手动指定的文件名只保留安全字符，且不含路径分隔符。
        val safe = preferredKo.takeIf {
            it.isNotEmpty() && !it.contains('/') && it.matches(Regex("^[A-Za-z0-9._-]+$"))
        } ?: ""
        val script = """
            KODIR=""
            for D in /data/adb/modules/apkesu_zygisk/susfs_ko /data/adb/modules_update/apkesu_zygisk/susfs_ko /data/adb/ksu/susfs_ko /data/adb/susfs_ko; do
              [ -d "${'$'}D" ] && KODIR="${'$'}D" && break
            done
            [ -z "${'$'}KODIR" ] && KODIR=${'$'}(find /data/adb -maxdepth 5 -type d -name susfs_ko 2>/dev/null | head -n1)

            KREL=${'$'}(cat /proc/sys/kernel/osrelease 2>/dev/null)
            [ -z "${'$'}KREL" ] && KREL=${'$'}(uname -r 2>/dev/null)
            KAND=${'$'}(echo "${'$'}KREL" | grep -oE 'android[0-9]+' | head -n1)
            KVER=${'$'}(echo "${'$'}KREL" | grep -oE '^[0-9]+\.[0-9]+' | head -n1)
            KMI=""
            [ -n "${'$'}KAND" ] && [ -n "${'$'}KVER" ] && KMI="${'$'}{KAND}-${'$'}{KVER}"
            echo MOD_KMI="${'$'}KMI"
            echo MOD_KODIR="${'$'}KODIR"

            PREFERRED="$safe"
            KO=""
            if [ -n "${'$'}PREFERRED" ] && [ -n "${'$'}KODIR" ] && [ -f "${'$'}KODIR/${'$'}PREFERRED" ]; then
              KO="${'$'}KODIR/${'$'}PREFERRED"
            elif [ -n "${'$'}KMI" ] && [ -n "${'$'}KODIR" ] && [ -f "${'$'}KODIR/susfs_guard_lkm-${'$'}{KMI}.ko" ]; then
              KO="${'$'}KODIR/susfs_guard_lkm-${'$'}{KMI}.ko"
            fi
            if [ -z "${'$'}KO" ]; then
              echo MOD_STATE=no_ko
              echo MOD_AVAIL=${'$'}(ls -1 "${'$'}KODIR"/*.ko 2>/dev/null | xargs -n1 basename 2>/dev/null | paste -sd ',' -)
              exit 0
            fi
            echo MOD_PATH="${'$'}KO"

            if grep -q 'susfs_guard_lkm' /proc/modules 2>/dev/null; then
              echo MOD_METHOD=already
              echo MOD_STATE=already
              exit 0
            fi

            KSUD=""
            for K in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd; do
              [ -x "${'$'}K" ] && KSUD="${'$'}K" && break
            done
            [ -z "${'$'}KSUD" ] && KSUD=${'$'}(command -v ksud 2>/dev/null)

            ERR=${'$'}(mktemp)
            METHOD=""
            OK=0
            if [ -n "${'$'}KSUD" ] && "${'$'}KSUD" insmod "${'$'}KO" 2>"${'$'}ERR"; then
              OK=1; METHOD=ksud-insmod
            elif insmod "${'$'}KO" expose_proc=1 2>>"${'$'}ERR"; then
              OK=1; METHOD=insmod
            elif [ -f "${'$'}KODIR/susfs_insmod" ] && chmod 755 "${'$'}KODIR/susfs_insmod" 2>/dev/null && "${'$'}KODIR/susfs_insmod" "${'$'}KO" 2>>"${'$'}ERR"; then
              OK=1; METHOD=susfs_insmod
            fi
            echo MOD_METHOD="${'$'}METHOD"
            if [ "${'$'}OK" -eq 1 ] || grep -q 'susfs_guard_lkm' /proc/modules 2>/dev/null; then
              echo MOD_STATE=loaded
            else
              echo MOD_STATE=failed
              echo MOD_ERR="${'$'}(head -c 300 "${'$'}ERR" | tr '\n' ' ')"
            fi
            rm -f "${'$'}ERR"
        """.trimIndent()
        val res = execRootDetail(script, 30)
        if (!res.finished) {
            val detail = res.err.takeIf { it.isNotBlank() }?.trim()
                ?: "su 在 30 秒内无响应（通常是 Root 管理器未授予本应用权限，su 卡住未返回）"
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
            kmi = map["MOD_KMI"].orEmpty(),
            available = map["MOD_AVAIL"].orEmpty(),
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
        /** 内核有 SUSFS 补丁，但版本与用户态 ksu_susfs 工具不兼容（如工具要求 v1.5.3+ 而内核更早）。 */
        VERSION_MISMATCH("检测到 SUSFS（内核版本过旧，ksu_susfs 要求 v1.5.3+）"),
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
        /** 内核 SUSFS 版本与用户态 ksu_susfs 工具不兼容（如工具要求 v1.5.3+ 而内核更早）。 */
        val susfsVersionMismatch: Boolean = false,
        /** 版本不匹配时 ksu_susfs 返回的真实提示原文（如 "Requires susfs v1.5.3+"）。 */
        val susfsMismatchMsg: String = "",
        /** SUSFS 独立内核模块 (.ko) 路径；非空即探测到可用的独立 LKM，可经 insmod 动态加载。 */
        val susfsKo: String,
        /** 发行版内置 .ko 目录（模块 ZIP 的 susfs_ko/），空表示未内置 / 模块未刷入。 */
        val susfsKoDir: String = "",
        /** 当前内核推导出的 KMI（androidNN-X.Y），用于自动挑选匹配的内置 .ko。 */
        val susfsKoKmi: String = "",
        /** 与当前内核 KMI 精确匹配的内置 .ko 路径；空表示无精确匹配（需手动选或加载会被内核拒）。 */
        val susfsKoMatch: String = "",
        /** 内置 .ko 文件名列表（逗号分隔），供 UI 手动选择。 */
        val susfsKoList: String = "",
        /** 内置 .ko 数量。 */
        val susfsKoCount: Int = 0,
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
        /** 探测脚本是否完整返回（含 PROBE_DONE 标记）；false 表示结果可能不完整。 */
        val probeOk: Boolean = true,
        /** 探测被截断但已拿到部分字段：UI 保留已采集信息，只提示「检测不完整」。 */
        val probePartial: Boolean = false,
        /** 探测失败时的真实诊断信息（su stderr / 超时说明），用于如实告知用户。 */
        val probeError: String = "",
    )

    /**
     * 运行 assets/susfs_probe.sh 采集真实 Root 环境。
     * 结果缓存，供首页与 SUSFS 页共用；force=true 时强制重新采集。
     *
     * 说明：内核级精确状态只有 Root 管理器自身能读取，
     * 「工作模式（LKM/GKI/自定义/越狱模式）」与「钩子类型」为基于环境特征的启发式推断。
     */
    fun detectRoot(context: Context?, force: Boolean = false): RootEnv {
        // 首页可能用 detectRoot(null)（读不到 assets）先缓存了一个「探测失败」的空结果；
        // 若本次带上了 context 且缓存不可信，则强制重采，避免污染 SUSFS 页显示。
        if (!force) lastEnv?.let { cached ->
            if (cached.probeOk || context == null) return cached
        }

        val script = try {
            context?.assets?.open("susfs_probe.sh")?.bufferedReader()?.use { it.readText() }
        } catch (_: Throwable) {
            null
        }

        val map = HashMap<String, String>()
        // 修订 16：文件式执行 + 完整性标记，慢探测后置并单独限时，超时提到 20s。
        val probeResult = if (!script.isNullOrEmpty())
            execRootDetail(script, 20, marker = "PROBE_DONE=1") else null
        probeResult?.out?.lineSequence()?.forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                if (key.isNotEmpty() && key.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
                    map[key] = line.substring(idx + 1).trim()
                }
            }
        }
        // 探测是否真正完整：必须能解析出 SU 基准字段，且脚本末尾 PROBE_DONE=1 到达。
        // 缺标记说明脚本被截断（旧版 stdin 吞脚本 / 命令超时），此时关键字段可能已齐但
        // 后半段缺失，必须如实显示诊断，绝不把半截结果伪装成「工作中 / 已 Root」。
        val hasBase = map.containsKey("SU") || map.containsKey("KERNEL")
        val complete = map["PROBE_DONE"] == "1"
        val probeOk = hasBase && complete
        val probeError = when {
            probeOk -> ""
            probeResult == null -> "无法读取内置探测脚本"
            !hasBase -> buildString {
                append("root 探测脚本未返回有效数据")
                probeResult.err.takeIf { it.isNotBlank() }?.let { append("：").append(it.trim().take(220)) }
            }
            else -> buildString {
                append("探测脚本未执行完（缺少结束标记，多为 root shell 兼容性或命令超时），部分字段可能缺失")
                probeResult.err.takeIf { it.isNotBlank() }?.let { append("：").append(it.trim().take(220)) }
            }
        }

        fun v(key: String, fallback: String = "未知"): String =
            map[key]?.takeIf { it.isNotEmpty() } ?: fallback

        val working = map["SU"] == "0" || (map.isEmpty() && hasRoot())

        // 越狱模式（运行时动态加载 LKM 的临时 root）与 permissive SELinux：
        // 这两种状态下改动任何分区都会破坏 AVB / dm-verity 验证启动链，导致无法开机或格机。
        // 因此 App 在这两种状态下会显示警示。
        // 注意：SUSFS 路径隐藏能力仅在解锁模式（正常 LKM / GKI，已刷入带 SUSFS 补丁内核）下可用；
        // 越狱模式无法现场刷内核补救，故 UI 层直接隐藏该功能（见 SusfsFragment / FeatureFragment）。
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
        // 版本不匹配优先于上述状态：内核有补丁但 ksu_susfs 工具拒绝通讯（如要求 v1.5.3+）。
        val susfsMismatch = map["SUSMISMATCH"] == "1"
        val finalSusfsState = if (susfsMismatch) SusfsState.VERSION_MISMATCH else susfsState

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
            susfsState = finalSusfsState,
            susfsBin = v("SUSBIN", ""),
            susfsFeatures = v("SUSFSFEAT", ""),
            susfsVariant = v("SUSFSVAR", ""),
            susfsVersionMismatch = susfsMismatch,
            susfsMismatchMsg = v("SUSMSG", ""),
            susfsKo = v("KO", ""),
            susfsKoDir = v("KODIR", ""),
            susfsKoKmi = v("KOKMI", ""),
            susfsKoMatch = v("KOMATCH", ""),
            susfsKoList = v("KOLIST", ""),
            susfsKoCount = v("KOCOUNT", "0").toIntOrNull() ?: 0,
            managerType = managerType,
            ksudPath = v("KSUD", ""),
            ksudSusfsSupport = v("KSUDSUP", ""),
            ksudSusfsFeatures = v("KSUDFEAT", ""),
            ksudAutoload = v("KSUDAUTO", ""),
            jailbreak = jailbreak,
            permissive = permissive,
            probeOk = probeOk,
            probePartial = hasBase && !complete,
            probeError = probeError,
        )
        lastEnv = env
        return env
    }

    // ---------------------------------------------------------------- shell 执行

    /**
     * 带诊断信息的 root 执行结果。
     * - finished=false：su 在规定时间内无响应（su 不可用、被拒绝或卡在授权弹窗）；
     * - finished=true 但 out 为空且 err 非空：su 已运行但脚本内部报错；
     * - finished=true 且 out 非空：执行成功。
     */
    data class RootExecResult(
        val finished: Boolean,
        val out: String,
        val err: String,
        val exit: Int,
    )

    /**
     * 以 root 权限执行 shell 脚本，分离捕获 stdout / stderr。
     *
     * 修订 16 · 根因修复：上一版把脚本经 stdin 喂给 root 的 `sh`（`su -c sh`）。`sh` 从
     * **不可 seek 的管道**读取脚本时，脚本内部的命令替换（`$("$KSUD" -V)`、`$(pm path ...)`、
     * `$(ksud susfs ...)` 等）会继承同一个 stdin，Android 的 mksh 会把后续脚本行一并消费，
     * 导致脚本只执行前半段就正常退出（finished=true、exit=0），stdout 只有开头几行。
     * 表现为「工作中 · ksud 3.3.3 · KernelSU」却「工作模式未知 / 模块 — / ko 不显示」。
     *
     * 新通道：由 **root 侧的 cat** 把脚本从 stdin 落到 /data/local/tmp 临时文件，再 `sh <文件>`
     * 执行——`sh` 从**可 seek 的普通文件**读取脚本，命令替换无法再吞掉后续行；写文件的是
     * root 进程，不依赖 App 对 /data/local/tmp 的写权限（绕开修订 10 的坑）。执行完立即删除
     * 临时文件并保留原始退出码。
     * 三级回退：文件式 → `su -c sh` → `su`，任一级拿到「完整」结果即返回，避免重复执行
     * 写配置脚本。传 marker 时只有 stdout 含该标记才算完整（探测脚本用 PROBE_DONE=1）。
     * 兼容 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk。
     */
    fun execRootDetail(script: String, timeoutSeconds: Long = 15, marker: String? = null): RootExecResult {
        val body = script.trimEnd() + "\nexit 0\n"
        val tmp = "/data/local/tmp/apkesu_${System.nanoTime()}.sh"
        // 单引号内 $? / $R 由 su 启动的 shell 展开，外层 sh -c 不会提前展开。
        val fileCmd = "su -c 'cat > $tmp && sh $tmp; R=\$?; rm -f $tmp; exit \$R'"
        val attempts = mutableListOf<Pair<String, RootExecResult>>()

        for (cmd in listOf(fileCmd, "su -c sh", "su")) {
            val r = execSu(cmd, body, timeoutSeconds)
            val complete = if (marker != null) {
                // 探测脚本：必须拿到结束标记才算完整，否则换下一级重采
                r.finished && r.out.contains(marker)
            } else {
                // 带副作用的脚本（写配置 / insmod）：任一通道执行完成即返回，
                // 绝不重复执行。仅「文件式 cat 落盘失败」会 finished=true 且 out 空，
                // 这一级需排除，让后续通道有机会真正跑起来。
                r.finished && (cmd != fileCmd || r.out.isNotBlank())
            }
            if (complete) return r
            attempts += cmd to r
        }

        // 全部未达标：保留输出最完整的一次（至少让用户看到已探测到的字段），并附诊断。
        val best = attempts.maxByOrNull { it.second.out.length }?.second
            ?: RootExecResult(false, "", "su 启动失败", -1)
        val diag = attempts.joinToString(" | ") { (cmd, r) ->
            val tag = if (cmd == fileCmd) "文件式" else cmd
            "[$tag]${if (r.finished) "exit=${r.exit}" else "超时"}/out=${r.out.length}B" +
                (r.err.takeIf { it.isNotBlank() }?.let { ", err=${it.trim().take(120)}" } ?: "")
        }
        return RootExecResult(
            false,
            best.out,
            (best.err.takeIf { it.isNotBlank() } ?: "脚本未完整执行（缺少结束标记）") + "  ｜ 诊断：$diag",
            -1,
        )
    }

    /** 经 `sh -c` 启动 su、把脚本写入 stdin、并行排空 stdout/stderr 并等待结束。 */
    private fun execSu(shellCmd: String, stdin: String, timeoutSeconds: Long): RootExecResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", shellCmd).start()
            val p = process
            val outS = StringBuilder()
            val errS = StringBuilder()
            val tOut = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { synchronized(outS) { outS.append(it).append('\n') } }
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    p.errorStream.bufferedReader().forEachLine { synchronized(errS) { errS.append(it).append('\n') } }
                } catch (_: Throwable) {
                }
            }
            tOut.start(); tErr.start()
            try {
                p.outputStream.bufferedWriter().use { w ->
                    w.write(stdin)
                    w.flush()
                } // close → root sh 收到 EOF，配合脚本末尾 exit 0 立即退出
            } catch (_: Throwable) {
            }
            val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            tOut.join(1500); tErr.join(1500)
            RootExecResult(
                finished,
                synchronized(outS) { outS.toString() },
                synchronized(errS) { errS.toString() },
                if (finished) p.exitValue() else -1,
            )
        } catch (t: Throwable) {
            RootExecResult(false, "", "su 启动失败：${t.javaClass.simpleName} ${t.message ?: ""}", -1)
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    /** 兼容旧调用：仅返回 stdout（超时或 su 不可用返回 null）。 */
    fun execRootCapture(script: String, timeoutSeconds: Long = 12): String? =
        execRootDetail(script, timeoutSeconds).takeIf { it.finished && it.out.isNotBlank() }?.out

    /** 以 root shell 执行脚本（不关心输出）。 */
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
