#!/data/data/com.termux/files/usr/bin/bash
# 设置轮播内容：直播间 / 合集视频
# 用法：cd ~/tvstation && ./set-content.sh
#
# 它只改 config.json 的 source / rooms / list_mid / list_id，
# 然后重启 tvstation，不需要手写 JSON。
set -u

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PATH"
cd "$HOME/tvstation" || { echo "找不到 ~/tvstation，先跑 install-termux.sh"; exit 1; }
[ -f config.json ] || { echo "没有 config.json，先跑 bash install-termux.sh"; exit 1; }

show_current() {
  python3 - <<'PY'
import json
try:
    c = json.load(open("config.json", encoding="utf-8-sig"))
except Exception as e:
    print("config.json 读不出来：", e)
    raise SystemExit(0)
print("当前模式：", c.get("source"))
print("直播间：  ", c.get("rooms"))
print("合集：    mid=%s  list_id=%s" % (c.get("list_mid"), c.get("list_id")))
PY
}

echo "======================================"
show_current
echo "======================================"
echo "1) 直播间轮播"
echo "2) 合集/列表视频轮播"
read -r -p "选 1 或 2: " CHOICE

case "$CHOICE" in
1)
  echo
  echo "输入直播间号，多个用逗号/空格分隔。"
  echo "也可以直接粘贴直播间链接，例如："
  echo "  https://live.bilibili.com/5017134"
  echo
  echo "注意：这版只认直播间号/链接，不认 UP 名字。"
  echo "拿号方法：B站 App → 该 UP 主页 → 直播 → 分享 → 复制链接。"
  echo "链接里 live.bilibili.com/ 后面的数字就是直播间号。"
  echo "如果是 b23.tv 短链，先在浏览器打开，复制展开后的 live.bilibili.com 链接。"
  read -r -p "> " INPUT
  INPUT="$INPUT" python3 - <<'PY' || exit 1
import json, os, re, sys

text = os.environ.get("INPUT", "").strip()
rooms = [int(x) for x in re.findall(r"live\.bilibili\.com/(?:blanc/)?(\d+)", text)]

if not rooms:
    for part in re.split(r"[,，\s]+", text):
        if part.isdigit():
            rooms.append(int(part))
        elif part:
            print("看不懂这一项：", part)
            print("请填直播间号（纯数字）或直播间链接。")
            sys.exit(1)

rooms = sorted(set(rooms))
if not rooms:
    print("没有识别到直播间号")
    sys.exit(1)

cfg = json.load(open("config.json", encoding="utf-8-sig"))
cfg["source"] = "live"
cfg["rooms"] = rooms
with open("config.json", "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("已写入直播间：", rooms)
PY
  ;;

2)
  echo
  echo "粘贴合集/列表链接，例如："
  echo "  https://space.bilibili.com/23191782/lists/1424266"
  echo "也可以直接输入 mid 和 列表 id（分两次输入）。"
  echo
  echo "注意：不是单个视频 BV 号，是“合集/列表”的链接。"
  echo "如果是 b23.tv 短链，先在浏览器打开，复制展开后的 space.bilibili.com 链接。"
  read -r -p "链接或 mid: " A

  case "$A" in
    *space.bilibili.com*)
      LINK="$A" python3 - <<'PY' || exit 1
import json, os, re, sys

text = os.environ.get("LINK", "")
m = re.search(r"space\.bilibili\.com/(\d+)/lists/(\d+)", text)
if not m:
    print("链接里没找到 mid 和 lists id。示例：")
    print("https://space.bilibili.com/23191782/lists/1424266")
    sys.exit(1)

mid, lid = int(m.group(1)), int(m.group(2))
cfg = json.load(open("config.json", encoding="utf-8-sig"))
cfg["source"] = "video"
cfg["list_mid"] = mid
cfg["list_id"] = lid
with open("config.json", "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("已写入合集：mid=%d  list_id=%d" % (mid, lid))
PY
      ;;

    *)
      [ -n "$A" ] || { echo "mid 不能为空"; exit 1; }
      read -r -p "列表 id: " B
      [ -n "$B" ] || { echo "列表 id 不能为空"; exit 1; }
      MID="$A" LID="$B" python3 - <<'PY' || exit 1
import json, os, sys

try:
    mid, lid = int(os.environ["MID"]), int(os.environ["LID"])
except ValueError:
    print("mid 和列表 id 必须是数字")
    sys.exit(1)

cfg = json.load(open("config.json", encoding="utf-8-sig"))
cfg["source"] = "video"
cfg["list_mid"] = mid
cfg["list_id"] = lid
with open("config.json", "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("已写入合集：mid=%d  list_id=%d" % (mid, lid))
PY
      ;;
  esac
  ;;

*)
  echo "只认 1 或 2"
  exit 1
  ;;
esac

echo
echo "重启服务..."
./tvctl restart
sleep 2
./tvctl status
