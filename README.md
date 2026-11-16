# B站直播遥控器 · 三代原生 App —— 项目总览（README）

> **一句话**：把 B站直播间 / 合集的画面，经手机 App 做 HTTP 代理、再通过局域网 ADB 拉起电视上的 mpv 播放，并能按间隔自动轮播、掉线自动换台。
> 本文是仓库的**总入口**：先读这里 → 按「§3 文档索引」深入 → 动手交接看 **HANDOFF.md**。

---

## 0. 当前状态（截至 2026-11-13）

| 项 | 状态 |
|---|---|
| `:core` 纯 Kotlin/JVM 内核 | ✅ **已完成**：状态机 / 播放引擎 / 手写 HTTP 代理 / 自研 ADB 协议 / B站接口 / 配置与迁移 / 向导 / 看门狗 / 诊断包 —— **170 条测试全绿**（基线只增不减） |
| Android 壳 `:app`（Compose UI + 前台服务 + 开机自启） | ⬜ **尚未开始** |
| 真机 / 真电视验收（H-1..H-10） | ⬜ **待接手**（见 HANDOFF §6） |
| 纯软件范围完成度 | **19/19 项，≈17.2 / 37.0 人日（46%）**；剩余 19.8 人日全部属设备依赖 |
| 版本常量 | APP `3.0.0-alpha.1`、CORE_API `1`、CONFIG_SCHEMA `1`（`core/.../TvrcVersion.kt`） |

> 权威现状以 **HANDOFF.md** 为准；历史文档里的测试条数是当时的快照，不代表当前（当前 = 170）。

---

## 1. 五分钟上手（跑通测试）

```bash
cd "/Users/lzl/E/code/B站直播遥控器app开发"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # 或：source tools/env-jdk17.sh
tools/gradle-8.10.2/bin/gradle :core:test --no-daemon --console=plain
```

- 期望输出 `BUILD SUCCESSFUL`，**170 条测试通过**。首次约 2 分钟（下 Kotlin 编译器），之后增量 6–10 秒。
- **不需要 Android SDK / Studio / AVD / 电视**（内核是纯 JVM）。
- 无电视自检：`./tools/verify-on-tv.sh --dry-run`（输出 5 PASS / 0 FAIL / 6 SKIP，退出码 0）。

---

## 2. 文件结构总览

```text
B站直播遥控器app开发/
├── README.md                        # ★ 总入口（本文件）
├── HANDOFF.md                       # ★ 交接第一读：环境/模块边界/契约/未验证清单/接手顺序
├── H-技术方案-安卓播控App.md         # 权威需求与设计（FR/NFR/里程碑/验收线）
├── I-选型评审-三代原生App技术栈.md   # 技术栈选型对比 + ADR
├── J-审校记录-H方案.md               # H 方案审校记录（P0/P1/P2 问题闭环）
├── K-实施阶段划分-三代原生App.md     # 实施阶段划分（S0–S5）
├── K1-事实清单-全文件盘点.md         # 全文件盘点与事实底稿（K 的取证依据）
├── L-编程清单-纯软件范围.md          # 纯软件范围编程清单（19 项已全部完成）
├── M-ADR-0006-自研ADB协议层替代dadb.md # ADR：用自研 ADB 协议层替代 dadb 库
├── README-如何编译与运行测试.md      # 编译/测试命令 + 开发过程追加日志（历史记录）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/  # Gradle wrapper
├── core/                            # ★ 纯 Kotlin/JVM 内核（已完成，170 测试）
│   ├── build.gradle.kts
│   ├── build/                       # 构建产物（git 忽略，可再生成）
│   └── src/main/kotlin/tvrc/core/   # 源码（子包明细见 HANDOFF §3）
│       adb/ bili/ config/ diag/ engine/ http/ json/ media/ net/ tools/ wizard/
│       Model.kt Reducer.kt TvController.kt TvrcVersion.kt AppGraph.kt
│   └── src/test/kotlin/tvrc/core/test/   # 测试与替身（Fake* / VirtualTime）
├── tools/
│   ├── env-jdk17.sh                 # source 后 JAVA_HOME 指向 JDK 17
│   ├── verify-on-tv.sh              # 交接自检（--dry-run 无电视可跑 / --json）
│   ├── verify-proxy.sh              # 代理真流验收（ffprobe/ffmpeg，全程不落盘）
│   ├── verify-wbi.sh                # B站 wbi 签名真请求验收
│   └── gradle-8.10.2/               # 本地 Gradle 发行版（~145MB，git 忽略）
├── 全流程调试手册/                   # 【二代】旧手机+Termux+APK 从零部署与八层排障（含 5 张配图）
├── 相关文件/                         # 【二代】同源实现存档：termux-port 脚本 / android-app 薄壳源码 / 成品 APK / zip
└── ui参考/                           # 静态 UI 示意（index.html / setup.html，非可运行代码）
```

> 构建产物目录 `core/build/`、`.gradle/`、`.kotlin/`、`tools/gradle-8.10.2/` 均已被 `.gitignore` 忽略，可随时删除重建；不要提交进 git。

---

## 3. 文档索引（推荐阅读顺序）

| 顺序 | 文档 | 作用 | 何时读 |
|:--:|---|---|---|
| — | **README.md**（本文） | 总入口、现状、结构、命令 | 第一次进仓库 |
| 1 | **HANDOFF.md** | 交接说明：环境、模块边界、三契约、未验证清单 H-1..H-10、技术债、接手顺序 | **动手前必读** |
| 2 | H-技术方案-安卓播控App.md | 权威需求与设计、验收线 | 查需求 / 验收对照 |
| 3 | I-选型评审-三代原生App技术栈.md | 技术栈对比与选型结论 | 换技术 / 理解选型 |
| 4 | J-审校记录-H方案.md | H 方案的审校与问题闭环 | 追溯修订依据 |
| 5 | K-实施阶段划分-三代原生App.md | 阶段划分与排期（S0–S5） | 排期 / 阶段目标 |
| 6 | K1-事实清单-全文件盘点.md | 逐文件事实底稿 | 查某文件是什么 |
| 7 | L-编程清单-纯软件范围.md | 纯软件 19 项清单（全部完成） | 看做过什么、验收标准 |
| 8 | M-ADR-0006-自研ADB协议层替代dadb.md | 自研 ADB 协议的决策记录 | 理解 adb 层 |
| 9 | README-如何编译与运行测试.md | 编译/测试命令 + 过程日志 | 跑起来 / 看开发轨迹 |
| 10 | 全流程调试手册/ | **二代**部署与排障手册 | 参考现场部署经验 |
| — | 相关文件/ | **二代**源码存档 | 迁移对照 / 复用参考 |
| — | ui参考/ | 静态界面示意 | 设计界面参考 |

---

## 4. 别搞混：仓库里其实有「两代」东西

1. **三代原生 App（本项目主体）** —— `core/` + 未来的 `:app`，Kotlin 原生、直连 ADB 5555、唯一监听 `0.0.0.0:8100`。**这是要继续开发/交接的对象。**
2. **二代实现（仅作参考）** —— `相关文件/termux-port`（Termux 上跑的 Python 控制面/媒体面 + Web 控制台，端口 8098/8099）、`相关文件/android-app`（WebView 薄壳 APK，包名 `com.tvstation.remote`）、`相关文件/TVStationRemote-0.2.0.apk`，以及 `全流程调试手册/`。**这些不是三代的编译对象，也不是三代的起点**，只用于迁移对照与现场经验参考。

---

## 5. 交接从这里开始

1. 读 **HANDOFF.md** 全文（尤其 §6 未验证清单、§7 技术债、§9 接手顺序）。
2. 按 HANDOFF §2 配好 JDK 17 + 仓库内 Gradle，跑通 §1 的 170 条测试。
3. 需要电视/真机的部分（H-1..H-10）按 HANDOFF §6 逐条验证并回填结果。
4. 每步都跑 `:core:test`（170 条不许减）+ `./tools/verify-on-tv.sh`。

---

## 6. 命令速查

```bash
# 全量回归（170 条）
JAVA_HOME=/opt/homebrew/opt/openjdk@17 tools/gradle-8.10.2/bin/gradle :core:cleanTest :core:test --no-daemon

# 单个测试类
tools/gradle-8.10.2/bin/gradle :core:test --tests 'tvrc.core.test.PlaybackEngineTest' --no-daemon

# 校验 / 迁移配置
tools/gradle-8.10.2/bin/gradle -q :core:runConfigCheck --args="$HOME/.tvrc/config.json" --no-daemon
tools/gradle-8.10.2/bin/gradle -q :core:runConfigCheck --args="--migrate 相关文件/termux-port/config.example.json" --no-daemon

# 交接自检 / 代理验收 / wbi 签名验收
./tools/verify-on-tv.sh --dry-run
./tools/verify-proxy.sh
./tools/verify-wbi.sh
```
