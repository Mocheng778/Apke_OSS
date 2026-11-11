#!/system/bin/sh
# 采集 Root 环境信息。每行输出 KEY=VALUE，由 DeviceInfo.detectRoot() 解析。
#
# 安全约束：本脚本全程只读（cat / getprop / grep / ls / id / uname / ksu_susfs show / dmesg），
# 不写 /dev/block/* 或任何分区。越狱模式（运行时动态加载 LKM 的临时 root）
# 与 permissive SELinux 下，任何分区写入都会破坏 AVB / dm-verity 验证启动链，
# 导致无法开机或格机清除数据。JAILBREAK / PERMISSIVE 输出即用于 App 侧显示警示。
PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/data/adb/ap/bin:$PATH
export PATH

echo "SU=$(id -u 2>/dev/null)"
echo "KERNEL=$(uname -r 2>/dev/null)"
echo "KERNELFULL=$(cat /proc/version 2>/dev/null)"
echo "SELINUX=$(getenforce 2>/dev/null)"
echo "FP=$(getprop ro.build.fingerprint 2>/dev/null)"
echo "SDK=$(getprop ro.build.version.sdk 2>/dev/null)"

# ---------------------------------------------------------------- Root 框架品牌
FW=""
if pm path com.sukisu.ultra >/dev/null 2>&1; then FW="SukiSU Ultra"
elif pm path com.rifsxd.ksunext >/dev/null 2>&1; then FW="KernelSU Next"
elif pm path me.weishu.kernelsu >/dev/null 2>&1; then FW="KernelSU"
elif pm path me.bmax.apatch >/dev/null 2>&1 || [ -e /data/adb/apd ]; then FW="APatch"
elif pm path com.topjohnwu.magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then FW="Magisk"
fi

# KSU 系分支（SterSU / MKSU / RKSU / 各类 fork）模糊识别
if [ -z "$FW" ]; then
  PKG=$(pm list packages 2>/dev/null | grep -iE 'stersu|mksu|rksu|sukisu|ksunext|kernelsu|ksu' | head -n1 | cut -d: -f2)
  case "$PKG" in
    *stersu*)   FW="SterSU" ;;
    *mksu*)     FW="MKSU" ;;
    *rksu*)     FW="RKSU" ;;
    *sukisu*)   FW="SukiSU Ultra" ;;
    *ksunext*)  FW="KernelSU Next" ;;
    *kernelsu*) FW="KernelSU" ;;
    *ksu*)      FW="KSU 分支" ;;
    *)          FW="" ;;
  esac
fi

# 目录特征兜底
if [ -z "$FW" ]; then
  if [ -d /data/adb/ksu ]; then FW="KernelSU 系列"
  elif [ -e /data/adb/apd ] || [ -e /data/adb/ap/bin/apd ]; then FW="APatch"
  elif [ -d /data/adb/magisk ]; then FW="Magisk"
  else FW="未知"
  fi
fi
echo "FW=$FW"

# ---------------------------------------------------------------- 管理器 / 内核版本
VER=""
for K in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd; do
  if [ -x "$K" ]; then
    VER=$("$K" -V 2>/dev/null | head -n1)
    [ -z "$VER" ] && VER=$("$K" --version 2>/dev/null | head -n1)
    [ -n "$VER" ] && break
  fi
done
[ -z "$VER" ] && VER=$(getprop ro.kernelsu.version 2>/dev/null)
[ -z "$VER" ] && VER=$(getprop ro.sukisu.version 2>/dev/null)
echo "FWVER=$VER"

# ---------------------------------------------------------------- 管理器类型与 ksud 原生 SUSFS 接口
# 不同管理器提供不同的 SUSFS 调用入口：
#   KernelSU Next → ksud susfs load / features / support / autoload（原生 LKM 加载，按 KMI 管理预编译模块）
#   SukiSU Ultra  → 内置 SUSFS 管理器，ksu_susfs 位于 /data/adb/ksu/bin
#   KernelSU 官方 / SterSU → 需另装 susfs4ksu 用户态模块，统一用 ksu_susfs
# 归一化类型供 App 选择「从各管理器的 GKI 原生调用 SUSFS」的入口。
MGRTYPE=UNKNOWN
case "$FW" in
  *SukiSU*)          MGRTYPE=SUKISU ;;
  *KernelSU Next*)   MGRTYPE=KSU_NEXT ;;
  KernelSU)          MGRTYPE=KSU ;;
  *SterSU*)          MGRTYPE=STERSU ;;
  *APatch*)          MGRTYPE=APATCH ;;
  *Magisk*)          MGRTYPE=MAGISK ;;
esac
echo "MGRTYPE=$MGRTYPE"

KSUD=""
for K in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd; do
  if [ -x "$K" ]; then KSUD=$K; break; fi
done
[ -z "$KSUD" ] && KSUD=$(command -v ksud 2>/dev/null)
echo "KSUD=$KSUD"
[ -n "$KSUD" ] && echo "KSUDVER=$("$KSUD" -V 2>/dev/null | head -n1 | tr -d '\r\n')"

# KernelSU Next 原生 SUSFS 接口能力（只读探查，不做任何加载/写入）
if [ -n "$KSUD" ]; then
  echo "KSUDSUP=$("$KSUD" susfs support 2>/dev/null | head -n1 | tr -d '\r\n')"
  echo "KSUDFEAT=$("$KSUD" susfs features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)"
  echo "KSUDAUTO=$("$KSUD" susfs autoload status 2>/dev/null | head -n1 | tr -d '\r\n')"
fi

# ---------------------------------------------------------------- SUSFS 权威探测
# SUSFS 是「编译进内核」的补丁（CONFIG_KSU_SUSFS=y，builtin），
# /proc/modules 里通常没有 susfs 模块名，SUSFS 甚至会主动隐藏自身模块痕迹；
# 只有 SUSFS 以可加载模块（susfs_guard_lkm）方式加载时才会出现在 /proc/modules。
# 因此权威判定必须走 ksu_susfs 的 supercall：show version 能返回即内核已打补丁。
SUSBIN=""
for B in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs \
         /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/bin/ksu_susfs \
         /data/adb/susfs4ksu/ksu_susfs /data/adb/susfs/bin/ksu_susfs \
         /data/adb/ksu/susfs/ksu_susfs /system/bin/ksu_susfs; do
  if [ -x "$B" ]; then SUSBIN=$B; break; fi
done
[ -z "$SUSBIN" ] && SUSBIN=$(command -v ksu_susfs 2>/dev/null)
# 全盘兜底：覆盖 susfs4ksu 任意安装形态（不同版本/管理器路径不一），
# 避免「装了却因路径不在白名单而被误报为缺少工具」。
if [ -z "$SUSBIN" ]; then
  SUSBIN=$(find /data/adb -maxdepth 6 -name ksu_susfs -type f -executable 2>/dev/null | head -n1)
fi

SUSV=""
SUSFEAT=""
SUSVAR=""
SUSMISMATCH=0
SUSMSG=""
if [ -n "$SUSBIN" ]; then
  SUSV=$("$SUSBIN" show version 2>/dev/null | head -n1 | tr -d '\r\n')
  SUSFEAT=$("$SUSBIN" show enabled_features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)
  SUSVAR=$("$SUSBIN" show variant 2>/dev/null | head -n1 | tr -d '\r\n')
  # 版本不匹配检测：用户态 ksu_susfs 与内核 SUSFS 补丁版本不兼容时，
  # ksu_susfs 会返回 "Requires susfs v1.5.3+" 之类提示，此时 add_sus_path 全部失败，
  # 路径隐藏无法生效。原样捕获该提示上报，避免误报「内核未带补丁」或「已启用」。
  for O in "$SUSV" "$SUSVAR" "$SUSFEAT"; do
    if echo "$O" | grep -qiE 'requires susfs|v1\.[0-9]+\.[0-9]+ ?\+'; then
      SUSMISMATCH=1
      SUSMSG=$(echo "$O" | head -c 200 | tr '\n' ' ')
      break
    fi
  done
fi

# KernelSU-Next 原生 susfs 检查：用 ksud 的 support / autoload 判定内核支持，
# 用 ksud susfs features 兜底填充已启用特性（当 ksu_susfs 二进制缺失时尤其有用）
KSUDSUP=""
if command -v ksud >/dev/null 2>&1; then
  KSUDSUP=$(ksud susfs support 2>/dev/null | head -n1 | tr -d '\r\n')
  [ -z "$SUSFEAT" ] && SUSFEAT=$(ksud susfs features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)
fi
# 已采集的 KSUDSUP / KSUDFEAT / KSUDAUTO 由上面的 ksud 探测段输出，这里不再重复

# dmesg 兜底（内核打了补丁但用户态工具缺失时）
DMESGSUS=0
dmesg 2>/dev/null | grep -qi susfs && DMESGSUS=1

SUSKERNEL=0
[ -n "$SUSV" ] && SUSKERNEL=1
echo "$KSUDSUP" | grep -qiE 'supported|true|yes|enabled' && SUSKERNEL=1
[ "$DMESGSUS" = "1" ] && SUSKERNEL=1
# 可加载模块形态的 SUSFS（susfs_guard_lkm 等）
grep -qiE 'susfs' /proc/modules 2>/dev/null && SUSKERNEL=1

# 状态分级：KERNEL_OK 可即时下发 / TOOL_ONLY 有工具但内核没补丁 /
#           KERNEL_NOTOOL 内核有补丁但缺工具 / NONE 完全未部署
SUSSTATE=NONE
if [ "$SUSKERNEL" = "1" ] && [ -n "$SUSBIN" ]; then SUSSTATE=KERNEL_OK
elif [ "$SUSKERNEL" = "1" ]; then SUSSTATE=KERNEL_NOTOOL
elif [ -n "$SUSBIN" ]; then SUSSTATE=TOOL_ONLY
fi
echo "SUSFS=$SUSKERNEL"
echo "SUSFSSTATE=$SUSSTATE"
echo "SUSBIN=$SUSBIN"
echo "SUSFSVER=$SUSV"
echo "SUSFSFEAT=$SUSFEAT"
echo "SUSFSVAR=$SUSVAR"
echo "SUSMISMATCH=$SUSMISMATCH"
echo "SUSMSG=$SUSMSG"

# ---------------------------------------------------------------- SUSFS 独立内核模块 (.ko) 探测
# SUSFS 除了编译进内核（GKI / builtin），也可作为独立 LKM（susfs_guard_lkm.ko 等）
# 动态 insmod 注入内核——这正是「不刷内核 / 不改分区」让 arm64 LKM 设备获得 SUSFS 的方式。
# 优先取 susfs4ksu 模块自带 .ko，再用 find 全盘兜底并过滤 susfs/sui 相关文件名。
KO=""
for D in /data/adb/modules/susfs4ksu /data/adb/ksu/susfs4ksu /data/adb/susfs4ksu; do
  KO=$(find "$D" -maxdepth 1 -name '*.ko' 2>/dev/null | head -n1)
  [ -n "$KO" ] && break
done
[ -z "$KO" ] && KO=$(find /data/adb -maxdepth 6 -name '*.ko' 2>/dev/null | grep -iE 'susfs|sui' | head -n1)
echo "KO=$KO"

# ---------------------------------------------------------------- 工作模式（互斥、单一真实状态）
# LKM：KernelSU 以「可加载内核模块」注入原厂内核，不替换内核，不刷 boot/init_boot。
#      /proc/modules 会出现 kernelsu/sukisu 模块；越狱模式属于 LKM 的一种特殊形态。
# GKI：用 KernelSU 的通用内核镜像「替换」原厂内核，KSU 编译进内核（builtin），
#      /proc/modules 里没有 kernelsu 模块。
# 判定优先级：越狱(JAILBREAK) > LKM > GKI，三者互斥，只输出一个 MODE。
KV=$(uname -r 2>/dev/null)
SE=$(getenforce 2>/dev/null | tr 'A-Z' 'a-z')
BOOTSE=$(getprop ro.boot.selinux 2>/dev/null | tr 'A-Z' 'a-z')
CMDLINE=$(cat /proc/cmdline 2>/dev/null)
PERM=0
[ "$SE" = "permissive" ] && PERM=1
[ "$BOOTSE" = "permissive" ] && PERM=1
echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive' && PERM=1

# 越狱特征文件（Magica / 运行时加载 LKM 的临时 root）
JBFILE=0
for F in /data/adb/ksu/.jailbreak /data/adb/.jailbreak /data/adb/ksu/jailbreak \
         /data/adb/ksu/.magica /data/local/tmp/.magica /data/adb/magica /data/local/tmp/magica; do
  [ -e "$F" ] && JBFILE=1
done

# LKM 特征：/proc/modules 或 /sys/module 中存在 KSU 可加载模块
LKM=0
grep -qiE 'kernelsu|sukisu|kpm|kpatch' /proc/modules 2>/dev/null && LKM=1
[ -d /sys/module/kernelsu ] && LKM=1
[ -d /sys/module/sukisu ] && LKM=1
# 内核命令行带 lkm 痕迹
echo "$CMDLINE" | grep -qiE 'ksu.*lkm|lkm.*ksu|sukisu.*lkm' && LKM=1

JB=0
if [ "$PERM" = "1" ]; then
  if [ "$JBFILE" = "1" ] || [ "$BOOTSE" = "permissive" ] || \
     echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive'; then JB=1; fi
fi
[ "$JBFILE" = "1" ] && JB=1
# 越狱模式（SukiSU/Magica）本质是「运行时动态加载 LKM」，本身即 LKM 形态
[ "$JB" = "1" ] && LKM=1

# 互斥单一工作模式
MODE=UNKNOWN
if [ "$JB" = "1" ]; then
  MODE=JAILBREAK_LKM
elif [ "$LKM" = "1" ]; then
  MODE=LKM
elif echo "$KV" | grep -qi android; then
  MODE=GKI
fi
echo "MODE=$MODE"
echo "JAILBREAK=$JB"
echo "PERMISSIVE=$PERM"
echo "LKM=$LKM"

# ---------------------------------------------------------------- 内核 / Zygisk 细节
HOOK=""
for H in /sys/kernel/sukisu/hook_type /sys/module/sukisu/parameters/hook_type \
         /sys/kernel/kernelsu/hook_type /sys/module/kernelsu/parameters/hook_type; do
  if [ -r "$H" ]; then HOOK=$(cat "$H" 2>/dev/null | head -n1); [ -n "$HOOK" ] && break; fi
done
if [ -z "$HOOK" ]; then
  case "$FW" in
    *SukiSU*)                       HOOK="Tracepoint / Syscall Redirect" ;;
    *KernelSU*|*KSU*|*SterSU*|*MKSU*|*RKSU*) HOOK="Kprobes / 内核态 Hook" ;;
    *APatch*)                       HOOK="KPM / Inline Hook" ;;
    *Magisk*)                       HOOK="Zygisk / 用户态 Hook" ;;
    *)                              HOOK="内核态 Hook" ;;
  esac
fi
echo "HOOK=$HOOK"

ZY=""
for M in zygisk_next rezygisk zygisksu zygisk; do
  P=/data/adb/modules/$M/module.prop
  if [ -f "$P" ]; then
    N=$(grep -m1 '^name=' "$P" | cut -d= -f2-)
    V=$(grep -m1 '^version=' "$P" | cut -d= -f2-)
    ZY="$N $V"
    break
  fi
done
[ -z "$ZY" ] && ZY="未检测到"
echo "ZYGISK=$ZY"

SU_CNT="?"
if [ -d /data/adb/ksu/profile ]; then SU_CNT=$(ls -1 /data/adb/ksu/profile 2>/dev/null | wc -l); fi
echo "SUPERUSER=$SU_CNT"

MODS=$(ls -1 /data/adb/modules 2>/dev/null | grep -vE '^\.|^$' | wc -l)
echo "MODULES=$MODS"
