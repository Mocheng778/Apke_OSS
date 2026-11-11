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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        private const val KEY_SELECTED_KO = "selected_ko"

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

    /** 最近一次探测结果，供「手动选择 .ko」对话框读取内置文件列表。 */
    private var lastEnv: DeviceInfo.RootEnv? = null

    /** 用户手动指定的 .ko 文件名；空=自动按内核 KMI 匹配。 */
    private fun selectedKo(): String = prefs.getString(KEY_SELECTED_KO, "") ?: ""

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
        binding.koStatus.setOnClickListener { showKoPicker() }

        loadDetection(refresh = false)
    }

    /**
     * 手动选择发行版内置的 .ko（呼应最初「用 susfs4ksu 自带的」方案）。
     * 首项「自动按内核 KMI 匹配」为推荐；其余为内置目录里的各 KMI 文件。
     * 自动匹配不到、或自动加载被内核拒绝时，用户可在这里手动指定对应 KMI 的 ko。
     */
    private fun showKoPicker() {
        val env = lastEnv
        val files = env?.susfsKoList
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.endsWith(".ko") && !it.contains('/') }
            ?.distinct()
            .orEmpty()
        if (files.isEmpty()) {
            snack(R.string.susfs_ko_picker_empty)
            return
        }
        val autoLabel = getString(R.string.susfs_ko_picker_auto)
        val labels = listOf(autoLabel) + files
        val current = selectedKo()
        val checked = if (current.isEmpty()) 0 else (files.indexOf(current) + 1).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.susfs_ko_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val chosen = if (which == 0) "" else files[which - 1]
                prefs.edit().putString(KEY_SELECTED_KO, chosen).apply()
                dialog.dismiss()
                renderKoStatus(lastEnv)
                if (chosen.isEmpty()) snack(R.string.susfs_ko_picker_set_auto)
                else snack(getString(R.string.susfs_ko_picker_set_file, chosen))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 模块卡片「模块文件」状态行：显示发行版内置 .ko 数量、当前内核 KMI 匹配情况、手动选择。 */
    private fun renderKoStatus(env: DeviceInfo.RootEnv?) {
        if (env == null) {
            binding.koStatus.setText(R.string.susfs_module_status_none)
            return
        }
        val chosen = selectedKo()
        binding.koStatus.text = when {
            env.susfsKoCount > 0 && chosen.isNotEmpty() ->
                getString(R.string.susfs_ko_status_manual, env.susfsKoCount, chosen, env.susfsKoKmi.ifEmpty { "—" })
            env.susfsKoCount > 0 && env.susfsKoMatch.isNotEmpty() ->
                getString(R.string.susfs_ko_status_matched, env.susfsKoCount, env.susfsKoKmi)
            env.susfsKoCount > 0 ->
                getString(R.string.susfs_ko_status_nomatch, env.susfsKoCount, env.susfsKoKmi.ifEmpty { "—" })
            env.susfsKo.isNotEmpty() ->
                getString(R.string.susfs_module_status_found, env.susfsKo)
            else -> getString(R.string.susfs_module_status_none)
        }
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
        lastEnv = env
        // 探测脚本没跑通时，环境信息全部不可信：宁可如实显示「检测失败 + 诊断」，
        // 也不要像旧版那样伪装成「工作中 / 已 Root」却把工作模式、超级用户、模块全填 —。
        if (!env.probeOk) {
            binding.susfsStatus.setText(R.string.susfs_status_probe_failed)
            binding.susfsVersion.setText(R.string.susfs_probe_version_hint)
            binding.jbWarningTitle.setText(R.string.susfs_probe_failed_title)
            binding.jbWarningText.text = getString(R.string.susfs_probe_failed, env.probeError)
            binding.jbWarningCard.visibility = View.VISIBLE
            renderModeChip(env.workMode)
            renderKoStatus(env)
            renderEnvInfo(env)
            return
        }

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

        // 越狱模式（临时 LKM root）：SUSFS 路径隐藏功能不显示。
        // 该能力仅在「解锁模式」（正常 LKM / GKI，已刷入带 SUSFS 补丁内核）下可用——
        // 走 SUSFS 仓库原生方案（写 config.sh / sus_path.txt + ksu_susfs add_sus_path）。
        // 因此越狱模式下隐藏所有可写配置 UI，仅保留只读状态 / 环境信息，并显示提示。
        val jailbreak = env.jailbreak || env.workMode == DeviceInfo.WorkMode.JAILBREAK_LKM
        if (jailbreak) {
            binding.cardSwitches.visibility = View.GONE
            binding.cardModule.visibility = View.GONE
            binding.cardPaths.visibility = View.GONE
            binding.loadEnableBtn.visibility = View.GONE
            binding.loadModuleBtn.visibility = View.GONE
            binding.applyBtn.visibility = View.GONE
            binding.jbWarningTitle.setText(R.string.susfs_jb_feature_hidden_title)
            binding.jbWarningText.setText(R.string.susfs_jb_feature_hidden)
            binding.jbWarningCard.visibility = View.VISIBLE
        } else {
            binding.cardSwitches.visibility = View.VISIBLE
            binding.cardModule.visibility = View.VISIBLE
            binding.cardPaths.visibility = View.VISIBLE
            binding.loadEnableBtn.visibility = View.VISIBLE
            binding.loadModuleBtn.visibility = View.VISIBLE
            binding.applyBtn.visibility = View.VISIBLE
            // permissive（非越狱）仅作分区安全提示，功能照常可用（全程不刷分区）
            if (env.permissive) {
                binding.jbWarningTitle.setText(R.string.susfs_jb_warning_title)
                binding.jbWarningText.setText(R.string.susfs_jb_warning)
                binding.jbWarningCard.visibility = View.VISIBLE
            } else {
                binding.jbWarningCard.visibility = View.GONE
            }
        }

        renderModeChip(env.workMode)
        renderKoStatus(env)
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
            // 探测失败时把真实诊断放在最前面，避免用户只看到一堆「未知 / —」而不知原因
            if (!env.probeOk && env.probeError.isNotEmpty()) {
                add(R.string.susfs_info_probe_diag to env.probeError)
            }
            add(R.string.susfs_info_framework to env.framework)
            add(R.string.susfs_info_manager_ver to env.frameworkVersion)
            add(R.string.susfs_info_kernel to env.kernel)
            add(R.string.susfs_info_kernel_full to env.kernelFull)
            add(R.string.susfs_info_hook to env.hookType)
            add(R.string.susfs_info_zygisk to env.zygisk)
            add(R.string.susfs_info_selinux to env.selinux)
            add(R.string.susfs_info_fingerprint to env.fingerprint)
            add(R.string.susfs_info_susfs to susfsText)
            // 版本不匹配时，把 ksu_susfs 返回的真实提示原样展示，避免误读为「未带补丁」
            if (env.susfsVersionMismatch && env.susfsMismatchMsg.isNotEmpty()) {
                add(R.string.susfs_info_version_mismatch to env.susfsMismatchMsg)
            }
            // 始终展示 ksu_susfs 工具状态；为空即「未找到」，明确告知用户需安装 susfs4ksu 模块
            add(
                R.string.susfs_info_susfs_bin to
                if (env.susfsBin.isNotEmpty()) env.susfsBin
                else getString(R.string.susfs_info_susfs_bin_missing)
            )
            // 当前实际调用的 SUSFS 入口：明确「从哪个管理器的 GKI 原生调用」
            add(R.string.susfs_info_mgr_susfs to mgrSusfsText(env))
            if (env.ksudAutoload.isNotEmpty()) {
                add(R.string.susfs_info_ksud_autoload to env.ksudAutoload)
            }
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

    /** 把管理器类型映射为「当前所调用的 SUSFS 入口」可读文案。 */
    private fun mgrSusfsText(env: DeviceInfo.RootEnv): String = when (env.managerType) {
        DeviceInfo.ManagerType.SUKISU ->
            getString(R.string.susfs_mgr_sukisu)
        DeviceInfo.ManagerType.KSU_NEXT ->
            if (env.ksudSusfsSupport.isNotEmpty())
                getString(R.string.susfs_mgr_ksu_next, env.ksudSusfsSupport)
            else getString(R.string.susfs_mgr_ksu_next_plain)
        DeviceInfo.ManagerType.KSU ->
            getString(R.string.susfs_mgr_ksu)
        DeviceInfo.ManagerType.STERSU ->
            getString(R.string.susfs_mgr_stersu)
        DeviceInfo.ManagerType.APATCH ->
            getString(R.string.susfs_mgr_apatch)
        DeviceInfo.ManagerType.MAGISK ->
            getString(R.string.susfs_mgr_magisk)
        else ->
            getString(R.string.susfs_mgr_unknown)
    }

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

    /** 加载 SUSFS 独立内核模块（LKM）：用发行版内置 .ko 动态 insmod，不刷分区。仅解锁模式可触发。 */
    private fun loadModule() {
        val preferred = selectedKo()
        binding.loadModuleBtn.isEnabled = false
        thread {
            val hasRoot = DeviceInfo.hasRoot()
            val r = if (hasRoot) DeviceInfo.loadSusfsModule(preferred) else null
            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadModuleBtn.isEnabled = true
                    fun methodLabel(m: String) = when (m) {
                        "ksud-insmod" -> getString(R.string.susfs_method_ksud_insmod)
                        "susfs_insmod" -> getString(R.string.susfs_method_susfs_insmod)
                        else -> getString(R.string.susfs_method_insmod)
                    }
                    when {
                        r == null -> snack(R.string.susfs_apply_noroot)
                        r.state == "no_ko" ->
                            if (r.available.isNotEmpty())
                                snack(getString(R.string.susfs_module_no_ko_choose, r.kmi.ifEmpty { "—" }))
                            else snack(R.string.susfs_module_no_ko)
                        r.state == "loaded" -> snack(
                            getString(R.string.susfs_module_loaded, "${r.path} · ${methodLabel(r.method)}")
                        )
                        r.state == "already" -> snack(
                            getString(R.string.susfs_module_already, r.path)
                        )
                        r.state == "failed" -> snack(
                            getString(R.string.susfs_module_failed,
                                if (r.error.isNotEmpty()) r.error else getString(R.string.susfs_module_failed_kmi))
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
            // 拿完整执行结果（含 stderr 诊断），用于失败时如实告知原因，
            // 不再笼统提示「请确认已授予 Root 权限」。
            val exec = if (hasRoot) {
                DeviceInfo.execRootDetail(buildScript(state, paths), 20L)
            } else null
            val out = exec?.takeIf { it.finished && it.out.isNotBlank() }?.out

            // 解析真实下发结果：APPLIED 为内核实际接受的路径条数
            val applied = out?.lineSequence()
                ?.firstOrNull { it.startsWith("SUS_APPLIED=") }
                ?.substringAfter("=")?.trim()?.toIntOrNull() ?: 0
            val haveBin = out?.lineSequence()
                ?.firstOrNull { it.startsWith("SUS_HAVE_BIN=") }
                ?.substringAfter("=")?.trim() == "1"
            val susErr = out?.lineSequence()
                ?.firstOrNull { it.startsWith("SUS_ERR=") }
                ?.substringAfter("=")?.trim() ?: ""
            // 版本不匹配：ksu_susfs 与内核 SUSFS 补丁版本不兼容（如工具要求 v1.5.3+ 而内核更早）。
            // 内核实际有补丁，因此与「未带补丁」区分，需提示用户对齐版本。
            val isMismatch = susErr.contains("requires", ignoreCase = true) ||
                susErr.contains("v1.", ignoreCase = true) ||
                env?.susfsVersionMismatch == true
            val total = paths.size

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadEnableBtn.isEnabled = true
                    binding.applyBtn.isEnabled = true
                    when {
                        !hasRoot -> snack(R.string.susfs_apply_noroot)
                        out == null -> snack(
                            getString(
                                R.string.susfs_apply_exec_failed,
                                exec?.err?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
                                    ?: getString(R.string.susfs_apply_failed),
                            )
                        )
                        !haveBin -> snack(getString(R.string.susfs_apply_no_tool, total))
                        applied == 0 -> if (isMismatch) {
                            snack(getString(R.string.susfs_apply_version_mismatch, total,
                                susErr.takeIf { it.isNotEmpty() } ?: "ksu_susfs 与内核 SUSFS 版本不匹配"))
                        } else {
                            snack(getString(R.string.susfs_apply_no_kernel, total))
                        }
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
     * 路径隐藏走 SUSFS 仓库原生方案：写 config.sh / sus_path.txt 持久化，并调用
     * ksu_susfs add_sus_path 走 supercall 把规则写进内核内存表（builtin 补丁内核没有
     * /proc/modules 模块名，也不靠配置文件即时生效）。该能力仅在解锁模式（正常 LKM / GKI，
     * 已刷入带 SUSFS 补丁内核）下可用；越狱模式无法现场刷内核，故在 UI 层隐藏本功能。
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

        // 先加载 SUSFS 独立内核模块（LKM）：优先用发行版内置、按内核 KMI 精确匹配的 .ko，
        // 不刷分区、不动 GKI SUSFS 配置与 KernelSU KO（仅解锁模式可触发）。
        // 内置 .ko 位于模块 ZIP 的 susfs_ko/，刷入后为 /data/adb/modules/apkesu_zygisk/susfs_ko/。
        lines += "KODIR=\"\""
        lines += "for D in /data/adb/modules/apkesu_zygisk/susfs_ko /data/adb/modules_update/apkesu_zygisk/susfs_ko /data/adb/ksu/susfs_ko /data/adb/susfs_ko; do"
        lines += "  [ -d \"\$D\" ] && KODIR=\"\$D\" && break"
        lines += "done"
        lines += "[ -z \"\$KODIR\" ] && KODIR=\$(find /data/adb -maxdepth 5 -type d -name susfs_ko 2>/dev/null | head -n1)"
        lines += "KREL=\$(cat /proc/sys/kernel/osrelease 2>/dev/null)"
        lines += "[ -z \"\$KREL\" ] && KREL=\$(uname -r 2>/dev/null)"
        lines += "KAND=\$(echo \"\$KREL\" | grep -oE 'android[0-9]+' | head -n1)"
        lines += "KVER=\$(echo \"\$KREL\" | grep -oE '^[0-9]+\\.[0-9]+' | head -n1)"
        lines += "KMI=\"\""
        lines += "[ -n \"\$KAND\" ] && [ -n \"\$KVER\" ] && KMI=\"\${KAND}-\${KVER}\""
        lines += "KO=\"\""
        lines += "[ -n \"\$KMI\" ] && [ -n \"\$KODIR\" ] && [ -f \"\$KODIR/susfs_guard_lkm-\${KMI}.ko\" ] && KO=\"\$KODIR/susfs_guard_lkm-\${KMI}.ko\""
        lines += "[ -z \"\$KO\" ] && [ -n \"\$KODIR\" ] && KO=\$(ls -1 \"\$KODIR\"/*.ko 2>/dev/null | head -n1)"
        lines += "[ -z \"\$KO\" ] && for D in /data/adb/modules/susfs4ksu /data/adb/ksu/susfs4ksu /data/adb/susfs4ksu; do KO=\$(find \"\$D\" -maxdepth 1 -name '*.ko' 2>/dev/null | head -n1); [ -n \"\$KO\" ] && break; done"
        lines += "if [ -n \"\$KO\" ]; then"
        lines += "  if ! grep -q 'susfs_guard_lkm' /proc/modules 2>/dev/null; then"
        lines += "    insmod \"\$KO\" expose_proc=1 2>/dev/null && echo KO_LOADED=1 || echo KO_LOADED=0"
        lines += "  else"
        lines += "    echo KO_LOADED=1"
        lines += "  fi"
        lines += "  echo KO_PATH=\"\$KO\""
        lines += "  echo KO_KMI=\"\$KMI\""
        lines += "else"
        lines += "  echo KO_LOADED=NA"
        lines += "fi"

        // 即时下发：定位 ksu_susfs（多路径 + PATH 兜底），逐条 add_sus_path，统计真实成功数。
        // 首条不下发时捕获 stderr，用于如实报告「版本不匹配」等真实失败原因（而非笼统「未带补丁」）。
        lines += "BIN=''"
        lines += "for b in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/bin/ksu_susfs /data/adb/susfs4ksu/ksu_susfs /data/adb/susfs/bin/ksu_susfs /data/adb/ksu/susfs/ksu_susfs /system/bin/ksu_susfs; do"
        lines += "  [ -x \"\$b\" ] && BIN=\"\$b\" && break"
        lines += "done"
        lines += "[ -z \"\$BIN\" ] && BIN=\$(command -v ksu_susfs 2>/dev/null)"
        lines += "[ -z \"\$BIN\" ] && BIN=\$(find /data/adb -maxdepth 6 -name ksu_susfs -type f -executable 2>/dev/null | head -n1)"
        lines += "APPLIED=0"
        lines += "SUS_ERR=\"\""
        lines += "if [ -n \"\$BIN\" ]; then"
        var firstPath = true
        for (path in sanitized) {
            if (firstPath) {
                lines += "  E=\$(\"\$BIN\" add_sus_path '$path' 2>&1)"
                lines += "  if [ \$? -eq 0 ]; then APPLIED=\$((APPLIED+1)); else SUS_ERR=\"\$E\"; fi"
                firstPath = false
            } else {
                lines += "  \"\$BIN\" add_sus_path '$path' >/dev/null 2>&1 && APPLIED=\$((APPLIED+1))"
            }
        }
        lines += "  echo SUS_HAVE_BIN=1"
        lines += "else"
        lines += "  echo SUS_HAVE_BIN=0"
        lines += "fi"
        lines += "echo SUS_ERR=\"\$SUS_ERR\""
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
