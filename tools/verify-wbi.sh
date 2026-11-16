#!/bin/bash
# P2-1 真请求验收：nav 取 wbi keys → 签名 → getRoomPlayInfo 返回 code==0（不是 -352/-403）
# 需要 curl + python3；不落盘流媒体，只取 JSON。
set -u
ROOM="${1:-5017134}"
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

curl -sS --max-time 20 -A "$UA" -c "$TMP/cookies" 'https://api.bilibili.com/x/web-interface/nav' -o "$TMP/nav.json" || { echo "NAV_FETCH_FAILED"; exit 2; }
python3 - "$TMP" "$ROOM" <<'PY'
import sys, json, hashlib, re, subprocess, os, time
tmp, room = sys.argv[1], sys.argv[2]
nav = json.load(open(os.path.join(tmp, "nav.json"), encoding="utf-8"))
if nav.get("code") not in (0, -101):
    print("NAV_CODE", nav.get("code")); sys.exit(3)
wbi = nav["data"]["wbi_img"]
img = wbi["img_url"].rsplit("/", 1)[-1].split(".")[0]
sub = wbi["sub_url"].rsplit("/", 1)[-1].split(".")[0]
tab=[46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52]
mk = "".join((img+sub)[i] for i in tab)[:32]
wts = int(time.time())
params = {"room_id": room, "protocol": "0,1", "format": "0,1,2", "codec": "0,1", "qn": "10000", "platform": "web", "ptype": "8"}
filtered = {k: re.sub(r"[!'()*]", "", str(v)) for k, v in params.items()}
q = "&".join(k + "=" + filtered[k] for k in sorted(filtered)) + "&wts=" + str(wts)
rid = hashlib.md5((q + mk).encode()).hexdigest()
url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?" + q + "&w_rid=" + rid
ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
out = subprocess.run(["curl","-sS","--max-time","20","-A",ua,"-b",os.path.join(tmp,"cookies"),"-e","https://live.bilibili.com/","-w","\nHTTP=%{http_code}",url], capture_output=True, text=True).stdout
body, _, http = out.rpartition("\nHTTP=")
try:
    j = json.loads(body)
    print("HTTP", http, "code", j.get("code"), "message", j.get("message"))
    print("WBI_OK" if j.get("code") == 0 else "WBI_FAIL")
except Exception:
    print("NON_JSON", body[:200])
PY
