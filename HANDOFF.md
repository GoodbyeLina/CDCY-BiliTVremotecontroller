# HANDOFF · 三代原生 App 交接说明（纯 JVM 部分已完成）

> 版本：v1.0（2026-11-13）· 对应 L 清单 P4-2
> 读这份文档的人：接手把三代做到电视上跑起来的人。
> 一句话现状：**逻辑层（:core）已完整可用、170 条测试全绿；Android 壳（:app）与真机验收尚未开始。**

---

## 1. 现在有什么、没有什么

| 有 | 没有 |
|---|---|
| `:core` 纯 Kotlin/JVM 模块：状态机、播放引擎、HTTP 代理、自研 ADB 协议、B站接口、配置、向导、看门狗、诊断包、错误文案 | `:app` Android 模块（Compose UI、前台服务、开机自启） |
| 170 条自动化测试（0 失败），含 7 条真 socket 代理测试、8 条自研 ADB 协议测试 | 任何一次真机/真电视运行记录 |
| 命令行自检：`tools/verify-on-tv.sh`、`tools/verify-proxy.sh` | APK 产物（`app/build/outputs/apk` 不存在） |
| 契约冻结（三个接口 + 版本常量） | 电视侧 mpv 侧载与实拉验证 |

三代整体口径：纯软件 17.2 / 37.0 人日（46%）；**剩余 19.8 人日全在设备依赖范围**（见 §6）。

---

## 2. 环境与构建命令（照抄即可）

```bash
cd "/Users/lzl/E/code/B站直播遥控器app开发"

# JDK 17（本机已存在，不在系统 JVM 注册表里，必须显式指定）
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # 17.0.15
# 或用仓库脚本：source tools/env-jdk17.sh

# Gradle 8.10.2（仓库内固定版本，不用 brew/系统 gradle）
tools/gradle-8.10.2/bin/gradle :core:cleanTest :core:test --no-daemon --console=plain

# 单独跑某个类
tools/gradle-8.10.2/bin/gradle :core:test --tests 'tvrc.core.test.PlaybackEngineTest' --no-daemon
```

- **回归基线 170 条**，只许增不许减（`TvrcVersion.MIN_TEST_COUNT` 与 `verify-on-tv.sh` 的 `regression_170` 都卡这条）。
- 不要改用户的全局 `JAVA_HOME`；工程用 `org.gradle.java.home` / 环境变量固定 17。
- 磁盘红线：仓库所在卷仅 ~17 GiB 可用。禁止把视频流写盘（一律 `-f null -`），禁止把 Gradle 发行版提交进 git（`tools/gradle-8.10.2/` 已 ignore）。

---

## 3. 模块边界

```
core/src/main/kotlin/tvrc/core/
├── Model.kt            五态（Idle/Starting/Playing/Switching/Error）+ Event/Effect/常量
├── Reducer.kt          PlaybackReducer.reduce(state,event) 纯函数，无 IO
├── TvController.kt     【契约】电视控制端口
├── TvrcVersion.kt      【契约】版本与回归基线常量
├── AppGraph.kt         装配根：配置+代理+adb+状态机+看门狗+环形日志（生产入口）
├── engine/
│   ├── PlaybackEngine.kt  把 Effect 落到设备/定时器，把回执变成 Event（唯一有状态的地方）
│   └── Scheduler.kt       【契约】定时器/时钟抽象 + RealScheduler
├── adb/                AdbClient【契约】/ AdbPacket / AdbAuth / AdbKeyStore / AdbTransport /
│                       DadbClient（自研协议实现）/ AdbTvController（TvController 的 adb 实现）
├── http/               HttpCore / Upstream(UA+RANGE) / MiniHttpServer（/status /live /video）
├── bili/               SourceParser / SourceResolver / BiliLiveApi / BiliArchiveApi /
│                       WbiSigner（wbi 签名）/ VideoUrlResolver（点播 view→durl 直链）
├── config/             Config.kt（AppConfig + ConfigValidator + ConfigStore/FsConfigStore）/ LegacyConfigMigrator.kt
├── wizard/             Wizard.kt（5 步状态机 + 草稿持久化）
├── diag/               Watchdog / ErrorCatalog / Diagnostics(RingLog+ZIP 诊断包)
├── media/              MediaSessionProbe（dumpsys media_session 文本 → 是否在播）
├── net/                SubnetScanner（/24 IP 漂移重扫）
├── json/Json.kt        手写零依赖 JSON
└── tools/              ProxyMain / ConfigCheckMain（命令行入口）
```

依赖方向：`tools → (engine|http|config|wizard|diag) → Model/Json`；`engine` 只依赖 `TvController` + `Scheduler`，**不依赖 adb/http**。加 Android 壳时只需要实现 `TvController`（内部用 `DadbClient`）与 `Scheduler`（Handler 版），其余原样复用。

---

## 4. 契约冻结（P4-3，core API v2）

2026-11-23：APP 版本标记统一为 `3.0.0-alpha.6`（versionCode 8）。新增媒体传输观察回调、安全有限请求重试及恢复边界；三项冻结接口与配置 schema 均未变更，CORE_API / CONFIG_SCHEMA 保持 1。此文前部为历史基线，当前进展以 README 与 docs/dev/2026-11-23-stability.md 为准。

| 接口 | 位置 | 实现方必须遵守 |
|---|---|---|
| `TvController` | `core/.../TvController.kt:13` | 不抛异常（用 `Ack.ok=false`）；单次调用 ≤10s；`probe()` 必须区分 alive 与 playing；`launch(target)` 收完整 intent 目标；顺序必须是 connect→wake |
| `AdbClient` | `core/.../adb/AdbClient.kt:18` | 不起 adb server、不 `adb connect`，直连 5555；`connect()` 幂等；超时抛 `AdbTimeoutException`（不重试），断连抛 `IOException`（可重连一次）；`close()` 必须释放 socket |
| `Scheduler`/`Clock` | `core/.../engine/Scheduler.kt:19` | 回调在别的线程；action 异常必须吞掉；`cancelAll()` 必须真取消；`delayMs<0` 视为 0；Android 侧用 Handler 实现即可，语义不变 |

升级规则：改任一接口签名 = 升 `TvrcVersion.CORE_API`，并在本文 §4 记一行变更；改配置字段 = 升 `CONFIG_SCHEMA` 且**必须**在 `LegacyConfigMigrator` 加迁移分支。三接口当前**无 TODO 遗留**。

- 2026-11-23：`TvStatus` 增加可选 `state: Int?`（`dumpsys media_session` 的 PlaybackState.state），用于顺序播放区分「暂停/缓冲」和「播完/进程退出」；旧实现只填 `alive`/`playing` 仍然可用。`CORE_API` 1→2。

---

## 5. 配置与迁移

`AppConfig` 16 字段（`core/.../config/Config.kt`）：`schemaVersion, source, rooms[], listMid, listId, pickCount, switchSeconds, statusSeconds, port, tvHost, tvPort, publicHost, mpvPkg, mpvActivity, wizardCompleted, idleTimeoutMinutes`，另有 `extra` 保留未知键（迁移不丢字段）。

关键默认值与裁决：

- `port = 8100`（避让二代的 8099；`ConfigValidator` **显式拒绝 8099**）
- `switchSeconds = 1800`，允许范围 **30–86400**（原定下限 60 已下调为 30，理由：二代现场 `switch_seconds=30` 长期稳定，三代要「配置直接用」）
- `idleTimeoutMinutes = 0` = 不自动停（FR-10 的「持续 N 分钟无任务自动停止」靠它）
- `statusSeconds = 10`（健康探测间隔）
- 迁移：二代 `config.json` 直接吃，`setup_seen→wizardCompleted`、`control_port` 忽略但留 `extra`、8099→8100、非法值收敛并记 note、可重复执行

跑迁移/校验：

```bash
# 校验一份配置（退出码 0 合法 / 1 不合法 / 2 不存在）
tools/gradle-8.10.2/bin/gradle -q :core:runConfigCheck --args="$HOME/.tvrc/config.json" --no-daemon

# 把二代配置迁过来看一眼（不落盘）
tools/gradle-8.10.2/bin/gradle -q :core:runConfigCheck \
  --args="--migrate 相关文件/termux-port/config.example.json" --no-daemon
```

---

## 6. 未验证清单（每条都必须由拿到电视的人回填）

风险等级：**高**=不验就可能整条路线推翻；中=影响体验/需调参；低=已知可控。

| # | 未验证项 | 风险 | 为什么软件验不了 | 怎么验（验完把结果写进 H §E） |
|---|---|---|---|---|
| H-1 | `:app` Compose 壳能编译能装 | 中 | 无 Android SDK/Studio 环境时产出的是负资产 | 装 SDK 命令行工具→`assembleDebug`→`adb install` |
| H-2 | `PlaybackService`（specialUse 前台服务）+ 通知常驻 | 高 | 依赖设备生命周期与 ROM 策略 | 锁屏 2 小时看是否仍在播（M4 验收线） |
| H-3 | `BootReceiver` 开机自启 | 高 | 真实开机流程与厂商限制 | 断电重启电视/手机后看是否自动恢复 |
| H-4 | 真机 adbd 行为（AUTH 弹窗、shell/busybox 差异） | **高** | 国产 ROM 差异极大，只能实测 | `adb connect` 后看是否弹窗；跑 `DadbClient` 真连一次（`verify-on-tv.sh` 的 adb_device/adb_authorized） |
| H-5 | 电视侧载 mpv + `am start -n is.xyz.mpv/.MPVActivity -d <url>` 实拉 | **高** | 需电视上真的装了 mpv 并解码 | `verify-on-tv.sh` 的 mpv_installed/tv_wake + 手工 `am start` |
| H-6 | 切台 ≤10 秒、连播 30 分钟端到端 | 高 | 端到端时延与稳定性 | 手机起服务→电视连播 30 分钟，记录每次切台耗时 |
| H-7 | `dumpsys media_session` 真实取值 | 中 | 厂商实现差异（接口已交，取值未验） | 播放时抓一次 `dumpsys media_session`，确认 state 字段能判 playing |
| H-8 | 保活矩阵、后台存活率 | 高 | 电源管理与内存回收 | 息屏 2 小时 + 电池优化白名单前后对比 |
| H-9 | IP 漂移真实网络环境 | 中 | 真实 Wi-Fi 抖动（逻辑已交，环境未验） | 改电视 IP 后看错误文案是否是 E-ADB-01 且能改配置恢复 |
| H-10 | R-1..R-9 真机清单（见 K 文档 §8） | 高 | 全部需要电视 | 逐条执行并把结论写回 K 文档 |

命令行自检能覆盖到 H-4/H-5 的第一步：`./tools/verify-on-tv.sh`（无电视时 `--dry-run`）。

---

## 7. 已知技术债 / 口径不一致（接手先看这 4 条）

1. ~~代理路由两套口径未统一~~（**已解决 2026-11-13，按二代已跑通方案**）：生产路由统一为 `/live?room=<房间号>`（别名 `/live.flv`）、`/video?bvid=<BV号>`、`/status` 与 `/api/status`；房间/BV 白名单外 400，上游解析失败 502，其它 404。`?url=` 形式仅当 `allowDirectUrl=true` 才可用（只在本机回环自测打开），生产默认 400 —— 电视公开面不存在任意 URL 代理。
2. **legacy shell 无退出码**：`DadbClient` 用 `__TVRC_EXIT__:<code>` 标记行兜底；某些 ROM 的 shell 会把标记回显两次，解析取最后一次。
3. **二代 `control_port`（8098）在三代无对应物**：三代没有 web 控制台，迁移时忽略但保留在 `extra`，不要误以为功能缺失。
4. **薄壳 APK（v0.2.0）与本工程无关**：它是二代路线 B 的 WebView 壳，包名 `com.tvstation.remote`，不要拿它当三代起点（详见 K1 事实清单）。

---

## 8. `tools/verify-on-tv.sh` 用法（P4-1）

```bash
./tools/verify-on-tv.sh --dry-run          # 无电视自检：jdk17 / gradle / 配置迁移 / 回归 170 / 端口
./tools/verify-on-tv.sh --dry-run --json   # 同上，输出 JSON（CI 用）
./tools/verify-on-tv.sh                    # 真机：adb 设备 → mpv → 点亮 → 真流 → APK
./tools/verify-on-tv.sh --config /path/to/config.json
```

JSON schema（`--json`）：

```json
{
  "ok": true,
  "dryRun": true,
  "summary": { "pass": 5, "fail": 0, "skip": 6 },
  "checks": [ { "id": "regression_170", "status": "PASS", "detail": "170 tests" } ]
}
```

- `status` ∈ `PASS`/`FAIL`/`SKIP`；`ok = (fail == 0)`，SKIP 不影响 `ok`。
- 退出码：0 全通过（含 SKIP）／1 有 FAIL／2 环境缺失。
- 检查项 id 固定：`jdk17, gradle, config_valid|config_migrate_from_v2, regression_170, port_free, adb_device, adb_authorized, mpv_installed, tv_wake, proxy_live_ffprobe, apk_install`。

---

## 9. 接手后建议顺序（S2 起点）

1. 装 Android SDK 命令行工具（≈2 GB），建 `:app` 模块（`useAndroidX=true`、`minSdk 29`），把 `:core` 作为纯 JVM 依赖接进去。
2. 实现 `AdbTvController : TvController`（内部 `DadbClient`+`AdbTransport`）与 `HandlerScheduler : Scheduler`——两个类，不碰引擎。
3. 一个 Activity + Compose：状态卡（五态）+ 开始/停止 + 错误文案（`ErrorCatalog`）+ 配置向导（`WizardMachine`）+ 诊断包导出（`DiagnosticsPack`）。
4. 真机跑 §6 的 H-4/H-5，先把「能连上、能拉到 mpv」验掉，再谈 H-2/H-3 保活。
5. 每步都跑 `:core:test`（170 条不许减）+ `./tools/verify-on-tv.sh`。

---

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-23 | APP 升至 alpha.5（versionCode 7）：批量导入（多链接/分享文案/txt 文档，自动去重）、节目单多选按顺序播放（合集展开为每集）、顺序播放按播放器结束信号自动切集；自检复用运行中的 ADB 连接，避免另开握手误报超时；修复导入框刷新后丢失粘贴内容；`TvStatus` 增加可选 `state`，CORE_API 1→2。 |
| 2026-11-21 | APP 升至 alpha.4（versionCode 6）：新增持久化结构化诊断事件、App 内诊断对话框、导入失败/播放状态/电视回执/网络异常追踪，并扩充导出诊断包。配置 schema 与 core API 保持 1；详见 docs/dev/2026-11-21-diagnostics.md。 |
| 2026-11-21 | APP 升至 alpha.3（versionCode 5）：依据 AOSP 修正 ADB 校验和、公钥编码与签名，分离自检连通/安装状态，增加 Android 15/16 屏幕安全区域适配。配置 schema 与 core API 保持 1；详见 docs/dev/2026-11-21-adb-and-phone-layout.md。 |
| 2026-11-21 | APP 升至 3.0.0-alpha.2（versionCode 4），修复设备 IP 保存与表单草稿丢失；新增设备设置校验，旧播放校验保留，配置 schema/core API 仍为 1。详见 docs/dev/2026-11-21-device-config-fix.md。 |
| 2026-11-13 | 建文档；162 条基线（路由统一后由 154 增至 162）；三契约冻结（core API v1）；未验证清单 H-1..H-10 全部标风险；记录 4 条技术债 |
| 2026-11-13 | 按二代方案统一代理路由：/live?room= 、/live.flv 别名、/video?bvid= 、/status 与 /api/status；?url= 仅 allowDirectUrl=true 回环自测；MiniHttpServerTest 15 条；测试 162 条全绿 |
| 2026-11-13 | **装配收口**：新增 core/src/main/kotlin/tvrc/core/AppGraph.kt（配置+代理+adb+状态机+看门狗+环形日志装配根，端口 0 自动回填 Session）与 bili/VideoUrlResolver.kt（view→playurl→durl）；新增 AppGraphTest 5 + VideoUrlResolverTest 3；测试 162→170 |
| 2026-11-13 | **文档整理（交接前）**：新增根目录 README.md 总入口（现状 / 5 分钟上手 / 文件结构总览 / 文档索引 / 「二代 vs 三代」区分）；修正 §3 模块树为实际代码（补 AppGraph / AdbTvController / WbiSigner / VideoUrlResolver / MediaSessionProbe / SubnetScanner，PlaybackEngine 归入 engine/）；README-如何编译与运行测试.md 顶部加「历史快照」提示并将当前条数校正为 170；.DS_Store 为系统杂物且已被 .gitignore 忽略，不纳入版本管理 |
