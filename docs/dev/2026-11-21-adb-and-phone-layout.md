# ADB 握手、自检与一加 Ace 3 屏幕适配（alpha.3）

## 故障原因

电视 IP 与手机处于同一 /24 网段，并不能证明 ADB 握手正确。alpha.2 中有以下 App 缺陷：

1. ADB 报文使用了 CRC32，AOSP 实际使用无符号字节的累加和。历史协议文档的字段名 `data_crc32` 容易造成误解，但实际算法以源码为准。
2. RSA 公钥结构开头应是 64 个 32 位字的模数长度，而不是 524 字节；线上 AUTH 公钥应为该结构的 Base64 文本、空格、注释及 NUL，而不是原始二进制结构。
3. AUTH token 应按已有 SHA-1 摘要做 RSA PKCS#1 v1.5 签名，不能再对 token 哈希一次。
4. 自检以 mpv 进程存在与否判定 ADB 连通，又以名为 android 的进程存在与否决定是否查包，导致连接状态与播放器安装状态互相混淆。

对照依据：AOSP Android 10 的 [校验和实现](https://github.com/aosp-mirror/platform_system_core/blob/android-10.0.0_r47/adb/adb.cpp)、[主机认证实现](https://github.com/aosp-mirror/platform_system_core/blob/android-10.0.0_r47/adb/client/auth.cpp) 与 [公钥结构实现](https://github.com/aosp-mirror/platform_system_core/blob/android-10.0.0_r47/libcrypto_utils/android_pubkey.c)。

## 修复结果

- 按 AOSP 修正校验和、公钥布局、Base64 线上编码和 token 签名。授权弹窗最多等待 30 秒，失败或异常释放 socket。
- 自检实际执行 echo 命令验证连接及授权，再独立查询已安装包；不能查询时显示「尚未检测/确认」，不再误报未安装。
- 保留连接失败原始原因，并在诊断日志中记录自检结果。
- Android 15/16 全屏布局根据 WindowInsets 避开状态栏、挖孔、手势区域和输入法；底部导航随键盘可用区域调整。
- 标题与版本号使用可收缩布局，状态分两行，自检长文本在剩余宽度内换行；设备输入框与按钮设置最小触控高度。
- 版本升级为 `3.0.0-alpha.3` / versionCode 5，保留 alpha.2 的配置保存与输入草稿修复。

## 验证与边界

运行 `:core:test :app:testDebugUnitTest :app:assembleDebug`。核心测试新增固定 CNXN 字节向量，RSA 测试直接解开签名检查 DigestInfo 与原 token，公钥测试校验字段与模数，避免只用同一套错误编解码器相互验证。

Android 测试覆盖 ADB 连通但播放器未运行、播放器未安装、连接失败不误报缺少包、查包失败不误报断连，以及 API 35 下状态栏/手势/键盘避让和标题宽度。保留所有配置表单回归。

2026-11-21 验证结果：核心 191 项、Android 12 项全部通过，无失败、错误或跳过；APK 构建成功。

真实一加 Ace 3 与电视仍需复测：覆盖安装、点击自检、在电视确认「允许调试」，再验证播放器安装与实际播放。网络 ADB 端口、设备隔离和厂商授权策略仍取决于真实设备；新诊断会保留具体错误。
