#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B站直播 → 电视 轮播台（树莓派单进程版）

做什么：
  1. 每 10 秒轮询若干 UP 主直播间是否在播
  2. 在「正在直播」的房间里，每 SWITCH_SECONDS 秒轮换一次
  3. 轮换时通过 adb 把播放地址递给电视上的 mpv-android
  4. 电视上的播放器退出/异常 → 自动重新拉起
  5. 提供本地 HTTP 代理，替播放器补上 B站 CDN 必需的 Referer + UA

用法：
  python3 station.py                    # 前台跑
  python3 station.py --dry              # 只打印决策，不控制电视
  python3 station.py --print-config     # 打印当前生效的配置（含自动探测结果）
  python3 station.py --set-source live  # 切换模式并写回 config.json（mode.sh 用）

参数都在同目录的 config.json；没有该文件时用脚本里的 DEFAULTS。

Termux / 旧手机迁移版改动（2026-11）：
  - mpv_running() 改用 pidof，兼容 Android 8+ 上 `ps` 默认只显示 shell 进程的变化
  - tv_play() 前先发 KEYCODE_WAKEUP，把熄屏的电视叫醒再派台
  - main() 启动时先 adb start-server，避免 Termux 里 adb server 没起来
  - 代理端口绑定失败时打印明确日志，而不是直接抛异常退出
"""
import hashlib
import json
import os
import random
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# ---------------- 配置 ----------------
# 所有可调参数都在同目录的 config.json 里；没有该文件时用下面的默认值。
# 环境变量 TVSTATION_CONFIG 可以指定别的配置文件路径（多实例/测试用）。
HERE = os.path.dirname(os.path.abspath(__file__)) or "."
CONFIG_PATH = os.environ.get("TVSTATION_CONFIG") or os.path.join(HERE, "config.json")

DEFAULTS = {
    "source": "live",              # live=直播间轮播   video=列表视频轮播
    "rooms": [5017134, 5064126, 4374913],
    "list_mid": 23191782,          # 列表所属 UP 主
    "list_id": 1424266,            # 视频列表 id（space.bilibili.com/<mid>/lists/<id>）
    "pick_count": 5,               # 每轮随机挑几个视频
    "switch_seconds": 30,          # 每台唱多久
    "status_seconds": 10,          # 多久查一次开播状态
    "port": 8099,                  # 补头代理端口
    "tv": "192.168.10.139:5555",   # 电视 adb 地址，写成 ip:5555
    "public_host": "",             # 电视回连用的本机 IP，留空=自动探测
    "mpv_pkg": "is.xyz.mpv",       # 电视上播放器的包名
    "mpv_activity": "is.xyz.mpv/.MPVActivity",
}


def _local_ip():
    """探测本机局域网 IP。UDP connect 不发包，只让内核选出局域网出口网卡。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        s.close()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                return ip
    except OSError:
        pass
    return "127.0.0.1"


def load_config(path=CONFIG_PATH):
    cfg = dict(DEFAULTS)
    cfg["_path"] = None
    if os.path.isfile(path):
        with open(path, encoding="utf-8-sig") as fh:
            user = json.load(fh)
        if not isinstance(user, dict):
            raise SystemExit("config.json 顶层必须是一个 JSON 对象")
        cfg.update(user)
        cfg["_path"] = path
    if not str(cfg.get("public_host") or "").strip():
        cfg["public_host"] = _local_ip()
    cfg["source"] = str(cfg.get("source") or "live").strip().lower()
    if cfg["source"] not in ("live", "video", "single"):
        raise SystemExit("config.json 里 source 只能是 live / video / single")
    cfg["rooms"] = [int(r) for r in (cfg.get("rooms") or []) if str(r).strip()]
    if not cfg["rooms"]:
        raise SystemExit("config.json 里 rooms 不能为空")
    ip, _, port = str(cfg.get("tv") or "").partition(":")
    if not ip:
        raise SystemExit("config.json 里 tv 必须写成 电视IP:5555")
    cfg["tv"] = "%s:%s" % (ip, port or "5555")
    return cfg


CFG = load_config()
SOURCE = CFG["source"]                       # "live"=直播间轮播   "video"=列表视频轮播
ROOMS = CFG["rooms"]
LIST_MID = int(CFG["list_mid"])              # 列表所属 UP 主
LIST_ID = int(CFG["list_id"])                # 视频列表 id
PICK_COUNT = int(CFG["pick_count"])          # 每轮随机挑几个视频
SWITCH_SECONDS = int(CFG["switch_seconds"])  # 每台唱多久
STATUS_SECONDS = int(CFG["status_seconds"])  # 多久查一次开播状态
PORT = int(CFG["port"])
TV = CFG["tv"]
PUBLIC_HOST = CFG["public_host"]
MPV_PKG = str(CFG["mpv_pkg"])
MPV_ACT = str(CFG["mpv_activity"])

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
REFERER = "https://www.bilibili.com/"
DRY = "--dry" in sys.argv

_url_cache = {}
_CTX_VERIFY = ssl.create_default_context()
_CTX_LAX = ssl._create_unverified_context()      # B站 CDN 节点证书常与域名不匹配，做兜底

# 同一份点播文件在多个镜像上路径完全相同，只是服务端域名不一样。
# 实测：qn=80 的直链有时给到某个会 404 的节点，换成下面这些域名即可 200。
CDN_MIRRORS = [
    "upos-sz-mirrorhw.bilivideo.com",
    "upos-sz-mirrorcos.bilivideo.com",
    "upos-sz-mirrorali.bilivideo.com",
    "upos-sz-mirroraliov.bilivideo.com",
    "upos-sz-mirror08c.bilivideo.com",
]


def log(msg):
    print(time.strftime("%m-%d %H:%M:%S ") + msg, flush=True)


# ---------------- B站接口 ----------------
_buvid = {"busy": False, "cookie": ""}


def _cookie():
    """取一个匿名 buvid，降低被风控 412 的概率"""
    if _buvid["cookie"] or _buvid["busy"]:
        return _buvid["cookie"]
    _buvid["busy"] = True
    try:
        req = urllib.request.Request(
            "https://api.bilibili.com/x/frontend/finger/spi",
            headers={"User-Agent": UA, "Referer": REFERER})
        with urllib.request.urlopen(req, timeout=10, context=_CTX_VERIFY) as r:
            d = json.loads(r.read().decode("utf-8"))
        _buvid["cookie"] = "buvid3=%s; buvid4=%s" % (d["data"]["b_3"], d["data"]["b_4"])
    except Exception:                                          # noqa: BLE001
        pass
    finally:
        _buvid["busy"] = False
    return _buvid["cookie"]


def _http_json(url, referer="https://live.bilibili.com/"):
    h = {"User-Agent": UA, "Referer": referer}
    ck = _cookie()
    if ck:
        h["Cookie"] = ck
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=15, context=_CTX_VERIFY) as r:
        return json.loads(r.read().decode("utf-8"))


def room_info(room):
    try:
        d = _http_json("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=%d" % room)["data"]
        return {"live": d.get("live_status") == 1, "title": d.get("title") or "",
                "online": d.get("online") or 0}
    except Exception as exc:                                  # noqa: BLE001
        return {"live": False, "title": "<查询失败:%s>" % str(exc)[:40], "online": 0}


def resolve_flv(room):
    now = time.time()
    hit = _url_cache.get(room)
    if hit and hit[0] > now:
        return hit[1]
    url = ("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
           "?room_id=%d&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8" % room)
    j = _http_json(url)
    info = j["data"]["playurl_info"]["playurl"]
    fresh = None
    for st in info["stream"]:
        for fmt in st["format"]:
            for cd in fmt["codec"]:
                if (st["protocol_name"], fmt["format_name"], cd["codec_name"]) != \
                        ("http_stream", "flv", "avc"):
                    continue
                ui = cd["url_info"][0]
                fresh = ui["host"] + cd["base_url"] + ui["extra"]
    if not fresh:
        raise RuntimeError("no flv stream")
    _url_cache[room] = (now + 600, fresh)
    log("[proxy] 房间 %d 直链主机 %s" % (room, urllib.parse.urlparse(fresh).netloc))
    return fresh


# ---------------- B站视频（列表轮播） ----------------
MIXIN_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
             33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
             26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
             20, 34, 44, 52]
_wbi = {"t": 0, "img": "", "sub": ""}
_videos = {"t": 0, "items": []}
_cid_cache = {}
_vurl_cache = {}


def _wbi_keys():
    if _wbi["t"] > time.time():
        return _wbi["img"], _wbi["sub"]
    d = _http_json("https://api.bilibili.com/x/web-interface/nav", referer=REFERER)
    wbi = d["data"]["wbi_img"]
    img = wbi["img_url"].rsplit("/", 1)[-1].split(".")[0]
    sub = wbi["sub_url"].rsplit("/", 1)[-1].split(".")[0]
    _wbi.update(t=time.time() + 6 * 3600, img=img, sub=sub)
    return img, sub


def _signed(path, params):
    img, sub = _wbi_keys()
    mixin = "".join((img + sub)[i] for i in MIXIN_TAB)[:32]
    p = dict(params)
    p["wts"] = int(time.time())
    p = {k: "".join(c for c in str(v) if c not in "!'()*") for k, v in sorted(p.items())}
    q = urllib.parse.urlencode(p)
    return path + "?" + q + "&w_rid=" + hashlib.md5((q + mixin).encode()).hexdigest()


def series_videos():
    """取列表里的全部视频（bvid / 标题 / 时长）"""
    if _videos["t"] > time.time():
        return _videos["items"]
    url = _signed("https://api.bilibili.com/x/series/archives",
                  {"mid": LIST_MID, "series_id": LIST_ID, "only_normal": "true",
                   "sort": "desc", "pn": 1, "ps": 50})
    j = _http_json(url, referer=REFERER)
    items = [{"bvid": a["bvid"], "title": a.get("title") or "",
              "duration": a.get("duration") or 0}
             for a in (j.get("data") or {}).get("archives") or []]
    _videos.update(t=time.time() + 3600, items=items)
    log("[列表] 取到 %d 个视频" % len(items))
    return items


def video_cid(bvid):
    if bvid in _cid_cache:
        return _cid_cache[bvid]
    j = _http_json("https://api.bilibili.com/x/player/pagelist?bvid=%s" % bvid,
                   referer=REFERER)
    cid = j["data"][0]["cid"]
    _cid_cache[bvid] = cid
    return cid


def resolve_video(bvid):
    """未登录最高 1080P：html5 接口给单文件 mp4，moov 在前，支持 Range"""
    now = time.time()
    hit = _vurl_cache.get(bvid)
    if hit and hit[0] > now:
        return hit[1]
    cid = video_cid(bvid)
    j = _http_json("https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=80"
                   "&fnval=0&fnver=0&fourk=0&platform=html5&high_quality=1" % (bvid, cid),
                   referer=REFERER)
    durl = (j.get("data") or {}).get("durl") or []
    if not durl:
        raise RuntimeError("no durl for %s" % bvid)
    fresh = durl[0]["url"]
    _vurl_cache[bvid] = (now + 1800, fresh)
    log("[proxy] bvid=%s 画质=%s 大小=%.0fMB 主机=%s" % (
        bvid, (j.get("data") or {}).get("quality"), durl[0].get("size", 0) / 1048576.0,
        urllib.parse.urlparse(fresh).netloc))
    return fresh


def _open_upstream(real, headers, allow_mirror):
    """打开上游。点播直链若在某个节点 404/403/5xx，就换同名镜像域名再试。"""
    cands = [real]
    if allow_mirror:
        p = urllib.parse.urlparse(real)
        for m in CDN_MIRRORS:
            if m != p.netloc:
                cands.append(urllib.parse.urlunparse(p._replace(netloc=m)))
    last = "未知"
    for c in cands:
        req = urllib.request.Request(c, headers=headers)
        for ctx in (_CTX_VERIFY, _CTX_LAX):
            try:
                resp = urllib.request.urlopen(req, timeout=20, context=ctx)
            except urllib.error.HTTPError as exc:
                last = "HTTP %s" % exc.code
                if exc.code in (403, 404, 502, 503):
                    break                     # 换镜像
                continue
            except (ssl.SSLError, urllib.error.URLError, OSError) as exc:
                last = str(exc)[:60]
                continue
            if c != real:
                log("[proxy] 原节点不可用(%s)，换镜像 %s" % (last, urllib.parse.urlparse(c).netloc))
            return resp
    raise RuntimeError("上游不可用：%s" % last)


# ---------------- 本地代理（补请求头） ----------------
def _serve_client(conn, addr):
    try:
        conn.settimeout(30)
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = conn.recv(4096)
            if not chunk:
                return
            head += chunk
            if len(head) > 65536:
                return
        line = head.split(b"\r\n", 1)[0].decode("latin1")
        parts = line.split(" ")
        if len(parts) < 2:
            return
        method = parts[0]
        u = urllib.parse.urlparse(parts[1])
        qs = urllib.parse.parse_qs(u.query)

        if u.path == "/api/status":
            body = json.dumps(STATE["snapshot"], ensure_ascii=False).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                         b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
            return

        rng = None
        for hl in head.split(b"\r\n")[1:]:
            if hl.lower().startswith(b"range:"):
                rng = hl.split(b":", 1)[1].strip().decode("latin1")
                break

        if u.path in ("/flv", "/live.flv"):
            room = int(qs.get("room", ["0"])[0])
            if room not in ROOMS:
                conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
                return
            try:
                real = resolve_flv(room)
            except Exception as exc:                          # noqa: BLE001
                log("[proxy] 解析失败 room=%s %s" % (room, exc))
                conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                return
            tag, ctype_hint = "room=%s" % room, "video/x-flv"
            rng = None                                        # 直播流不支持 Range
        elif u.path == "/video":
            bvid = (qs.get("bvid") or [""])[0]
            if not bvid.startswith("BV"):
                conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
                return
            try:
                real = resolve_video(bvid)
            except Exception as exc:                          # noqa: BLE001
                log("[proxy] 视频解析失败 bvid=%s %s" % (bvid, exc))
                conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                return
            tag, ctype_hint = "bvid=%s" % bvid, "video/mp4"
        else:
            conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
            return

        h = {"User-Agent": UA, "Referer": REFERER}
        if rng:
            h["Range"] = rng
        try:
            upstream = _open_upstream(real, h, allow_mirror=(u.path == "/video"))
        except Exception as exc:                              # noqa: BLE001
            log("[proxy] %s 上游失败 %s" % (tag, exc))
            conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
            return

        status = getattr(upstream, "status", 200)
        ctype = upstream.headers.get("Content-Type", ctype_hint)
        hdr = ["HTTP/1.1 %d %s" % (status, "Partial Content" if status == 206 else "OK"),
               "Content-Type: %s" % ctype,
               "Cache-Control: no-cache",
               "Connection: close"]
        for name in ("Content-Length", "Content-Range", "Accept-Ranges"):
            val = upstream.headers.get(name)
            if val:
                hdr.append("%s: %s" % (name, val))
        conn.sendall(("\r\n".join(hdr) + "\r\n\r\n").encode())

        if method == "HEAD":
            return
        total = 0
        t0 = time.time()
        while True:
            buf = upstream.read(65536)
            if not buf:
                break
            conn.sendall(buf)
            total += len(buf)
        dur = time.time() - t0
        log("[proxy] %s 结束 %d KB / %.0fs (来自 %s)" % (tag, total // 1024, dur, addr[0]))
    except Exception as exc:                                  # noqa: BLE001
        log("[proxy] 连接异常 %s %s" % (addr[0], str(exc)[:80]))
    finally:
        try:
            conn.close()
        except Exception:                                     # noqa: BLE001
            pass


def start_proxy():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("0.0.0.0", PORT))
    except OSError as exc:
        log("[proxy] 端口 %d 绑定失败：%s（改 config.json 的 port 再重启）" % (PORT, exc))
        raise
    srv.listen(16)
    log("代理已启动 0.0.0.0:%d" % PORT)

    def loop():
        while True:
            c, a = srv.accept()
            threading.Thread(target=_serve_client, args=(c, a), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()


# ---------------- 电视控制 ----------------
def adb(*args, timeout=25):
    try:
        p = subprocess.run(["adb", "-s", TV] + list(args), timeout=timeout,
                           capture_output=True, text=True)
        return (p.stdout or "") + (p.stderr or "")
    except Exception as exc:                                  # noqa: BLE001
        return "<adb error %s>" % str(exc)[:60]


def adb_connect():
    try:
        subprocess.run(["adb", "connect", TV], timeout=20, capture_output=True)
    except Exception:                                         # noqa: BLE001
        pass


def adb_ensure():
    """确认设备在线，掉线就重连"""
    out = adb("get-state")
    if "device" not in out:
        adb_connect()
        time.sleep(1)
        out = adb("get-state")
    return "device" in out


def tv_state():
    """读 mpv 的播放状态：3=播放中  2=暂停  其它=未知"""
    out = adb("shell", "dumpsys", "media_session", timeout=20)
    for line in out.splitlines():
        k = line.find("{state=")
        if k < 0:
            continue
        num = ""
        for ch in line[k + 7:]:
            if ch.isdigit():
                num += ch
            else:
                break
        if num:
            return int(num)
    return -1


def tv_resume(wait=2.5, tries=3):
    """mpv-android 每次 onNewIntent 换台后会停在暂停态（画面定住、没声音），
    这里确认不是「播放中」就补发播放键。只读状态再决定，避免把正在播的按停。"""
    time.sleep(wait)
    for i in range(tries):
        st = tv_state()
        if st == 3:
            log("[电视] 播放中")
            return True
        log("[电视] 状态=%s，补发播放键（第%d次）" % (st, i + 1))
        adb("shell", "input", "keyevent", "85")
        time.sleep(1.5)
    st = tv_state()
    log("[电视] 最终状态=%s" % st)
    return st == 3


def tv_wake():
    """把电视从熄屏/屏保叫醒，再让 mpv 接管。

    224 = KEYCODE_WAKEUP。电视如果是完全断电/深度待机，adb 根本连不上，
    这段也唤不醒；那种情况要靠 HDMI-CEC、红外或电视自己的定时开机。
    """
    if DRY:
        return
    adb("shell", "input", "keyevent", "224")   # KEYCODE_WAKEUP
    time.sleep(0.6)
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(0.2)
    # 不额外发 HOME：am start 本身会把 mpv 带到前台，也能退出屏保；
    # 多发 HOME 会让已经在播的电视每次换台都闪一下桌面。


def tv_play(url, label=""):
    if DRY:
        log("[dry] 会播 %s" % url)
        return
    tv_wake()
    out = adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
              "-n", MPV_ACT, "-d", "'%s'" % url)
    ok = "Error" not in out
    log("[电视] %s %s" % (label or url, "OK" if ok else out.strip()[:80]))
    if ok:
        tv_resume()


def tv_stop():
    if DRY:
        return
    adb("shell", "am", "force-stop", MPV_PKG)


def mpv_running():
    """电视上的 mpv 还在不在。

    Android 8+ 的 `ps` 默认只列出当前 shell 用户的进程，看不到 u0_a* 的 mpv，
    原来的 `ps | grep` 会误报“播放器退出”并频繁重开。这里先用 pidof，
    再退回 `ps -A`，最后才退回老式 `ps`。
    """
    out = adb("shell", "pidof", MPV_PKG)
    pids = out.replace("\r", " ").split()
    if pids and all(p.isdigit() for p in pids):
        return True
    out = adb("shell", "ps", "-A")
    if MPV_PKG in out:
        return True
    if "unknown option" in out.lower() or "invalid option" in out.lower():
        out = adb("shell", "ps", "|", "grep", MPV_PKG)
        return MPV_PKG in out
    return False


# ---------------- 主循环 ----------------
STATE = {"snapshot": [], "current": None}


def _poller(states, lock):
    """后台线程：只负责查开播状态，不跟切台抢时间"""
    last = {}
    while True:
        for r in ROOMS:
            st = room_info(r)
            with lock:
                states[r] = st
        with lock:
            STATE["snapshot"] = [{"room": r, **states[r]} for r in ROOMS]
            snap = list(STATE["snapshot"])
        for item in snap:
            r = item["room"]
            sig = (item["live"], item["title"])
            if last.get(r) != sig:
                last[r] = sig
                log("[状态] %d %s %s (在线%s)" % (
                    r, "直播中" if item["live"] else "未开播",
                    item["title"][:30], item["online"]))
        time.sleep(STATUS_SECONDS)


def run_live():
    """模式一：在正在直播的房间里轮播"""
    states = {r: {"live": False, "title": "", "online": 0} for r in ROOMS}
    lock = threading.Lock()
    threading.Thread(target=_poller, args=(states, lock), daemon=True).start()

    current = None
    next_switch = 0.0
    switched_at = 0.0
    last_mpv_check = 0.0

    while True:
        now = time.time()
        with lock:
            live_rooms = [r for r in ROOMS if states[r]["live"]]
            titles = {r: states[r]["title"] for r in ROOMS}

        if not live_rooms:
            if current is not None:
                log("[调度] 全部下播，停止播放")
                tv_stop()
                current = None
                STATE["current"] = None
            time.sleep(1)
            continue

        reason = None
        if current is None:
            reason = "开始播放"
        elif current not in live_rooms:
            reason = "当前台下播"
        elif now >= next_switch:
            reason = "到点轮换"
        elif now - switched_at > 10 and now - last_mpv_check >= 5:
            last_mpv_check = now
            if not mpv_running():
                reason = "播放器意外退出"

        if reason:
            if current in live_rooms and reason in ("到点轮换", "当前台下播"):
                idx = (live_rooms.index(current) + 1) % len(live_rooms)
            elif reason == "播放器意外退出" and current in live_rooms:
                idx = live_rooms.index(current)
            else:
                idx = 0
            target = live_rooms[idx]
            log("[调度] %s → 房间 %d（%s）" % (reason, target, titles.get(target, "")[:24]))
            if not adb_ensure():
                log("[警告] 电视 adb 不在线，重连中")
            tv_play("http://%s:%d/flv?room=%d" % (PUBLIC_HOST, PORT, target),
                    "room=%d" % target)
            current = target
            switched_at = time.time()
            next_switch = switched_at + SWITCH_SECONDS
            last_mpv_check = switched_at
            STATE["current"] = current
            time.sleep(2)
            continue

        time.sleep(0.5)


def run_single():
    """单直播间模式：只盯 rooms[0]，开播就播，下播就等，不按时间轮换。"""
    room = ROOMS[0]
    if len(ROOMS) > 1:
        log("[单台] rooms 里有 %d 个房间，只使用第一个 %d" % (len(ROOMS), room))

    playing = False
    lasts_play = 0.0
    last_check = 0.0
    misses = 0
    last_live = None

    while True:
        now = time.time()
        st = room_info(room)
        STATE["snapshot"] = [{"room": room, **st}]

        if st["live"] != last_live:
            last_live = st["live"]
            log("[单台] 房间 %d %s（%s）" % (
                room, "开播" if st["live"] else "未开播", st["title"][:30]))

        if st["live"]:
            misses = 0
            if not playing:
                log("[单台] 开始播放 → 房间 %d（%s）" % (room, st["title"][:24]))
                if not adb_ensure():
                    log("[警告] 电视 adb 不在线，重连中")
                tv_play("http://%s:%d/flv?room=%d" % (PUBLIC_HOST, PORT, room),
                        "room=%d" % room)
                playing = True
                lasts_play = time.time()
                STATE["current"] = room
            elif now - lasts_play > 10 and now - last_check >= 5:
                last_check = now
                if not mpv_running():
                    log("[单台] 播放器意外退出，重拉房间 %d" % room)
                    if not adb_ensure():
                        log("[警告] 电视 adb 不在线，重连中")
                    tv_play("http://%s:%d/flv?room=%d" % (PUBLIC_HOST, PORT, room),
                            "room=%d" % room)
                    lasts_play = now
        else:
            # 接口偶尔失败时 room_info 也会返回 live=False；
            # 连续 3 次（约 30 秒）才真正停，避免网络抖动一下就把台关了。
            if playing:
                misses += 1
                if misses >= 3:
                    log("[单台] 已下播，停止播放")
                    tv_stop()
                    playing = False
                    STATE["current"] = None
            else:
                misses = 0

        time.sleep(STATUS_SECONDS)


def run_video():
    """模式二：从列表里随机挑 PICK_COUNT 个视频轮播，一轮走完重新随机"""
    videos = []
    for _ in range(6):
        try:
            videos = series_videos()
            if videos:
                break
        except Exception as exc:                              # noqa: BLE001
            log("[列表] 拉取失败：%s" % str(exc)[:80])
        time.sleep(5)
    if not videos:
        log("[列表] 没有可用视频，退出")
        return

    queue, pos = [], 0
    current = None
    switched_at = 0.0
    last_mpv_check = 0.0

    while True:
        now = time.time()

        reason = None
        if current is None:
            reason = "开始播放"
        elif now >= switched_at + SWITCH_SECONDS:
            reason = "到点轮换"
        elif now - switched_at > 10 and now - last_mpv_check >= 5:
            last_mpv_check = now
            if not mpv_running():
                reason = "播放器退出，跳过"

        if reason:
            if current is None:
                if not queue:
                    queue = random.sample(videos, min(PICK_COUNT, len(videos)))
                    pos = 0
                    log("[调度] 随机挑选 %d 个：%s" % (
                        len(queue), "、".join(v["bvid"] for v in queue)))
            else:
                pos += 1
                if pos >= len(queue):
                    queue = random.sample(videos, min(PICK_COUNT, len(videos)))
                    pos = 0
                    log("[调度] 一轮播完，重新随机 %d 个：%s" % (
                        len(queue), "、".join(v["bvid"] for v in queue)))

            item = queue[pos]
            STATE["snapshot"] = [{"bvid": v["bvid"], "title": v["title"][:40]}
                                 for v in queue]
            log("[调度] %s → %s（%s，%d 秒）" % (
                reason, item["bvid"], item["title"][:26], item["duration"]))
            if not adb_ensure():
                log("[警告] 电视 adb 不在线，重连中")
            switched_at = time.time()          # 从「开始派台」计时，画面停留才接近 SWITCH_SECONDS
            tv_play("http://%s:%d/video?bvid=%s" % (PUBLIC_HOST, PORT, item["bvid"]),
                    item["bvid"])
            current = item
            last_mpv_check = switched_at
            STATE["current"] = item["bvid"]
            time.sleep(1)
            continue

        time.sleep(0.5)


def print_config():
    print(json.dumps(CFG, ensure_ascii=False, indent=2, sort_keys=True))


def set_source(mode):
    """切换模式并写回 config.json（mode.sh / 控制服务调用）"""
    if mode not in ("live", "video", "single"):
        raise SystemExit("用法: python3 station.py --set-source live|video|single")
    path = CONFIG_PATH
    data = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8-sig") as fh:
            data = json.load(fh)
    data["source"] = mode
    if not str(data.get("public_host") or "").strip():
        data["public_host"] = _local_ip()
    data.setdefault("tv", TV)
    data.setdefault("rooms", ROOMS)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    print("已切换 %s 模式：%s" % (mode, path))


def main():
    start_proxy()
    try:
        # Termux 里 adb server 可能还没起；先显式拉起来，失败也不致命。
        subprocess.run(["adb", "start-server"], timeout=20,
                       capture_output=True, text=True)
    except Exception as exc:                                  # noqa: BLE001
        log("[adb] start-server 失败：%s" % str(exc)[:60])
    adb_connect()
    log("启动：模式=%s 配置文件=%s" % (SOURCE, CFG["_path"] or "（内置默认值）"))
    log("参数：电视=%s 回连地址=%s 房间=%s" % (TV, PUBLIC_HOST, ROOMS))
    if SOURCE == "video":
        run_video()
    elif SOURCE == "single":
        run_single()
    else:
        run_live()


if __name__ == "__main__":
    if "--print-config" in sys.argv:
        print_config()
        sys.exit(0)
    if "--set-source" in sys.argv:
        i = sys.argv.index("--set-source")
        set_source(sys.argv[i + 1] if len(sys.argv) > i + 1 else "")
        sys.exit(0)
    try:
        main()
    except KeyboardInterrupt:
        log("退出")
