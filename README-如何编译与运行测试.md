# 三代原生 App · :core 纯 JVM 骨架 —— 如何编译与运行测试

> 本阶段（K 计划 S1）只做**不依赖 Android 的内核**：播放状态机 + 引擎（Effect→定时器）+ 手写 HTTP 代理 + adb 能力层 + 测试替身 + 单元测试。
> 不需要 Android Studio、不需要 SDK、不需要 AVD、不需要电视。全部在本机 JDK 17 上以 2 分钟内跑完（170 条测试）。

> **📌 阅读提示（2026-11-13 整理）**：本文是**开发过程记录**（按「本轮新增」逐段追加）。§2 命令、§4 与 §7–§10 的设计说明仍然有效；但文中出现的测试条数（如 33 / 42 / 66 / 80 / 91 / 117 / … 以及「合计 N 条」）都是**当时的历史快照**，当前实际为 **170 条**。最新、权威的现状与文件结构见根目录 [README.md](README.md) 与 [HANDOFF.md](HANDOFF.md)。

---

## 1. 目录结构

> 说明：根目录只保留「项目文档 + 构建脚本 + 内核模块 + 参考资料」四类；构建产物目录（`core/build/`、`.gradle/`、`.kotlin/`）可再生成，已由 `.gitignore` 忽略，不纳入版本管理。

```
B站直播遥控器app开发/
├── README-如何编译与运行测试.md   # 本文：编译/测试入口与内核说明
├── H-技术方案-安卓播控App.md      # 权威需求与设计（FR/NFR/里程碑/验收线）
├── I-选型评审-三代原生App技术栈.md # 技术栈选型评审（对比矩阵 + ADR）
├── J-审校记录-H方案.md            # H 方案审校记录（P0/P1/P2 问题清单）
├── K-实施阶段划分-三代原生App.md  # 实施阶段划分与阶段计划
├── K1-事实清单-全文件盘点.md      # 全文件盘点与事实底稿（K 的取证依据）
├── .gitignore                     # 忽略构建产物 / IDE / 操作系统 / 临时分析目录
├── settings.gradle.kts            # 根工程 tvrc，含 :core
├── build.gradle.kts               # Kotlin 2.0.21 插件走 buildscript classpath（不依赖 plugin portal）
├── gradle.properties              # 固定 org.gradle.java.home=/opt/homebrew/opt/openjdk@17
├── gradlew / gradlew.bat          # Gradle wrapper 启动脚本（wrapper 指向国内镜像）
├── gradle/wrapper/                # wrapper jar + properties
├── tools/
│   ├── env-jdk17.sh               # source 一下，JAVA_HOME 指向 JDK 17
│   └── gradle-8.10.2/             # 本地 Gradle 发行版（已下载，可直接用）
├── core/                          # 纯 JVM 内核模块（本项目当前主体，明细见 §1.1）
├── ui参考/                        # 静态 UI 示意（非可运行代码）
│   ├── index.html                 # 7 屏主界面骨架示意
│   └── setup.html                 # 5 步配置向导示意
├── 全流程调试手册/                # 从零部署到可用的八层排障手册
│   ├── 全流程调试手册.md
│   └── docs/images/               # 5 张配图（arch / termux / layers / app-setup / wizard-flow）
└── 相关文件/                      # 二代实现参考（同源存档，非本项目编译产物）
    ├── termux-port/               # 二代 Termux 控制面/媒体面脚本与 Web 控制台（19 文件）
    ├── android-app/               # 二代 Android 薄壳源码（16 文件）
    ├── TVStationRemote-0.2.0.apk  # 二代成品 APK
    ├── termux-port.zip            # termux-port 打包存档
    └── android-app-src.zip        # android-app 源码打包存档
```

### 1.1 core 源码明细

```
core/
├── build.gradle.kts
└── src/
    ├── main/kotlin/tvrc/core/
    │   ├── Model.kt         # Session / Mode / 五态 / Event / Effect / 常量
    │   ├── Reducer.kt       # PlaybackReducer.reduce 纯函数
    │   ├── TvController.kt  # 电视控制接口 + Ack / TvStatus
    │   ├── engine/
    │   │   ├── Scheduler.kt      # Scheduler / Clock 抽象 + RealScheduler
    │   │   └── PlaybackEngine.kt # Effect→设备/定时器；回执→Event
    │   ├── http/
    │   │   ├── HttpCore.kt       # 请求解析 + ByteRange
    │   │   ├── Upstream.kt       # 上游取流 + CDN_ONLY 证书策略
    │   │   └── MiniHttpServer.kt  # /status /live /video 手写代理
    │   ├── json/
    │   │   └── Json.kt            # 手写最小 JSON 解析器
    │   ├── bili/
    │   │   ├── SourceParser.kt     # 用户输入 → Source
    │   │   └── BiliLiveApi.kt      # 直播信息/FLV 直链 + Session 组装
    │   └── adb/
    │       ├── AdbClient.kt      # shell 接口 + 超时异常
    │       ├── AdbKeyStore.kt    # RSA 密钥 + ADB 公钥 wire 格式
    │       └── AdbTransport.kt   # 串行/超时/重连/存活判定
    └── test/kotlin/tvrc/core/test/
        ├── FakeTv.kt / FakeAdbClient.kt / FakeUpstream.kt
        ├── VirtualTime.kt             # 虚拟时钟 + 虚拟调度器
        ├── PlaybackReducerTest.kt     # 19 条
        ├── PlaybackEngineTest.kt      # 8 条
        ├── MiniHttpServerTest.kt      # 7 条
        ├── AdbTransportTest.kt        # 8 条
        ├── JsonTest.kt                # 6 条
        ├── SourceParserTest.kt        # 10 条
        └── BiliLiveApiTest.kt         # 8 条
```

### 1.2 项目文档索引

| 文档 / 目录 | 作用 | 何时看 |
| --- | --- | --- |
| `README-如何编译与运行测试.md`（本文） | 编译、运行测试与内核说明 | 想跑起来 / 改核心代码 |
| `H-技术方案-安卓播控App.md` | 权威需求与设计：FR、NFR、M0–M5 里程碑、验收线 | 需求变更 / 验收对照 |
| `I-选型评审-三代原生App技术栈.md` | 技术栈选型对比与 ADR | 技术选型 / 替换方案 |
| `J-审校记录-H方案.md` | H 方案审校记录与问题闭环 | 追溯修订依据 |
| `K-实施阶段划分-三代原生App.md` | 实施阶段划分与阶段计划 | 排期 / 阶段目标 |
| `K1-事实清单-全文件盘点.md` | 全文件盘点与事实底稿 | 查文件作用 / 取证 |
| `全流程调试手册/` | 从零部署到可用的八层排障手册 | 真机联调 / 排障 |
| `相关文件/` | 二代实现参考（Termux 脚本 + Android 薄壳 + 成品 APK） | 迁移对照 / 复用参考 |
| `ui参考/` | 静态 UI 示意 | 界面设计参考 |


---

## 2. 一条命令跑测试

```bash
cd "/Users/lzl/E/code/B站直播遥控器app开发"

# 方案 A：用仓库内自带的 Gradle（已验证可用，不联网下载）
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/gradle-8.10.2/bin/gradle :core:test --no-daemon --console=plain

# 方案 B：先 source 环境脚本，再用 wrapper
source tools/env-jdk17.sh
./gradlew :core:test
```

> **当前结果（本机实测）**：**170 个测试全部通过**，`BUILD SUCCESSFUL`；首次需下 Kotlin 编译器（约 2 分钟），之后增量重跑约 **6–10 秒**。
> **只会下 Gradle 发行版一次**（约 130 MB，走 mirrors.huaweicloud.com），之后全离线。
> **磁盘**：Gradle 发行版 130 MB + 依赖缓存约 300–400 MB，远小于 17 GB 余量。

---

## 3. JDK 17 怎么用（关键坑）

本机已装 **OpenJDK 17.0.15**：`/opt/homebrew/opt/openjdk@17`（306 MB）。

```bash
source tools/env-jdk17.sh      # 输出 JAVA_HOME=.../openjdk@17 且 java -version 为 17.0.15
```

**坑**：`/usr/libexec/java_home -v 17` 会错误地返回 JDK 24（Homebrew 版没注册进系统 JVM 目录）。
所以本工程**不靠 java_home**，而是在 `gradle.properties` 里写死：

```properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@17
```

这样无论你当前 shell 的 JAVA_HOME 是什么，Gradle 编译/测试都用 17，**不会影响你日常用 JDK 24 的其它项目**。

---

## 4. 状态机做了什么（对照 H 方案四条 P0 修正）

| 状态 | 含义 |
|---|---|
| `Idle` | 空闲，无播放任务 |
| `Starting` | 已下发唤醒+起播，等电视回执 |
| `Playing` | 电视确认在播，到点换台 |
| `Switching` | 换台中，等回执 |
| `Error` | **终态**，看门狗不得拉起 |

已落地的硬规则：

- **连不上电视**：最多尝试 3 次（2s、4s 退避），仍失败 → `Error(TV_UNREACHABLE)` + 给用户一句人话原因。
- **播放中连续掉线**：探测 `alive=false` 连续 3 次（5s 一次）→ 判定 `TV_LOST` → 自动换下一项，而不是死等。
- **用户主动停止**：进 `Idle(USER_STOP)`，之后空闲超时/看门狗**不产生任何副作用**，绝不「关了又自己开」。
- **播放中不响应空闲超时**：`Playing + IdleTimeout` 无副作用（FR-10 只对"无播放任务"生效）。
- **异常自动恢复**：仅 `TV_UNREACHABLE` / `PLAY_FAILED` 可自动恢复，上限 3 次、冷却 60 秒；用尽后转终态等用户手动重试。
- **换台间隔**：非法值（如 30 秒）自动收敛到 60..86400 区间，不让 App 崩。

---

## 5. 测试覆盖清单（早期快照合计 91 条，当前 170——逐项明细仅示意分类：状态机 19 + 引擎 8 + 代理 7 + adb 8 + JSON 6 + 输入解析 10 + 直播 API 8 + ADB 协议 14 + 电视控制器 11 + 媒体会话判定 7 + 网段重扫 6 + wbi 签名 7 + 合集列表 6）

| # | 用例 | 保障的行为 |
|---|---|---|
| 1 | startFromIdle | 空闲点开始 → Starting，先 wake 再 launch |
| 2 | emptySession | 空内容 → NO_CONTENT 错误，不下发命令 |
| 3 | ackOk | 回执 ok → Playing，nextSwitchAt 正确 |
| 4 | threeStartFailures | 3 次失败 → 放弃并进 TV_UNREACHABLE |
| 5 | tickBeforeDeadline | 未到点不换台、无副作用 |
| 6 | tickAtDeadline | 到点换台 → Switching + launch 下一项 |
| 7 | threeMisses | 连续 3 次掉线 → TV_LOST 换台 |
| 8 | aliveResetsMiss | 恢复在线 → missCount 归零 |
| 9 | switchingAckOk | 换台回执 ok → Playing 新索引 |
| 10 | switchingAckFail | 换台失败 → PLAY_FAILED + 60s 冷却 |
| 11 | userStop | 用户停止 → Idle，看门狗无法拉起 |
| 12 | retryInCooldown | 冷却期内手动重试被拦并提示 |
| 13 | autoRetry | 自动恢复计数 +1，且重新下发命令 |
| 14 | autoRetryExhausted | 恢复次数用尽 → 终态不再拉起 |
| 15 | noContentNotRecoverable | NO_CONTENT 不参与自动恢复 |
| 16 | switchSecondsClamped | 30 秒被收敛到 60 秒 |
| 17 | wrapAround | 末项换台回到第一项 |
| 18 | fakeTv | 校验替身本身记录命令与注入失败 |

---

## 6. 下一步（S1 剩余 / S2 入口）

- `TvController` 的 **adb 实现**（dadb / 打包 adb），接 `TvAck` / `TvReport` 真实回执（接口已定，换实现即可）；
- `SourceParser`（接二代 `parse_input` 的口径）+ `Session` 组装；
- 然后才进 Android `:app`（Compose UI + 前台服务 + BootReceiver）。


---

## 7. 本轮新增：MiniHttpServer + AdbTransport（15 条新测试，合计 33 条）

### 7.1 MiniHttpServer（手写 HTTP 代理，7 条真 socket 测试）

`core/src/main/kotlin/tvrc/core/http/`

| 文件 | 内容 |
|---|---|
| `HttpCore.kt` | 请求头解析 `HttpParsing.readRequest`、query 解析、`ByteRange.parse`（`bytes=a-b` / `a-` / `-N`） |
| `Upstream.kt` | `UpstreamFetcher` 接口 + `HttpUrlConnectionFetcher`；`TlsPolicy.CDN_ONLY` 只对 B站 CDN 域放开证书校验（F-09）；`UpstreamHeaders` 带 Referer/UA |
| `MiniHttpServer.kt` | `/status`（schemaVersion + capabilities）、`/live`（**绝不带 Range**）、`/video`（透传 Range；上游返 200 全量时**本地裁剪成 206**）、镜像只对 `/video`、`Connection: close`、64KB 逐块转发 |

测试要点（`MiniHttpServerTest`，全部走真实 TCP + 真实 HTTP 报文）：
1. `/status` 返回 `"schemaVersion":1` 与 `capabilities`；
2. `/live` 即使客户端带 Range 也不向上游转发，且不走镜像；
3. `/video` 透传 Range，206 与 `Content-Range` 原样返回；
4. 上游无视 Range 返 200 全量 → 本地裁剪成 `bytes 10-19/100`；
5. 未知路径 404、POST 405；
6. 256KB 大 body 完整无截断；
7. `/video` 主源 404 → 镜像回退成功。

### 7.2 AdbTransport（adb 能力层，8 条单测）

`core/src/main/kotlin/tvrc/core/adb/`

| 文件 | 内容 |
|---|---|
| `AdbClient.kt` | `ShellResult(exitCode,stdout,stderr)`、`AdbTimeoutException`、`interface AdbClient{connect(); shell(cmd,timeout)}` |
| `AdbKeyStore.kt` | RSA 私钥 PKCS#8 持久化 + 文件权限 600（F-02）；`adbPublicKeyBlob` 生成 ADB 公钥 wire 格式（524 字节结构 + name\0） |
| `AdbTransport.kt` | 公平锁串行 + 每条命令带超时（F-11）、超时不重试、IO 断线重连一次、拒绝 `start-server`/`adb connect`（F-01）、`am start` 转义 `&` `?`（F-05）、`pidof→ps -A→ps\|grep` 三级存活回退（F-04）、`replenishPlayState` 补 keyevent 85 ×3（F-03） |

测试要点（`AdbTransportTest`）：
1. 8 线程并发 → 最大并发恒为 1（串行）；
2. 超时不重连、之后仍可正常执行；
3. IO 断线 → 重连一次 → 同一命令重试成功（connectCalls=2）；
4. `adb start-server` / `adb connect` 直接被拒（IllegalArgumentException）；
5. `am start` 的 URL 中 `?` `&` 被反斜杠转义；
6. 存活判定按 pidof→ps -A→ps|grep 依次回退（3 条命令）；
7. 全空 → 判定不在播；
8. 密钥两次加载同一把 + 公钥 blob 长度/结构/Base64 往返正确。

### 7.3 仍未做（需要真机/AVD 才能验的 L2）

- `AdbClient` 的**真实实现**（dadb 直连 5555，替换 FakeAdbClient）——接口已定，换实现即可；
- 代理接 B站 真实流地址与签名（S2）；
- Android `:app`：Compose UI + `PlaybackService`（specialUse 前台服务）+ BootReceiver。


---

## 8. 本轮新增：PlaybackEngine（8 条引擎测试，合计 42 条）

### 8.1 它解决什么

状态机是纯函数，只产出 `Effect` 列表；`PlaybackEngine` 把 `Effect` 落到真实世界，再把设备回执与定时到点变成 `Event` 灌回状态机：

| Effect | 引擎动作 | 回灌的 Event |
|---|---|---|
| `WakeTv` | `tv.wake()` | 失败才发 `TvAck(false)`（成功则等 Launch 回执，避免重复 ack） |
| `Launch` | `tv.launch(target)` | `TvAck(ok, detail)` |
| `StopTv` | `tv.stop()` | 无 |
| `ProbeTv(afterMs)` | 定时到点后 `tv.probe()` | `TvReport(alive, playing)` |
| `ScheduleSwitch(afterMs)` | 定时到点 | `Tick` |
| `ScheduleRetry(afterMs)` | 定时到点 | `RetryDue` |
| `CancelTimers` | `scheduler.cancelAll()` + 取消空闲定时器 | 无 |
| `Notify(state)` | `onState(state)` + 挂/取消空闲超时 | 无 |
| `UserMessage(code,text)` | `onMessage(code,text)`（给 UI 的人话原因） | 无 |
| `Log(level,msg)` | `logger(...)` | 无 |

状态改动全部在同一把锁内完成（`synchronized` 可重入），定时器线程与调用线程不会把状态机改乱。

### 8.2 两个可替换点（为什么这样切）

- `Scheduler`：生产用 `RealScheduler`（单线程 daemon `ScheduledExecutorService`）；Android 上换成 `Handler.postDelayed` 即可，接口不变。
- `Clock`：生产用 `SystemClock`；测试用 `VirtualClock`。

测试里 `VirtualScheduler.advanceBy(60_000)` 直接「拨表」，**0 真等待、0 抖动**，8 条引擎测试总共 0.04 秒跑完。

### 8.3 引擎测试（PlaybackEngineTest，8 条）

1. `startReachesPlayingWithExpectedCommands`：点开始 → 命令严格 `[wake, launch:a]`，状态通知先 Starting 后 Playing；
2. `switchTimerMovesToNextItem`：拨 60 秒 → 自动换到第 2 项（`launch:b`）；
3. `threeMissesTriggerTvLostSwitch`：连续 3 次 `alive=false` → `TV_LOST` 换台，missCount 依次 1→2→换台；
4. `healthyProbeKeepsPlayingAndReschedules`：健康时探测链不断（15 秒内 ≥3 次）、missCount=0；
5. `userStopCancelsAllTimers`：停止 → Idle、下发 stop、之后拨 200 秒**再无任何命令**、待执行定时器为 0；
6. `idleTimeoutFiresOnce`：`idleTimeoutMs=1000` 的空闲回调只触发一次；
7. `retryRespectsCooldown`：3 次起播失败 → Error(TV_UNREACHABLE)；冷却期内手动重试被拦（COOLDOWN）；拨过 60 秒后重试恢复 Playing；
8. `switchingFailureEntersPlayFailedError`：换台起播失败 → Error(PLAY_FAILED) 并给用户提示。

> 设计说明：`attempt` 表示「刚做过的第几次」。`attempt = MAX` 时仍要再发一次（第 MAX 次）起播，真正放弃由回执失败分支（`attempt < MAX` 不成立）判定——所以 `RetryDue` 的兜底条件是 `attempt > MAX`，不是 `>=`。

### 8.4 用法示例

```kotlin
val engine = PlaybackEngine(
    tv = adbTvController,          // :app 里注入真实实现
    scheduler = RealScheduler(),
    idleTimeoutMs = 0L,            // 需要 FR-10 空闲自停时给非 0
    onState = { ui.render(it) },
    onMessage = { code, text -> ui.toast(text) },
    onIdleShutdown = { service.stopSelf() }
)
engine.start(session)   // 等价于用户点「开始」
engine.stop()           // 用户点「停止」
engine.retry()          // Error 后用户点「重试」
```

---

## 9. 本轮新增：SourceParser + B站直播 API + 最小 JSON（24 条测试，合计 66 条）

### 9.1 三个新文件

| 文件 | 内容 |
|---|---|
| core/src/main/kotlin/tvrc/core/json/Json.kt | 手写最小 JSON 解析器（零依赖）：JsonValue（Obj/Arr/Str/Num/Bool/Null）+ 递归下降 Json.parse；取值扩展 field(name) / strOrNull() / longOrNull() / arrOrNull() 全部定义在 JsonValue? 上，链式取值不会 NPE |
| core/src/main/kotlin/tvrc/core/bili/SourceParser.kt | 用户输入 → Source：LiveRoom / Video(BV) / Series(mid,listId) / Season(mid,seasonId) / Up(mid) / ShortLink(b23.tv) / Unknown。规则逐条对齐二代 control.py:257 parse_input（F-09） |
| core/src/main/kotlin/tvrc/core/bili/BiliLiveApi.kt | HttpText（可替换 GET 文本层）+ UrlConnectionHttpText；BiliLiveApi{roomInfo, flvStream} + RealBiliLiveApi；RoomInfo / FlvStream / BiliApiException；LiveSessions.liveSession()/singleSession() 把房间号拼成 http://{host}:8100/live?room={roomId}（与二代 station.py 一致）并装成 Session |

### 9.2 端点口径（与二代 station.py 一致）

- GET https://api.live.bilibili.com/room/v1/Room/get_info?room_id={id} → code==0，取 data.live_status（1=直播中）、data.title、data.online
- GET https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id={id}&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8 → 遍历 data.playurl_info.playurl.stream[].format[].codec[]，只取 protocol_name=http_stream && format_name=flv && codec_name=avc，直链 = url_info[0].host + codec.base_url + url_info[0].extra
- 统一请求头 User-Agent + Referer: https://live.bilibili.com/；code!=0 或结构缺失一律抛 BiliApiException（上层转成 ErrorKind 的用户人话）

### 9.3 输入解析速查（SourceParser）

| 输入 | 结果 |
|---|---|
| 5017134 | LiveRoom(5017134) |
| https://live.bilibili.com/123、.../blanc/456?foo=1 | LiveRoom(123) / LiveRoom(456) |
| https://b23.tv/abcDEF123 | ShortLink(...)（只识别，展开留 S2） |
| BV1xx411c7mD、.../video/BV... | Video(bvid) |
| space.bilibili.com/{mid}/lists/{id} | Series(mid, id) |
| space.bilibili.com/{mid}/channel/collectiondetail?sid={sid} | Season(mid, sid) |
| space.bilibili.com/{mid} | Up(mid) |
| 空串 / 乱输 | Unknown |

> 已容错：首尾空白、零宽空格、链接夹在一句话中间。合集/UP/BV 当前只做「识别 + 引导」（与 H 方案 FR-09 一致），展开在第二版。

### 9.4 测试（24 条）

- JsonTest 6 条：平铺对象、嵌套数组对象、字符串转义、数字/布尔/null、任意空白、畸形输入抛 JsonException
- SourceParserTest 10 条：上表每一行 + 零宽空格/整句包含
- BiliLiveApiTest 8 条：roomInfo 直播中/未开播/接口报错、flvStream 正确挑 http_stream+flv+avc 并跳过 hls/hev、无匹配抛错、非 JSON 抛错、liveSession 目标 URL、singleSession 单房间模式

> 全部走 FakeHttpText 回放固定 JSON，不联网、不依赖 B站真实接口。
---

## 10. 本轮新增：dadb 真实现（ADB 二进制协议，14 条测试，合计 80 条）

### 10.1 为什么不用 dadb 库

选型评审（I 文件）的兜底顺序是 dadb → 打包 adb 二进制 → libadb-android。真写下来发现协议本身很小（握手 + 6 种帧），自己实现反而更可控：无第三方依赖、无 AAR、能在纯 JVM 里用进程内假设备 100% 测完。共享代码仍按 AGPL 风险规避处理——只参考协议规范，不拷贝任何库代码。

### 10.2 三个新文件

| 文件 | 内容 |
|---|---|
| core/src/main/kotlin/tvrc/core/adb/AdbPacket.kt | ADB 帧编解码：24 字节头（command/arg0/arg1/length/crc32/magic，全小端）+ payload；magic = command 按位取反；读时校验 magic、CRC32、长度上限 1 MB；常量 CNXN/AUTH/OPEN/OKAY/WRTE/CLSE、AUTH_TOKEN/SIGNATURE/RSAPUBLICKEY、A_VERSION=0x01000000、MAX_DATA=256KB |
| core/src/main/kotlin/tvrc/core/adb/AdbAuth.kt | AUTH 签名：SHA1withRSA（PKCS#1 v1.5），2048 位密钥定长 256 字节，不足左补零 |
| core/src/main/kotlin/tvrc/core/adb/DadbClient.kt | 实现既有 AdbClient 接口：connect() 直连 5555 走 CNXN → AUTH(token→signature→必要时 RSAPUBLICKEY) → CNXN；shell() 走 OPEN("shell:cmd\0") → OKAY → WRTE… → CLSE，逐帧回 OKAY；shellWithExitCode() 用 echo 标记取退出码；超时抛 AdbTimeoutException（不重试） |

### 10.3 握手与命令时序

```
client                          device:5555
  ---- CNXN(version=0x01000000, maxdata=256KB, "host::tvrc\0") ---->
  <--- AUTH(TOKEN, 20 字节 token) -------------------------------   需要授权时
  ---- AUTH(SIGNATURE, SHA1withRSA(token), 256B) ---------------->
  <--- AUTH(TOKEN, 新 token) 或 CNXN ---------------------------   仍未授权则再要一次
  ---- AUTH(RSAPUBLICKEY, 524B blob + 名字 + \0) --------------->   电视弹出「允许调试」
  <--- CNXN ----------------------------------------------------   授权完成

  ---- OPEN(localId, 0, "shell:echo hi\0") --------------------->
  <--- OKAY(localId, remoteId) ---------------------------------
  <--- WRTE(remoteId, localId, 数据) ---------------------------  (可多帧)
  ---- OKAY(localId, remoteId) --------------------------------->   逐帧确认
  <--- CLSE(remoteId, localId) ---------------------------------   结束
```

### 10.4 测试怎么做到「不用真机」（14 条）

- `FakeAdbServer`（测试专用）：`ServerSocket(0,1,127.0.0.1)` 起一个假设备，脚本化按协议回帧；读写共用同一对缓冲流，避免二次包装吃字节；脚本里抛的异常由 `stopAndCheck()` 冒出来。
- `AdbPacketTest` 6 条：帧往返、空 payload、magic 错拒收、CRC 错拒收、超长 length 拒收、CRC32 已知值 0xCBF43926。
- `DadbClientTest` 8 条：无授权直接 CNXN；**签名能被设备公钥验过**；第二次 token 回退发公钥（524B blob + 名字）；shell 收集多帧 WRTE 到 CLSE；`shellWithExitCode` 解析 `__TVRC_EXIT__3`；无响应 200ms 抛 `AdbTimeoutException`；未 connect 调 shell 抛 IOException；密钥持久化后再次加载是同一把。

### 10.5 已知边界（写清不装作有）

- 只实现 legacy shell 服务：**没有退出码**（`shell()` 固定 0），要退出码用 `shellWithExitCode()`（命令后追加 echo 标记）。
- 未实现 shell_v2（stdout/stderr 分离、窗口尺寸）、SYNC（push/pull）、端口转发、多路复用多流同时使用。当前每次 shell 一条流、串行使用，够播放控制用。
- 真实设备首次连接会弹「允许 USB 调试」，需要电视侧点一次；若电视关掉「ADB 授权确认」则可免。这一步只能在有设备时验（属 R 轮次）。
- 未做 USB/TCP 切换、mDNS 发现；地址由配置给（`host:5555`）。

### 16. P2-4：代理真流验收（tools/verify-proxy.sh，测试仍 120 条）

脚本链路：解析 B站直播/点播直链 → gradle :core:runProxy 起 MiniHttpServer → ffprobe /live?url=…（2 条流）→ /video?url=… Range 0-1023 得 206 + 1024B → ffmpeg -t 8 -f null - 双流解码 → 磁盘红线检查。**实测发现并修复**：UpstreamHeaders 原 Android 移动 UA 被 live CDN 403（552B），改桌面 Chrome UA（DESKTOP_UA）后 200。路由口径已统一（2026-11-13）：生产路由 /live?room=<房间号>（别名 /live.flv）、/video?bvid=<BV号>、/status 与 /api/status；?url= 只在 allowDirectUrl=true 的回环自测模式可用（verify-proxy.sh 走它）。

### 17. P3-1 配置层（Config.kt，测试 128 条）

- 文件：core/src/main/kotlin/tvrc/core/config/Config.kt
- AppConfig：schemaVersion / source / rooms / listMid / listId / pickCount / switchSeconds / statusSeconds / port / tvHost / tvPort / publicHost / mpvPkg / mpvActivity / wizardCompleted / idleTimeoutMinutes + extra（未知键原样保留，迁移不丢字段）
- ConfigValidator.check()：12 条规则（source 合法、live 必填 rooms、series 必填 list_mid+list_id、switch 60–86400、pick 1–50、status 3–600、port 1024–65535 且拒绝 8099、tv 非空、idle 0–1440）
- JsonConfigRepository：load() → Ok(config, check) / Missing / Invalid；loadOrDefaults() 坏文件降级默认并在 notes 里给出原因；save() 先校验，非法不落盘；FsConfigStore 原子写（.tmp + renameTo，失败回退直写）
- 默认值：switchSeconds=1800（FR-05 默认 30 分钟；二代现场 30 秒由 P3-2 迁移照抄文件值）、port=8100、idleTimeoutMinutes=0
- Json.kt 增补 write()/escape()（配置序列化，稳定可 diff）

### 18. P3-2 二代配置迁移（LegacyConfigMigrator.kt，测试 134 条）

- migrateText(json) / migrate(AppConfig)：字段同名直搬；setup_seen → wizard_completed；control_port 忽略但保留在 extra；port 8099 → 8100；非法值收敛（switch/pick/status 夹到合法区间并记 note）；幂等（二次迁移零 note、配置相等）
- 裁决：MIN_SWITCH_SECONDS 60 → 30（二代现场 30 秒已验证稳定，三代「配置直接用」必须承载；默认仍 1800，向导推荐 60–86400）

### 15. P2-2：BiliArchiveApi（6 条测试，合计 117 条）

系列/合集稿件列表：seriesPage(/x/series/archives) 与 seasonPage(/x/polymer/.../seasons_archives_list)、seriesAll/seasonAll 自动翻页（取满 total 或空页即停，maxPages 兜底）。ArchiveItem(bvid,title,cover,durationSec,pubdate)；缺 bvid 跳过、字段缺失降级。VideoTargets.target 组装 http://host:8100/video?bvid=<bvid>，LiveSessions.videoSession 转 Mode.VIDEO 轮播会话。

### 14. P2-1：WbiSigner（7 条测试，合计 111 条）

wbi 签名：MIXIN_KEY_ENC_TAB 重排取前 32 位 → 参数值过滤 !'()* → key 升序拼接 + wts → w_rid=md5(query+mixinKey)。固定向量由独立 Python hashlib 实现算出（非自证）。另含 WbiKeysParser（nav 响应→img/sub key）与 BuvidParser（b_3/b_4）。真请求验收脚本：tools/verify-wbi.sh（curl nav + python 签名 + getRoomPlayInfo，期望 code=0）。

### 13. P1-3：SubnetRescanner（6 条测试，合计 104 条）

电视 DHCP 换 IP 后按 /24 重扫：HostProbe 注入探测、SubnetRescanner.resolve 先信原址（Kept）、失败扫 .1–.254（升序、并发）、取第一个可达（Moved）、全不可达 NotFound；非 /24 直接抛异常，绝不误扫大网段。

### 12. P1-2：MediaSessionProbe（7 条测试，合计 98 条）

判定 dumpsys media_session 文本：会话头取包名、块内 state=3 视为播放中、同包多会话优先 playing、找不到目标包返回 null。取文本层是 MediaSessionTextSource（AdbMediaSessionSource 走 transport.shell），真实 ROM 格式差异列为 H-7 交接项。

### 11. P1-1：AdbTvController（11 条测试，合计 91 条）

TvController 的 adb 实现已就位：wake=keyevent 224、launch=am start（成功后 keyevent 85 补发最多 3 次，F-03）、stop=am force-stop、probe=pidof→ps -A→ps|grep 三级回退（F-04）。playing 字段留给 P1-2 的 MediaSessionProbe。测试用 FakeAdbClient 逐字断言命令行与顺序。

### 10.6 下一步（仍不需要手机/电视）

把 `AdbClient` 接成 `TvController`（`AdbTvController`）：`wake`=keyevent 224、`launch`=`am start -a VIEW -d <url> -n <mpv activity>`、`stop`=`am force-stop`、`probe`=`pidof` 三级回退。用现有 `FakeAdbClient` 就能写完并测完，等真机到位只需换实现注入。
---

## 13. AppGraph 装配层（一条链路跑起来）

之前所有零件（代理 / adb / 状态机 / 看门狗 / 环形日志）各自有测试，但没有一处把它们装在一起。现在补上 `core/src/main/kotlin/tvrc/core/AppGraph.kt`。

```kotlin
val graph = AppGraph.production(config, tv = AdbTvController(transport))
val port = graph.start()   // 起代理（config.port=0 时由系统分配并回填电视 URL）→ 起轮播 → 起看门狗巡检
graph.stop()               // 用户停止
graph.close()              // 释放线程与端口
```

装配内容：

| 依赖 | 进 AppGraph 的方式 | 说明 |
|---|---|---|
| `AppConfig` | 构造参数 | `port`、`rooms`、`switchSeconds`、`idleTimeoutMinutes` 直用 |
| `TvController` | 构造注入 | Android 侧传 `AdbTvController`，测试传 `FakeTv` |
| `BiliLiveApi` | 构造注入 | `/live?room=` 的解析源；失败回退上次成功的缓存，仍无则 502 |
| `VideoUrlResolver` | 构造注入 | `view → cid → playurl → durl[0].url`；同样失败回退缓存 |
| `UpstreamFetcher` | 构造注入 | 代理透传层，测试用 `FakeUpstream` |
| `Scheduler` / `Clock` | 构造注入 | 测试用 `VirtualScheduler` / `VirtualClock` |
| `RingLog` | 内部持有并暴露 | 引擎日志、状态变化、看门狗、直链解析失败都进环 |
| `Watchdog` | 内部接线 | 每 10s 巡检一次（`watchdogIntervalMs`），`Restart`→`engine.retry()`，`GiveUp`→停机 |

行为要点：

- **空闲超时**：`idleTimeoutMinutes>0` 且状态为 Idle 时，到点调用 `onIdleShutdown`，同时 `engine.stop()` + `server.close()`，`isRunning` 变 false。
- **端口 0**：`start()` 返回真实绑定端口，并据此重建 Session，电视收到的 URL 不会出现 `:0`。
- **看门狗只救「本该在播」**：`lastProgressMs` 只在进入 Playing 时刷新；Idle/Error 不触发重启（对齐 H 5.6）。
- 测试：`AppGraphTest` 5 条（起代理与起播命令、/status 与白名单/404/400、看门狗重启计数、空闲超时关代理、直链失败 502）+ `VideoUrlResolverTest` 3 条。

> `tools/ProxyMain.kt` 仍保留独立入口（只起代理、开 `allowDirectUrl`），供 `verify-proxy.sh` 做回环自测；AppGraph 是生产路径。
