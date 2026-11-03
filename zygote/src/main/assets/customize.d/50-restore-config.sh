#!/system/bin/sh
# ============================================================
# ApkeSU 配置还原脚本
# 作用：把备份的 config.json 还原到 ApkeSU 的系统服务与
#       管理器应用中，实现"通过脚本替换配置文件"。
#
# 备份放置路径（手动放入即生效）：
#   /data/adb/apkesu/backup/config.json
#
# 配置文件实际路径：
#   系统服务端: /data/misc/hide_my_applist_*/config.json
#   管理器端:   /data/data/org.apkesu.app/files/config.json
#
# 本脚本在模块刷入/升级时自动执行一次，
# 同时会注册开机还原脚本（见下方 post-fs-data.d）。
# 手动立即还原：直接以 root 执行本脚本即可。
# ============================================================

BACKUP=/data/adb/apkesu/backup/config.json
PKG=org.apkesu.app
BOOT_SCRIPT=/data/adb/post-fs-data.d/apkesu-restore-config.sh

restore_one() {
  src=$1      # 备份文件
  dst=$2      # 目标 config.json
  owner=$3    # 目标属主 (uid:gid)，留空则不改
  dir=$(dirname "$dst")
  [ -d "$dir" ] || return 1
  cp -f "$src" "$dst.new" || return 1
  if [ -n "$owner" ]; then
    chown "$owner" "$dst.new"
  fi
  chmod 600 "$dst.new"
  chcon u:object_r:misc_data_file:s0 "$dst.new" 2>/dev/null
  mv -f "$dst.new" "$dst"
  return 0
}

if [ -f "$BACKUP" ]; then
  ui_print "- Restoring ApkeSU config from $BACKUP"

  # 1) 系统服务端配置（/data/misc/hide_my_applist_*/）
  for d in /data/misc/hide_my_applist_*/; do
    [ -d "$d" ] || continue
    if restore_one "$BACKUP" "${d}config.json" "1000:1000"; then
      ui_print "  - Restored: ${d}config.json"
    fi
  done

  # 2) 管理器应用配置
  APPDIR=/data/data/$PKG
  if [ -d "$APPDIR/files" ]; then
    OWNER=$(stat -c %u:%g "$APPDIR" 2>/dev/null)
    if restore_one "$BACKUP" "$APPDIR/files/config.json" "$OWNER"; then
      ui_print "  - Restored: $APPDIR/files/config.json"
    fi
  fi
else
  ui_print "- No ApkeSU backup config found at $BACKUP (skip restore)"
fi

# 3) 注册开机还原脚本：每次开机时若存在备份则覆盖还原，
#    保证刷入后任何重启都能恢复指定配置。不需要开机还原时
#    删除 /data/adb/apkesu/backup/config.json 或本文件即可。
mkdir -p /data/adb/post-fs-data.d /data/adb/apkesu/backup
cat > "$BOOT_SCRIPT" <<'EOF'
#!/system/bin/sh
# ApkeSU 开机配置还原（由 50-restore-config.sh 安装）
BACKUP=/data/adb/apkesu/backup/config.json
[ -f "$BACKUP" ] || exit 0
for d in /data/misc/hide_my_applist_*/; do
  [ -d "$d" ] || continue
  cp -f "$BACKUP" "${d}config.json.new" && \
    chown 1000:1000 "${d}config.json.new" && \
    chmod 600 "${d}config.json.new" && \
    mv -f "${d}config.json.new" "${d}config.json"
done
APPDIR=/data/data/org.apkesu.app
if [ -d "$APPDIR/files" ]; then
  OWNER=$(stat -c %u:%g "$APPDIR" 2>/dev/null)
  cp -f "$BACKUP" "$APPDIR/files/config.json.new" && \
    [ -n "$OWNER" ] && chown "$OWNER" "$APPDIR/files/config.json.new" && \
    chmod 600 "$APPDIR/files/config.json.new" && \
    mv -f "$APPDIR/files/config.json.new" "$APPDIR/files/config.json"
fi
EOF
chmod 755 "$BOOT_SCRIPT"

exit 0
