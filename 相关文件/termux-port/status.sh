#!/data/data/com.termux/files/usr/bin/bash
# 一眼看清运行状态：服务 / 配置 / 日志 / 代理 / 电视前台窗口
set -u
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PATH"
export SVDIR="${SVDIR:-$PREFIX/var/service}"
cd "$HOME/tvstation" || exit 1

echo "== 服务 =="
sv status tvstation 2>/dev/null || echo "(tvstation 没装或没在运行)"
sv status tvcontrol 2>/dev/null || echo "(tvcontrol 没装或没在运行)"
echo

echo "== 配置 =="
python3 station.py --print-config 2>/dev/null | grep -E '"(source|rooms|tv|public_host|port)"' \
  || echo "(读配置失败)"
echo

echo "== 最近日志 =="
tail -n 10 station.log 2>/dev/null || echo "(还没有 station.log)"
echo

echo "== 代理 =="
PORT=$(python3 -c 'import json;print(json.load(open("config.json",encoding="utf-8-sig")).get("port",8099))' 2>/dev/null || echo 8099)
curl -s --max-time 3 "http://127.0.0.1:$PORT/api/status" || echo "(代理没响应)"
echo
echo "== 控制台 =="
curl -s --max-time 3 "http://127.0.0.1:8098/api/health" || echo "(tvcontrol 没响应)"
echo

echo "== 电视前台窗口 =="
TV=$(python3 -c 'import json;print(json.load(open("config.json",encoding="utf-8-sig")).get("tv",""))' 2>/dev/null || true)
if [ -n "$TV" ]; then
  adb connect "$TV" >/dev/null 2>&1 || true
  adb -s "$TV" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || echo "(adb 不在线)"
else
  echo "(没有 config.json，读不到电视地址)"
fi
