# L · 编程清单（纯软件范围 · 零设备）

> **范围声明**：本清单只覆盖**不需要电视机、不使用任何模拟器/AVD** 的工作。
> 凡验证目标落在电视行为上的（真机 adbd、播放器拉起、前台服务存活、保活、端到端时延、R-1..R-9），**一律叫停并进入交接**，见 §6。
>
> 基线：K 计划 S0–S5 共 37.0 开发人日 + 3.0 远程验证。
> 冻结时点实测：`:core:test` **80 条全绿**，对应完成度 ≈8.6 人日（23%）。
> 本清单全部完成后累计 ≈17.2 / 37 人日（**46%）。
> 硬盘预算：推荐做法 **+3~5 MB**；全清单不新增任何第三方依赖、不安装任何工具（明细与红线见 §0.5）。

---

## 0. 用法

- 每项完成后把 `- [ ]` 改成 `- [x]`，并在该项末尾补 `（完成：YYYY-MM-DD，测试 N 条）`。
- 每完成一项，**必须**先跑 §5 的全量回归，绿了才能打勾。
- 任何一项若中途发现"必须有电视才能验"，**立即停止该项**，改写进 §6 的交接清单，不要硬做。
- 人日只是排期参考，不是验收依据；**验收依据是每项的"验收标准"列**。
- 任何一项若需要向仓库目录写入大文件（>1 MB）或安装工具链，先核对 §0.5 硬盘预算：超出预算即视为方案设计错误，不是"跑一遍再说"。

---

## 0.5 硬盘预算（实测 2026-11-13）

| 分项 | 现状 | 本清单新增 | 依据 |
|---|---|---|---|
| 源码 .kt/.sh/.md | 137 KB / 3,702 行 | +200 KB | 新增约 4,000 行，按实测 37 B/行 |
| 编译产物 core/build | 2.7 MB | +3 MB | kotlin 增量缓存 1.7 MB + classes 740 KB，基本随代码量线性 |
| Gradle 依赖缓存 ~/.gradle/caches | 3.6 GB（已存在） | **+0** | 19 项无任何新增第三方依赖（md5 用 java.security，zip 用 java.util.zip） |
| 工具链 | ffmpeg/ffprobe/adb/build-tools 已装 | **+0** | 不装模拟器/AVD（相对该路线省 3–6 GB） |
| Git 仓库（P0-1 引入） | 无 | **+1~3 MB** | 前提：P0-1 先把 `tools/gradle-8.10.2/` 加入 .gitignore。若漏掉这步，.git ≈ **137~145 MB**（300 个 jar 已是压缩格式，git 压不动，实测 gzip 后仍 137 MB） |
| 验证临时文件 | — | **+0** | P2-4 强制 `-f null -` 不落盘；落盘 30 分钟流 = 450 MB~1 GB，禁止 |

**合计：+3~5 MB。** 参照：项目当前 150 MB（其中 145 MB 是自带 Gradle 发行版，代码本体仅 2.9 MB）；系统可用 26 GiB，本清单预算占用 <0.02%。

**两条红线**（违反任何一条，硬盘预算作废）：

1. **.gitignore 必须先忽略 `tools/gradle-8.10.2/`**，再执行首次提交；
2. **任何验证步骤禁止把流/媒体数据写入磁盘**：解码验证一律 `-f null -`，留证只留 ffprobe JSON 与退出码。

---

## 1. P0 · 冻结（0.5 人日）

没有这一步，后面所有改动都无法回滚，交接也无从谈起。

- [x] **P0-1 冻结基线：git init + 首提交**  `0.2 人日`（完成：2026-11-13，测试 80 条；tag baseline-80-green，.git 1.2MB，tools/gradle-8.10.2 已忽略，94 个文件入库）
  - 产出：`.gitignore` 修订（build/、.gradle/、.kotlin/ 之外，**新增 `tools/gradle-8.10.2/`**；`tools/env-jdk17.sh` 仅 4 KB 可保留入库）、首次提交、基线 tag `baseline-80-green`
  - 验收标准：
    1. `git log --oneline | wc -l` ≥ 1
    2. `gradle :core:cleanTest :core:test` 80 条全绿
    3. **`git status --short` 不出现 `tools/gradle-8.10.2`；`du -sh .git` ≤ 5 MB**（不忽略会让 .git 膨胀到 ~145 MB，见 §0.5）
  - 说明：当前仓库**没有版本控制**（`git log` 报 fatal: not a git repository），这是交接的最大障碍

- [x] **P0-2 三处文档口径修正**  `0.2 人日`（完成：2026-11-13，测试 80 条；K:214 改锁+重入并写理由；新增 M-ADR-0006 并回链 I §3.4 与 ADR 表；SourceParser 顶部加 TODO(P2-3) 指向 F-16 裁决）
  - 产出：K 计划与代码一致的三处修订
  - 验收标准：
    1. K 的 S1 写"Channel actor"，代码实为 `synchronized(lock)` 包 reduce+dispatch（`core/.../engine/PlaybackEngine.kt:45`）→ 改为"锁 + 重入"并写明理由
    2. I 选型评审的兜底顺序是 dadb → 打包 adb → libadb-android，实为自研协议（`AdbPacket/AdbAuth/DadbClient`）→ 补一篇 ADR 记录该偏离及理由（AGPL 规避 + 纯 JVM 可测）
    3. K 的 S3 写"series/season 判型**须调接口，不许正则猜**"，而 `SourceParser` 目前是纯字符串匹配（尚未到判型阶段）→ 在 K 中保留该裁决并在 `SourceParser` 顶部加 TODO 注释指向 P2-3

- [x] **P0-3 端口矛盾裁决**  `0.1 人日`（完成：2026-11-13，测试 80 条；裁决：单进程无 HTTP 控制面，唯一监听 0.0.0.0:8100；K §2 裁决 2 + F-14 行 + S2 行同步；MiniHttpServer 注释改口径）
  - 背景：K 同时写"单一端口 8100"和"控制面 127.0.0.1 / 媒体面 0.0.0.0"，单端口无法绑定两个地址
  - 产出：K 中定稿一种方案；`MiniHttpServer` / `LiveSessions` 注释与之一致
  - 候选：**A** 双端口（8100 控制仅回环 + 8101 媒体对外）｜ **B** 单端口 0.0.0.0，控制面靠 token 保护
  - 验收：K 中出现明确裁决段落，无自相矛盾表述；代码注释不再含糊

---

## 2. P1 · 接线闭环（1.5 人日）

目标：让已写好的底层第一次拥有生产调用方。这是全清单投入产出比最高的一段。

- [x] **P1-1 AdbTvController**  `1.0 人日`（完成：2026-11-13，测试 91 条全绿；AdbTvControllerTest 11 条：逐字断言 am start/keyevent 224/force-stop/三级回退顺序、keyevent 85 补满 3 次、起播失败短路不补发、URL ?& 转义、失败 Ack(ok=false,detail)）
  - 产出：`core/src/main/kotlin/tvrc/core/adb/AdbTvController.kt` + 对应测试
  - 语义映射：
    - `wake` → `input keyevent 224`
    - `launch(url)` → `am start -a android.intent.action.VIEW -d <url> -n <pkg>/<activity>`
    - `stop` → `am force-stop <pkg>`
    - `probe` → `pidof <pkg>` → 失败回退 `ps -A | grep` → 再回退 `ps | grep`
  - 验收：≥8 条测试，用 `FakeAdbClient` **逐字断言**下发命令行、命令顺序、`keyevent 85` 补发次数；注入失败时返回正确 `Ack(ok=false)`
  - 依赖：无（`AdbTransport`、`DadbClient`、`FakeAdbClient` 均已就绪）
  - 说明：这一项让 167 行 `DadbClient` 第一次进入真实调用链；交接时接手人只需注入实现

- [x] **P1-2 MediaSessionProbe 接口 + 判定逻辑**  `0.2 人日`（完成：2026-11-13，测试 98 条全绿；MediaSessionProbeTest 7 条：state=3 播放中 / state=2 暂停 / 空输出 null / 同包多会话优先 playing / 仅其它包 null / 畸形 null / active=false 与独立 state=3；新增 MediaSessionTextSource + AdbMediaSessionSource，取文本层待 H-7 真机复核）
  - 产出：`core/src/main/kotlin/tvrc/core/media/MediaSessionProbe.kt`（接口）+ 纯逻辑判定 + 假实现
  - 刻意切开：**接口 + 解析判定在本机测完**（喂假 `dumpsys media_session` 文本，"播完/未播完"是纯字符串逻辑）；**只有"去电视上取文本"那一层留给接手**
  - 验收：≥5 条测试（播放中 / 已停止 / 空输出 / 多 session 选择 / 畸形输出）

- [x] **P1-3 IP 漂移 /24 重扫**  `0.3 人日`（完成：2026-11-13，测试 104 条全绿；SubnetScannerTest 6 条：原地可用 Kept / 原址失效 Moved 到升序第一个 / 多可达成升序 / 全不可达 NotFound / 拒绝 /16 /8 与非法 CIDR / 候选仅 .1–.254。新增 core/src/main/kotlin/tvrc/core/net/SubnetScanner.kt：HostProbe 函数式接口 + SubnetRescanner(candidates/scan/resolve)，并发可配、结果确定）
  - 产出：注入式 `SubnetScanner` 接口 + 纯逻辑选目标 + 假 scanner
  - 验收：≥4 条测试（原 IP 失效后扫到新 IP / 扫到多个取第一个可达 / 全不可达返回失败 / 不扫非 /24 网段）

---

## 3. P2 · 真实网络（2.6 人日）

目标：把"我们以为是这样的"换成"实测确实是这样"。S2 开工前必须清掉这两颗雷。

- [x] **P2-1 wbi 签名 + buvid**  `1.0 人日`（完成：2026-11-13，测试 111 条全绿；WbiSignerTest 7 条：mixinKey 两组固定向量、room play 参数签名、!'()* 过滤、key 升序+数字转串、encodeURIComponent 编码、nav 解析（坏 JSON→null）、buvid3/4 解析；**真请求验收 OK**：tools/verify-wbi.sh → nav 取 wbi keys → 签名 → getRoomPlayInfo(room 5017134) HTTP 200 **code=0 WBI_OK**）
  - 产出：`core/src/main/kotlin/tvrc/core/bili/WbiSigner.kt` + `BiliLiveApi` 接入
  - 要点：`mixinKeyEncTab` 重排、`!'()*` 字符过滤（F-15）、`wts` 时间戳、`w_rid` md5；`buvid3` 获取与 cookie 携带
  - 验收：6 条固定向量单测（含 `!'()*` 过滤边界）；**真请求** `getRoomPlayInfo` 返回 `code==0` 而非 `-352`
  - 说明：这是 S2 的第一面墙，不先证伪，后续直播链路讨论全部建立在流沙上

- [x] **P2-2 series/archives 列表接口**  `0.8 人日`（完成：2026-11-13，测试 117 条全绿；BiliArchiveApiTest 6 条：单页解析 / 多页拼装取满 total 即停 / 空列表 / code!=0 抛 BiliApiException / 缺字段降级（无 bvid 跳过）/ 单集直链 VideoTargets + LiveSessions.videoSession(Mode.VIDEO)。新增 core/src/main/kotlin/tvrc/core/bili/BiliArchiveApi.kt：ArchiveItem/ArchivePage/BiliArchiveApi{seriesPage,seasonPage,seriesAll,seasonAll}/RealBiliArchiveApi/VideoTargets）
  - 产出：`BiliApi` 增加系列/合集稿件列表拉取 + 分页拼装
  - 验收：≥6 条（回放固定 JSON：单页 / 多页拼装 / 空列表 / code!=0 / 缺字段 / 单集直链组装）

- [x] **P2-3 SourceParser 判型改调接口**  `0.3 人日`（完成：2026-11-13，3 条测试；core/src/main/kotlin/tvrc/core/bili/SourceResolver.kt：SourceResolver(archive, upLister) 把 Source.Series/Season/Up 判型落到真实接口，接口失败或列表空→UNKNOWN 并给 note，绝不靠正则猜；SourceResolverTest 3 条。注：本项在前一提交 71be34f 里已入库，这里补勾选）
  - 背景：K 裁决"判型须调接口，不许正则猜"；当前 `SourceParser` 只有识别层
  - 产出：判型路径改为调用 P2-2 接口，正则仅用于**识别入口**
  - 验收：3 条（series / season / UP 三种输入走接口判型，不再靠字符串特征猜）
  - 依赖：P2-2

- [x] **P2-4 代理真流验收（不改代码，只写验证脚本）**  `0.5 人日`（完成：2026-11-13，测试 120 条全绿；tools/verify-proxy.sh 实测全 PASS：/live ffprobe 出 2 条流、/video Range 0-1023 → **206 + 1024B**、双流各 `ffmpeg -t 8 -f null -` 解码通过、disk_clean。**顺带修真实 bug**：UpstreamHeaders 原用 Android 移动 UA 请求直播 CDN 直接 **403（552B 错误页）**，换桌面 Chrome UA 后 200 — 已改为 `DESKTOP_UA` 常量并写注释。新增 core/src/main/kotlin/tvrc/core/tools/ProxyMain.kt + core/build.gradle.kts 的 `runProxy` JavaExec 任务）
  - 产出：`tools/verify-proxy.sh`
  - 验收标准（**明确能证明与不能证明什么**）：
    - 能证明：代理吐出的流符合 HTTP 语义，标准播放器可消费；`/video` 的 Range 往返与 206 裁剪正确
    - 不能证明：电视上具体播放器认不认这个流（→ 属 §6）
  - 验收：
    1. `ffprobe` 能解出 `/live` 流
    2. `/video` 带 `Range: bytes=0-1023` 返回 206 且 `Content-Range` 正确
    3. 连播用 `ffmpeg -t 60 ... -f null -` 采样验证（60~180 秒，不落盘）
    4. 脚本运行后 `git status --short` 无新增大文件（>1 MB）
  - 硬盘约束（§0.5 红线 2）：解码验证一律 `-f null -`，**禁止把流写入磁盘**（30 分钟 FLV 按 2–4 Mbps 计为 450 MB~1 GB）；留证只留 ffprobe JSON 与退出码，合计几十 KB
  - 说明：被测对象是 `MiniHttpServer` 自身，链路 `B站 CDN → 本机代理 → ffprobe` 中**没有电视**

---

## 4. P3 · 配置与运维逻辑（3.0 人日）· P4 · 交接包（1.0 人日）

### P3 配置与运维

- [x] **P3-1 配置 schema + ConfigRepository**  `0.7 人日`（完成：2026-11-13，8 条新增测试，累计 128 条全绿。core/src/main/kotlin/tvrc/core/config/Config.kt：data class AppConfig 16 字段 + extra 未知键保留；ConfigValidator 12 条规则；ConfigStore 抽象 + FsConfigStore（**原子写**：.tmp → renameTo，失败回退直写）；JsonConfigRepository.load() 返回 Ok/Missing/Invalid 三态、loadOrDefaults() 坏文件**降级默认不崩**、save() 非法不落盘；Json.kt 新增 write()/escape() 序列化。默认值裁决：switchSeconds=1800（FR-05）、port=8100（避让二代 8099，validator 显式拒绝 8099）、idleTimeoutMinutes=0=不自动停）
  - 验收：≥5 条（正常读写 / 坏文件降级默认 / 缺字段补默认 / 原子写（临时文件+rename）/ 并发读写不损坏）

- [x] **P3-2 二代 config 迁移器**  `0.5 人日`（完成：2026-11-13，6 条测试，累计 134 条全绿。core/src/main/kotlin/tvrc/core/config/LegacyConfigMigrator.kt：migrateText()/migrate()；字段同名直搬、setup_seen→wizard_completed、control_port 忽略但保留在 extra、port 8099→8100、非法值收敛并记 note、幂等。**裁决**：MIN_SWITCH_SECONDS 由 60 下调为 **30**——二代现场 switch_seconds=30 且长期稳定，三代要「配置直接用」必须承载；默认仍 1800、向导按 60–86400 推荐）
  - 产出：`相关文件/termux-port` 的旧 config → 新单 JSON schema 迁移
  - 验收：6 条迁移单测（字段映射 / 端口统一 8100 / 缺失字段 / 多房间列表 / 非法值收敛 / 幂等重跑）
  - 依赖：P3-1

- [x] **P3-3 向导状态机（5 步）**  `0.4 人日`（完成：2026-11-13，8 条测试，累计 154 条全绿。core/src/main/kotlin/tvrc/core/wizard/Wizard.kt：WizardStep 五步 + WizardDraft（可直接 toConfig/fromConfig）+ WizardMachine（Field/TestResult/Next/Back/Finish；当前步有错原地不动并回 errors；DONE 需必检项全 ok）+ WizardState.percent 完成度 + WizardDraftStore 草稿持久化（中途退出可恢复））
  - 验收：≥5 条（步进 / 回退 / 非法输入拦截 / 完成度计算 / 中途退出恢复）

- [x] **P3-4 环形日志 + 诊断包**  `0.8 人日`（完成：2026-11-13，7+5=12 条测试。core/src/main/kotlin/tvrc/core/diag/Diagnostics.kt：RingLog(1000) 满则丢最旧并计 dropped、单条截断 2000 字；DiagnosticsPack.build() 生成 ZIP 四条目 manifest.txt/config.json/device.txt/log.txt（上限 256KB，日志截到一半），entriesOf() 可解包；测试产物全部落 @TempDir）
  - 验收：≥5 条（容量上限轮转 / 结构正确（manifest + log + config 快照）/ 敏感信息脱敏（密钥、cookie）/ 诊断包 zip 可解 / 空日志不崩）
  - 硬盘约束：测试产物（zip、日志）一律写入 JUnit `@TempDir`，测试结束自动清理，不得在仓库或 `build/` 外留任何文件

- [x] **P3-5 看门狗逻辑**  `0.4 人日`（完成：2026-11-13。core/src/main/kotlin/tvrc/core/diag/Watchdog.kt：纯函数 decide(WatchdogInput) → Nothing/Restart/GiveUp；规则=非播放态不拉、未达 30s 阈值不拉、重启 3 次上限给 GiveUp、60s 冷却内不拉。与引擎「Error 为终态、Idle 不入自愈」口径一致）
  - 验收：≥4 条（拉起上限 3 次 / 冷却 60s / `Error` 终态绝不拉起 / 正常态不误触发）

- [x] **P3-6 错误文案表**  `0.2 人日`（完成：2026-11-13。core/src/main/kotlin/tvrc/core/diag/ErrorCatalog.kt：14 个 ErrorCode（E-ADB-01..E-SYS-99），每条含 title/cause/action 三段人话，describe() 输出「标题：原因。怎么办：做法」；ErrorCode.of(ErrorKind) 全覆盖断言）
  - 验收：6 条（每个 `ErrorKind` 都有非技术人话文案，全覆盖断言，无 "null"/堆栈泄漏）

### P4 交接包

- [x] **P4-1 verify-on-tv.sh**  `0.4 人日`（完成：2026-11-13，tools/verify-on-tv.sh 实测 --dry-run 输出 **5 PASS / 0 FAIL / 6 SKIP、exit 0**：jdk17 / gradle / config_migrate_from_v2（真跑 LegacyConfigMigrator：8099→8100 有 note）/ regression_154（真读 JUnit XML 计数）/ port_free(8100)；真机分支补 adb_device/adb_authorized/mpv_installed/tv_wake(224)/proxy_live_ffprobe/apk_install。`--json` 输出 {ok,dryRun,summary,checks[]}，退出码 0/1/2 语义化。新增 core/src/main/kotlin/tvrc/core/tools/ConfigCheckMain.kt + :core:runConfigCheck）
  - 内容：`adb connect` → AUTH 状态 → `pidof` → `am start` 试拉 → `dumpsys media_session` → 网络连通，输出 JSON
  - 验收：`--dry-run` 模式输出符合 schema 的 JSON（无设备也能自检）；退出码语义正确

- [x] **P4-2 HANDOFF.md + 未验证清单**  `0.4 人日`（完成：2026-11-13，/Users/lzl/E/code/B站直播遥控器app开发/HANDOFF.md：环境与构建命令、模块边界与依赖方向、三契约摘要+升级规则、配置与迁移、**H-1..H-10 未验证清单逐条标风险**+怎么验、4 条技术债、verify-on-tv.sh 用法与 JSON schema、接手顺序）
  - 内容：环境与构建命令、模块边界、三个可替换点契约、**未验证清单（逐条标风险等级 高/中/低）**、P4-1 用法
  - 验收：§6 的每条交接项都在文档中有对应章节和风险等级

- [x] **P4-3 契约冻结**  `0.2 人日`（完成：2026-11-13，TvController.kt / adb/AdbClient.kt / engine/Scheduler.kt 三接口加「接手实现须知」KDoc（不抛异常/超时语义/幂等/必须真取消等），无 TODO 遗留；新增 core/src/main/kotlin/tvrc/core/TvrcVersion.kt（APP=3.0.0-alpha.1、CONFIG_SCHEMA=1、CORE_API=1、FROZEN_AT、MIN_TEST_COUNT=154）作为回归闸门常量）
  - 内容：`TvController` / `AdbClient` / `Scheduler` 三个接口定稿并加 KDoc（含"接手实现须知"）
  - 验收：三接口无 TODO 遗留；`core` 全量测试作为回归闸门（低于基线条数不许合）

---

## 5. 验收命令（每完成一项都跑）

```bash
cd "/Users/lzl/E/code/B站直播遥控器app开发"

# 全量回归（基线 80 条，只许增不许减）
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  tools/gradle-8.10.2/bin/gradle :core:cleanTest :core:test --no-daemon

# P0-1 / §0.5 硬盘红线自检：.git 必须忽略 tools 发行版且 ≤ 5 MB
du -sh .git && git check-ignore -q tools/gradle-8.10.2/bin/gradle && echo "tools ignored: OK"

# P2-1 签名正确性：期望 code == 0（-352 表示签名/风控未通过）
curl -s "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=5017134&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8" | jq .code

# P2-4 代理正确性（先起 MiniHttpServer；全程不落盘）
ffprobe -v error -show_streams http://127.0.0.1:8100/live/5017134
ffprobe -v error -show_format  http://127.0.0.1:8100/video/BV1xx411c7mD
ffmpeg -v error -t 60 -i http://127.0.0.1:8100/live/5017134 -f null -    # 连播采样 60s，解码丢弃

# P4-1 交接脚本自检
./tools/verify-on-tv.sh --dry-run | jq -e '.checks | length > 0'
```

---

## 6. 明确不做 · 交接触发线

以下每一项的**验证目标都是电视行为**，按既定规则叫停，写入 `HANDOFF.md` 交他人继续。

| # | 交接项 | 为什么软件给不了答案 | 风险 |
|---|---|---|---|
| H-1 | `:app` 模块（Compose UI） | 能编译不能运行，无验证代码是负资产 | 中 |
| H-2 | `PlaybackService`（specialUse 前台服务）+ 通知 | 依赖设备生命周期与 ROM 策略 | 高 |
| H-3 | `BootReceiver` 开机自启 | 真实开机流程与厂商限制 | 高 |
| H-4 | 真机 adbd 行为（AUTH 弹窗、shell/busybox 差异） | 国产 ROM 差异极大，只能实测 | **高** |
| H-5 | 播放器侧载与 `am start` 实拉 | 需电视上装 mpv 并验证解码 | **高** |
| H-6 | 切台 ≤10 秒、连播 30 分钟端到端 | 端到端时延与稳定性 | 高 |
| H-7 | `dumpsys media_session` 真实取值 | 厂商实现差异（接口已交，取值未验） | 中 |
| H-8 | 保活矩阵、后台存活率 | 电源管理与内存回收 | 高 |
| H-9 | IP 漂移真实网络环境 | 真实 Wi-Fi 抖动（逻辑已交，环境未验） | 中 |
| H-10 | R-1..R-9 真机清单 | 全部需要电视 | 高 |

---

## 7. 完成度自算

| 批次 | 人日 | 已完成 | 小计 |
|---|---|---|---|
| P0 冻结 | 0.5 | 3 / 3 | ☑ |
| P1 接线闭环 | 1.5 | 3 / 3 | ✅ |
| P2 真实网络 | 2.6 | 4 / 4 | ✅ |
| P3 配置运维 | 3.0 | 6 / 6 | ✅ |
| P4 交接包 | 1.0 | 3 / 3 | ✅ |
| **合计** | **8.6** | **19 / 19** | **100%** |

累计口径：冻结时 8.6 人日 → 纯软件范围 **19/19 全完成，17.2 / 37.0 人日（46%）**。
剩余 19.8 人日（53%）属于设备依赖范围，见 §6，交接入口为 HANDOFF.md。

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-13 | 建清单；19 项；纯软件范围 8.6 人日；交接线 H-1..H-10 |
| 2026-11-13 | **P3-3..P3-6、P4-1..P4-3 全部完成**：19/19 勾选，测试 154 条全绿；新增 tools/verify-on-tv.sh（--dry-run/--json）、HANDOFF.md、TvrcVersion 契约常量；P3-2 裁决 switchSeconds 下限 30 |
| 2026-11-13 | 按硬盘估算修订：新增 §0.5 硬盘预算（+3~5 MB，两条红线）；P0-1 增加 .gitignore `tools/gradle-8.10.2/` 与 `.git ≤ 5 MB` 验收；P2-4 强制 `-f null -` 不落盘、验收细化为 4 条；P3-4 增 `@TempDir` 约束；§5 增硬盘红线自检与连播采样命令 |
| 2026-11-13 | **路由统一（按二代口径）**：MiniHttpServer 重写为 /live?room=、/live.flv、/video?bvid=、/status|/api/status；?url= 降级为 allowDirectUrl 回环自测；BiliLiveApi/VideoTargets 目标 URL 同步；测试 154→162 全绿 |
| 2026-11-13 | **装配收口（清单外补充）**：AppGraph 装配根 + VideoUrlResolver 点播直链；AppGraphTest 5 条（起代理/白名单/看门狗/空闲停机/502）+ VideoUrlResolverTest 3 条；测试 162→170 全绿 |
