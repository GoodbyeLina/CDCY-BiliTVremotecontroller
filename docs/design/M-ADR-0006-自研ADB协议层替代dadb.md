# M · ADR-0006：自研 ADB 协议层（AdbPacket/AdbAuth/DadbClient），替代 dadb 主选

> 状态：已接受（2026-11-13，P0-2）｜ 影响范围：core/src/main/kotlin/tvrc/core/adb/、I 选型评审 §3.4、K S1

## 背景

I-选型评审 §3.4 的结论是「dadb 为主（dev.mobile:dadb），兜底顺序 dadb → 打包 adb → libadb-android」。实际开发走到这一步时，我们发现 ADB 经典 5555 路径所需的协议面非常小：

- 握手：CNXN → AUTH(token→signature→RSAPUBLICKEY) → CNXN，共 3 种 AUTH 子类型；
- 命令：OPEN("shell:cmd\0") → OKAY → WRTE… → CLSE，6 种帧；
- 帧格式：24 字节定长头（command/arg0/arg1/length/crc32/magic，全小端）+ payload，CRC32 校验。

全部语义 ≈ 170 行 Kotlin 可覆盖（AdbPacket 85 行 + AdbAuth 12 行 + DadbClient 170 行）。

## 决策

**不引入 dadb 库，自研上述三层**，直接实现既有 `AdbClient` 接口。I §3.4 的兜底顺序降级为「预案」：若真机出现自研实现覆盖不了的差异（如只开配对模式 TLS），按原顺序切库或打包 adb，接口不变、上层不动。

## 理由

1. **零依赖**：dadb（含 kotlin-stdlib 依赖链）会把第三方坐标引入 :core，违背「纯 JVM、零传递依赖、`gradle --offline` 可构建」的模块定位；自研后 19 项清单新增依赖数为 0。
2. **纯 JVM 可测**：协议层可以在进程内用假设备（`FakeAdbServer`，真实 TCP + 真实字节流）100% 测完——握手、签名可被公钥验证、逐帧确认、超时——不需要手机、电视或 AVD。dadb 的连接生命周期绑定其自身实现，无法这样切。
3. **行为边界自明**：自研只做 legacy shell，明确「无退出码（提供 shellWithExitCode 包装）、无 shell_v2、无 SYNC/端口转发」；库的黑盒面更大，出问题时要读库源码。
4. **许可与分发可控**：无第三方 NOTICE/许可文件需要随 APK 分发；对传染性许可的顾虑（按 L 清单口径）一并消除。

## 代价与边界（写明，不装作没有）

- 自研代码要自己维护协议兼容：目前已覆盖经典 5555 全握手；**未实现** Android 11+ 无线调试配对（TLS/SPAKE2）——那条路径仍按 I §9.1 的 V-01b 走 libadb-android 或打包 adb 预案。
- `shell()` 无退出码是 legacy 协议本身的限制，不是偷懒；`shellWithExitCode()` 用 echo 标记补齐。
- 首次授权弹窗「允许 USB 调试」仍需真机点一次（H-4 交接项）。

## 后果

- `DadbClient` 已在基线中通过 14 条协议测试（AdbPacketTest 6 + DadbClientTest 8）；
- I §3.4 的「dadb 主选」表述保持原文（评审时点的判断），以本 ADR 为准的偏离记录挂在其 ADR 表（ADR-0006）；
- 交接时接手人看到的契约：`AdbClient` 接口 + `AdbTransport`（串行/超时/重连语义）不变，换实现零上层改动。
