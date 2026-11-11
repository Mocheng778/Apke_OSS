#!/system/bin/sh
# 采集 Root 环境信息。每行输出 KEY=VALUE，由 DeviceInfo.detectRoot() 解析。
# 注意：内核级精确状态只有 Root 管理器自身能读取，本脚本中的
# 「工作模式 / 钩子类型」为基于环境特征的启发式推断。
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

# ---------------------------------------------------------------- 工作模式
KV=$(uname -r 2>/dev/null)
LKM=0
if grep -qiE 'kernelsu|sukisu|susfs|kpm' /proc/modules 2>/dev/null; then LKM=1; fi
if [ -d /sys/module/kernelsu ] || [ -d /sys/module/sukisu ]; then LKM=1; fi
echo "LKM=$LKM"

MAJ=$(echo "$KV" | cut -d. -f1)
MIN=$(echo "$KV" | cut -d. -f2 | cut -d- -f1)
GKI=0
if [ "$MAJ" -gt 5 ] 2>/dev/null; then GKI=1; fi
if [ "$MAJ" -eq 5 ] 2>/dev/null && [ "$MIN" -ge 10 ] 2>/dev/null; then GKI=1; fi
if ! echo "$KV" | grep -q android; then GKI=0; fi
echo "GKI=$GKI"

CUSTOM=0
if echo "$KV" | grep -qiE '\-g[0-9a-f]{6,}|-sukisu|-ksu|-custom|dirty|perf\+|@'; then CUSTOM=1; fi
if grep -qiE 'sukisu|kernelsu|@release|custom' /proc/version 2>/dev/null; then CUSTOM=1; fi
echo "CUSTOM=$CUSTOM"

# 越狱模式：SukiSU 定义为「设备以 permissive SELinux 启动 + Magica 运行时加载 LKM 提权」
SE=$(getenforce 2>/dev/null | tr 'A-Z' 'a-z')
BOOTSE=$(getprop ro.boot.selinux 2>/dev/null | tr 'A-Z' 'a-z')
CMDLINE=$(cat /proc/cmdline 2>/dev/null)
PERM=0
[ "$SE" = "permissive" ] && PERM=1
[ "$BOOTSE" = "permissive" ] && PERM=1
if echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive'; then PERM=1; fi

JBFILE=0
for F in /data/adb/ksu/.jailbreak /data/adb/.jailbreak /data/adb/ksu/jailbreak \
         /data/adb/ksu/.magica /data/local/tmp/.magica /data/adb/magica /data/local/tmp/magica; do
  [ -e "$F" ] && JBFILE=1
done

JB=0
if [ "$PERM" = "1" ]; then
  if [ "$JBFILE" = "1" ] || [ "$BOOTSE" = "permissive" ] || \
     echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive'; then JB=1; fi
fi
[ "$JBFILE" = "1" ] && JB=1
echo "JAILBREAK=$JB"
echo "PERMISSIVE=$PERM"

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

# ---------------------------------------------------------------- SUSFS 支持
SUS=0
if grep -qi susfs /proc/modules 2>/dev/null; then SUS=1; fi
[ -d /sys/module/susfs ] && SUS=1
SUSBIN=""
for B in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs \
         /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/susfs4ksu/ksu_susfs; do
  if [ -x "$B" ]; then SUS=1; SUSBIN=$B; break; fi
done
echo "SUSFS=$SUS"
SUSV=""
if [ -n "$SUSBIN" ]; then SUSV=$("$SUSBIN" show version 2>/dev/null | head -n1); fi
echo "SUSFSVER=$SUSV"
