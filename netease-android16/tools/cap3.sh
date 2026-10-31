#!/system/bin/sh
P=com.hihonor.cloudmusix
am force-stop $P
sleep 0.3
monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
i=0
p=
while [ $i -lt 60 ]; do
  p=$(pidof $P)
  if [ -n "$p" ]; then break; fi
  sleep 0.05
  i=$((i+1))
done
run-as $P cp /proc/$p/maps /data/local/tmp/maps.txt 2>/dev/null || run-as $P cat /proc/$p/maps > /data/local/tmp/maps.txt
sleep 3
logcat -d | grep "$p" | grep -E "FORTIFY|Fatal signal" > /data/local/tmp/crash.txt
echo "PID=$p maps=$(wc -c < /data/local/tmp/maps.txt) crash=$(wc -l < /data/local/tmp/crash.txt)"
cat /data/local/tmp/crash.txt