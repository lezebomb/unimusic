#!/system/bin/sh
P=com.hihonor.cloudmusix
am force-stop $P
sleep 0.3
monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
i=0
while [ $i -lt 40 ]; do
  p=$(pidof $P)
  if [ -n "$p" ]; then
    run-as $P cat /proc/$p/maps > /data/local/tmp/maps.txt 2>/data/local/tmp/err.txt
    run-as $P cat /proc/$p/task/*/comm > /data/local/tmp/comm.txt 2>/dev/null
    echo "PID=$p size=$(wc -c < /data/local/tmp/maps.txt)"
    break
  fi
  sleep 0.05
  i=$((i+1))
done
cat /data/local/tmp/err.txt