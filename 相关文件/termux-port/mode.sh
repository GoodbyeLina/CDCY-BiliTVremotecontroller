#!/data/data/com.termux/files/usr/bin/bash
# 切换轮播模式：live=直播间轮播   video=列表视频轮播
# 只改 config.json 里的 source，再让 runit 重启服务；不动源码。
set -u

MODE="${1:-}"
case "$MODE" in
  live|video) ;;
  *) echo "用法: $0 live|video"; exit 1 ;;
esac

cd "$HOME/tvstation" || { echo "找不到 ~/tvstation，先跑 install-termux.sh"; exit 1; }

python3 station.py --set-source "$MODE" || exit 1
./tvctl restart || exit 1
sleep 2
./tvctl status
echo
echo "（约 10 秒内电视应出画面；不出就看 ./status.sh）"
