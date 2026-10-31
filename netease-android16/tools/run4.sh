#!/system/bin/sh
P=com.hihonor.cloudmusix
am force-stop $P
sleep 0.3
logcat -c
monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
sleep 4
p=$(pidof $P)
echo "lastpid=$p"
logcat -d > /data/local/tmp/full.txt
echo "=== tail 60 ==="
tail -60 /data/local/tmp/full.txt