# 树莓派 B站轮播台 → 旧手机 Termux 迁移说明书

> 适用对象：Android 7.0–11 的旧手机；电视仍是 Android、已装 mpv-android；手机和电视在同一个 WiFi / 局域网。
>
> **从零开始的完整调试步骤（含兼容性检查、Termux 装什么、分层排查、配图）见 [`../全流程调试手册.md`](../全流程调试手册.md)。**
>
> 一句话结论：**可行，手机只替代树莓派，电视端一行都不用改。** 但要换掉 systemd、sudo、apt 这三样树莓派专属的东西，并把“手机 IP 自动探测”和“播放器存活检测”两处改成 Termux 友好写法。

## 最短路径（只想快点跑起来）

```bash
# 1. F-Droid 装 Termux + Termux:Boot，点开一次 Termux:Boot
# 2. Termux 里装依赖
pkg update -y && pkg upgrade -y
pkg install -y python android-tools termux-services iproute2 curl

# 3. 把 termux-port 整个文件夹传到 ~/tvstation（见第 4 节）
cd ~/tvstation

# 4. 安装（按提示填电视 IP、手机 IP、房间号）
bash install-termux.sh

# 5. 电视上点“允许调试”，然后
./tvctl status

# 6. 以后换直播间/合集
./set-content.sh
```

详细原理、验证和故障排查见下面各节。

## 目录

- [0. 先核对：这套程序是不是你说的功能](#0-先核对这套程序是不是你说的功能)
- [1. 这套程序到底怎么工作](#1-这套程序到底怎么工作)
- [2. 迁移总览](#2-迁移总览)
- [3. 准备](#3-准备)
- [4. 把程序传到手机](#4-把程序传到手机)
- [5. 安装](#5-安装)
- [6. 四层验证](#6-四层验证)
- [7. 开机自启验证](#7-开机自启验证)
- [8. 日常使用](#8-日常使用)
- [9. 故障排查](#9-故障排查)
- [10. 回滚](#10-回滚)
- [11. 代码与文件清单](#11-代码与文件清单)
- [12. 边界与待确认](#12-边界与待确认)

---

## 0. 先核对：这套程序是不是你说的功能

压缩包 `tvstation-20261031.zip` 里只有一套 `tvstation`，共 10 个文件。核心是单文件 `station.py`（纯 Python 标准库，不用 pip 装任何东西）。

| 你描述的功能 | 包里有没有 | 对应代码 | 说明 |
|---|---|---|---|
| 同一 WiFi 下控制小米电视 | ✅ 有 | `adb connect 电视IP:5555`、`adb shell am start` | 电视必须能开网络调试 |
| 电视装 MPV 跑直播 | ✅ 有 | `tv_play()` 把 `http://手机IP:8099/flv?room=...` 递给 `is.xyz.mpv` | 电视从头到尾不直连 B 站 |
| 多个直播间轮播 | ✅ 有 | `run_live()` | 每 30 秒换台；全下播就停 |
| 轮播“录播” | ⚠️ 部分有 | `run_video()` | 轮播的是 **B站合集/列表里的在线视频**（`mid` + `list_id`），不是本地录播文件 |
| 写入网址给电视 | ✅ 有 | `am start -a android.intent.action.VIEW -d 'http://...'` | mpv-android 收到 URL 直接播 |
| 补 Referer/UA 的 HTTP 代理 | ✅ 有 | `:8099` 的 `/flv`、`/video`、`/api/status` | B站 CDN 只认带 Referer+UA 的请求 |
| 换台后自动补播放键 | ✅ 有 | `tv_resume()` 读 `dumpsys media_session` 后发 `keyevent 85` | 解决 mpv 换台后停在暂停态 |
| adb 指令“唤醒电脑/电视” | ❌ 包里没有 | 无 | 已确认你要求控制端装 adb SDK；迁移版补了 `tv_wake()` |
| 开机自启 / 崩了自愈 | ✅ 有（但只适用于 Linux） | `tvstation.service` 的 `Restart=always` | Termux 上改用 `termux-services`（runit） |

**两个要明确的点：**

1. **“录播”**：当前包的 video 模式是拉一个 B站合集/列表，随机挑 5 个在线视频轮播，一轮播完重新随机。它不是“把直播录下来”，也不是“播手机里下载好的 mp4”。如果你要的是本地文件轮播，那要另外加一个模块（当前包没有）。
2. **“唤醒”**：包内没有任何唤醒代码。你回答“要求电脑或者手机里面要有 adb 的 SDK 包”，我据此理解为：**控制端（手机 Termux）要有 adb，靠 adb 把电视叫醒再派台**。迁移版加的是 `KEYCODE_WAKEUP`（224）+ `wm dismiss-keyguard`。如果你说的是用 adb 唤醒一台**电脑**（而不是电视），adb 本身做不到，那需要 Wake-on-LAN，得另说。

---

## 1. 这套程序到底怎么工作

### 1.1 三个角色

```
┌──────────────┐        HTTPS（带 Referer + UA）        ┌──────────────────┐
│  B站 API/CDN │ ◀───────────────────────────────────── │  树莓派（播出中心）│
└──────────────┘                                        │  station.py       │
                                                        │  :8099 补头代理    │
                                                        └────────┬─────────┘
                                                                 │ HTTP 直链
                                                                 ▼
                                                        ┌──────────────────┐
                                                        │  Android 电视     │
                                                        │  mpv-android     │
                                                        └──────────────────┘
                                                                 ▲
                                                                 │ adb over WiFi :5555
                                                        ┌────────┴─────────┐
                                                        │ 树莓派上的 adb    │
                                                        └──────────────────┘
```

- **树莓派/手机**：负责三件事——查 B站接口、把带请求头的字节流转给电视、用 adb 指挥电视上的 mpv。
- **电视**：只负责解码和显示。它只连树莓派/手机，不直连 B站。
- **B站**：提供直播间状态、FLV 直链、视频列表和 MP4 直链。

### 1.2 为什么中间要多一台机器

B站 CDN 的直链对请求头挑得很死：

- 只带 `Referer` → 403
- 只带桌面 `User-Agent` → 403
- 两个都带 → 200

电视上的 mpv-android 改不了请求头，所以由树莓派/手机替它带上头去拉流，再把字节原样转给电视。**这就是 `:8099` 代理存在的唯一原因。**

### 1.3 直播模式（`source = "live"`）

```
启动
 │
 ├─ 绑定 0.0.0.0:8099（补头代理）
 ├─ adb start-server ──▶ adb connect 电视:5555
 │
 ├─ 后台线程：每 10 秒查一遍所有房间
 │    GET api.live.bilibili.com/room/v1/Room/get_info?room_id=...
 │    记录：是否在播、标题、人气
 │
 └─ 主循环（每 0.5 秒看一次）
      ├─ 没有任何房间在播？
      │    └─ 如果电视还在播 → adb force-stop mpv，等待
      ├─ 当前没在播 / 当前台下播 / 到 30 秒了？
      │    └─ 选下一个“正在直播”的房间
      │         ├─ tv_wake()：WAKEUP(224) → dismiss-keyguard
      │         ├─ am start -n is.xyz.mpv/.MPVActivity -d 'http://手机:8099/flv?room=...'
      │         └─ tv_resume()：读 dumpsys media_session，state≠3 就补 keyevent 85
      └─ 播放器意外退出？
           └─ 重新拉起当前房间
```

关键点：

- **不是“谁在播就一直播谁”**：每 30 秒换下一个正在直播的房间。
- **不是“轮询到就切”**：状态线程只更新状态，切台由主循环决定，避免两个线程抢 adb。
- **换台后可能暂停**：mpv-android 的 `onNewIntent` 会停在暂停态，所以 `tv_resume()` 会读状态再补播放键。

### 1.4 视频/“录播”模式（`source = "video"`）

```
启动
 │
 ├─ 拉合集列表
 │    GET api.bilibili.com/x/series/archives?mid=<UP主>&series_id=<列表id>&ps=50
 │    需要 wbi 签名（代码里 _signed() + MIXIN_TAB 已实现）
 │
 ├─ 随机挑 5 个视频
 │
 └─ 主循环
      ├─ 每 30 秒换下一个
      ├─ 派台 URL：http://手机:8099/video?bvid=BV...
      ├─ 代理再调 /x/player/playurl 拿未登录 1080P 单文件 mp4 直链
      └─ 一轮 5 个播完 → 重新随机 5 个
```

关键点：

- 只支持**公开合集/列表**里的视频，不支持需要登录/大会员/地区限制的内容。
- 未登录走 `qn=80`（1080P）；拿不到就退回更低清晰度或失败。
- 直链会过期，代码里缓存 30 分钟；过期后重新解析。

### 1.5 电视控制（adb）

| 动作 | 命令 | 说明 |
|---|---|---|
| 连电视 | `adb connect 电视IP:5555` | 第一次要在电视上点“允许调试” |
| 确认在线 | `adb -s 电视IP:5555 get-state` | 期望 `device` |
| 唤醒 | `adb shell input keyevent 224` | `KEYCODE_WAKEUP`；深度待机/断电时 adb 连不上，唤不醒 |
| 回桌面（手动） | `adb shell input keyevent 3` | `KEYCODE_HOME`；`tv_wake` 不自动发，避免换台闪桌面 |
| 派台 | `adb shell am start -a android.intent.action.VIEW -n is.xyz.mpv/.MPVActivity -d 'http://...'` | 把 URL 递给 mpv |
| 读播放状态 | `adb shell dumpsys media_session` | 找 `{state=3}` 表示播放中 |
| 补播放键 | `adb shell input keyevent 85` | `KEYCODE_MEDIA_PLAY_PAUSE` |
| 停止 | `adb shell am force-stop is.xyz.mpv` | 全部下播时用 |
| 看前台窗口 | `adb shell dumpsys window \| grep mCurrentFocus` | 确认 mpv 真的在前台 |

### 1.6 它不做什么

- 不录像、不转码、不做鉴权。
- 不支持多台电视。
- 不支持非 Android 电视（webOS/Tizen 没有 adb）。
- 8099 端口没有鉴权，只适合局域网。
- 电视完全断电/深度待机时，adb 唤不醒；要靠 HDMI-CEC、红外或电视定时开机。

---

## 2. 迁移总览

### 2.1 树莓派 vs Termux 对照

| 树莓派上的东西 | Termux 上怎么办 | 迁移版文件 |
|---|---|---|
| `systemd` / `systemctl` | `termux-services`（runit） | `templates/service-run`、`tvctl` |
| `sudo` / `apt` | Termux 不需要 root；用 `pkg install` | `install-termux.sh` |
| `ufw` 放行 8099 | Android 没有 ufw；确保路由器没开 AP 隔离 | 无 |
| `adb`（apt 包） | `pkg install android-tools` | `install-termux.sh` 检查 |
| `setup.sh` | 不能用（依赖 systemd/sudo/ufw） | `install-termux.sh` |
| `mode.sh`（systemctl） | 改成 `sv restart` | `mode.sh` |
| `status.sh`（systemctl） | 改成 `sv status` | `status.sh` |
| `run.sh`（nohup + pkill） | 保留为调试兜底；正式用 runit | `run.sh` |
| 开机自启 | `Termux:Boot` + `~/.termux/boot/00-tvstation` | `templates/boot-start` |
| 崩溃自愈 | runit 自动重启 | `templates/service-run` |
| `ps \| grep` 查 mpv | Android 8+ 默认看不到别的应用，改 `pidof` | `station.py` 已改 |
| 自动探测本机 IP | 可能取到蜂窝网卡，改成显式 `public_host` | `install-termux.sh` 写入 |
| `station.log` | 同一个文件；run 脚本做 10MB 轮转 | `templates/service-run` |
| 手机 App/网页控制 | 树莓派时代没有 | 新增 `tvcontrol` :8098 | `control.py`、`web/index.html` |

### 2.2 迁移后的架构

```
┌──────────────┐        HTTPS（带 Referer + UA）        ┌──────────────────────┐
│  B站 API/CDN │ ◀───────────────────────────────────── │  旧手机 Termux        │
└──────────────┘                                        │  station.py :8099    │
                                                        │  android-tools adb   │
                                                        └────────┬─────────────┘
                                                                 │ HTTP 直链
                                                                 ▼
                                                        ┌──────────────────────┐
                                                        │  小米电视             │
                                                        │  mpv-android         │
                                                        └──────────────────────┘
                                                                 ▲
                                                                 │ adb over WiFi :5555
                                                                 └── 同一台手机

手机 App / 浏览器
  └─ http://127.0.0.1:8098（tvcontrol）
       ├─ 搜 UP / 解析直播间链接
       ├─ 写 config.json（source=single, rooms=[...]）
       └─ sv restart tvstation
```

> `tvcontrol` 只监听 `127.0.0.1`，是“遥控器”；`station.py` 的 8099 才是给电视用的代理。

### 2.3 迁移后的启动链

```
手机开机
  └─ Android 发 BOOT_COMPLETED
       └─ Termux:Boot
            └─ ~/.termux/boot/00-tvstation
                 ├─ 等 wlan0 拿到 IPv4（最多 120 秒）
                 ├─ termux-wake-lock（防止息屏后 CPU/网络被挂起）
                 ├─ service-daemon start（runit 守护进程）
                 ├─ sv up tvstation
                 │    └─ $PREFIX/var/service/tvstation/run
                 │         └─ python3 -u station.py >> station.log
                 └─ sv up tvcontrol
                      └─ $PREFIX/var/service/tvcontrol/run
                           └─ python3 -u control.py >> control.log
```

### 2.4 哪些文件要换

```
termux-port/
├── station.py              ← 原 station.py + Termux 补丁 + single 模式
├── control.py              ← 控制 API + 网页（:8098）
├── web/index.html          ← 手机 App/浏览器界面
├── config.example.json     ← 配置模板（install 会生成 config.json）
├── install-termux.sh       ← 替代 setup.sh
├── tvctl                   ← 替代 systemctl
├── mode.sh                 ← 改自原 mode.sh
├── status.sh               ← 改自原 status.sh
├── run.sh                  ← 改自原 run.sh（调试兜底）
├── set-content.sh          ← 交互式换直播间/合集
├── 新手搭建.md             ← 小白逐步说明
├── APP设计反馈.md          ← App 路线与设计
├── uninstall-termux.sh     ← 卸载
└── templates/
    ├── service-run         ← tvstation 服务脚本
    ├── control-run         ← tvcontrol 服务脚本
    └── boot-start          ← Termux:Boot 脚本
```

原包里的 `setup.sh`、`tvstation.service.in`、`sudoers.in` **不要用**；`mode.sh`、`status.sh`、`run.sh` 用迁移版覆盖。

---

## 3. 准备

### 3.1 清单

- [ ] 旧手机 Android 7.0–11（你已确认）
- [ ] 手机能长期插电、WiFi 稳定
- [ ] 手机和电视在同一局域网，路由器没开“AP 隔离/客户端隔离”
- [ ] 电视是 Android，已装 mpv-android（`is.xyz.mpv`），已开网络调试
- [ ] 电视 IP 固定（路由器 DHCP 绑定）
- [ ] 手机 WiFi IP 固定（路由器 DHCP 绑定，强烈建议）
- [ ] 一根能长期供电的充电线/充电头

### 3.2 手机设置（很重要，不做会“跑着跑着就停”）

1. **电池优化**：设置 → 应用 → Termux → 电池 → 选“不优化/无限制”。
2. **自启动**：国产 ROM 还要额外给 Termux 开“自启动/后台运行”权限：
   - 小米/MIUI/HyperOS：安全中心 → 应用管理 → Termux → 自启动、省电策略“无限制”，并在最近任务里把 Termux 锁定。
   - 华为/荣耀：应用启动管理 → Termux → 手动管理 → 允许自启动、关联启动、后台活动。
   - OPPO/vivo/一加：电池 → 应用耗电管理 → Termux → 允许后台运行；自启动里打开。
   - 三星：电池 → 后台使用限制 → 把 Termux 加入“永不休眠的应用”。
3. **WiFi 保持**：设置 → WiFi → 高级 → 如果有“休眠时保持 WiFi 开启”，选“始终”。插电时一般不会断，但开着更稳。
4. **固定 IP**：优先在路由器里按手机 MAC 做 DHCP 绑定；不要在手机里随便设静态 IP，换网络时容易出问题。Android 10+ 默认“随机 MAC”，要在该 WiFi 的高级设置里改成“使用设备 MAC”，再绑定。
5. **不要 force-stop Termux**：被强行停止过的应用，Android 不会给它发自启广播，直到你手动再打开一次。
6. **锁屏**：如果手机设了 PIN/图案，Android 7+ 的 `BOOT_COMPLETED` 要等第一次解锁后才发；也就是**重启后要手动解锁一次**，Termux:Boot 才会跑。想完全无人值守，只能不设锁屏（自己权衡安全）。
7. **充电安全**：旧手机长期插电会发热/电池鼓包。放在通风处，定期摸一下电池；能拆电池的机型可以考虑拆电池+稳压供电（需要动手能力）。
8. **关闭电池保护/充电上限**：有些系统有“充到 80% 停止”的保护，长期插电时会一直停在 80%，拔电后撑不了多久。做播出机就关掉它。

### 3.3 装 Termux 和 Termux:Boot

> 不要用 Google Play 的 Termux（实验版，功能不全）。用 F-Droid，Termux 和 Termux:Boot 必须来自**同一个签名来源**，不能混装。

1. 手机浏览器打开 <https://f-droid.org/packages/com.termux/>，下载 APK 安装。
2. 再打开 <https://f-droid.org/packages/com.termux.boot/>，下载 Termux:Boot 安装。
3. 安装完**先点开一次 Termux:Boot 图标**，然后可以关掉。这一步是让 Android 允许它开机自启。
4. 打开 Termux，执行：

```bash
pkg update -y && pkg upgrade -y
pkg install -y python android-tools termux-services iproute2 curl
```

> `android-tools` 提供的就是 `adb`/`fastboot`，相当于电脑上的 platform-tools；不需要装完整 Android SDK。
>
> 国内网络如果 `pkg` 很慢或报镜像错误，先执行 `termux-change-repo`，选清华/中科大/北外等镜像，再重试。

装完确认：

```bash
python3 -V          # 期望 3.11 / 3.12
adb version         # 期望 Android Debug Bridge version ...
sv --help 2>&1 | head -1
ip -4 addr show wlan0 | grep inet
```

### 3.4 电视端

和树莓派时代一样，不用改：

1. 电视装 mpv-android（包名 `is.xyz.mpv`）。
2. 电视打开开发者模式 → 网络调试/USB 调试。
3. 记下电视 IP。

手机第一次连电视时，电视会弹“允许 USB 调试吗？”，勾选“一律允许”再点允许。

### 3.5 网络

- 手机和电视必须在同一网段（比如都是 `192.168.10.x`）。
- 路由器如果开了“AP 隔离/访客网络隔离”，电视连不到手机的 8099，必须关掉。
- 建议手机连 5GHz（如果路由器有），延迟更低；但旧手机只支持 2.4GHz 也能跑，720P 约 250–300 KB/s。
- 手机上不要开 VPN/代理软件：它们可能把局域网流量也接管，电视就连不上 8099。
- 如果路由器 AP 隔离改不了，可以让手机开热点、电视连手机热点，`public_host` 填手机热点网关 IP（Android 常见 `192.168.43.1`）。这时手机走移动数据访问 B站，流量要够。

---

## 4. 把程序传到手机

### 方法 A：scp（推荐，手机和电脑同一 WiFi）

手机 Termux 里：

```bash
pkg install -y openssh
passwd                 # 设置一个登录密码
sshd                   # 启动 sshd，默认端口 8022
whoami                 # 记下用户名，例如 u0_a123
ip -4 addr show wlan0 | grep inet   # 记下手机 IP
```

Windows PowerShell（在 `termux-port` 的**上一级**目录）：

```powershell
scp -P 8022 -r .\termux-port u0_a123@192.168.10.50:~/
```

然后手机 Termux 里：

```bash
mv ~/termux-port ~/tvstation
cd ~/tvstation
```

### 方法 B：USB / 微信 / QQ 传到手机 Download

1. 把 `termux-port` 文件夹压缩成 `termux-port.zip`，传到手机 `Download`。
2. Termux 里：

```bash
termux-setup-storage          # 第一次会弹权限，点允许
pkg install -y unzip
unzip ~/storage/downloads/termux-port.zip -d ~/
mv ~/termux-port ~/tvstation
cd ~/tvstation
```

### 方法 C：电脑起一个临时 HTTP 服务

Windows 在 `termux-port` 的上一级目录：

```powershell
python -m http.server 8000
```

手机 Termux：

```bash
pkg install -y curl unzip
curl -O http://电脑IP:8000/termux-port.zip
unzip termux-port.zip -d ~/
mv ~/termux-port ~/tvstation
cd ~/tvstation
```

> Windows 防火墙如果弹窗，允许“专用网络”。

> 传输/编辑 `.sh` 时保持 **LF** 换行；Windows 记事本会改成 CRLF，运行时报 `bad interpreter`。用 VS Code / Notepad++ 并确认右下角是 LF。

---

## 5. 安装

```bash
cd ~/tvstation
bash install-termux.sh
```

它会依次问：

```
== 1/7 电视 ==
  电视 ADB 地址（IP 或 IP:5555） [192.168.10.139:5555]:

== 2/7 手机 WiFi 地址 ==
  手机 WiFi IP（电视要能访问它） [192.168.10.50]:

== 3/7 轮播内容 ==
  默认模式 live=直播间轮播 / video=列表视频 [live]:
  直播间号（英文逗号分隔） [5017134,5064126,4374913]:
  列表所属 UP 主 mid（video 模式用） [23191782]:
  视频列表 id（video 模式用） [1424266]:
```

方括号里是默认值，回车即用。无人值守可以：

```bash
TVSTATION_TV=192.168.10.139 \
TVSTATION_HOST=192.168.10.50 \
TVSTATION_SOURCE=live \
TVSTATION_ROOMS=5017134,5064126 \
bash install-termux.sh --yes
```

安装脚本做的事：

1. 检查 `python3 / adb / sv / ip / curl`。
2. 写 `~/tvstation/config.json`（已有会先备份）。
3. `adb start-server` → `adb connect 电视` → 检查 `get-state` 和 `pm path is.xyz.mpv`。
4. 生成 `$PREFIX/var/service/tvstation/run` 和 `$PREFIX/var/service/tvcontrol/run`。
5. 生成 `~/.termux/boot/00-tvstation`。
6. `termux-wake-lock` → `service-daemon start` → `sv-enable tvstation` / `sv-enable tvcontrol`。
7. `curl 127.0.0.1:8099/api/status` 和 `127.0.0.1:8098/api/health` 自检。

如果第 5 步显示 `adb 状态=unauthorized`：去电视上点“允许”，再重跑一遍 `bash install-termux.sh`（会保留/备份 config）。

---

## 6. 四层验证

### 第 1 层：服务在跑

```bash
cd ~/tvstation
./tvctl status
tail -n 20 station.log
```

期望日志：

```
===== 2026-11-05 12:00:00 runit start =====
代理已启动 0.0.0.0:8099
启动：模式=live 配置文件=/data/data/com.termux/files/home/tvstation/config.json
参数：电视=192.168.10.139:5555 回连地址=192.168.10.50 房间=[5017134, 5064126]
```

### 第 2 层：代理活着

```bash
curl -s --max-time 3 http://127.0.0.1:8099/api/status
```

live 模式期望：

```json
[{"room": 5017134, "live": true, "title": "直播间标题", "online": 1234}]
```

video 模式期望当前一轮的视频清单：

```json
[{"bvid": "BV1xx411c7XX", "title": "视频标题"}]
```

空数组 `[]` 说明还没查到状态，等 10 秒再试。

### 第 3 层：电视连得过来

```bash
adb connect 电视IP:5555

# 电视能不能访问手机（很多电视没 curl，失败不一定代表不通）
adb -s 电视IP:5555 shell "curl -s --max-time 3 http://手机IP:8099/api/status || echo FAIL"

# 电视上 mpv 是否在前台
adb -s 电视IP:5555 shell dumpsys window | grep mCurrentFocus
# 期望包含 is.xyz.mpv/.MPVActivity
```

### 第 4 层：画面出来了

电视应该：

- 10–15 秒内出现画面；
- 每 30 秒换一个台；
- 换台后不停在暂停态。

日志同步出现：

```
[调度] 开始播放 → 房间 5017134（...）
[电视] room=5017134 OK
[电视] 播放中
```

到这一步就成功了。

---

## 7. 开机自启验证

1. 确认 Termux:Boot 已安装并**点开过一次**。
2. 确认 Termux 电池优化已关闭、自启动权限已给。
3. 重启手机。
4. **解锁一次**（如果有锁屏；Android 7+ 的 BOOT_COMPLETED 要等首次解锁）。
5. 不要打开 Termux，等 1–2 分钟。
6. 在电脑/另一台手机上：

```bash
curl -s http://手机IP:8099/api/status
```

有 JSON 返回就说明自启成功。也可以打开 Termux 看：

```bash
~/tvstation/tvctl status
tail -n 20 ~/tvstation/station.log
```

如果没起来，按顺序查：

- `~/.termux/boot/00-tvstation` 是否存在、是否 `chmod 755`；
- Termux:Boot 是否被系统“强行停止”；
- 电池优化/自启动权限；
- 手机是否还没解锁；
- 手动执行 `bash ~/.termux/boot/00-tvstation` 看报错。

---

## 8. 日常使用

```bash
cd ~/tvstation

./set-content.sh        # 换直播间/合集（推荐，不用手改 JSON）
./tvctl status          # 服务 + 8099 + 最近日志
./tvctl restart         # 改完 config.json 重启
./tvctl log 100         # 看最近 100 行
./tvctl wake            # 只唤醒电视，不派台
./mode.sh live          # 切直播间轮播
./mode.sh video         # 切列表视频轮播
./status.sh             # 服务/配置/日志/代理/电视前台，一次看全
tail -f station.log     # 实时日志
```

手机 App / 浏览器控制台：

```
http://127.0.0.1:8098
```

- 只在本机（手机自己）能打开，不要改成 `0.0.0.0`。
- 可以搜 UP 名字、粘贴直播间链接、切换单直播间、停止/唤醒/看日志。
- Android APK 见 `android-app/`（WebView 壳，仍需 Termux 后端）。

常用接口：

| 地址 | 说明 |
|---|---|
| `http://127.0.0.1:8098` | 手机本机控制台（App/浏览器） |
| `http://手机IP:8099/api/status` | 当前轮播状态（JSON） |
| `http://手机IP:8099/flv?room=房间号` | 直播流（仅限 `rooms` 里配过的房间） |
| `http://手机IP:8099/video?bvid=BV号` | 视频流（仅限列表里的视频） |

改“播什么”优先用 `./set-content.sh`；只有改端口、电视 IP、手机 IP 这类网络参数，才需要编辑 `~/tvstation/config.json` 后 `./tvctl restart`。

> 完全没用过 Termux 的话，先看 [新手搭建.md](新手搭建.md)，里面是从装 APK 开始的逐步截图式说明（文字版）。

### 8.1 手机 App（薄壳版）

`android-app/` 里是一个真正的 APK：原生图标 + WebView 壳，加载 `http://127.0.0.1:8098/`。

- 装好后打开 App，就是控制台。
- 后端没起来时，App 显示原生错误页，可以点“打开 Termux”。
- 它**仍然需要 Termux 后端**；不是完全独立 App。
- 构建/安装见 [android-app/README.md](../android-app/README.md)，现成 APK：
  `../TVStationRemote-0.2.0.apk`（debug 签名，可直接安装）。

> 第一版只做直播间：搜 UP、粘贴直播间链接、单台切换、停止/唤醒/日志。
> 视频/合集/BV 是第二版。

> `control_port` 固定用 `8098`；APK 里写死了 `http://127.0.0.1:8098/`，不要改它。

### 8.2 首次配置向导与健康检查

App 第一次打开分两段：

**原生段（不需要后端）**

1. 检查 Android 版本、CPU 架构、存储。
2. 检查 Termux / Termux:Boot 是否安装。
3. 检查 `http://127.0.0.1:8098/api/health` 是否就绪。
4. 缺什么就显示什么按钮：
   - 安装 Termux
   - 安装 Termux:Boot
   - 打开 Termux
   - 复制后端安装命令
   - 重新检测
5. 全部通过才进入 WebView。

**网页段（后端已运行）**

1. 手机网络：自动读当前 `wlan0` IP，和 `public_host` 比较；不一致一键改用当前 IP。
2. 电视连接：扫描当前 `/24` 网段的 5555 端口，或手动输入；`adb connect`；处理 `unauthorized`。
3. 播放器：检查 `is.xyz.mpv`；没有就提示装 mpv-android。
4. 内容：搜 UP / 粘直播间链接；写 `source=single`、`rooms=[房间]`；允许“未开播”完成。
5. 完成：显示手机 IP / 电视 / 直播间。

每一步没过，“下一步”是灰的；所有修改都先备份旧 `config.json`。

**以后每次打开**

- 原生段快速检查 Android / Termux / Boot / 后端。
- 网页段调 `/api/setup/status`：
  - 全部通过 → 直接进主页，不再显示向导。
  - 哪一步失败 → 只弹那一步的修复卡片（可关闭，顶部会有“配置未完成”提示条）。
- 设置页有：
  - 手机网络 [修改]
  - 电视连接 [修改]
  - 电视播放器 [检查]
  - 当前直播间 [修改]
  - 重新初始化（换 WiFi / 换电视后用）

> 第一次装好后打开 App，即使安装脚本已经把配置写好了，也会让你按 5 步确认一遍（`setup_seen` 标记）；确认完以后就不再重复。

> 换 WiFi 后电视 IP 也变了：向导第 2 步会扫描当前网段，不用自己去电视上看 IP。

---

## 9. 故障排查

先跑 `./status.sh`，再对号入座。

| 症状 | 先看 | 原因与处置 |
|---|---|---|
| `sv: command not found` | `pkg install termux-services` | 装完重启 Termux（让 service-daemon 起来） |
| `./tvctl status` 显示 `down` | `tail -n 50 ~/tvstation/station.log` | Python 报错/端口被占/adb 不存在 |
| 服务反复重启 | `station.log` 里 `runit start` 连续出现 | 看上一轮异常；端口 8099 被占就改 `port` |
| 电视黑屏，日志有 `[调度]` | `dumpsys window \| grep mCurrentFocus` | 电视侧问题：mpv 没装、`am start` 失败、屏幕没亮 |
| `unauthorized` | `adb -s 电视IP:5555 get-state` | 电视上点允许；或删手机 `~/.android/adbkey*` 重连再授权 |
| `device offline` | `adb connect 电视IP:5555` | 电视休眠/断网；`tv_wake` 只在 adb 还能连上时有用 |
| 手机自己能 curl，电视连不上 | 手机是否开了 VPN/代理 | 关掉 VPN，或让 `192.168.x.x` 绕过代理 |
| 状态一直是 `[]` | `curl -s https://api.bilibili.com/x/web-interface/nav \| head -c 80` | 手机出不了网，或 B站风控 |
| 日志 502 | `curl -v "http://127.0.0.1:8099/video?bvid=..."` | CDN 节点 403/404；代码会自动换镜像，全挂就是 B站改规则 |
| 画面停住没声音 | `adb shell dumpsys media_session \| grep state=` | `state=2` 是暂停；`tv_resume` 会补 85；一直补不回来重启服务 |
| 手机 IP 变了 | `./status.sh` | 改 `config.json` 的 `public_host`；建议路由器 DHCP 绑定 |
| 电视 IP 变了 | `./status.sh` | 改 `config.json` 的 `tv`；建议 DHCP 绑定 |
| 跑几小时就停 | 看 Termux 是否被系统杀 | 电池优化、自启动、wakelock；不要 force-stop |
| 播 20 秒就断 | 日志 `[proxy] 原节点不可用` | 个别 CDN 节点没这份文件，代码已内置 5 个镜像回退 |
| 1080P 卡顿/花屏 | 手机 `top` / WiFi 信号 | 旧手机解码弱或带宽不够；这是 `qn=80` 单文件直链的固有风险 |
| 端口绑定失败 | 日志 `端口 8099 绑定失败` | 改 `config.json` 的 `port`，`./tvctl restart` |

几条展开：

**9.1 服务起不来。** 先看 `station.log` 最后 30 行。最常见：`adb: not found`（没装 `android-tools`）、`Address already in use`（端口被占）、`config.json` 格式错（用 `python3 -m json.tool config.json` 检查）。

**9.2 电视连不到手机。** 8099 是手机上的普通 App 端口，Android 不会拦；问题通常在路由器 AP 隔离、手机 WiFi 断了，或手机开了 VPN/代理。在电视浏览器打开 `http://手机IP:8099/api/status` 测试。

**9.3 手机被系统杀。** 这是旧手机方案最大的风险。表现是 `./tvctl status` 还能看到服务，但 8099 不响应；或者 Termux 通知消失。按 3.2 节设置，并在最近任务里锁定 Termux。

**9.4 重启后没自启。** 先确认手机解锁过；Android 7+ 的 `BOOT_COMPLETED` 要等首次解锁。然后确认 Termux:Boot 点开过、没被 force-stop。

**9.5 电视完全关机。** adb 连不上，`tv_wake` 也发不出去。这是硬限制；需要 HDMI-CEC/红外/电视定时开机。

---

## 10. 回滚

```bash
cd ~/tvstation
bash uninstall-termux.sh
```

它会停服务、删 runit 服务、删 `~/.termux/boot/00-tvstation`，并询问是否删除 `~/tvstation`。电视上的 mpv 不用动。

如果想彻底清理：

```bash
pkg uninstall termux-services     # 可选
# 再从 Android 设置里卸载 Termux:Boot / Termux
```

---

## 11. 代码与文件清单

| 文件 | 作用 |
|---|---|
| `station.py` | 主程序。原版 + Termux 补丁 + `single` 单直播间模式 |
| `control.py` | 控制 API + 网页，监听 `127.0.0.1:8098` |
| `web/index.html` | 手机 App/浏览器界面（搜索、切换、设置、日志） |
| `web/setup.js` | 首次配置/健康检查向导 |
| `web/setup.css` | 向导样式 |
| `config.example.json` | 配置模板；`install-termux.sh` 会生成 `config.json` |
| `install-termux.sh` | 一键安装：问答 → 写配置 → 查 adb → 装 runit 服务 → 装 boot 脚本 → 自检 |
| `set-content.sh` | 交互式改“播什么”：直播间号/链接、合集链接，改完自动重启 |
| `新手搭建.md` | 从装 Termux 开始的小白逐步说明 |
| `APP设计反馈.md` | 把轮播台做成 App 的三种路线、需要确认的细节 |
| `tvctl` | `start/stop/restart/status/log/wake`，替代 `systemctl` |
| `mode.sh` | 切 `live`/`video`，改 `config.json` 后 `sv restart` |
| `status.sh` | 服务/配置/日志/代理/电视前台窗口 |
| `run.sh` | 不走 runit 的手动后台启动，调试用 |
| `uninstall-termux.sh` | 卸载 |
| `templates/service-run` | tvstation 的 runit 服务脚本（`@HOME@` 由安装脚本替换） |
| `templates/control-run` | tvcontrol 的 runit 服务脚本 |
| `templates/boot-start` | Termux:Boot 脚本（`@HOME@` 由安装脚本替换） |

### 11.1 `station.py` 的三处补丁

**补丁 1：`mpv_running()` 改用 `pidof`**

```python
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
```

**补丁 2：`tv_wake()` + 在 `tv_play()` 里先唤醒**

```python
def tv_wake():
    """把电视从熄屏/屏保叫醒，再让 mpv 接管。"""
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
    ...
```

**补丁 3：`main()` 先 `adb start-server`**

```python
def main():
    start_proxy()
    try:
        subprocess.run(["adb", "start-server"], timeout=20,
                       capture_output=True, text=True)
    except Exception as exc:
        log("[adb] start-server 失败：%s" % str(exc)[:60])
    adb_connect()
    ...
```

> 完整文件就是 `termux-port/station.py`，不用手工改；安装时直接用它。

### 11.2 `single` 单直播间模式

`station.py` 新增了 `run_single()`：

- 只盯 `rooms[0]`，不按 30 秒轮换。
- 开播 → `tv_wake` → `am start` → 补播放键。
- 下播 → 连续 3 次（约 30 秒）确认后 `force-stop`，等待。
- 播放器退出 → 重新拉起。
- 控制服务写 `source=single`、`rooms=[房间号]` 后重启 `tvstation`，就完成切换。

> 这样既满足“一次只播一个直播间”，又避免旧 `live` 模式在单房间时每 30 秒重开一次。

---

## 12. 边界与待确认

### 12.1 已确认

- 手机 Android 7.0–11：Termux 完整支持。
- “录播”= B站合集/列表在线视频：当前 `video` 模式正好对上，不需要改代码。
- 长期插电 + 开机自启：用 Termux:Boot + runit + wakelock。

### 12.2 需要你确认/注意

1. **“唤醒电脑”到底唤的是谁？** 我按“用 adb 唤醒电视”实现。如果你要的是用 adb 唤醒一台**电脑**，那做不到，需要 Wake-on-LAN（网卡魔术包），这是另一套代码；告诉我，我再补。
2. **电视完全关机时唤不醒。** adb 依赖电视的网络待机；深度待机/断电时只能靠 HDMI-CEC/红外/定时开机。
3. **旧手机长期插电的电池安全。** 建议通风、定期检查电池；能拆电池的机型可以考虑拆电池供电。
4. **锁屏会导致重启后需手动解锁一次。** Android 7+ 的 `BOOT_COMPLETED` 在首次解锁后才发；想完全无人值守只能不设锁屏。
5. **手机 IP 必须固定。** 最稳的是路由器 DHCP 绑定；否则 IP 一变，电视就连不上 8099。
6. **B站接口随时可能变。** 这是非官方接口，`wbi` 签名/风控/CDN 防盗链一变就可能失效，不是迁移能解决的。

### 12.3 合规

只供家庭内部观看。不要对外分发、不要商用、不要把 8099 暴露到公网。
