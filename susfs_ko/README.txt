SUSFS 独立内核模块 (.ko) — 随「隐藏应用列表-OSS」发行版分发
============================================================
来源仓库 : https://github.com/inforcqb/susfs4ksu-lkm
版本标签 : v2.3.0-r18.9-1
许可     : GPL-2.0 (https://github.com/inforcqb/susfs4ksu-lkm/blob/main/LICENSE)
上游移植 : susfs4ksu (功能逻辑/hook 点), SukiSU-Ultra (patch_memory/lsm_hook/symbol_resolver)
用途     : 内核不重编译/不刷分区即可获得 SUSFS 支持 (LKM 动态 insmod)

KMI 选择规则 (按 uname -r 推导 androidNN-X.Y):
  android12-5.10 / android13-5.10 / android13-5.15 / android14-5.15
  android14-6.1  / android15-6.6  / android16-6.12 / android17-6.18
每份 .ko 只与对应 KMI 内核匹配, vermagic 不符会被内核拒绝 (不会强行加载)。

加载方式:
  KernelSU 系 : ksud insmod <path>   或   insmod <path> expose_proc=1
  非 KSU      : ./susfs_insmod <path>
仅 arm64 / GKI 内核; 非 GKI、未识别 KMI、厂商 ABI 差异均可能加载失败。
本目录文件仅供技术研究与学习, 使用风险自负。
