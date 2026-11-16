#!/bin/bash
# P2-4 代理真流验收（不改 App 代码）：B站 CDN → 本机 MiniHttpServer → ffprobe/ffmpeg
# 能证明：/live 可解、/video Range→206+Content-Range、60s 内连续可解码（-f null -，不落盘）
# 不能证明：电视上的播放器认不认这个流（属 HANDOFF 未验证清单）
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PROXY_PORT:-8100}"
ROOM="${1:-6}"   # 默认 B站官方直播间 6（常年开播）；自测别的房间: ./tools/verify-proxy.sh <room> [BV]
BV="${2:-BV1xx411c7mD}"
TMP="$(mktemp -d)"; PROXY_PID=""
cleanup() { [ -n "$PROXY_PID" ] && kill "$PROXY_PID" 2>/dev/null; wait "$PROXY_PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

echo "== 1) 解析上游直链 =="
python3 - "$TMP" "$ROOM" "$BV" <<PY
import sys, json, subprocess, os, urllib.parse, hashlib, re, time
tmp, room, bv = sys.argv[1], sys.argv[2], sys.argv[3]
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
def curl(url, ref="https://live.bilibili.com/"):
    return subprocess.run(["curl","-sS","--max-time","25","-A",UA,"-e",ref,url],capture_output=True,text=True).stdout
def get_json(url, ref="https://live.bilibili.com/", tries=4):
    last=""
    for _ in range(tries):
        body = curl(url, ref)
        try:
            return json.loads(body)
        except Exception:
            last = body; time.sleep(1.5)
    raise RuntimeError("bad json: " + last[:120])
# nav → wbi keys
nav=get_json("https://api.bilibili.com/x/web-interface/nav","https://www.bilibili.com/")
wbi=nav["data"]["wbi_img"]; img=wbi["img_url"].rsplit("/",1)[-1].split(".")[0]; sub=wbi["sub_url"].rsplit("/",1)[-1].split(".")[0]
tab=[46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52]
mk="".join((img+sub)[i] for i in tab)[:32]
def sign(params):
    wts=int(time.time())
    f={k:re.sub(r"[!'()*]","",str(v)) for k,v in params.items()}
    q="&".join(k+"="+f[k] for k in sorted(f))+"&wts="+str(wts)
    return q+"&w_rid="+hashlib.md5((q+mk).encode()).hexdigest()
# live flv
q=sign({"room_id":room,"protocol":"0,1","format":"0,1,2","codec":"0,1","qn":"10000","platform":"web","ptype":"8"})
j=get_json("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?"+q)
if not (j.get("data") or {}).get("playurl_info"):
    sys.exit("FAIL: 房间 %s 当前未开播（live_status=%s）。换一个在线房间重试: ./tools/verify-proxy.sh <room>" % (room, (j.get("data") or {}).get("live_status")))
live=""
for s in j["data"]["playurl_info"]["playurl"]["stream"]:
    if s.get("protocol_name")!="http_stream": continue
    for fm in s["format"]:
        if fm.get("format_name")!="flv": continue
        for c in fm["codec"]:
            if c.get("codec_name")=="avc" and c["url_info"]:
                ui=c["url_info"][0]; live=ui["host"]+c["base_url"]+ui.get("extra","")
# video mp4 (durl)
v=get_json("https://api.bilibili.com/x/web-interface/view?bvid="+bv,"https://www.bilibili.com/")
cid=v["data"]["cid"]
p=get_json("https://api.bilibili.com/x/player/playurl?bvid="+bv+"&cid="+str(cid)+"&qn=64&fnval=1","https://www.bilibili.com/")
video=p["data"]["durl"][0]["url"]
import shlex
open(os.path.join(tmp,"urls.env"),"w").write("LIVE_URL="+shlex.quote(live)+"\nVIDEO_URL="+shlex.quote(video)+"\n")
print("live_len",len(live),"video_len",len(video))
PY
. "$TMP/urls.env" 2>/dev/null || { echo "FAIL: 上游直链解析失败（见上方原因）"; exit 2; }
[ -n "$LIVE_URL" ] || { echo "FAIL: 直播直链为空"; exit 2; }
[ -n "$VIDEO_URL" ] || { echo "FAIL: 点播直链为空"; exit 2; }

echo "== 2) 启动代理（gradle :core:runProxy，端口 ${PORT}）=="
cd "$ROOT"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/gradle-8.10.2/bin/gradle :core:runProxy --args="--port $PORT" --no-daemon --console=plain > "$TMP/proxy.log" 2>&1 &
PROXY_PID=$!
for i in $(seq 1 90); do grep -q PROXY_READY "$TMP/proxy.log" && break; sleep 1; done
grep -q PROXY_READY "$TMP/proxy.log" || { echo "FAIL: 代理未就绪"; tail -20 "$TMP/proxy.log"; exit 3; }

LIVE_PROXY="http://127.0.0.1:$PORT/live?url=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$LIVE_URL")"
VIDEO_PROXY="http://127.0.0.1:$PORT/video?url=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$VIDEO_URL")"

echo "== 3) /live ffprobe =="
ffprobe -v error -show_streams -of json "$LIVE_PROXY" > "$TMP/live.json" 2>"$TMP/live.err" && echo "PASS live_ffprobe" || { echo "FAIL live_ffprobe"; head -5 "$TMP/live.err"; }
python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(\"live_streams\",len(d.get(\"streams\",[])))" "$TMP/live.json" 2>/dev/null || true

echo "== 4) /video Range 0-1023 期望 206 =="
HTTP_CODE=$(curl -s -o "$TMP/part.bin" -D "$TMP/video.hdr" -H "Range: bytes=0-1023" -w "%{http_code}" "$VIDEO_PROXY")
grep -i "content-range" "$TMP/video.hdr" || true
BYTES=$(wc -c < "$TMP/part.bin" | tr -d " ")
if [ "$HTTP_CODE" = "206" ] && [ "$BYTES" = "1024" ]; then echo "PASS video_range status=206 bytes=1024"; else echo "FAIL video_range status=$HTTP_CODE bytes=$BYTES"; fi

echo "== 5) 连续解码（-t 8，-f null -，不落盘）=="
ffmpeg -v error -t 8 -i "$LIVE_PROXY" -f null - 2>"$TMP/ff_live.err" && echo "PASS live_decode_8s" || { echo "FAIL live_decode_8s"; head -5 "$TMP/ff_live.err"; }
ffmpeg -v error -t 8 -i "$VIDEO_PROXY" -f null - 2>"$TMP/ff_video.err" && echo "PASS video_decode_8s" || { echo "FAIL video_decode_8s"; head -5 "$TMP/ff_video.err"; }

echo "== 6) 磁盘红线：无新增大文件（>1MB）=="
BIG=$(git status --short | awk '{print $2}' | while read -r f; do [ -f "$f" ] && s=$(wc -c < "$f") && [ "$s" -gt 1048576 ] && echo "$f $s"; done)
[ -z "$BIG" ] && echo "PASS disk_clean" || { echo "FAIL disk_big_files"; echo "$BIG"; }
echo "== done =="
