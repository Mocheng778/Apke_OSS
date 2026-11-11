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

# ---------------------------------------------------------------- SUSFS 权威探测
# SUSFS 是「编译进内核」的补丁（CONFIG_KSU_SUSFS=y，builtin），
# /proc/modules 里通常没有 susfs 模块名，SUSFS 甚至会主动隐藏自身模块痕迹；
# 只有 SUSFS 以可加载模块（susfs_guard_lkm）方式加载时才会出现在 /proc/modules。
# 因此权威判定必须走 ksu_susfs 的 supercall：show version 能返回即内核已打补丁。
SUSBIN=""
for B in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs \
         /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/susfs4ksu/ksu_susfs; do
  if [ -x "$B" ]; then SUSBIN=$B; break; fi
done
[ -z "$SUSBIN" ] && SUSBIN=$(command -v ksu_susfs 2>/dev/null)

SUSV=""
SUSFEAT=""
SUSVAR=""
if [ -n "$SUSBIN" ]; then
  SUSV=$("$SUSBIN" show version 2>/dev/null | head -n1 | tr -d '\r\n')
  SUSFEAT=$("$SUSBIN" show enabled_features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)
  SUSVAR=$("$SUSBIN" show variant 2>/dev/null | head -n1 | tr -d '\r\n')
fi

# KernelSU-Next 内置 susfs 检查
KSUDSUP=""
if command -v ksud >/dev/null 2>&1; then
  KSUDSUP=$(ksud susfs support 2>/dev/null | head -n1 | tr -d '\r\n')
  [ -z "$SUSFEAT" ] && SUSFEAT=$(ksud susfs features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)
fi

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
