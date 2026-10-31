#!/system/bin/sh
am force-stop com.hihonor.cloudmusix
sleep 0.5
monkey -p com.hihonor.cloudmusix -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
i=0
while [ $i -lt 60 ]; do
  p=$(pidof com.hihonor.cloudmusix)
  if [ -n "$p" ]; then
    cp /proc/$p/maps /data/local/tmp/maps.txt 2>/dev/null
    echo "PID=$p"
    sleep 0.2
    cp /proc/$p/maps /data/local/tmp/maps2.txt 2>/dev/null
    break
  fi
  sleep 0.05
  i=$((i+1))
done
ls -l /data/local/tmp/maps*.txt