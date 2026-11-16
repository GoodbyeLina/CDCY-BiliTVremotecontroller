#!/data/data/com.termux/files/usr/bin/bash
# 树莓派 B站轮播台 → Termux（旧手机）一键安装
#
# 用法（在 Termux 里）：
#   bash install-termux.sh
#   # 或者无人值守（环境变量优先）：
#   TVSTATION_TV=192.168.10.139 TVSTATION_HOST=192.168.10.241 \
#   TVSTATION_SOURCE=live TVSTATION_ROOMS=5017134,5064126 \
#   bash install-termux.sh --yes
#
# 做的事：检查依赖 → 问电视/手机地址和轮播内容 → 写 config.json
#        → 检查 adb/mpv → 装 runit 服务 → 装 Termux:Boot 脚本
#        → 启动并自检。
#
# 前置（只装一次）：
#   pkg update && pkg upgrade -y
#   pkg install -y python android-tools termux-services iproute2 curl
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="$HOME/tvstation"
ASSUME_YES=0
[ "${1:-}" = "--yes" ] && ASSUME_YES=1

bold() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[✓]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[×]\033[0m %s\n' "$*" >&2; exit 1; }

# 优先级：环境变量 > 交互输入 > 默认值
ask() {
  local prompt="$1" def="${2:-}" varname="${3:-}" ans=""
  if [ -n "$varname" ]; then
    eval "ans=\"\${$varname:-}\""
    if [ -n "$ans" ]; then printf '%s' "$ans"; return; fi
  fi
  if [ "$ASSUME_YES" = 1 ]; then printf '%s' "$def"; return; fi
  if [ -t 0 ]; then
    read -r -p "  $prompt${def:+ [$def]}: " ans || true
  fi
  printf '%s' "${ans:-$def}"
}

# ---------- 0. 把程序放到 ~/tvstation ----------
if [ "$HERE" != "$TARGET" ]; then
  info "把程序复制到 $TARGET"
  mkdir -p "$TARGET" || die "建目录失败：$TARGET"
  cp -R "$HERE"/. "$TARGET"/ || die "复制失败（检查存储空间）"
fi
cd "$TARGET" || die "进不去 $TARGET"

# Windows 解压/传输过来时可能丢了可执行位，这里统一补上。
chmod 755 station.py control.py tvctl mode.sh status.sh run.sh install-termux.sh uninstall-termux.sh set-content.sh 2>/dev/null || true

# 如果已有 config.json，把它当作问答的默认值：重跑时一路回车就能保留原配置。
cfg_get() {
  python3 - "$1" <<'PY' 2>/dev/null || true
import json, sys
try:
    with open("config.json", encoding="utf-8-sig") as fh:
        v = json.load(fh).get(sys.argv[1], "")
    if isinstance(v, list):
        v = ",".join(str(x) for x in v)
    print(v)
except Exception:
    pass
PY
}
OLD_TV="$(cfg_get tv)"
OLD_HOST="$(cfg_get public_host)"
OLD_MODE="$(cfg_get source)"
OLD_ROOMS="$(cfg_get rooms)"
OLD_MID="$(cfg_get list_mid)"
OLD_LIST="$(cfg_get list_id)"
DEF_TV="${OLD_TV:-192.168.10.139:5555}"
DEF_MODE="${OLD_MODE:-live}"
DEF_ROOMS="${OLD_ROOMS:-5017134,5064126,4374913}"
DEF_MID="${OLD_MID:-23191782}"
DEF_LIST="${OLD_LIST:-1424266}"

# ---------- 1. 依赖 ----------
bold "0/7 环境检查"
missing=""
for c in python3 adb sv ip curl; do
  command -v "$c" >/dev/null 2>&1 || missing="$missing $c"
done
if [ -n "$missing" ]; then
  warn "缺少命令：$missing"
  echo "  先执行：pkg install -y python android-tools termux-services iproute2 curl"
  die "装完依赖再重跑本脚本"
fi
ok "python3 $(python3 -V 2>&1 | awk '{print $2}')"
python3 - <<'PY' || die "python3 版本太老，需要 3.8+"
import sys
sys.exit(0 if sys.version_info >= (3, 8) else 1)
PY
ok "adb: $(command -v adb)"
ok "sv:  $(command -v sv)"

# ---------- 2. 电视地址 ----------
bold "1/7 电视"
info "电视必须是 Android，装好 mpv-android（is.xyz.mpv），并打开网络调试。"
TV="$(ask '电视 ADB 地址（IP 或 IP:5555）' "$DEF_TV" TVSTATION_TV)"
case "$TV" in
  *:*) ;;
  *) TV="$TV:5555" ;;
esac
TV_IP="${TV%%:*}"

# ---------- 3. 手机 WiFi 地址 ----------
bold "2/7 手机 WiFi 地址"
WIFI_IP="$(ip -4 addr show wlan0 2>/dev/null | awk '/inet /{sub(/\/.*/,"",$2); print $2; exit}')"
if [ -z "$WIFI_IP" ]; then
  WIFI_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  [ -n "$WIFI_IP" ] && warn "没读到 wlan0，暂用默认路由地址 $WIFI_IP（可能是蜂窝网，务必确认）"
fi
[ -n "$WIFI_IP" ] || die "读不到手机 IP：先在 Termux 执行 ip -4 addr，把 wlan0 的 inet 地址填进来"
if [ -n "$OLD_HOST" ] && [ "$OLD_HOST" != "$WIFI_IP" ]; then
  warn "旧配置 public_host=$OLD_HOST，当前 wlan0=$WIFI_IP；默认用当前地址"
fi
DEF_HOST="$WIFI_IP"
PHONE_IP="$(ask '手机 WiFi IP（电视要能访问它）' "$DEF_HOST" TVSTATION_HOST)"
[ -n "$PHONE_IP" ] || die "手机 IP 不能为空"
info "电视将回连：http://$PHONE_IP:8099"

# ---------- 4. 轮播内容 ----------
bold "3/7 轮播内容"
MODE="$(ask '默认模式 live=直播间轮播 / video=列表视频' "$DEF_MODE" TVSTATION_SOURCE)"
case "$MODE" in
  live|video) ;;
  *) warn "只认 live/video，按 live 处理"; MODE=live ;;
esac
ROOMS="$(ask '直播间号（英文逗号分隔）' "$DEF_ROOMS" TVSTATION_ROOMS)"
MID="$(ask '列表所属 UP 主 mid（video 模式用）' "$DEF_MID" TVSTATION_MID)"
LIST_ID="$(ask '视频列表 id（video 模式用）' "$DEF_LIST" TVSTATION_LIST)"

# ---------- 5. 写 config.json ----------
bold "4/7 写 config.json"
if [ -f config.json ]; then
  BAK="config.json.bak.$(date +%Y%m%d%H%M%S)"
  cp -f config.json "$BAK" && warn "已有 config.json，备份为 $BAK"
fi
TVX_TV="$TV" TVX_HOST="$PHONE_IP" TVX_MODE="$MODE" TVX_ROOMS="$ROOMS" \
TVX_MID="$MID" TVX_LIST="$LIST_ID" \
python3 - <<'PY' || die "写 config.json 失败"
import json, os
e = os.environ
rooms = [int(x) for x in e["TVX_ROOMS"].replace("，", ",").replace(" ", "").split(",") if x]
if not rooms:
    raise SystemExit("rooms 不能为空")

seen = False
if os.path.isfile("config.json"):
    try:
        with open("config.json", encoding="utf-8-sig") as fh:
            seen = bool(json.load(fh).get("setup_seen"))
    except Exception:
        seen = False

cfg = {
    "source": e["TVX_MODE"],
    "rooms": rooms,
    "list_mid": int(e["TVX_MID"]),
    "list_id": int(e["TVX_LIST"]),
    "pick_count": 5,
    "switch_seconds": 30,
    "status_seconds": 10,
    "port": 8099,
    "control_port": 8098,
    "setup_seen": seen,
    "tv": e["TVX_TV"],
    "public_host": e["TVX_HOST"],
    "mpv_pkg": "is.xyz.mpv",
    "mpv_activity": "is.xyz.mpv/.MPVActivity",
}
with open("config.json", "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("  已写入", os.path.abspath("config.json"))
PY
ok "config.json 就绪"
python3 -m json.tool config.json >/dev/null || die "config.json 不是合法 JSON"
python3 - <<'PY' || die "station.py 语法检查失败"
compile(open("station.py", encoding="utf-8").read(), "station.py", "exec")
PY
python3 - <<'PY' || die "control.py 语法检查失败"
compile(open("control.py", encoding="utf-8").read(), "control.py", "exec")
PY
ok "station.py / control.py 语法检查通过"

# ---------- 6. adb 检查 ----------
bold "5/7 电视连通性"
adb start-server >/dev/null 2>&1 || true
adb connect "$TV" 2>&1 | sed 's/^/  /'
sleep 1
ST="$(adb -s "$TV" get-state 2>/dev/null || true)"
if [ "$ST" = "device" ]; then
  ok "adb 已连通并授权：$TV"
  if adb -s "$TV" shell pm path is.xyz.mpv 2>/dev/null | grep -q package; then
    ok "电视上已装 mpv-android"
  else
    warn "电视上没找到 is.xyz.mpv：先在电视装 mpv-android，再回来重跑"
  fi
else
  warn "adb 状态=${ST:-未知}"
  info "如果电视弹出「允许 USB 调试」，勾选「一律允许」再点允许，然后重跑本脚本。"
fi

# ---------- 7. 安装 runit 服务 ----------
bold "6/7 安装 runit 服务"
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export SVDIR="$PREFIX/var/service"
export LOGDIR="$PREFIX/var/log"
mkdir -p "$SVDIR/tvstation" || die "建服务目录失败"
sed "s|@HOME@|$HOME|g" templates/service-run > "$SVDIR/tvstation/run" || die "生成 run 脚本失败"
chmod 755 "$SVDIR/tvstation/run"
ok "服务脚本：$SVDIR/tvstation/run"

mkdir -p "$SVDIR/tvcontrol" || die "建控制服务目录失败"
sed "s|@HOME@|$HOME|g" templates/control-run > "$SVDIR/tvcontrol/run" || die "生成 control run 失败"
chmod 755 "$SVDIR/tvcontrol/run"
ok "控制服务脚本：$SVDIR/tvcontrol/run"

# ---------- 8. 开机自启 ----------
bold "7/7 开机自启与启动"
mkdir -p "$HOME/.termux/boot" || die "建 ~/.termux/boot 失败"
sed "s|@HOME@|$HOME|g" templates/boot-start > "$HOME/.termux/boot/00-tvstation" || die "生成 boot 脚本失败"
chmod 755 "$HOME/.termux/boot/00-tvstation"
ok "Termux:Boot 脚本：~/.termux/boot/00-tvstation"
info "必须装 Termux:Boot，并先手动点开它一次；否则开机不会执行。"

termux-wake-lock 2>/dev/null || warn "termux-wake-lock 失败（不影响本次启动）"
service-daemon start >/dev/null 2>&1 || true
sleep 2
sv-enable tvstation >/dev/null 2>&1 || sv up tvstation || warn "tvstation 启动失败"
sv-enable tvcontrol >/dev/null 2>&1 || sv up tvcontrol || warn "tvcontrol 启动失败"
sleep 3
sv status tvstation || true
sv status tvcontrol || true

# ---------- 9. 自检 ----------
echo
curl -s --max-time 3 "http://127.0.0.1:8099/api/status" && echo || warn "8099 还没响应，等 10 秒再试"
curl -s --max-time 3 "http://127.0.0.1:8098/api/health" && echo || warn "8098 控制服务还没响应"
tail -n 10 station.log 2>/dev/null || true

bold "完成"
cat <<EOF
  服务控制：  cd ~/tvstation && ./tvctl start|stop|restart|status|log
  控制台：    http://127.0.0.1:8098（手机浏览器/app 打开）
  切模式：    ./mode.sh live|video
  看状态：    ./status.sh
  看日志：    tail -f ~/tvstation/station.log
  改配置：    编辑 ~/tvstation/config.json 后 ./tvctl restart
  电视地址：  $TV
  手机地址：  $PHONE_IP（强烈建议在路由器里做 DHCP 固定）
EOF
