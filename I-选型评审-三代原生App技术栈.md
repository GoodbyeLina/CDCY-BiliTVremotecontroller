# I-选型评审：三代原生 App 技术栈

| 项 | 内容 |
| --- | --- |
| 文档编号 | TVAPP-REVIEW-001 |
| 版本 | v1.0 |
| 日期 | 2026-11-12 |
| 评审对象 | `H-技术方案-安卓播控App.md` 第 4 章架构、7.4 技术栈、9.4 兜底方案 |
| 评审视角 | 多人协作 + 中途接手 + 后续交回团队（可交接性权重最高） |
| 结论 | 保留四层架构与 Kotlin/协程路线；替换 3 处选型，修正 2 处平台级错误，新增 1 条模块边界强制约束 |

---

## 0. 结论摘要

**总体判断：H 方案方向是对的，具体选型有 5 处需要改。**

| 项 | H 方案 | 本评审结论 | 性质 |
| --- | --- | --- | --- |
| 语言 | Kotlin 2.0+ | **保留**（加"不用实验特性"约束） | 一致 |
| UI | Compose | **保留 Compose**，去掉 Navigation 库，改 sealed Screen | 微调 |
| 网络客户端 | OkHttp | **保留 OkHttp**，不做 Ktor Client 包装；JSON 用 kotlinx.serialization | 微调 |
| 本地代理 | Ktor Server (CIO) | **改自研 MiniHttpServer（≈300 行）**，Ktor 降为备选 | 替换 |
| adb | dadb | **保留 dadb**，接口从"命令字符串"改为"语义方法 + argv 原语" | 微调（重要） |
| 本地存储 | DataStore | **改单 JSON 文件 + kotlinx.serialization** | 替换 |
| DI | 未定 | **手写 CompositionRoot，不引入 Hilt** | 明确 |
| 状态机 | Channel actor | **保留 actor，内部改纯 reducer + Effect 列表** | 强化 |
| 前台服务类型 | "建议 dataSync，受限改 specialUse" | **直接 specialUse**：Android 15 对 dataSync 有 6h/24h 累计上限，常驻必被杀 | 纠错 |
| 模块结构 | 单模块 | **:core（纯 JVM）+ :app（Android）两模块** | 新增 |
| 工程化 | 未提 | 版本目录 + JDK toolchain + 适应度检查 + ADR | 新增 |

人日影响：**+2~3 人日**（:core 拆分、reducer 测试、代理集成测试、交接演练），换来 R-01/R-02/V-01 三个高风险项返工成本下降。

---

## 1. 评审方法与权重

选型不是选最先进，是选"这个团队在这个项目上最不容易出事"的一套。

| 维度 | 权重 | 说明 |
| --- | --- | --- |
| 交接与可维护 | 30% | 中途接手 + 交回团队，最贵的是"看不懂、不敢改" |
| 稳定性与风险 | 25% | 24/7 常驻、单进程、失败必须可见 |
| 测试性 | 15% | 纯函数与纯 JVM 可测比例，直接决定回归成本 |
| 性能与资源 | 15% | NFR-01~07：APK ≤20MB、内存 ≤150MB、CPU ≤5% |
| 实现成本 | 15% | 31–35 人日的盘子，不能为架构而架构 |

**元规则**：优先选可逆的决策。语言不可逆（决定团队技能假设），按长期方向定；UI 层可逆（错了重写 3–5 人日，core 不动），按"哪种更不容易藏 bug"定。

---

## 2. 路线级选择

### 2.1 五条候选

| 路线 | 形态 | 复用现有资产 | 主要问题 |
| --- | --- | --- | --- |
| **A 全原生 Kotlin + Compose** | 单 APK，四层全 Kotlin | 重写二代逻辑 + 复用薄壳骨架 | 需要 Kotlin/Android 能力 |
| **B 全原生 Kotlin + XML** | 同上，UI 用 View 体系 | 同上 | UI 状态同步靠纪律 |
| **C Flutter / RN** | 跨端壳 + 原生通道 | 复用不了 | adb、FGS、WifiLock 全要 platform channel；Dart 侧无成熟 adb 客户端 |
| **D Chaquopy 内嵌 CPython** | Kotlin 壳 + 原样跑二代 Python | **直接复用 2000 行已验证逻辑** | 双语言、构建链脆、adb 仍需二进制 |
| **E 保留 Termux 薄壳** | 现状 | 全部复用 | 违反设计原则 6，验收线不过 |

### 2.2 加权矩阵

| 维度(权重) | A Kotlin+Compose | B Kotlin+XML | C Flutter | D Chaquopy | E Termux 壳 |
| --- | --- | --- | --- | --- | --- |
| 交接 30% | 8 | 8 | 6 | 4 | 7 |
| 稳定 25% | 8 | 8 | 5 | 6 | 4 |
| 测试 15% | 8 | 7 | 6 | 5 | 3 |
| 性能 15% | 8 | 9 | 7 | 7 | 6 |
| 成本 15% | 7 | 7 | 5 | 9 | 10 |
| **加权** | **7.85** | **7.85** | **5.85** | **5.75** | **5.90** |

A 与 B 打平。不是回避：**UI 层对总分不敏感，它是本项目最可逆的一层**。

### 2.3 结论

**选 A（Kotlin + Compose）。** 打平后按三条非加权理由裁决：

1. **与设计原则 1 同构**：Compose 的 State→UI 单向自动渲染，"界面显示在播、实际已断"在结构上不会出现；XML 需手写 `render(state)`，靠评审纪律维持。
2. **长期方向**：新项目官方推荐 Compose，新人学的是 Compose，"陌生度"天平 2–3 年内翻转。
3. **可逆性够**：换回 XML 只动 UI 层 + DI 装配，core 零改动。ADR 里写清这条退路。

**路线 D 不选，但要留在文档里**：若 M2 被 B站接口/代理链路打爆、工期告急，Chaquopy 是唯一能在 1 周内把"已跑通逻辑"搬进 APK 的应急路线。写进风险册，标注代价（双运行时、构建链脆弱、无法演进）。**禁止中途混用**（附录 B 第 12 条）。

---

## 3. 组件级选型

### 3.1 UI：Compose vs XML + ViewBinding

| 维度 | Compose | XML + ViewBinding | XML + DataBinding |
| --- | --- | --- | --- |
| 状态同步 | 自动，结构性正确 | 手写 render，靠纪律 | 半自动，表达式难调 |
| 代码量（6 屏） | 基准 | +40% | +60% |
| 学习曲线（有 Android 经验） | 2–3 天 | 0 | 3–5 天（含坑） |
| 布局可视编辑 | 无（Preview 有限） | 有 | 有 |
| APK 增量（R8 后） | +2.5~3.5 MB | 0 | +0.3 MB |
| 冷启动 | 略慢（首帧组合） | 略快 | 略快 |
| 测试 | createComposeRule，状态注入友好 | Espresso，需真机/Robolectric | 同左 |
| 生态趋势 | 主流 | 维护模式 | 弱化 |

**结论：Compose + Material3，单 Activity，不引入 Navigation 库。** 只有 6 屏、导航深度 ≤2，引入 nav-graph/参数序列化/返回栈三套概念收益为零：

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
    data object Logs : Screen
}
// ViewModel 持有 MutableStateFlow<Screen>，返回键由 BackHandler 处理
```

Screen 是普通值，单测直接断言，比 nav-graph 好测也好读。

> 若团队明确"只会 XML"：XML + ViewBinding 是**允许的降级**，但须同时满足 —— UI 层零业务判断、`render(state)` 是唯一视图更新入口。切换不影响 4.1 的任何下层。

### 3.2 网络客户端：OkHttp 直连 vs Ktor Client vs Retrofit

| 维度 | OkHttp + kotlinx.serialization | Ktor Client (OkHttp 引擎) | Retrofit + OkHttp |
| --- | --- | --- | --- |
| 依赖层数 | 1 | 2（Ktor 包一层 OkHttp） | 2（注解 + 生成） |
| 流式转发友好度 | **最好**（`body.byteStream()` 直接喂代理） | 需 ByteReadChannel 桥接 | 需 `@Streaming` + 自理 |
| 头统一（Referer/UA） | 一个 Interceptor 覆盖 API 与媒体 | 两套配置需对齐 | 两套配置需对齐 |
| 测试 | MockWebServer（字节级真实） | MockEngine（不出网，不校真实头） | MockWebServer |
| 交接 | 最主流 | 中 | 高（多一层抽象） |

**结论：只用一套 OkHttp 同时跑 B站 API 与媒体转发，共用 Dispatcher / ConnectionPool / 头拦截器；JSON 用 kotlinx.serialization。**

核心理由：**代理存在的唯一理由是补 Referer/UA（5.2）**。两套客户端 = 头配置两个真相源，B站风控一变就改两处。一套 OkHttp + 一个 `BiliHeadersInterceptor` 是结构性的解。

版本锁 `4.12.x`（文档与经验最多的一代）。团队若已在 5.x 可整体迁移，**不要混用**；升级是独立 PR，跑完 V-04 再合。

### 3.3 本地 HTTP 代理：最需要重新决策的一处

需求：2 端点（`/video`、`/live`）+ `/status`；Range 透传；直播无长度需 chunked；并发 ≤3；客户端断开立刻停上游；端口冲突可降级。

| 维度(权重) | 自研 MiniHttpServer | Ktor Server CIO | NanoHTTPD | AndroidAsync |
| --- | --- | --- | --- | --- |
| 流式/Range 控制 30% | 10 | 7 | 6 | 6 |
| 测试性（JVM 真 socket 直测）20% | 10 | 7 | 7 | 5 |
| 交接/文档 25% | 5 | 9 | 7 | 4 |
| 体积/APK 15% | 10 | 5 | 8 | 7 |
| 长期维护 10% | 8 | 7 | 3 | 2 |
| **加权** | **8.55** | **7.20** | **6.45** | **5.05** |

**结论：自研 MiniHttpServer（≈300 行），Ktor Server 写成同接口备选。**

这是**明确的取舍，不是无争议最优**：赢在控制力、体积、可测性，输在"接手人要读 300 行才敢信"。缓解办法是把风险面钉成契约：

| 项 | 约束 |
| --- | --- |
| 方法 | 只接受 `GET`，其它 405 |
| 版本 | 只解析 HTTP/1.1 请求行；不支持 pipelining |
| 连接 | 每响应加 `Connection: close`，一请求一连接（mpv 接受） |
| 头 | 上限 8 KB、5 秒读完，超限/超时直接关连接 |
| 录播 | 有 Content-Length 原样回；带 Range 且上游 206 → 回 206 + `Content-Range` + `Accept-Ranges: bytes` |
| 直播 | 无长度 → `Transfer-Encoding: chunked`，32–64 KB 一块 |
| 断开 | 客户端 EOF/写失败 → 立刻 `call.cancel()` 关上游 |
| 上游 | connectTimeout 8s、readTimeout 30s、callTimeout 0（直播不设总超时） |
| 并发 | `Semaphore(3)`，超出排队不拒绝 |
| 绑定 | 只绑 WiFi 网卡地址（不用 0.0.0.0），IP 变化由 NetworkMonitor 重建 |

**为什么不用 Ktor**：为 2 端点引入 ktor-server-core + cio + ktor-http（≈2.5–4 MB），还要跟 Ktor 3.x 的 API 演进（3.0 改过 routing/plugin 接口），对 30 人日项目是纯负担。**为什么不用 NanoHTTPD**：2019 年后无发布、线程池阻塞模型、Range 还得自己写 —— 用框架的收益只剩解析器，而解析器恰是它最老的部分。**切换触发**：要加鉴权、多端点、WebSocket 或 HTTP/2 时换 Ktor（`ProxyServer` 接口不变）。

**自研的成本必须一次花够**：6 条 JVM 集成测试（§6），否则它是技术债不是决策。

### 3.4 adb：四级兜底，主选 dadb

实际只用 shell 类命令：`input keyevent`、`am start`、`am force-stop`、`pidof`、`dumpsys`、`pm path`、`getprop`。不传文件、不端口转发。

| 维度(权重) | dadb | AdbLib (cgutman) | libadb-android | 打包 adb 二进制 | 纯协议自研 |
| --- | --- | --- | --- | --- | --- |
| 可行性（能连上目标电视）35% | 8 | 6 | 8 | 10 | 6 |
| 运维复杂度 20% | 9 | 8 | 8 | 4 | 6 |
| 交接 20% | 8 | 5 | 6 | 9 | 3 |
| 体积 15% | 10 | 9 | 9 | 4 | 10 |
| 长期维护 10% | 8 | 4 | 6 | 9 | 5 |
| **加权** | **8.50** | **6.45** | **7.55** | **7.60** | **5.85** |

**结论：dadb 为主（`dev.mobile:dadb`），兜底顺序改为 dadb → 打包 adb → libadb-android。**

> **实施偏离（2026-11-13）**：实际开发未引入 dadb，改为自研协议层（`AdbPacket/AdbAuth/DadbClient`，零依赖 + 纯 JVM 可测），见 **M-ADR-0006**。本节兜底顺序降级为预案：接口 `AdbClient` 不变，真机出现差异时按原顺序切库，上层零改动。

对 9.4 的修订：原文第二级是"换 dadb 版本或改 AdbLib"。**AdbLib 是 2019 年前后的 Java 实现、长期无维护，排在"打包 adb"之前没有道理** —— 打包 adb 可行性最高（10 分）、行为与 Termux 完全一致（二代已验证，与二代跑的是同一个 adb 工具）、交接最容易。注意：这里说的"行为一致"仅指**开发侧排查**——万一出问题，开发者可在 adb shell 里手动复现同一条命令来定位，排查路径与二代经验直接对照。**用户侧永远是纯 App 交互**（向导 + 按钮控制台，见 FR-13 与 1.3 验收线），两条 adb 路线的 UI 完全相同，`TvController` 接口不变、换实现不动上层。真正的取舍是包体（+6~9 MB）不是风险。

两条必须写进文档的细节：

1. **不要直接分发 SDK platform-tools 里的 adb**（受 Android SDK 许可约束）。从 AOSP 源码自编（adb 本体 Apache-2.0），产出 `lib/arm64-v8a/libadb.so`，通过 `nativeLibraryDir` 执行 —— Android 10+ 从应用私有目录直接 exec 受限，nativeLibraryDir 是官方支持的可执行位置。
2. **libadb-android 的价值在配对**：Android 11+ 若电视只暴露"无线调试（配对码 + TLS）"，经典 5555 走不通，dadb 与 AdbLib 都覆盖不到。V-01 要同时验"经典 5555"与"配对模式"两条路径，结论进 9.1（新增 V-01b）。

**接口设计修订（重要）**：H 方案把 `AdbTransport` 定成语义方法方向正确，但要再切一刀，否则实现替换会漏：

```kotlin
// 唯一原语：argv → stdout。dadb 与打包 adb 各实现一份，二选一
interface AdbShell {
    suspend fun exec(argv: List<String>, timeoutMs: Long = 8_000): ShellResult // code + stdout + stderr
    suspend fun connect(): Boolean
}

// 业务语义层：编排器只见这一层
interface TvController {
    suspend fun connect(): TvState
    suspend fun wake()
    suspend fun play(url: String)        // 幂等：同 URL 不重复拉起
    suspend fun stop()
    suspend fun isInstalled(pkg: String): Boolean
    suspend fun isPlaying(): Boolean      // dumpsys media_session 解析
}
```

好处：`TvController` 的实现可用 `FakeAdbShell` 在 JVM 断言 argv（"停止是否先 force-stop 再校验 pidof"这类逻辑可测），`DadbShell` 只剩 80 行胶水、无需测。**编排器永远看不到命令行字符串。**

### 3.5 本地存储：JSON 文件 vs DataStore vs Room

| 方案 | 适配度 | 说明 |
| --- | --- | --- |
| **单 JSON 文件**（kotlinx.serialization + AtomicFile 写 + schemaVersion） | **推荐** | 配置是一个文档、不是一张表；新增字段给默认值 + `ignoreUnknownKeys = true` 天然满足 12.4 的升级约定；JVM 单测直接读写临时目录；排障时用户能把这个文件发过来 |
| DataStore Preferences | 备选 | 无嵌套结构，`rooms[]` 最终还是要塞一段 JSON 进某个 key —— 等于 JSON + 一层框架 |
| DataStore Proto | 不选 | 多一套 protobuf 插件与代码生成，为 10 个字段不值得 |
| Room | 不选 | 无查询需求；KSP + schema + 迁移是纯成本 |
| SharedPreferences | 不选 | 同步写、类型不安全，`apply()` 的异步语义容易误判 |

**日志**：环形内存 2000 条 + `filesDir/logs/app.log` 追加写 + 5 MB 轮转，不用 Room（P2 只要"看最近 200 条 + 导出 txt"）。写入走单写协程 + Channel，不阻塞业务。

**adb 私钥**：`filesDir/adb/`，不进 cacheDir（系统可清）；有条件时 Keystore 包一层（8.3 已提，留作 P2）。

### 3.6 DI：手写 CompositionRoot vs Hilt vs Koin

| 方案 | 规模假设 | 代价 | 结论 |
| --- | --- | --- | --- |
| **手写 AppGraph（构造器注入）** | ≤20 类、单进程、两模块 | 60 行，零代码生成，对象图一眼看完 | **推荐** |
| Hilt | ≥3 模块、≥4 人、需要生命周期作用域 | KSP 编译成本、大量注解、入口点约束 | 暂不引入 |
| Koin | 快速原型 | 运行时解析，错误推迟到运行；多一处"依赖在哪注册"的搜索 | 不选 |

推荐形态：

```kotlin
class AppGraph(context: Context) {
    val logger: Logger by lazy { FileLogger(File(...)) }              // Logger 是 :core 的接口
    val config: ConfigRepository by lazy { JsonConfigRepository(File(...)) }
    val bili: BiliApi by lazy { OkHttpBiliApi(logger, BiliHeadersInterceptor()) }
    val tv: TvController by lazy { DadbTvController(DadbShell(keyFile, logger), logger) }
    val proxy: ProxyServer by lazy { MiniHttpServer(bili, logger, wifiBinder) }
    val orchestrator: Orchestrator by lazy { Orchestrator(config, bili, tv, proxy, logger) }
}
```

规则：**任何类不得静态持有 Context**；Service/ViewModel 只从构造器拿依赖；ViewModel 用 `viewModelFactory { }` 注入。这条让 90% 的类在 JVM 上可直接 new 出来测。

> Hilt 的引入触发条件写进 ADR：模块数 ≥3 或团队 ≥4 人且已统一 Hilt 习惯 —— 届时是**新增一层**，不是重写。

### 3.7 状态机：actor + 纯 reducer（对 H 方案的强化）

H 方案定的"Channel actor、单协程串行消费"方向对。要补的是**把决策与副作用分开**，否则 5.4 的主循环只能在真机上验。

| 方案 | 可测性 | 问题 |
| --- | --- | --- |
| loop 里直接调 adb/网络（5.4 伪代码形态） | 低 | 每个分支都要真机真电视 |
| Flow + `scan` / `runningFold` 纯折叠 | 高（状态） | 副作用无处安放，最后还是要 Channel |
| **actor（Channel 入口）+ StateFlow（出口）+ 纯 reducer → Effect 列表** | **最高** | 需多写 30 行"执行 Effect"的壳 |

推荐形态：

```kotlin
// 纯函数：无 suspend、无 android.*、无 IO —— 100% JVM 单测
fun reduce(state: State, event: Event): Pair<State, List<Effect>>

// Effect 是数据：StartProxy(port) / StopProxy / TvPlay(url) / TvStop /
//   ScheduleSwitch(ms) / Notify(text) / Log(level, msg) / Retry(item, delayMs)
class Orchestrator(...) {
    private val events = Channel<Event>(Channel.UNLIMITED)    // 入口：串行
    private val _state = MutableStateFlow<State>(State.Idle)  // 出口：单一状态源
    val state: StateFlow<State> = _state.asStateFlow()
    init {
        scope.launch {
            for (e in events) { val (s, fx) = reduce(_state.value, e); _state.value = s; fx.forEach(::execute) }
        }
    }
}
```

收益具体到本项目：

- 5.6 的 8 状态 × 9 类事件 ≈ 20 条迁移，全部用表驱动测试覆盖一个文件，不需要电视。
- "连续 3 次失败进 Error""按需模式空闲超时""常驻模式不自动停"变成 reducer 里三行判断，评审可直接读。
- 定时器不塞进 reducer：`ScheduleSwitch` 是 Effect，壳层起 `delay`，超时再投 `SWITCH_DUE` 事件 —— reducer 里没有时间。

线程：actor 用 `Dispatchers.Default.limitedParallelism(1)`；网络/代理 IO；UI Main；所有 suspend 出口 `withTimeout`，禁止裸调用。

### 3.8 日志与可观测

保持 12.1，补三点：

1. `Logger` 是 :core 的接口（core 不能用 `android.util.Log`），:app 提供 Logcat + File 组合实现。
2. 日志带 `traceId`（一次播放循环一个），排障时能把代理、adb、B站三条线串成"一次轮播"。12.1 没提但最有用。
3. 脱敏在 Logger 层做（白名单：只允许 room_id / bvid / 状态码 / 耗时），不靠"记得别打 Cookie"。

---

## 4. 架构落地：模块、包、依赖方向

### 4.1 两模块（本评审唯一的结构性新增）

```
app/                      :app        Android 应用（AGP）
  ui/                     Compose 屏 + ViewModel
  platform/               PlaybackService、BootReceiver、Notification、WifiLock、
                          NetworkMonitor、SettingsLaunch、FileLogger、DadbShell
  di/AppGraph.kt
core/                     :core       纯 Kotlin JVM（kotlin("jvm")，无 android.*）
  model/                  PlayItem、Content、State、Event、Effect、AppException
  orchestrate/            reduce()、Orchestrator
  content/                SourceParser（原 ContentResolver）
  bili/                   BiliApi、WbiSigner、BiliHeadersInterceptor
  proxy/                  ProxyServer、MiniHttpServer、Upstream
  tv/                     TvController、AdbShell（接口）+ argv 构建
  config/                 ConfigRepository、JsonConfigRepository、migrations
  log/                    Logger、RingBuffer
```

**为什么值得拆**：:core 不应用 Android 插件，`android.*` 编译期就不可见 —— 边界从"文档要求"变成"编译器保证"。代价 0.5 人日，收益是"业务层不依赖 Android"不会在第一次赶工时被违反。

两条推论：需要 Context 的东西（路径、网络状态、通知）以 File / interface 注入 core；定时、日志、时钟都从 core 接口注入，测试时换可控实现。

### 4.2 与 H 方案四层的对应

| H 方案层 | 落点 | 说明 |
| --- | --- | --- |
| 表现层（Compose） | :app/ui | 只读 State、只发 Event |
| 状态层（ViewModel/Config） | :app/ui + :core/config + :core/orchestrate | ViewModel 只做映射，状态机在 core |
| 业务层（编排/解析/API/代理/adb） | :core/* | 全部纯 JVM |
| 平台层（Service/Receiver/锁/通知） | :app/platform | 只做载体，不写业务 |
| （新增）端口与装配 | :app/di | 唯一对象图真相源 |

依赖方向：:app → :core 单向；core 内部 model 是叶子。

### 4.3 命名修订

- `ContentResolver` → **`SourceParser`**：与 `android.content.ContentResolver` 同名，接手人第一眼必然误解，且这类重名会在 import 上浪费真实时间。
- 日志统一：`Logger`（接口）/ `RingBuffer`（内存）/ `FileLogSink`（落盘），不要混叫 LogBus。

### 4.4 架构适应度检查（机器强制，不靠评审）

```bash
# 第一道闸：core/build.gradle.kts 根本不加 android 插件
# 第二道：core 源码不得出现 Android 符号（防有人塞 android.jar 绕过）
./gradlew :core:compileKotlin -q
grep -rn "import android\." core/src/main && echo "FAIL: core 不得依赖 Android" && exit 1 || true
# 第三道：core 测试 15 秒内跑完（超时说明测试依赖了设备）
./gradlew :core:test :app:testDebugUnitTest :app:lint
```

本地 `./gradlew check` 三条全绿才允许合并，不必上云 CI。

---

## 5. 相对 H 方案的 6 条硬修改

### 5.1 前台服务类型：dataSync → specialUse（平台级纠错）

5.8 写的是"类型建议 dataSync；若被限制改 specialUse"。**不能这么写**：Android 15（API 35，本项目 targetSdk）对 `dataSync` 类型的前台服务引入 **24 小时内累计 6 小时上限**，超时系统停止服务并回调 `onTimeout()`。本项目 24/7 常驻，6 小时必被杀 —— 不是"可能被限制"，是"一定被限制"。

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<service android:name=".platform.PlaybackService"
         android:foregroundServiceType="specialUse"
         android:exported="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="local_network_media_relay_and_device_control" />
</service>
```

`specialUse` 无时长上限，代价是上架商店需人工审核 —— 本项目自签名侧载，不构成障碍。`mediaPlayback` 看着更正当，但本 App 不在本机解码播放（媒体是转发的），用它属于类型滥用、系统行为更不可预测。写进 ADR-0006，并加 **V-13：Android 15 真机常驻 8 小时不中断**。

### 5.2 adb：语义接口 + argv 原语（见 §3.4）

禁止 `AdbTransport.shell("am start ...")` 这种把命令拼接暴露给业务层的形态；URL 可能带引号/空格/中文，拼接即埋雷（二代 Python 就是字符串拼接，转 Kotlin 必须改 argv 数组）。

### 5.3 代理绑定 WiFi 地址 + 监听网络变化

`ServerSocket(8099)` 默认绑 0.0.0.0。本项目要求"只监听局域网"（8.3），且手机 IP 变化后电视的 URL 失效（R-04）必须自动恢复。所以：代理启动时解析 WiFi 网卡地址显式 bind；`NetworkMonitor`（`ConnectivityManager.NetworkCallback`）在 WiFi 断开/IP 变化时投 `NETWORK_CHANGED` 事件，reducer 决定"停播 → 重建代理 → 用新 IP 重新下发 URL"。这补上了 V-08 的实现机制（原文只写"要恢复"）。

### 5.4 配置 schema 演进写成代码约束

`Json { ignoreUnknownKeys = true; encodeDefaults = true }` + 顶层 `schemaVersion: Int` + 每次升级一个 `Migration` 函数 + 迁移前自动备份 `config.bak.json`。这样"覆盖安装后配置丢失"可控，也落实 12.4。

### 5.5 工具链：JDK 问题的正解不是"再装一个 JDK 17"

7.4 说"本机现有 JDK 25 不兼容，需单独装 17"。更省事的做法：

```kotlin
// app/build.gradle.kts
kotlin { jvmToolchain(17) }
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

Gradle 用 Studio 自带 JBR（21）跑，编译产物目标是 17 —— 让 Gradle 自己满足 toolchain，不要求每个接手人手动对齐 `JAVA_HOME`。这条直接消除"新人第一天环境就卡住"的交接事故。

### 5.6 版本锁定策略

`gradle/libs.versions.toml` 统一版本，禁止 `+` / 动态版本；B站接口与 Android 平台升级各自独立 PR；依赖升级**不做自动合并**（Renovate/Dependabot 只开 PR）—— 本项目对"B站风控"与"前台服务策略"两类外部变化极敏感，自动升级是把不可控变量引进来。

---

## 6. 测试策略

原则：**能在 JVM 上跑的逻辑，绝不上真机。** 目标不是覆盖率数字，是"改一行 reducer / 改一个正则，5 秒内知道有没有坏事"。

| 层 | 被测对象 | 手段 | 跑在哪 | 覆盖目标 |
| --- | --- | --- | --- | --- |
| core/orchestrate | `reduce()` 全部迁移 | 表驱动参数化（状态 × 事件 → 状态 + Effect 断言） | JVM | 每条迁移 ≥1 例，含非法事件 |
| core/content | 链接识别、合集/列表判定 | 固定 JSON/URL 样本 + 假 BiliApi | JVM | 8 种输入形态 + 边界 |
| core/bili | wbi 签名、playurl 解析、镜像选择 | 固定密钥向量 + MockWebServer | JVM | 签名 5 组；镜像 403 换源 |
| core/proxy | Range、206、chunked、断连、并发 | **真 ServerSocket（port 0）+ OkHttp 当客户端** | JVM | 6 条集成用例 |
| core/tv | 停止/唤醒/幂等的 argv | FakeAdbShell 记录 argv | JVM | 命令序列断言 |
| core/config | 迁移、缺字段、损坏文件 | 临时目录 + 旧版本样本 | JVM | 3 代 schema |
| app/platform | 通知构建、Boot 判定、脱敏 | Robolectric（少量） | JVM | 冒烟 |
| app/ui | 关键屏渲染 | `createComposeRule` | 模拟器 | 3–5 条冒烟 |
| 设备 | V-01~V-13 | 手工 + 9.3 记录格式 | 真机/真电视 | 按 9.2 排期 |

工具集（刻意保持少）：JUnit4 + `kotlin.test` 断言 + Turbine（Flow 断言）+ MockWebServer + Robolectric。**不引入 JUnit5**（Android 侧仍是 JUnit4，双框架只会让人写错注解）。

底线：`./gradlew :core:test` 必须 < 15 秒 —— 这条比覆盖率更重要，它决定测试会不会被跳过。

---

## 7. 交接包（"多人 + 中途接手"最该投入的地方）

### 7.1 文档清单

| 文件 | 作用 | 谁维护 |
| --- | --- | --- |
| `README.md` | 15 分钟跑起来：装 Studio → 打开 → 连真机 → 点运行 → 看到日志 | 一次性写好 |
| `H-技术方案-安卓播控App.md` | 需求与设计（已有） | 需求变更时 |
| `I-选型评审-三代原生App技术栈.md`（本文） | 为什么这么选 | 选型变更时 |
| `docs/adr/0001..0008` | 8 条决策的上下文与代价 | 每次架构改动 |
| `docs/接口清单.md`（填 6 章） | 端口契约 | 加接口时 |
| `docs/验证记录.md` | V-01~V-13 实测结论 | 每次测完 |
| `docs/故障排查.md` | 12.3 + 新增条目 | 每次踩坑 |
| `docs/交接清单.md` | 签名密钥、已知限制、未做项、联系人 | 交付前 |

### 7.2 ADR 清单（照抄即可建文件）

| 编号 | 决策 | 关键取舍 |
| --- | --- | --- |
| ADR-0001 | Kotlin 单语言，不引入 Chaquopy | 放弃复用 2000 行 Python，换单一运行时 |
| ADR-0002 | Compose，无 Navigation 库 | 状态一致 vs 老手熟悉度 |
| ADR-0003 | 单 JSON 文件配置，不用 DataStore | 结构简单 vs 框架背书 |
| ADR-0004 | 自研 MiniHttpServer | 控制力/体积 vs 框架可信度（**代价最大的一条，要写清**） |
| ADR-0005 | dadb 主选，四级兜底 | 无子进程 vs 二进制兼容性 |
| ADR-0006 | 自研 ADB 协议层替代 dadb 主选（见 M-ADR-0006 文件） | 零依赖 + 纯 JVM 可测 + 许可可控，兜底顺序保留为预案 |
| ADR-0006 | FGS = specialUse | Android 15 的 dataSync 6h 上限 |
| ADR-0007 | 手写 DI，不引入 Hilt | 显式对象图 vs 行业惯例 |
| ADR-0008 | core/app 两模块 | 编译器强制边界 vs 构建复杂度 |

### 7.3 交接检查清单（交付前逐条打勾）

- [ ] 新机器按 README 从零构建成功，用时记录在 README 里（>20 分钟就要改）
- [ ] `./gradlew check` 全绿；`:core:test` < 15 秒
- [ ] 签名 keystore + 密码保管方式已写入 `交接清单.md`，且不在仓库里
- [ ] 6 章的接口清单与代码一致（抽查 3 个）
- [ ] 8 条 ADR 都能被一个没参与开发的人读懂"为什么"
- [ ] V-01~V-13 结论都有证据（截图/日志片段），未测的写"未验证"
- [ ] 已知限制清单（不能做的、做不稳的、下次要改的）单独一节
- [ ] 有一个人独立跑通"改配置 → 播放 → 停止"全流程，且没问原作者

---

## 8. 实施顺序修订（对 10.2 的最小改动）

| 里程碑 | 修订 | 增减 |
| --- | --- | --- |
| M0 | V-01 同时跑两条路径：dadb 探针 + 打包 adb 探针（各 ~100 行调试入口） | +0.5 |
| M1 | 先建 :core/:app 两模块骨架 + 适应度检查 | +0.5 |
| M1 | 状态机按 reducer 形态落地（含表驱动测试） | +0.5 |
| M3 | 代理补 6 条 JVM 集成测试 | +0.5 |
| M4 | FGS 改 specialUse，加 V-13（8 小时常驻） | +0.5 |
| M5 | ADR 汇总 + 交接演练（第二人按 README 从零构建） | +0.5 |

**关键路径不变**（V-01 → M2 → M3 → M5）；总人日 31–35 → 34–38，可用版 24–28 → 27–31。若必须守原预算，优先砍 P2（遥控界面、定时启停），**不要砍测试与 ADR** —— 那是交接的地基。

---

## 9. 风险登记册更新

| 编号 | 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- | --- |
| R-11（新） | 自研 HTTP 服务的协议边界 bug（Range/chunked/断连） | 中 | 中 | 协议契约（§3.3）+ 6 条 JVM 集成测试 + `ProxyServer` 接口可切 Ktor |
| R-12（新） | 电视只开"无线调试（配对/TLS）"，经典 5555 不通 | 中 | 高 | V-01b 双路径验证；备选 libadb-android 或打包 adb |
| R-13（新） | targetSdk 35 下 dataSync 6h 上限杀常驻 | **已消除** | — | 改 specialUse（ADR-0006） |
| R-14（新） | 中途为赶工期混入 Chaquopy，形成双运行时 | 低 | 高 | ADR-0001 明令禁止；应急路线只允许整体替换，不允许混用 |
| R-01 | dadb 连不上电视 | 中→低 | 高 | 兜底顺序改 dadb → 打包 adb → libadb-android（§3.4） |
| R-09 | 单人中断、无人接手 | 中 | 中 | ADR + 接口清单 + 交接演练（§7.3）比"注释到位"可执行得多 |
| R-02 | 厂商杀后台 | 高 | 高 | 不变（前台服务 + 看门狗 + 白名单引导）；V-05 仍需真机 |

---

## 附录 A 依赖清单与版本策略

| 依赖 | 用途 | 策略 |
| --- | --- | --- |
| Kotlin 2.x 稳定版 | 语言 | 锁死；不使用 `@OptIn` 实验 API |
| AGP 8.x 稳定版 | 构建 | 锁死；与 Studio 版本同步升级 |
| Compose BOM + Material3 | UI | BOM 锁死，不单独指定各 artifact |
| kotlinx-coroutines | 并发 | 锁死，只用稳定 API |
| kotlinx-serialization-json | JSON | 锁死 |
| OkHttp 4.12.x | HTTP（API + 媒体） | 锁死 |
| dadb（dev.mobile） | adb | 锁死；升级需跑 V-01 |
| mockwebserver（test） | 测试 | 与 OkHttp 同版本 |
| Turbine / Robolectric（test） | 测试 | 锁死 |

预计 Release APK（R8，arm64-only）：**10–13 MB**，NFR-07 的 20 MB 有余量；**若启用打包 adb 兜底则 +7 MB，必须同时开 ABI 拆分（只出 arm64-v8a）**，否则逼近上限。

## 附录 B 本项目明令禁止的做法

1. 业务层出现命令行字符串拼接（`$url` 直接进 shell 串）—— 一律 argv 数组。
2. 在 Compose 组合函数里做 IO。
3. 同时存在两个"是否在播"的真相（MutableState + LiveData + 通知三份）。
4. 代理里把整段视频读进内存（必须逐块转发，32–64 KB）。
5. Service 里 `runBlocking`。
6. 空 `catch`；或无 issue 号的 `TODO`。
7. 依赖动态版本（`+`、`latest.release`）。
8. adb 私钥放 cacheDir。
9. `dataSync` 类型的前台服务。
10. :core 里出现 `import android.*`。
11. `GlobalScope`；或未绑定生命周期的 `scope.launch`。
12. 中途引入第二种运行时（Chaquopy/Node/QuickJS）来"复用旧逻辑"。

## 附录 C 与 H 方案差异一览（评审会一页纸）

| # | 位置 | 原文 | 建议 | 理由一句话 |
| --- | --- | --- | --- | --- |
| 1 | 4.1 | 单模块 | :core + :app | 边界由编译器而非评审保证 |
| 2 | 4.2 | ContentResolver | SourceParser | 与 Android 同名类冲突 |
| 3 | 5.3 | AdbTransport 语义方法 | + AdbShell(argv) 原语 | 实现替换不漏、可 JVM 测试 |
| 4 | 5.4 | loop 内直接调 adb | 纯 reduce() + Effect | 20 条迁移可表驱动测 |
| 5 | 5.8 | dataSync / specialUse 二选 | 直接 specialUse | Android 15 有 6h 上限 |
| 6 | 7.4 | Ktor Server (CIO) | 自研 MiniHttpServer | 2 端点不值 3 MB + API 演进 |
| 7 | 7.4 | DataStore | 单 JSON 文件 | 配置是文档不是表；可导出排障 |
| 8 | 7.4 | JDK 17 单独装 | toolchain(17) + Studio JBR | 免去每个接手人对齐环境 |
| 9 | 8.3 | 8099 不鉴权 | 绑 WiFi 地址 + 网络回调重绑 | 与"只监听局域网"一致，且解 V-08 |
| 10 | 9.4 | dadb → AdbLib → 打包 adb | dadb → 打包 adb → libadb-android | 打包 adb 可行性最高，AdbLib 已停更 |