#!/data/data/com.termux/files/usr/bin/bash
# 不走 runit 的手动后台启动（调试/兜底用）。
# 正式运行请用 ./tvctl start，让 runit 负责崩溃重启。
set -u
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PATH"
export SVDIR="${SVDIR:-$PREFIX/var/service}"
cd "$HOME/tvstation" || exit 1

if sv status tvstation 2>/dev/null | grep -q '^run:'; then
  echo "tvstation 已由 runit 托管；用 ./tvctl restart 或 ./tvctl stop"
  exit 0
fi

if [ -f station.pid ] && kill -0 "$(cat station.pid)" 2>/dev/null; then
  echo "已经在跑 pid=$(cat station.pid)"
  exit 0
fi

nohup python3 -u station.py >> station.log 2>&1 &
echo $! > station.pid
sleep 1
echo "started pid=$(cat station.pid)"
echo "停止：kill \$(cat ~/tvstation/station.pid)"
