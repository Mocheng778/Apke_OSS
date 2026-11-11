#!/system/bin/sh
# 重新拉取 SUSFS LKM 全套 .ko（供仓库复现 / 更新到上游新版）。
# 来源：https://github.com/inforcqb/susfs4ksu-lkm  release v2.3.0-r18.9-1
# 用法：在 PC 上执行 sh fetch_ko.sh（需 curl），或在已 root 设备上用终端执行。
TAG="v2.3.0-r18.9-1"
BASE="https://github.com/inforcqb/susfs4ksu-lkm/releases/download/${TAG}"
FILES="
susfs_guard_lkm-android12-5.10.ko
susfs_guard_lkm-android13-5.10.ko
susfs_guard_lkm-android13-5.15.ko
susfs_guard_lkm-android14-5.15.ko
susfs_guard_lkm-android14-6.1.ko
susfs_guard_lkm-android15-6.6.ko
susfs_guard_lkm-android16-6.12.ko
susfs_guard_lkm-android17-6.18.ko
susfs_insmod
"
for f in $FILES; do
  echo "fetch $f"
  curl -fSL --retry 5 --retry-all-errors -C - -o "$f" "$BASE/$f" || echo "! failed: $f"
done
echo "done. 详见 README.txt"
