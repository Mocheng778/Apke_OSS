#!/system/bin/sh
# 采集 Root 环境信息。每行输出 KEY=VALUE，由 DeviceInfo.detectRoot() 解析。
#
# 修订 16 · 关键改动：
#  1) 字段按「重要性 + 速度」重排：所有 UI 关键字段（MODE / SUPERUSER / MODULES /
#     ko 状态 / 框架版本）全部前置，慢探测（ksud susfs *、ksu_susfs show *、dmesg、
#     全盘 find）放到末尾。即使慢探测被超时截断，关键字段也已完整输出。
#  2) 每个可能阻塞的外部命令统一走 T() 包装（timeout 2s），避免单条命令卡死整脚本。
#  3) 末尾输出 PROBE_DONE=1 作为完整性标记；App 侧据此区分「完整」与「部分」结果，
#     不再把半截输出伪装成「工作中」。
#  4) 工作模式判定增强兜底：只要有 root 且能识别到 KSU 系特征，至少落到 LKM 或 GKI，
#     避免一律显示 UNKNOWN。
#
# 安全约束：本脚本全程只读（cat / getprop / grep / ls / id / uname / ksu_susfs show / dmesg），
# 不写 /dev/block/* 或任何分区。
PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/data/adb/ap/bin:$PATH
export PATH

# 可能阻塞的外部命令统一限 2 秒；设备无 timeout 时直接执行（此时靠字段前置兜底）。
T() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 2 "$@"
  else
    "$@"
  fi
}

# ================================================================ A. 基础快字段（最先输出）
echo "SU=$(id -u 2>/dev/null)"
KREL=$(uname -r 2>/dev/null)
echo "KERNEL=$KREL"
echo "KERNELFULL=$(cat /proc/version 2>/dev/null)"
echo "SELINUX=$(getenforce 2>/dev/null)"
echo "FP=$(getprop ro.build.fingerprint 2>/dev/null)"
echo "SDK=$(getprop ro.build.version.sdk 2>/dev/null)"

# ================================================================ B. 工作模式（互斥，单一真实状态）
# LKM：KernelSU 以「可加载内核模块」注入原厂内核，不替换内核，不刷 boot/init_boot。
# GKI：用 KernelSU 通用内核镜像「替换」原厂内核，KSU 编译进内核（builtin）。
# 优先级：越狱(JAILBREAK) > LKM > GKI > KSU 特征兜底。
SE=$(getenforce 2>/dev/null | tr 'A-Z' 'a-z')
BOOTSE=$(getprop ro.boot.selinux 2>/dev/null | tr 'A-Z' 'a-z')
CMDLINE=$(cat /proc/cmdline 2>/dev/null)
PERM=0
[ "$SE" = "permissive" ] && PERM=1
[ "$BOOTSE" = "permissive" ] && PERM=1
echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive' && PERM=1

JBFILE=0
for F in /data/adb/ksu/.jailbreak /data/adb/.jailbreak /data/adb/ksu/jailbreak \
         /data/adb/ksu/.magica /data/local/tmp/.magica /data/adb/magica /data/local/tmp/magica; do
  [ -e "$F" ] && JBFILE=1
done

LKM=0
grep -qiE 'kernelsu|sukisu|kpm|kpatch' /proc/modules 2>/dev/null && LKM=1
[ -d /sys/module/kernelsu ] && LKM=1
[ -d /sys/module/sukisu ] && LKM=1
echo "$CMDLINE" | grep -qiE 'ksu.*lkm|lkm.*ksu|sukisu.*lkm' && LKM=1

JB=0
if [ "$PERM" = "1" ]; then
  if [ "$JBFILE" = "1" ] || [ "$BOOTSE" = "permissive" ] || \
     echo "$CMDLINE" | grep -qi 'androidboot.selinux=permissive'; then JB=1; fi
fi
[ "$JBFILE" = "1" ] && JB=1
[ "$JB" = "1" ] && LKM=1

# KSU 系特征（目录即可判定，不依赖 pm 等慢命令）
HAS_KSU=0
[ -d /data/adb/ksu ] && HAS_KSU=1
[ -e /data/adb/ksud ] && HAS_KSU=1
[ -e /data/adb/ksu/bin/ksud ] && HAS_KSU=1
[ -e /data/adb/apd ] && HAS_KSU=1

MODE=UNKNOWN
if [ "$JB" = "1" ]; then
  MODE=JAILBREAK_LKM
elif [ "$LKM" = "1" ]; then
  MODE=LKM
elif echo "$KREL" | grep -qiE 'android|gki'; then
  # 内核版本形如 6.1.75-android14-11-g...：通用内核镜像 = GKI 模式
  MODE=GKI
elif [ "$HAS_KSU" = "1" ]; then
  # 有 KSU 管理器但内核版本不含 android 标记：多为向原厂内核注入 LKM
  MODE=LKM
elif echo "$KREL" | grep -qiE '^[0-9]+\.[0-9]+'; then
  # 最后兜底：有 root 且内核为常规版本号，按 GKI 归类（启发式，仅显示用）
  MODE=GKI
fi
echo "MODE=$MODE"
echo "JAILBREAK=$JB"
echo "PERMISSIVE=$PERM"
echo "LKM=$LKM"
echo "MODEBASIS=$HAS_KSU"

# ================================================================ C. 超级用户 / 模块数（纯 ls，无外部慢命令）
SU_CNT="—"
if [ -d /data/adb/ksu/profile ]; then
  SU_CNT=$(ls -1 /data/adb/ksu/profile 2>/dev/null | wc -l | tr -d ' ')
  [ -z "$SU_CNT" ] && SU_CNT="—"
fi
echo "SUPERUSER=$SU_CNT"
MODS=$(ls -1 /data/adb/modules 2>/dev/null | grep -vE '^\.|^$' | wc -l | tr -d ' ')
[ -z "$MODS" ] && MODS="0"
echo "MODULES=$MODS"

# ================================================================ D. 发行版内置 .ko（快字段前置）
# 内置于模块 ZIP 的 susfs_ko/，刷入后位于 /data/adb/modules/apkesu_zygisk/susfs_ko/。
KO=""
KODIR=""
KOKMI=""
KOMATCH=""
KOLIST=""
KOCOUNT=0
for D in \
  /data/adb/modules/apkesu_zygisk/susfs_ko \
  /data/adb/modules_update/apkesu_zygisk/susfs_ko \
  /data/adb/ksu/susfs_ko \
  /data/adb/susfs_ko; do
  [ -d "$D" ] && KODIR="$D" && break
done
[ -z "$KODIR" ] && KODIR=$(T find /data/adb -maxdepth 5 -type d -name susfs_ko 2>/dev/null | head -n1)

# 依据 /proc/sys/kernel/osrelease 推导 KMI（androidNN-X.Y），与内置 .ko 文件名后缀匹配。
[ -z "$KREL" ] && KREL=$(uname -r 2>/dev/null)
KAND=$(echo "$KREL" | grep -oE 'android[0-9]+' | head -n1)
KVER=$(echo "$KREL" | grep -oE '^[0-9]+\.[0-9]+' | head -n1)
if [ -n "$KAND" ] && [ -n "$KVER" ]; then
  KOKMI="${KAND}-${KVER}"
fi

if [ -n "$KODIR" ] && [ -d "$KODIR" ]; then
  for F in "$KODIR"/*.ko; do
    [ -f "$F" ] || continue
    KOCOUNT=$((KOCOUNT + 1))
    B=$(basename "$F")
    KOLIST="$KOLIST${KOLIST:+,}$B"
  done
  if [ -n "$KOKMI" ] && [ -f "$KODIR/susfs_guard_lkm-${KOKMI}.ko" ]; then
    KOMATCH="$KODIR/susfs_guard_lkm-${KOKMI}.ko"
    KO="$KOMATCH"
  else
    KO=$(ls -1 "$KODIR"/*.ko 2>/dev/null | head -n1)
  fi
fi
# 内置缺失时，回退到用户自行安装的 susfs4ksu 模块 / 全盘兜底（限时限深）。
[ -z "$KO" ] && for D in /data/adb/modules/susfs4ksu /data/adb/ksu/susfs4ksu /data/adb/susfs4ksu; do
  KO=$(find "$D" -maxdepth 1 -name '*.ko' 2>/dev/null | head -n1)
  [ -n "$KO" ] && break
done
[ -z "$KO" ] && KO=$(T find /data/adb -maxdepth 6 -name '*.ko' 2>/dev/null | grep -iE 'susfs|sui' | head -n1)
echo "KO=$KO"
echo "KODIR=$KODIR"
echo "KOKMI=$KOKMI"
echo "KOMATCH=$KOMATCH"
echo "KOLIST=$KOLIST"
echo "KOCOUNT=$KOCOUNT"

# ================================================================ E. Root 框架品牌 / 版本 / 管理器类型
FW=""
if T pm path com.sukisu.ultra >/dev/null 2>&1; then FW="SukiSU Ultra"
elif T pm path com.rifsxd.ksunext >/dev/null 2>&1; then FW="KernelSU Next"
elif T pm path me.weishu.kernelsu >/dev/null 2>&1; then FW="KernelSU"
elif T pm path me.bmax.apatch >/dev/null 2>&1 || [ -e /data/adb/apd ]; then FW="APatch"
elif T pm path com.topjohnwu.magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then FW="Magisk"
fi
if [ -z "$FW" ]; then
  PKG=$(T pm list packages 2>/dev/null | grep -iE 'stersu|mksu|rksu|sukisu|ksunext|kernelsu|ksu' | head -n1 | cut -d: -f2)
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
if [ -z "$FW" ]; then
  if [ -d /data/adb/ksu ]; then FW="KernelSU 系列"
  elif [ -e /data/adb/apd ] || [ -e /data/adb/ap/bin/apd ]; then FW="APatch"
  elif [ -d /data/adb/magisk ]; then FW="Magisk"
  else FW="未知"
  fi
fi
echo "FW=$FW"

VER=""
for K in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd; do
  if [ -x "$K" ]; then
    VER=$(T "$K" -V 2>/dev/null | head -n1)
    [ -z "$VER" ] && VER=$(T "$K" --version 2>/dev/null | head -n1)
    [ -n "$VER" ] && break
  fi
done
[ -z "$VER" ] && VER=$(getprop ro.kernelsu.version 2>/dev/null)
[ -z "$VER" ] && VER=$(getprop ro.sukisu.version 2>/dev/null)
echo "FWVER=$VER"

MGRTYPE=UNKNOWN
case "$FW" in
  *SukiSU*)            MGRTYPE=SUKISU ;;
  *"KernelSU Next"*)   MGRTYPE=KSU_NEXT ;;
  KernelSU)            MGRTYPE=KSU ;;
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
[ -n "$KSUD" ] && echo "KSUDVER=$(T "$KSUD" -V 2>/dev/null | head -n1 | tr -d '\r\n')"

# ================================================================ F. Hook 类型 / Zygisk
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

# ================================================================ G. 慢探测（后置，超时不影响 A~F）
# KernelSU Next 原生 SUSFS 接口能力（只读探查，不做任何加载/写入）
if [ -n "$KSUD" ]; then
  echo "KSUDSUP=$(T "$KSUD" susfs support 2>/dev/null | head -n1 | tr -d '\r\n')"
  echo "KSUDFEAT=$(T "$KSUD" susfs features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)"
  echo "KSUDAUTO=$(T "$KSUD" susfs autoload status 2>/dev/null | head -n1 | tr -d '\r\n')"
fi

# SUSFS 权威探测：ksu_susfs show version 通过 supercall 判定内核是否带补丁。
SUSBIN=""
for B in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs \
         /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/bin/ksu_susfs \
         /data/adb/susfs4ksu/ksu_susfs /data/adb/susfs/bin/ksu_susfs \
         /data/adb/ksu/susfs/ksu_susfs /system/bin/ksu_susfs; do
  if [ -x "$B" ]; then SUSBIN=$B; break; fi
done
[ -z "$SUSBIN" ] && SUSBIN=$(command -v ksu_susfs 2>/dev/null)
if [ -z "$SUSBIN" ]; then
  SUSBIN=$(T find /data/adb -maxdepth 6 -name ksu_susfs -type f -executable 2>/dev/null | head -n1)
fi

SUSV=""
SUSFEAT=""
SUSVAR=""
SUSMISMATCH=0
SUSMSG=""
if [ -n "$SUSBIN" ]; then
  SUSV=$(T "$SUSBIN" show version 2>/dev/null | head -n1 | tr -d '\r\n')
  SUSFEAT=$(T "$SUSBIN" show enabled_features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)
  SUSVAR=$(T "$SUSBIN" show variant 2>/dev/null | head -n1 | tr -d '\r\n')
  # 版本不匹配：用户态 ksu_susfs 与内核 SUSFS 补丁版本不兼容时返回
  # "Requires susfs v1.5.3+" 之类提示，此时 add_sus_path 全部失败。
  for O in "$SUSV" "$SUSVAR" "$SUSFEAT"; do
    if echo "$O" | grep -qiE 'requires susfs|v1\.[0-9]+\.[0-9]+ ?\+'; then
      SUSMISMATCH=1
      SUSMSG=$(echo "$O" | head -c 200 | tr '\n' ' ')
      break
    fi
  done
fi
[ -z "$SUSFEAT" ] && [ -n "$KSUD" ] && SUSFEAT=$(T "$KSUD" susfs features 2>/dev/null | tr '\r\n' ' ' | cut -c1-400)

DMESGSUS=0
T dmesg 2>/dev/null | grep -qi susfs && DMESGSUS=1

SUSKERNEL=0
[ -n "$SUSV" ] && SUSKERNEL=1
[ "$DMESGSUS" = "1" ] && SUSKERNEL=1
grep -qiE 'susfs' /proc/modules 2>/dev/null && SUSKERNEL=1
KSUDSUP_T=$(T "$KSUD" susfs support 2>/dev/null | head -n1 | tr -d '\r\n')
echo "$KSUDSUP_T" | grep -qiE 'supported|true|yes|enabled' && SUSKERNEL=1

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

# 完整性标记：App 据此区分「完整探测」与「被截断的部分结果」。
echo "PROBE_DONE=1"
