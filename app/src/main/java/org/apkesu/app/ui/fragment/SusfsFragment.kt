package org.apkesu.app.ui.fragment

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentSusfsBinding
import org.apkesu.app.databinding.ViewSusfsInfoRowBinding
import org.apkesu.app.databinding.ViewSusfsPathRowBinding
import org.apkesu.app.ui.util.DeviceInfo
import kotlin.concurrent.thread

class SusfsFragment : Fragment(R.layout.fragment_susfs) {
    private val binding by viewBinding(FragmentSusfsBinding::bind)

    companion object {
        private const val PREFS_NAME = "susfs_path_hide"

        private const val KEY_APP_HIDE = "sw_app_hide"
        private const val KEY_KERNEL_SPOOF = "sw_kernel_spoof"
        private const val KEY_SUSFS_LOG = "sw_susfs_log"
        private const val KEY_HIDE_MNT_NONROOT = "sw_hide_mnt_nonroot"
        private const val KEY_PRESET_PATHS = "preset_paths"
        private const val KEY_CUSTOM_PATHS = "custom_paths"

        val PRESET_PATHS = listOf(
            "/data/adb/ksu",
            "/data/adb/magisk",
            "/data/adb/apd",
            "/data/adb/modules",
            "/data/adb/susfs4ksu",
            "/system/bin/su",
            "/system/xbin/su",
            "/system_ext/bin/su",
            "/vendor/bin/su",
            "/sbin/.magisk",
            "/debug_ramdisk",
            "/debug_ramdisk/.magisk",
        )

        private const val SUSFS_DIR = "/data/adb/susfs4ksu"

        /**
         * 分区写入守卫：自定义路径只允许这些字符，杜绝 shell 元字符注入
         * （; | & $ ` > < 换行 等一旦进入脚本可能被拼成 dd/flash 写分区命令）。
         */
        private val PATH_ALLOWED = Regex("^/[A-Za-z0-9/._-]+$")

        /** 块设备 / 伪文件系统路径，严禁作为隐藏路径添加。 */
        private val BLOCKED_PREFIXES = listOf("/dev/", "/proc/", "/sys/")

        private fun isSafePath(path: String): Boolean =
            PATH_ALLOWED.matches(path) && BLOCKED_PREFIXES.none { path.startsWith(it) }
    }

    private val prefs get() = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(toolbar = this, title = getString(R.string.title_susfs))
        }

        setEdge2EdgeFlags(binding.root)

        restoreSwitches()
        renderPresetPaths()
        renderCustomPaths()

        binding.addPathBtn.setOnClickListener { addCustomPath() }
        binding.pathInput.setOnEditorActionListener { _, _, _ ->
            addCustomPath()
            true
        }

        binding.loadEnableBtn.setOnClickListener {
            loadDetection(refresh = true)
            applyAll()
        }

        binding.applyBtn.setOnClickListener {
            applyAll()
        }
        binding.loadModuleBtn.setOnClickListener { loadModule() }

        loadDetection(refresh = false)
    }

    // ---------------------------------------------------------------- 状态与环境检测

    private fun loadDetection(refresh: Boolean) {
        if (refresh) {
            binding.susfsStatus.setText(R.string.susfs_support_checking)
        }

        val appContext = requireContext().applicationContext
        thread {
            // 真实识别 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk
            // 及 LKM / GKI / 自定义 / 越狱模式，越狱模式（临时 root）同样可采集。
            val env = DeviceInfo.detectRoot(appContext, force = refresh)

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    renderStatus(env)
                }
            }
        }
    }

    private fun renderStatus(env: DeviceInfo.RootEnv) {
        binding.susfsStatus.setText(
            if (env.working) R.string.susfs_status_working else R.string.susfs_status_inactive
        )
        val version = env.frameworkVersion.takeIf { it != "—" && it.isNotEmpty() }
            ?: env.kernel.takeIf { it != "未知" && it.isNotEmpty() }
            ?: "—"
        binding.susfsVersion.text = getString(
            R.string.susfs_version_label,
            if (env.working) "$version · ${env.framework}" else env.framework
        )

        // 越狱模式 / permissive：改动分区会破坏验证启动链导致格机，显示红字警示
        binding.jbWarningCard.visibility =
            if (env.jailbreak || env.permissive) View.VISIBLE else View.GONE

        renderModeChip(env.workMode)
        binding.koStatus.text = if (env.susfsKo.isNotEmpty())
            getString(R.string.susfs_module_status_found, env.susfsKo)
        else getString(R.string.susfs_module_status_none)
        renderEnvInfo(env)
    }

    /** 只显示一个互斥的真实工作模式标签（LKM / GKI / LKM 越狱），不再并列堆叠。 */
    private fun renderModeChip(mode: DeviceInfo.WorkMode) {
        val container = binding.modeChips
        container.removeAllViews()

        val chip = TextView(requireContext()).apply {
            text = mode.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (mode.accent) Color.WHITE else Color.parseColor("#5F4386"))
            background = ContextCompat.getDrawable(
                requireContext(),
                if (mode.accent) R.drawable.susfs_chip_jailbreak else R.drawable.susfs_chip_normal
            )
            setPadding(dp(14), dp(6), dp(14), dp(6))
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(chip, lp)
    }

    private fun renderEnvInfo(env: DeviceInfo.RootEnv) {
        binding.statSuperuser.text = env.superuser
        binding.statModules.text = env.modules

        val container = binding.infoContainer
        container.removeAllViews()

        // SUSFS 支持：显示真实的部署状态（而非仅凭 /proc/modules 的布尔值），
        // 让用户明白「路径隐藏没生效」到底缺的是内核补丁还是用户态工具。
        val susfsText = buildString {
            append(env.susfsState.label)
            if (env.susfsVersion.isNotEmpty()) append(" ${env.susfsVersion}")
        }

        val rows = buildList {
            add(R.string.susfs_info_framework to env.framework)
            add(R.string.susfs_info_manager_ver to env.frameworkVersion)
            add(R.string.susfs_info_kernel to env.kernel)
            add(R.string.susfs_info_kernel_full to env.kernelFull)
            add(R.string.susfs_info_hook to env.hookType)
            add(R.string.susfs_info_zygisk to env.zygisk)
            add(R.string.susfs_info_selinux to env.selinux)
            add(R.string.susfs_info_fingerprint to env.fingerprint)
            add(R.string.susfs_info_susfs to susfsText)
            // 始终展示 ksu_susfs 工具状态；为空即「未找到」，明确告知用户需安装 susfs4ksu 模块
            add(
                R.string.susfs_info_susfs_bin to
                if (env.susfsBin.isNotEmpty()) env.susfsBin
                else getString(R.string.susfs_info_susfs_bin_missing)
            )
            if (env.susfsVariant.isNotEmpty()) {
                add(R.string.susfs_info_susfs_variant to env.susfsVariant)
            }
            if (env.susfsFeatures.isNotEmpty()) {
                add(R.string.susfs_info_susfs_features to env.susfsFeatures)
            }
        }

        for ((labelRes, value) in rows) {
            val row = ViewSusfsInfoRowBinding.inflate(layoutInflater, container, false)
            row.infoRowLabel.setText(labelRes)
            row.infoRowValue.text = value
            container.addView(row.root)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 开关

    private fun restoreSwitches() {
        binding.switchAppHide.isChecked = prefs.getBoolean(KEY_APP_HIDE, true)
        binding.switchKernelSpoof.isChecked = prefs.getBoolean(KEY_KERNEL_SPOOF, true)
        binding.switchSusfsLog.isChecked = prefs.getBoolean(KEY_SUSFS_LOG, false)
        binding.switchHideMntNonroot.isChecked = prefs.getBoolean(KEY_HIDE_MNT_NONROOT, true)

        binding.switchAppHide.setOnCheckedChangeListener { _, v -> saveBool(KEY_APP_HIDE, v) }
        binding.switchKernelSpoof.setOnCheckedChangeListener { _, v -> saveBool(KEY_KERNEL_SPOOF, v) }
        binding.switchSusfsLog.setOnCheckedChangeListener { _, v -> saveBool(KEY_SUSFS_LOG, v) }
        binding.switchHideMntNonroot.setOnCheckedChangeListener { _, v -> saveBool(KEY_HIDE_MNT_NONROOT, v) }
    }

    private fun saveBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun selectedPresets(): Set<String> =
        prefs.getStringSet(KEY_PRESET_PATHS, PRESET_PATHS.toSet()) ?: PRESET_PATHS.toSet()

    private fun customPaths(): MutableSet<String> =
        prefs.getStringSet(KEY_CUSTOM_PATHS, emptySet())?.toMutableSet() ?: mutableSetOf()

    // ---------------------------------------------------------------- 路径列表

    private fun renderPresetPaths() {
        val selected = selectedPresets()
        binding.presetContainer.removeAllViews()

        for (path in PRESET_PATHS) {
            val check = CheckBox(requireContext()).apply {
                text = path
                textSize = 12f
                isChecked = path in selected
                setOnCheckedChangeListener { _, checked ->
                    val current = selectedPresets().toMutableSet()
                    if (checked) current.add(path) else current.remove(path)
                    prefs.edit().putStringSet(KEY_PRESET_PATHS, current).apply()
                }
            }
            binding.presetContainer.addView(check)
        }
    }

    private fun renderCustomPaths() {
        binding.customContainer.removeAllViews()

        for (path in customPaths()) {
            val row = ViewSusfsPathRowBinding.inflate(layoutInflater, binding.customContainer, false)
            row.pathText.text = path
            row.pathRemove.setOnClickListener {
                val current = customPaths()
                current.remove(path)
                prefs.edit().putStringSet(KEY_CUSTOM_PATHS, current).apply()
                renderCustomPaths()
            }
            binding.customContainer.addView(row.root)
        }
    }

    private fun addCustomPath() {
        val raw = binding.pathInput.text?.toString()?.trim().orEmpty()
        when {
            raw.isEmpty() -> {
                snack(R.string.susfs_path_empty)
                return
            }
            !raw.startsWith("/") || !PATH_ALLOWED.matches(raw) -> {
                snack(R.string.susfs_path_invalid)
                return
            }
            BLOCKED_PREFIXES.any { raw.startsWith(it) } -> {
                snack(R.string.susfs_path_blocked)
                return
            }
        }

        val current = customPaths()
        if (raw in current || raw in PRESET_PATHS) {
            snack(R.string.susfs_path_exists)
            return
        }

        current.add(raw)
        prefs.edit().putStringSet(KEY_CUSTOM_PATHS, current).apply()
        binding.pathInput.text?.clear()
        renderCustomPaths()
        snack(getString(R.string.susfs_path_added, raw))
    }

    // ---------------------------------------------------------------- 应用配置

    private data class SwitchState(
        val appHide: Boolean,
        val kernelSpoof: Boolean,
        val susfsLog: Boolean,
        val hideMnt: Boolean,
    )

    /** 加载 SUSFS 独立内核模块（LKM）：动态 insmod，不刷分区，越狱模式同样安全。 */
    private fun loadModule() {
        binding.loadModuleBtn.isEnabled = false
        thread {
            val hasRoot = DeviceInfo.hasRoot()
            val r = if (hasRoot) DeviceInfo.loadSusfsModule() else null
            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadModuleBtn.isEnabled = true
                    when {
                        r == null -> snack(R.string.susfs_apply_noroot)
                        r.state == "no_ko" -> snack(R.string.susfs_module_no_ko)
                        r.state == "loaded" -> snack(getString(R.string.susfs_module_loaded, r.path))
                        r.state == "already" -> snack(getString(R.string.susfs_module_already, r.path))
                        r.state == "failed" -> snack(
                            getString(R.string.susfs_module_failed,
                                if (r.error.isNotEmpty()) r.error else "insmod 被内核拒绝（vermagic/KMI 不匹配）")
                        )
                        else -> snack(R.string.susfs_apply_failed)
                    }
                    loadDetection(refresh = true)
                }
            }
        }
    }

    private fun applyAll() {
        val paths = (selectedPresets() + customPaths()).distinct().sorted()

        if (paths.isEmpty()) {
            snack(R.string.susfs_select_first)
            return
        }

        // 主线程一次性读取开关状态，后续 shell 操作全部放后台
        val state = SwitchState(
            appHide = binding.switchAppHide.isChecked,
            kernelSpoof = binding.switchKernelSpoof.isChecked,
            susfsLog = binding.switchSusfsLog.isChecked,
            hideMnt = binding.switchHideMntNonroot.isChecked,
        )
        val appContext = requireContext().applicationContext

        binding.loadEnableBtn.isEnabled = false
        binding.applyBtn.isEnabled = false

        thread {
            val hasRoot = DeviceInfo.hasRoot()
            val env = if (hasRoot) DeviceInfo.detectRoot(appContext, force = false) else null
            val out = if (hasRoot) {
                DeviceInfo.execRootCapture(buildScript(state, paths), 20L)
            } else null

            // 解析真实下发结果：APPLIED 为内核实际接受的路径条数
            val applied = out?.lineSequence()
                ?.firstOrNull { it.startsWith("SUS_APPLIED=") }
                ?.substringAfter("=")?.trim()?.toIntOrNull() ?: 0
            val haveBin = out?.lineSequence()
                ?.firstOrNull { it.startsWith("SUS_HAVE_BIN=") }
                ?.substringAfter("=")?.trim() == "1"
            val total = paths.size

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadEnableBtn.isEnabled = true
                    binding.applyBtn.isEnabled = true
                    when {
                        !hasRoot -> snack(R.string.susfs_apply_noroot)
                        out == null -> snack(R.string.susfs_apply_failed)
                        !haveBin -> snack(getString(R.string.susfs_apply_no_tool, total))
                        applied == 0 -> snack(getString(R.string.susfs_apply_no_kernel, total))
                        applied < total -> snack(getString(R.string.susfs_apply_partial, applied, total))
                        else -> snack(getString(R.string.susfs_loaded, applied))
                    }
                    // 应用后刷新状态，SUSFS 支持/版本会同步更新
                    loadDetection(refresh = true)
                }
            }
        }
    }

    /**
     * 分区写入守卫：
     * - 所有「写入目标」严格限定为 /data/adb/ 下的 config.sh 与 sus_path.txt（数据区）；
     * - 用户提供的路径只作为文件内容 / ksu_susfs 参数，绝不拼接成写入目标；
     * - 不含任何 dd / flash / 块设备操作，不触碰 boot、system、vendor、vbmeta 等分区；
     *
     * 关键修复：路径隐藏要「即时生效」必须通过 ksu_susfs add_sus_path 走 supercall
     * 把规则写进内核内存表（builtin 补丁内核没有 /proc/modules 模块名，也不靠 /data/adb
     * 配置文件即时生效）。add_sus_path 是纯内存态 ioctl/supercall，不写任何分区，
     * 不会破坏 AVB / dm-verity，因此越狱模式（临时 LKM root）下同样允许执行——
     * 越狱模式严禁的是「刷内核 / 写分区」，与本操作无关。
     */
    private fun buildScript(state: SwitchState, paths: List<String>): String {
        val lines = mutableListOf<String>()
        val sanitized = paths.filter { isSafePath(it) }

        // susfs4ksu 配置文件持久化（重启后由模块 boot 脚本读取），均为 /data/adb/ 数据区
        val dirs = listOf(
            SUSFS_DIR,
            "/data/adb/modules/susfs4ksu",
            "/data/adb/ksu/susfs4ksu",
        )

        val cfg = listOf(
            "sus_su=${if (state.appHide) 2 else 0}",
            "hide_cusrom=${if (state.appHide) 1 else 0}",
            "spoof_uname=${if (state.kernelSpoof) 1 else 0}",
            "spoof_cmdline=${if (state.kernelSpoof) 1 else 0}",
            "susfs_log=${if (state.susfsLog) 1 else 0}",
            "hide_sus_mnts_for_non_su_procs=${if (state.hideMnt) 1 else 0}",
        )
        val cfgArgs = cfg.joinToString(" ") { "'$it'" }
        val pathArgs = if (sanitized.isEmpty()) "" else sanitized.joinToString(" ") { "'$it'" }

        lines += "# partition guard: every write target is under /data/adb/ only; no block/partition writes"
        for (dir in dirs) {
            lines += "mkdir -p $dir 2>/dev/null || true"
            lines += "printf '%s\\n' $cfgArgs > $dir/config.sh 2>/dev/null || true"
            lines += if (sanitized.isEmpty()) {
                ": > $dir/sus_path.txt 2>/dev/null || true"
            } else {
                "printf '%s\\n' $pathArgs > $dir/sus_path.txt 2>/dev/null || true"
            }
            lines += "chmod 644 $dir/config.sh $dir/sus_path.txt 2>/dev/null || true"
        }

        // 先尝试加载 SUSFS 独立内核模块（LKM）：仅当存在且尚未加载时 insmod，
        // 不刷分区、不动 GKI SUSFS 配置与 KernelSU KO，越狱模式下安全。
        lines += "KO=\"\""
        lines += "for D in /data/adb/modules/susfs4ksu /data/adb/ksu/susfs4ksu /data/adb/susfs4ksu; do"
        lines += "  KO=\$(find \"\$D\" -maxdepth 1 -name '*.ko' 2>/dev/null | head -n1)"
        lines += "  [ -n \"\$KO\" ] && break"
        lines += "done"
        lines += "[ -z \"\$KO\" ] && KO=\$(find /data/adb -maxdepth 6 -name '*.ko' 2>/dev/null | grep -iE 'susfs|sui' | head -n1)"
        lines += "if [ -n \"\$KO\" ]; then"
        lines += "  KN=\$(basename \"\$KO\" .ko)"
        lines += "  if ! grep -q \"\$KN\" /proc/modules 2>/dev/null; then"
        lines += "    insmod \"\$KO\" 2>/dev/null && echo KO_LOADED=1 || echo KO_LOADED=0"
        lines += "  else"
        lines += "    echo KO_LOADED=1"
        lines += "  fi"
        lines += "  echo KO_PATH=\"\$KO\""
        lines += "else"
        lines += "  echo KO_LOADED=NA"
        lines += "fi"

        // 即时下发：定位 ksu_susfs（多路径 + PATH 兜底），逐条 add_sus_path，统计真实成功数
        lines += "BIN=''"
        lines += "for b in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/bin/ksu_susfs /data/adb/susfs4ksu/ksu_susfs /data/adb/susfs/bin/ksu_susfs /data/adb/ksu/susfs/ksu_susfs /system/bin/ksu_susfs; do"
        lines += "  [ -x \"\$b\" ] && BIN=\"\$b\" && break"
        lines += "done"
        lines += "[ -z \"\$BIN\" ] && BIN=\$(command -v ksu_susfs 2>/dev/null)"
        lines += "[ -z \"\$BIN\" ] && BIN=\$(find /data/adb -maxdepth 6 -name ksu_susfs -type f -executable 2>/dev/null | head -n1)"
        lines += "APPLIED=0"
        lines += "if [ -n \"\$BIN\" ]; then"
        for (path in sanitized) {
            lines += "  \"\$BIN\" add_sus_path '$path' >/dev/null 2>&1 && APPLIED=\$((APPLIED+1))"
        }
        lines += "  echo SUS_HAVE_BIN=1"
        lines += "else"
        lines += "  echo SUS_HAVE_BIN=0"
        lines += "fi"
        lines += "echo SUS_APPLIED=\$APPLIED"

        lines += "true"
        return lines.joinToString("\n")
    }

    private fun snack(resId: Int) {
        Snackbar.make(binding.root, resId, Snackbar.LENGTH_LONG).show()
    }

    private fun snack(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }
}
