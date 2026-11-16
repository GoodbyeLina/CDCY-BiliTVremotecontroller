#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B站轮播台 · 手机控制服务（Termux）

给手机 App / 本地网页用的控制后端，只监听 127.0.0.1:8098。

功能：
  - 搜 UP 名字（wbi 签名 + buvid cookie）
  - 解析直播间/合集/视频链接
  - 把选中的直播间写成 single 模式并重启 tvstation
  - 开始/停止/重启/唤醒电视
  - 读取状态和日志

它和 station.py 是两个独立进程：
  tvstation  :8099  代理 + 轮播 + adb
  tvcontrol  :8098  控制 API + 网页
"""

import hashlib
import html
import ipaddress
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
HOME = os.environ.get("HOME") or os.path.expanduser("~")
TVSTATION_HOME = os.environ.get("TVSTATION_HOME") or os.path.join(HOME, "tvstation")
CONFIG_PATH = os.path.join(TVSTATION_HOME, "config.json")
STATION_LOG = os.path.join(TVSTATION_HOME, "station.log")
CONTROL_LOG = os.path.join(TVSTATION_HOME, "control.log")
WEB_INDEX = os.path.join(HERE, "web", "index.html")

PREFIX = os.environ.get("PREFIX") or "/data/data/com.termux/files/usr"
SVDIR = os.environ.get("SVDIR") or os.path.join(PREFIX, "var", "service")
SV = os.path.join(PREFIX, "bin", "sv")
ADB = os.path.join(PREFIX, "bin", "adb")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
REFERER = "https://www.bilibili.com/"
LIVE_REFERER = "https://live.bilibili.com/"

_CTX = ssl.create_default_context()
_LAX = ssl._create_unverified_context()
MIXIN_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
             33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
             26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
             20, 34, 44, 52]

_cookie = {"value": "", "busy": False}
_wbi = {"t": 0.0, "img": "", "sub": ""}


class BiliError(Exception):
    pass


def log(msg):
    print(time.strftime("%m-%d %H:%M:%S ") + msg, flush=True)


def _http_json(url, referer=REFERER, timeout=12):
    headers = {"User-Agent": UA, "Referer": referer}
    ck = _buvid()
    if ck:
        headers["Cookie"] = ck
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_CTX) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise BiliError("B站接口 HTTP %s" % exc.code)
    except Exception as exc:  # noqa: BLE001
        raise BiliError("B站接口请求失败：%s" % str(exc)[:80])


def _buvid():
    """取匿名 buvid，降低被 412 的概率。"""
    if _cookie["value"] or _cookie["busy"]:
        return _cookie["value"]
    _cookie["busy"] = True
    try:
        d = _http_json("https://api.bilibili.com/x/frontend/finger/spi", timeout=8)
        _cookie["value"] = "buvid3=%s; buvid4=%s" % (d["data"]["b_3"], d["data"]["b_4"])
    except Exception:  # noqa: BLE001
        pass
    finally:
        _cookie["busy"] = False
    return _cookie["value"]


def _wbi_keys():
    if _wbi["t"] > time.time():
        return _wbi["img"], _wbi["sub"]
    d = _http_json("https://api.bilibili.com/x/web-interface/nav")
    # 未登录时 code=-101，但 data.wbi_img 仍然存在；不要因为 -101 就放弃。
    wbi = (d.get("data") or {}).get("wbi_img")
    if not wbi:
        raise BiliError("拿不到 wbi 签名参数：%s" % (d.get("message") or d.get("code")))
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


# ---------------- 配置 ----------------

DEFAULTS = {
    "source": "single",
    "rooms": [],
    "list_mid": 0,
    "list_id": 0,
    "pick_count": 5,
    "switch_seconds": 30,
    "status_seconds": 10,
    "port": 8099,
    "tv": "",
    "public_host": "",
    "mpv_pkg": "is.xyz.mpv",
    "mpv_activity": "is.xyz.mpv/.MPVActivity",
    "video_bvids": [],
}


def load_config():
    cfg = dict(DEFAULTS)
    if os.path.isfile(CONFIG_PATH):
        with open(CONFIG_PATH, encoding="utf-8-sig") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            cfg.update(data)
    return cfg


def save_config(cfg):
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, CONFIG_PATH)


# ---------------- B站：搜索/房间 ----------------

def _strip_tags(s):
    return html.unescape(re.sub(r"<[^>]+>", "", str(s or ""))).strip()


def search_up(keyword, limit=8):
    """按名字搜 UP 主，返回候选列表。"""
    params = {"search_type": "bili_user", "keyword": keyword, "page": 1}
    url = _signed("https://api.bilibili.com/x/web-interface/wbi/search/type", params)
    j = _http_json(url, referer="https://search.bilibili.com/")
    if j.get("code") != 0:
        raise BiliError("搜索失败：%s（可能是风控，稍后再试或直接粘贴直播间链接）" %
                        (j.get("message") or j.get("code")))
    result = (j.get("data") or {}).get("result") or []
    items = []
    for item in result[:limit]:
        mid = item.get("mid")
        if not mid:
            continue
        room_id = item.get("room_id") or 0
        try:
            room_id = int(room_id)
        except (TypeError, ValueError):
            room_id = 0
        upic = item.get("upic") or ""
        if upic.startswith("//"):
            upic = "https:" + upic
        items.append({
            "mid": int(mid),
            "uname": _strip_tags(item.get("uname")),
            "fans": int(item.get("fans") or 0),
            "room_id": room_id,
            "upic": upic,
            "sign": _strip_tags(item.get("usign"))[:60],
        })
    return items


def room_info(room_id):
    """查直播间状态。"""
    url = ("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=%d" % int(room_id))
    j = _http_json(url, referer=LIVE_REFERER, timeout=10)
    if j.get("code") != 0:
        raise BiliError("房间 %s 查不到：%s" % (room_id, j.get("message") or j.get("code")))
    d = j.get("data") or {}
    return {
        "room_id": int(d.get("room_id") or room_id),
        "live": d.get("live_status") == 1,
        "title": _strip_tags(d.get("title")),
        "online": int(d.get("online") or 0),
    }


def mid_room(mid):
    """UP 的 mid -> 直播间号（不开播也能拿到）。"""
    url = ("https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=%d" % int(mid))
    j = _http_json(url, referer=LIVE_REFERER, timeout=10)
    if j.get("code") != 0:
        raise BiliError("查 UP %s 的直播间失败：%s" % (mid, j.get("message") or j.get("code")))
    d = j.get("data") or {}
    room_id = int(d.get("roomid") or 0)
    if room_id <= 0:
        raise BiliError("这个 UP（mid=%s）没有开通直播间。" % mid)
    return {
        "room_id": room_id,
        "live": d.get("liveStatus") == 1,
        "title": _strip_tags(d.get("title")),
        "online": int(d.get("online") or 0),
    }


# ---------------- 链接解析 ----------------

def _resolve_short(url):
    """展开 b23.tv 短链。失败就抛 BiliError。"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": REFERER})
        with urllib.request.urlopen(req, timeout=10, context=_CTX) as resp:
            final = resp.geturl()
            body = resp.read(200000).decode("utf-8", "ignore")
        if "bilibili.com" in final and "b23.tv" not in final:
            return final
        m = re.search(r'https?://[^"\'<>\\ ]*bilibili\.com[^"\'<>\\ ]*', body)
        if m:
            return m.group(0).replace("\\/", "/")
    except Exception as exc:  # noqa: BLE001
        raise BiliError("短链展开失败：%s。请在浏览器打开后复制完整链接。" % str(exc)[:60])
    raise BiliError("短链展开失败。请在浏览器打开后复制完整链接。")


def parse_input(text):
    """把用户输入解析成结构化目标。

    返回 dict 或 None：
      {"type":"live","room_id":123}
      {"type":"up","mid":456}
      {"type":"series","mid":456,"list_id":789}
      {"type":"season","mid":456,"season_id":789}
      {"type":"video","bvid":"BV..."}
      {"type":"room","room_id":123}   # 纯数字，按直播间号处理
    """
    text = (text or "").strip()
    if not text:
        return None
    text = text.replace("\u200b", "")

    if "b23.tv" in text:
        m = re.search(r"https?://b23\.tv/\S+", text)
        if m:
            return parse_input(_resolve_short(m.group(0)))

    m = re.search(r"live\.bilibili\.com/(?:blanc/)?(\d+)", text)
    if m:
        return {"type": "live", "room_id": int(m.group(1))}

    m = re.search(r"space\.bilibili\.com/(\d+)/lists/(\d+)", text)
    if m:
        return {"type": "series", "mid": int(m.group(1)), "list_id": int(m.group(2))}

    m = re.search(r"space\.bilibili\.com/(\d+)/channel/collectiondetail\?[^#]*\bsid=(\d+)", text)
    if m:
        return {"type": "season", "mid": int(m.group(1)), "season_id": int(m.group(2))}

    m = re.search(r"(?:bilibili\.com/video/|^)(BV[0-9A-Za-z]{8,})", text)
    if m:
        return {"type": "video", "bvid": m.group(1)}

    m = re.search(r"space\.bilibili\.com/(\d+)", text)
    if m:
        return {"type": "up", "mid": int(m.group(1))}

    if re.fullmatch(r"\d{1,12}", text):
        return {"type": "room", "room_id": int(text)}

    return None


FORMAT_HINT = ("看不懂这个输入。支持：\n"
               "· UP 名字，例如：某某某\n"
               "· 直播间链接，例如：https://live.bilibili.com/5017134\n"
               "· 合集链接，例如：https://space.bilibili.com/23191782/lists/1424266\n"
               "· 视频链接，例如：https://www.bilibili.com/video/BV1xx411c7XX")


# ---------------- 服务控制 ----------------

def _sv_env():
    env = dict(os.environ)
    env["SVDIR"] = SVDIR
    env["PREFIX"] = PREFIX
    env["PATH"] = os.path.join(PREFIX, "bin") + os.pathsep + env.get("PATH", "")
    return env


def sv(*args, timeout=15):
    if not os.path.isfile(SV):
        return 127, "找不到 sv：%s（先 pkg install termux-services）" % SV
    try:
        p = subprocess.run([SV] + list(args), timeout=timeout,
                           capture_output=True, text=True, env=_sv_env())
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as exc:  # noqa: BLE001
        return 1, "执行 sv 失败：%s" % str(exc)[:80]


def service_status(name):
    code, out = sv("status", name, timeout=8)
    out = out.strip()
    if out.startswith("run:"):
        return "run", out
    if out.startswith("down:"):
        return "down", out
    return "unknown", out or ("sv 返回码 %s" % code)


def restart_station():
    code, out = sv("restart", "tvstation", timeout=20)
    if code != 0:
        # 服务可能被 sv down 了，尝试 up
        code2, out2 = sv("up", "tvstation", timeout=20)
        if code2 != 0:
            return False, (out + "\n" + out2).strip()
    time.sleep(1.5)
    state, raw = service_status("tvstation")
    if state != "run":
        return False, "sv 返回成功，但 tvstation 状态=%s：%s" % (state, raw[:200])
    return True, out.strip()


def adb(*args, timeout=15):
    if not os.path.isfile(ADB):
        return 127, "找不到 adb：%s（先 pkg install android-tools）" % ADB
    try:
        p = subprocess.run([ADB] + list(args), timeout=timeout,
                           capture_output=True, text=True, env=_sv_env())
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as exc:  # noqa: BLE001
        return 1, "执行 adb 失败：%s" % str(exc)[:80]


def tv_wake(tv):
    adb("connect", tv, timeout=12)
    _, out1 = adb("-s", tv, "shell", "input", "keyevent", "224", timeout=12)
    _, out2 = adb("-s", tv, "shell", "wm", "dismiss-keyguard", timeout=12)
    return (out1 + out2).strip() or "已发送唤醒键"


# ---------------- 业务动作 ----------------

def do_search(q):
    q = (q or "").strip()
    if not q:
        return {"ok": False, "error": "先输入 UP 名字，或粘贴直播间/合集/视频链接。"}

    parsed = None
    try:
        parsed = parse_input(q)
    except BiliError as exc:
        return {"ok": False, "error": str(exc)}

    if parsed:
        if parsed["type"] == "live":
            try:
                info = room_info(parsed["room_id"])
            except BiliError as exc:
                return {"ok": False, "error": str(exc)}
            return {"ok": True, "kind": "live", "room": info,
                    "message": "找到直播间，点“播放这个直播间”即可。"}

        if parsed["type"] == "room":
            try:
                info = room_info(parsed["room_id"])
            except BiliError as exc:
                return {"ok": False, "error": str(exc)}
            return {"ok": True, "kind": "live", "room": info,
                    "message": "按直播间号找到，点“播放这个直播间”即可。"}

        if parsed["type"] == "up":
            try:
                info = mid_room(parsed["mid"])
            except BiliError as exc:
                return {"ok": False, "error": str(exc)}
            return {"ok": True, "kind": "live", "room": info,
                    "message": "这是 UP 的直播间，点“播放这个直播间”即可。"}

        if parsed["type"] in ("series", "season"):
            return {"ok": False,
                    "error": "这是合集链接。合集视频播放是第二版；第一版先做直播间。"
                             "你可以先粘贴直播间链接，例如 https://live.bilibili.com/5017134。"}

        if parsed["type"] == "video":
            return {"ok": False,
                    "error": "这是单个视频链接。视频播放是第二版；第一版先做直播间。"
                             "你可以先粘贴直播间链接，例如 https://live.bilibili.com/5017134。"}

    # 没被识别成链接 -> 按 UP 名字搜
    try:
        items = search_up(q)
    except BiliError as exc:
        return {"ok": False, "error": str(exc)}

    if not items:
        return {"ok": False,
                "error": "没搜到「%s」。试试完整名字/UID，或直接粘贴直播间链接，例如 "
                         "https://live.bilibili.com/5017134。" % q}

    return {"ok": True, "kind": "candidates", "candidates": items,
            "message": "找到 %d 个结果，请选择正确的 UP。" % len(items)}


def do_play_live(payload):
    room_id = payload.get("room_id")
    mid = payload.get("mid")
    text = payload.get("url") or payload.get("q") or ""

    try:
        if room_id:
            room_id = int(room_id)
        elif mid:
            room_id = mid_room(int(mid))["room_id"]
        elif text:
            parsed = parse_input(text)
            if not parsed:
                return {"ok": False, "error": FORMAT_HINT}
            if parsed["type"] == "live":
                room_id = parsed["room_id"]
            elif parsed["type"] == "room":
                room_id = parsed["room_id"]
            elif parsed["type"] == "up":
                room_id = mid_room(parsed["mid"])["room_id"]
            elif parsed["type"] == "video":
                return {"ok": False, "error": "这是视频链接，不是直播间；视频功能在第二版。"}
            else:
                return {"ok": False, "error": "这是合集链接，不是直播间；请选一个直播间或粘贴直播间链接。"}
        else:
            return {"ok": False, "error": "没有收到直播间号。"}
    except BiliError as exc:
        return {"ok": False, "error": str(exc)}
    except (TypeError, ValueError):
        return {"ok": False, "error": "直播间号必须是数字。"}

    if room_id <= 0:
        return {"ok": False, "error": "直播间号必须大于 0。"}

    # 先查一次，确认房间存在；没开播也允许设置
    try:
        info = room_info(room_id)
    except BiliError as exc:
        return {"ok": False, "error": str(exc)}

    cfg = load_config()
    cfg["source"] = "single"
    cfg["rooms"] = [room_id]
    save_config(cfg)

    ok, out = restart_station()
    if not ok:
        return {"ok": False,
                "error": "配置已写入，但重启 tvstation 失败：%s" % out[:300]}

    if info["live"]:
        msg = "已切换到直播间 %d，电视 3–5 秒后出画面。" % room_id
    else:
        msg = "已设为当前直播间 %d，但当前未开播；开播后会自动播放。" % room_id
    return {"ok": True, "room_id": room_id, "live": info["live"],
            "title": info["title"], "message": msg}


def do_control(payload):
    action = (payload.get("action") or "").strip()
    if action == "start":
        code, out = sv("up", "tvstation", timeout=20)
        if code != 0:
            return {"ok": False, "error": out.strip()[:300]}
        return {"ok": True, "action": action, "message": "已启动 tvstation。"}

    if action == "stop":
        code, out = sv("down", "tvstation", timeout=20)
        if code != 0:
            return {"ok": False, "error": out.strip()[:300]}
        # 顺手把电视上的 mpv 停掉，否则它会停在最后一帧/报错。
        cfg = load_config()
        tv = cfg.get("tv") or ""
        pkg = cfg.get("mpv_pkg") or "is.xyz.mpv"
        if tv and os.path.isfile(ADB):
            adb("-s", tv, "shell", "am", "force-stop", pkg, timeout=10)
        return {"ok": True, "action": action, "message": "已停止 tvstation。"}

    if action == "restart":
        ok, out = restart_station()
        if not ok:
            return {"ok": False, "error": out.strip()[:300]}
        return {"ok": True, "action": action, "message": "已重启 tvstation。"}

    if action == "wake":
        cfg = load_config()
        tv = cfg.get("tv") or ""
        if not tv:
            return {"ok": False, "error": "config.json 里没有电视地址。"}
        if not os.path.isfile(ADB):
            return {"ok": False, "error": "找不到 adb，请先在 Termux 执行 pkg install android-tools。"}
        out = tv_wake(tv)
        return {"ok": True, "action": "wake", "message": out[:300]}

    return {"ok": False, "error": "未知操作：%s" % action}


def do_settings(payload):
    cfg = load_config()
    changed = False

    if "tv" in payload:
        tv = str(payload["tv"]).strip()
        if not re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?", tv):
            return {"ok": False, "error": "电视地址格式不对，示例：192.168.1.23:5555"}
        if ":" not in tv:
            tv += ":5555"
        port = int(tv.rsplit(":", 1)[1])
        if not (1 <= port <= 65535):
            return {"ok": False, "error": "端口必须在 1–65535。"}
        if cfg.get("tv") != tv:
            cfg["tv"] = tv
            changed = True

    if "public_host" in payload:
        host = str(payload["public_host"]).strip()
        if not re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}", host):
            return {"ok": False, "error": "手机 IP 格式不对，示例：192.168.1.50"}
        if cfg.get("public_host") != host:
            cfg["public_host"] = host
            changed = True

    if "port" in payload:
        try:
            port = int(payload["port"])
        except (TypeError, ValueError):
            return {"ok": False, "error": "代理端口必须是数字。"}
        if not (1024 <= port <= 65535):
            return {"ok": False, "error": "代理端口必须在 1024–65535。"}
        if cfg.get("port") != port:
            cfg["port"] = port
            changed = True

    if not changed:
        return {"ok": True, "message": "没有变化。"}

    save_config(cfg)
    ok, out = restart_station()
    if not ok:
        return {"ok": False, "error": "配置已保存，但重启失败：%s" % out[:300]}
    return {"ok": True, "message": "设置已保存，服务已重启。"}


def do_state():
    try:
        cfg = load_config()
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": "config.json 读不出来：%s" % str(exc)[:120]}

    svc, svc_raw = service_status("tvstation")
    ctl, ctl_raw = service_status("tvcontrol")

    proxy = None
    proxy_error = ""
    port = int(cfg.get("port") or 8099)
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/status" % port,
                                    timeout=2) as resp:
            proxy = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        proxy_error = str(exc)[:120]

    return {
        "ok": True,
        "config": {
            "source": cfg.get("source"),
            "rooms": cfg.get("rooms"),
            "tv": cfg.get("tv"),
            "public_host": cfg.get("public_host"),
            "port": port,
            "switch_seconds": cfg.get("switch_seconds"),
            "list_mid": cfg.get("list_mid"),
            "list_id": cfg.get("list_id"),
        },
        "service": svc,
        "service_raw": svc_raw,
        "control": ctl,
        "control_raw": ctl_raw,
        "proxy": proxy,
        "proxy_error": proxy_error,
    }


def do_logs(n=80):
    n = max(10, min(int(n or 80), 300))
    for path in (STATION_LOG, CONTROL_LOG):
        if os.path.isfile(path):
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    lines = fh.readlines()[-n:]
                return {"ok": True, "log": "".join(lines)}
            except Exception as exc:  # noqa: BLE001
                return {"ok": False, "error": str(exc)[:120]}
    return {"ok": True, "log": "(还没有日志)"}


# ---------------- 首次配置 / 健康检查 ----------------

def wlan_info():
    """当前 WiFi 的 IPv4 和前缀。"""
    ip = ""
    prefix = 24
    try:
        out = subprocess.run(["ip", "-4", "addr", "show", "wlan0"],
                             timeout=3, capture_output=True, text=True,
                             env=_sv_env())
        m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)/(\d+)",
                      (out.stdout or "") + (out.stderr or ""))
        if m:
            ip = m.group(1)
            prefix = int(m.group(2))
    except Exception:  # noqa: BLE001
        pass
    if not ip:
        # 兜底：UDP connect 不发包，只看出口地址；可能拿到蜂窝网
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(2)
            s.connect(("223.5.5.5", 80))
            ip = s.getsockname()[0]
            s.close()
        except Exception:  # noqa: BLE001
            pass
    return {"ok": bool(ip), "ip": ip, "prefix": prefix}


def _is_private_ip(ip):
    try:
        return ipaddress.ip_address(ip).is_private
    except Exception:  # noqa: BLE001
        return False


def _tcp_open(host, port, timeout=0.5):
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except Exception:  # noqa: BLE001
        return False


def _normalize_tv(tv):
    tv = (tv or "").strip()
    if not re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?", tv):
        return ""
    if ":" not in tv:
        tv += ":5555"
    host, _, port = tv.partition(":")
    if not (1 <= int(port) <= 65535):
        return ""
    return tv


def _tv_quick_state(tv):
    """返回 (tcp_ok, adb_state, detail)。"""
    if not tv:
        return False, "", "还没有配置电视地址"
    host, _, port = tv.partition(":")
    port = int(port or 5555)
    if not _tcp_open(host, port, timeout=0.8):
        return False, "", "电视 %s:%d 连不上（没开机/没开网络调试/不在同一 WiFi）" % (host, port)
    code, out = adb("-s", tv, "get-state", timeout=6)
    state = out.strip().splitlines()[-1] if out.strip() else ""
    if state == "device":
        return True, state, "adb 在线"
    if "unauthorized" in out.lower():
        return False, "unauthorized", "电视还没允许这台手机调试"
    if "offline" in out.lower():
        return False, "offline", "adb 显示 offline，电视可能休眠或断网"
    return False, state or "unknown", (out.strip()[:120] or "adb 状态未知")


def _proxy_ok(cfg):
    port = int(cfg.get("port") or 8099)
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/status" % port,
                                    timeout=1.5) as resp:
            json.loads(resp.read().decode("utf-8"))
        return True, "8099 正常"
    except Exception as exc:  # noqa: BLE001
        return False, "8099 无响应：%s" % str(exc)[:80]


def _content_ok(cfg):
    src = cfg.get("source") or "live"
    if src == "video":
        ok = bool(cfg.get("list_mid") and cfg.get("list_id"))
        detail = "合集 mid=%s list_id=%s" % (cfg.get("list_mid"), cfg.get("list_id"))
        return ok, detail
    rooms = cfg.get("rooms") or []
    if not rooms:
        return False, "还没有选直播间"
    return True, "直播间 %s" % ",".join(str(x) for x in rooms)


def setup_status():
    cfg = load_config()
    info = wlan_info()
    current_ip = info.get("ip") or ""
    cfg_host = str(cfg.get("public_host") or "")

    phone_ok = bool(current_ip and cfg_host and current_ip == cfg_host)
    if not current_ip:
        phone_detail = "当前没有连上 WiFi（或读不到 wlan0 的 IPv4）"
    elif not cfg_host:
        phone_detail = "当前手机 IP %s，配置里还没有填" % current_ip
    elif current_ip != cfg_host:
        phone_detail = "当前手机 IP %s，配置里是 %s（WiFi 换过了）" % (current_ip, cfg_host)
    else:
        phone_detail = "当前手机 IP %s，和配置一致" % current_ip

    tv = str(cfg.get("tv") or "")
    tcp_ok, adb_state, tv_detail = _tv_quick_state(tv)
    tv_ok = tcp_ok and adb_state == "device"

    mpv_pkg = str(cfg.get("mpv_pkg") or "is.xyz.mpv")
    mpv_ok = False
    mpv_detail = "先连上电视再检查播放器"
    if tv_ok:
        code, out = adb("-s", tv, "shell", "pm", "path", mpv_pkg, timeout=6)
        mpv_ok = "package" in out
        mpv_detail = ("电视上已装 %s" % mpv_pkg) if mpv_ok else (
            "电视上没找到 %s，请先装 mpv-android" % mpv_pkg)

    proxy_ok, proxy_detail = _proxy_ok(cfg)
    content_ok, content_detail = _content_ok(cfg)

    # 如果代理有状态，把当前台/是否开播补进内容详情
    try:
        with urllib.request.urlopen(
                "http://127.0.0.1:%d/api/status" % int(cfg.get("port") or 8099),
                timeout=1.0) as resp:
            snap = json.loads(resp.read().decode("utf-8"))
        if isinstance(snap, list) and snap:
            rooms = [x for x in snap if isinstance(x, dict) and x.get("room")]
            if rooms:
                r = rooms[0]
                content_detail = "直播间 %s · %s" % (
                    r.get("room"), "直播中" if r.get("live") else "等待开播")
    except Exception:  # noqa: BLE001
        pass

    steps = [
        {"id": "phone_ip", "label": "手机网络", "ok": phone_ok,
         "detail": phone_detail,
         "fix": "确认手机连着电视所在的 WiFi，然后点“使用当前 IP”。"},
        {"id": "tv_adb", "label": "电视连接", "ok": tv_ok,
         "detail": tv_detail,
         "fix": "电视开机、打开网络调试；也可以点“扫描局域网”重新找电视 IP。"},
        {"id": "mpv", "label": "电视播放器", "ok": mpv_ok,
         "detail": mpv_detail,
         "fix": "在电视上安装 mpv-android（包名 is.xyz.mpv），装完点“重新检测”。"},
        {"id": "proxy", "label": "代理服务", "ok": proxy_ok,
         "detail": proxy_detail,
         "fix": "在 Termux 里执行 ./tvctl status；不跑就 ./tvctl start。"},
        {"id": "content", "label": "轮播内容", "ok": content_ok,
         "detail": content_detail,
         "fix": "搜一个 UP 或粘贴直播间链接，选一个直播间。"},
    ]
    required = ("phone_ip", "tv_adb", "mpv", "proxy", "content")
    complete = all(s["ok"] for s in steps if s["id"] in required)

    return {
        "ok": True,
        "complete": complete,
        "seen": bool(cfg.get("setup_seen")),
        "steps": steps,
        "phone": {"current": current_ip, "configured": cfg_host,
                  "prefix": info.get("prefix"), "wlan": bool(current_ip)},
        "tv": {"configured": tv, "state": adb_state, "mpv": mpv_ok},
        "content": {"source": cfg.get("source"), "rooms": cfg.get("rooms") or [],
                    "list_mid": cfg.get("list_mid"), "list_id": cfg.get("list_id")},
        "config": {
            "source": cfg.get("source"),
            "rooms": cfg.get("rooms"),
            "tv": cfg.get("tv"),
            "public_host": cfg.get("public_host"),
            "port": int(cfg.get("port") or 8099),
        },
    }


def setup_apply_phone(payload):
    host = str(payload.get("public_host") or "").strip()
    if not re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}", host):
        return {"ok": False, "error": "手机 IP 格式不对，示例：192.168.1.50"}
    info = wlan_info()
    if info.get("ip") and host != info["ip"]:
        # 允许手动填，但提醒当前实际地址不同
        pass
    cfg = load_config()
    cfg["public_host"] = host
    save_config(cfg)
    ok, out = restart_station()
    if not ok:
        return {"ok": False, "error": "IP 已保存，但重启 tvstation 失败：%s" % out[:200]}
    return {"ok": True, "message": "手机 IP 已设为 %s，服务已重启。" % host,
            "current": info.get("ip")}


def setup_apply_tv(payload):
    tv = _normalize_tv(payload.get("tv"))
    if not tv:
        return {"ok": False, "error": "电视地址格式不对，示例：192.168.1.23:5555"}
    if not os.path.isfile(ADB):
        return {"ok": False, "error": "找不到 adb，请先在 Termux 执行 pkg install android-tools。"}

    adb("connect", tv, timeout=8)
    tcp_ok, state, detail = _tv_quick_state(tv)
    if state != "device":
        return {"ok": False, "error": detail,
                "state": state, "hint": "电视上弹“允许 USB 调试”时勾选“一律允许”再点允许。"}

    cfg = load_config()
    cfg["tv"] = tv
    save_config(cfg)
    ok, out = restart_station()
    if not ok:
        return {"ok": False, "error": "电视地址已保存，但重启 tvstation 失败：%s" % out[:200]}

    pkg = str(cfg.get("mpv_pkg") or "is.xyz.mpv")
    _, pout = adb("-s", tv, "shell", "pm", "path", pkg, timeout=6)
    mpv = "package" in pout
    return {"ok": True, "message": "电视已连接：%s" % tv,
            "tv": tv, "mpv": mpv,
            "mpv_detail": ("已装 %s" % pkg) if mpv else ("没找到 %s，请先装 mpv-android" % pkg)}


def setup_test_playback():
    cfg = load_config()
    tv = str(cfg.get("tv") or "")
    if not tv:
        return {"ok": False, "error": "还没有配置电视地址。"}
    _, win = adb("-s", tv, "shell", "dumpsys", "window", timeout=8)
    foreground = ""
    for line in win.splitlines():
        if "mCurrentFocus" in line:
            foreground = line.strip()
            break
    _, media = adb("-s", tv, "shell", "dumpsys", "media_session", timeout=8)
    state = -1
    for line in media.splitlines():
        k = line.find("{state=")
        if k >= 0:
            num = ""
            for ch in line[k + 7:]:
                if ch.isdigit():
                    num += ch
                else:
                    break
            if num:
                state = int(num)
                break
    mpv_fg = "is.xyz.mpv" in foreground
    return {"ok": True, "foreground": foreground,
            "mpv_foreground": mpv_fg,
            "media_state": state,
            "playing": state == 3,
            "detail": "mpv 在前台" if mpv_fg else "mpv 不在前台"}


def setup_mark_seen():
    cfg = load_config()
    cfg["setup_seen"] = True
    save_config(cfg)
    return {"ok": True}


def setup_scan_tv():
    info = wlan_info()
    ip = info.get("ip") or ""
    if not ip or not _is_private_ip(ip):
        return {"ok": False, "error": "手机没有连上局域网 WiFi，先连 WiFi 再扫描。"}

    prefix = ".".join(ip.split(".")[:3])

    def probe(n):
        host = "%s.%d" % (prefix, n)
        if host == ip:
            return None
        try:
            with socket.create_connection((host, 5555), timeout=0.3):
                return host
        except Exception:  # noqa: BLE001
            return None

    hosts = []
    with ThreadPoolExecutor(max_workers=64) as pool:
        for r in pool.map(probe, range(1, 255)):
            if r:
                hosts.append(r)

    candidates = []
    for host in hosts[:8]:
        tv = host + ":5555"
        adb("connect", tv, timeout=4)
        _, state = adb("-s", tv, "get-state", timeout=4)
        state = (state or "").strip().splitlines()[-1] if (state or "").strip() else ""
        _, model = adb("-s", tv, "shell", "getprop", "ro.product.model", timeout=4)
        model = (model or "").strip()
        candidates.append({"ip": host, "tv": tv, "state": state, "model": model})

    return {"ok": True, "phone_ip": ip, "scanned": len(hosts),
            "candidates": candidates,
            "hint": "没扫到？确认电视开机、网络调试打开、和手机同一 WiFi；也可以手动输入。"}


# ---------------- HTTP ----------------

class Handler(BaseHTTPRequestHandler):
    server_version = "tvcontrol/1.0"

    def log_message(self, fmt, *args):
        # 只记错误，避免每 5 秒刷一行
        if args and str(args[1]).startswith("4"):
            log("%s %s" % (self.address_string(), fmt % args))

    def _send(self, code, body, ctype):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:  # noqa: BLE001
            pass

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False),
                   "application/json; charset=utf-8")

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > 65536:
            return {}
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:  # noqa: BLE001
            return {}

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        qs = urllib.parse.parse_qs(u.query)

        if path in ("/", "/index.html"):
            try:
                with open(WEB_INDEX, encoding="utf-8") as fh:
                    page = fh.read()
                self._send(200, page, "text/html; charset=utf-8")
            except Exception:  # noqa: BLE001
                self._send(200, "<h1>tvcontrol</h1><p>web/index.html 缺失</p>",
                           "text/html; charset=utf-8")
            return

        if path in ("/setup.js", "/setup.css"):
            name = path.lstrip("/")
            fpath = os.path.join(HERE, "web", name)
            ctype = ("application/javascript; charset=utf-8" if name.endswith(".js")
                     else "text/css; charset=utf-8")
            try:
                with open(fpath, encoding="utf-8") as fh:
                    body = fh.read()
                self._send(200, body, ctype)
            except Exception:  # noqa: BLE001
                self._send(404, "not found", "text/plain; charset=utf-8")
            return

        if path == "/api/health":
            self._json({"ok": True, "app": "tvcontrol"})
            return

        if path == "/api/state":
            self._json(do_state())
            return

        if path == "/api/search":
            q = (qs.get("q") or [""])[0]
            self._json(do_search(q))
            return

        if path == "/api/log":
            n = (qs.get("n") or ["80"])[0]
            try:
                n = int(n)
            except ValueError:
                n = 80
            self._json(do_logs(n))
            return

        if path == "/api/setup/status":
            self._json(setup_status())
            return

        if path == "/api/setup/scan_tv":
            self._json(setup_scan_tv())
            return

        self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        payload = self._read_json()

        if path == "/api/play_live":
            self._json(do_play_live(payload))
            return
        if path == "/api/control":
            self._json(do_control(payload))
            return
        if path == "/api/settings":
            self._json(do_settings(payload))
            return
        if path == "/api/play_video":
            self._json({"ok": False,
                        "error": "视频播放是第二版；第一版先做直播间。"})
            return

        if path == "/api/setup/apply_phone":
            self._json(setup_apply_phone(payload))
            return

        if path == "/api/setup/apply_tv":
            self._json(setup_apply_tv(payload))
            return

        if path == "/api/setup/test_playback":
            self._json(setup_test_playback())
            return

        if path == "/api/setup/seen":
            self._json(setup_mark_seen())
            return

        self._json({"ok": False, "error": "not found"}, 404)


def main():
    cfg = {}
    try:
        cfg = load_config()
    except Exception:  # noqa: BLE001
        pass
    port = int(os.environ.get("TVSTATION_CONTROL_PORT")
               or cfg.get("control_port") or 8098)

    bind = os.environ.get("TVSTATION_CONTROL_BIND", "127.0.0.1")
    try:
        httpd = ThreadingHTTPServer((bind, port), Handler)
    except OSError as exc:
        log("控制端口 %s:%d 绑定失败：%s" % (bind, port, exc))
        sys.exit(1)

    log("tvcontrol 已启动 http://%s:%d（配置：%s）" % (bind, port, CONFIG_PATH))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("退出")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
