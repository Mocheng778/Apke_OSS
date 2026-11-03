#!/system/bin/sh

rm -f /data/adb/post-fs-data.d/apkesu.sh
rm -f /data/adb/post-mount.d/apkesu.sh
rm -f /data/adb/boot-completed.d/apkesu.sh

# INFO: Only removes if dir is empty
rmdir /data/adb/post-fs-data.d
rmdir /data/adb/post-mount.d
rmdir /data/adb/boot-completed.d

true
