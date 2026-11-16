# B站直播遥控器 · 三代原生 App —— 项目总览（README）

> **一句话**：把 B站直播间 / 合集的画面，经手机 App 做 HTTP 代理、再通过局域网 ADB 拉起电视上的 mpv 播放，并能按间隔自动轮播、掉线自动换台。

> **前端开发先看效果：** [可交互原型 HTML](ui参考/三代App-可交互原型.html) · [直接下载原型](https://raw.githubusercontent.com/GoodbyeLina/CDCY-BiliTVremotecontroller/main/ui%E5%8F%82%E8%80%83/%E4%B8%89%E4%BB%A3App-%E5%8F%AF%E4%BA%A4%E4%BA%92%E5%8E%9F%E5%9E%8B.html) · [前端效果与操作说明](ui参考/README.md) · [界面预览图](ui参考/三代App-原型预览.png)
>
> 下载后用 Chrome / Edge 打开 HTML，可离线体验内容库、节目单、开播监控和配置向导。GitHub 文件页只展示源码，不会直接运行 HTML。原型使用模拟数据，视觉细节可调整，正式功能以需求表为准。

## 当前开发需求与完成情况

**更新：2026-11-16。三代目前有核心代码，尚无独立 Android App；现成的 0.2.0 APK 是依赖 Termux 的旧版。**

下面按用户提出的 8 项需求排列。**“部分完成”表示存在底层代码，不能当作用户已经能使用。** 详细任务编号、代码依据、解析样例和逐项验收见 [完整需求与验收表](docs/requirements-v3.md)。

| # | 用户需求 | 当前完成情况 | 接下来要完成什么 | 完成后的验收方式 |
|---|---|---|---|---|
| 1 | 视频支持 BV、电脑网页网址、手机分享链接 | **部分完成**：标准网页和 BV 能识别；分享短链只识别不展开，实际 App 链路仍需修复 | 统一提取、短链展开、内容解析与播放入口；处理带参数网址和整段分享文案 | 用户给出的网页和分享原文都能导入、显示正确视频并播放，无需手动截取 BV |
| 2 | 直播支持房间号、网页网址、手机分享链接 | **部分完成**：房间号和标准直播网址能识别，短链及实际入口待补齐 | 打通同一套链接处理流程，查询真实房间信息和开播状态 | 用户给出的房间 `612978` 网页及分享链接均可识别；开播时可播放，未开播时明确提示 |
| 3 | 完成独立、功能完整的 Android App | **未完成**：已有 Kotlin 核心，尚无三代 Android 模块和 APK | 接入原生界面、前台服务、真实播放状态、权限、通知、配置与恢复 | 安装一个 APK 即可配置和使用，不依赖 Termux；真实手机与电视完成视频、直播、切台、停止及后台运行验证 |
| 4 | 改善前端视觉与首次使用引导 | **部分完成**：已有设计原型和向导逻辑，尚无原生页面 | 实现内容库 / 节目单 / 开播监控 / 设备页；引导检查网络、授权、播放器和试播，失败可修复 | 新用户跟随引导可完成首次播放；加载、空列表、失败状态明确；小屏和深浅色显示正常 |
| 5 | 解析 B站视频合集和列表 | **部分完成**：已有系列/合集接口和分页，未接入完整播放流程 | 展开完整列表、去重、保持顺序；展示单集与整组播放入口 | 多页合集可完整导入，可单集播放或按序播放；不可用条目有原因提示 |
| 6 | 首页展示视频/直播列表，能找回以前看过的内容 | **未实现**：已有交互原型，缺持久内容库及原生页面 | 视频与合集 / 直播间切换；保存导入记录、观看历史和收藏；支持搜索及再次播放 | 重启 App 后内容仍在；可切换列表、查看最近看过并再次播放；重复导入不重复建项 |
| 7 | 待机唤醒电视、添加节目单、每天定时播放 | **唤醒已有待真机验证；节目单与定时未实现** | 节目单增删改排序；设时间、重复日和唤醒选项；重启恢复任务、处理权限及执行失败 | 电视网络和 ADB 在待机时可用的条件下，手机息屏也能到点唤醒并播放指定节目单；查看执行记录 |
| 8 | 监控指定直播间，开播通知并按规则强制播放 | **部分完成**：已有状态查询，缺监控服务、通知和抢播编排 | 自适应轮询、开播去重、通知、优先级、自动唤醒、下播恢复；长连接作为后续优化 | 同一次开播只触发一次；只监控主动关注的房间；可配置抢播及恢复规则；断网不误判，关闭后停止查询 |

**已确认的监控方向：**以本地自适应轮询为基础，集中调度、复用连接，非重点时段降频，失败退避；不全天对全部直播间固定高频查询。直播消息长连接先验证可靠性与耗电收益，再作为后续优化。首版不引入监控服务器。

**抢播默认规则：**尊重用户手动停止；只有明确开启“允许覆盖手动停止”的直播间才可覆盖。重点直播可按优先级打断普通节目单，下播后恢复原队列位置。

**开发顺序：**链接修复与独立 APK → 内容库、合集与原生引导 → 节目单、定时和开播监控 → 长连接优化。前端原型已展示，视觉细节仍可调整；视频分 P、收藏夹范围、监控数量和精确续播需求见完整需求表中的待定项。

维护要求：功能完成时同步更新本页和 [详细需求状态](docs/requirements-v3.md)，附实现提交、测试结果与真机记录；不能仅凭有代码或界面原型标记“完成”。

---

## 前端效果预览

点击 [可交互原型](ui参考/三代App-可交互原型.html) 下载体验；下面是同一文件的内容库首页，完整操作路径见 [前端说明](ui参考/README.md)。

<img src="ui参考/三代App-原型预览.png" alt="三代 App 内容库首页：视频与合集、直播间切换，最近看过、收藏及底部导航" width="360">

## 项目说明与历史资料

下面保留原项目入口、构建方法和历史阶段记录。开发当前需求先看上面的表，再按需阅读 [详细需求](docs/requirements-v3.md)、[交接说明](HANDOFF.md) 和后面的文档索引。

---

## 0. 历史开发基线（2026-11-13）

| 项 | 状态 |
|---|---|
| `:core` 纯 Kotlin/JVM 内核 | 已有状态机 / 播放引擎 / HTTP 代理 / 自研 ADB 协议 / B站接口 / 配置与迁移 / 向导 / 看门狗 / 诊断模块；历史报告 **170 条测试通过**，完整集成缺口见首页需求表 |
| Android 壳 `:app`（Compose UI + 前台服务 + 开机自启） | ⬜ **尚未开始** |
| 真机 / 真电视验收（H-1..H-10） | ⬜ **待接手**（见 HANDOFF §6） |
| 纯软件范围完成度 | **19/19 项，≈17.2 / 37.0 人日（46%）**；剩余 19.8 人日全部属设备依赖 |
| 版本常量 | APP `3.0.0-alpha.1`、CORE_API `1`、CONFIG_SCHEMA `1`（`core/.../TvrcVersion.kt`） |

> 后续开发状态以本页需求总览与 **docs/requirements-v3.md** 为准；HANDOFF.md 保留历史交接依据。170 项通过是已有测试报告的基线，不代表新增需求或真机验收已经通过。

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
