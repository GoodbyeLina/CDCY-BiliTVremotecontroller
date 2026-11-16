# B站轮播台 · Android 手机 App（薄壳版）

这是“路线 B”：一个真正的 APK，图标、启动页、错误页都是原生的；界面是一个
WebView，加载 Termux 里的本地控制台 `http://127.0.0.1:8098/`。

> 现成 APK：`../TVStationRemote-0.2.0.apk`（debug 签名，可直接安装）。
> 这是 0.2.0 第一版：只做直播间；视频/合集/BV 是第二版。
>
> 从零调试的完整步骤见 [`../全流程调试手册.md`](../全流程调试手册.md)。

> 它**不是**完全独立 App：后台仍然需要 Termux 里的 `tvstation` + `tvcontrol`。
> 这样做的原因是：Python 代理、adb、runit 已经在 Termux 里跑通了，APK 只做“遥控器”。

## 它长什么样

- 打开 App → **原生环境检查**：
  - Android 版本 / CPU 架构 / 存储
  - Termux、Termux:Boot 是否安装
  - `127.0.0.1:8098` 后端是否就绪
  - 缺什么显示什么按钮：安装 Termux、安装 Termux:Boot、打开 Termux、复制后端命令、重新检测
  - 全部通过才进 WebView
- 进 WebView 后 → **配置向导**：
  - 1/5 手机网络（自动检测当前 IP）
  - 2/5 电视连接（扫描局域网 5555 / 手动 IP / adb 授权）
  - 3/5 电视播放器（mpv-android）
  - 4/5 选直播间（搜 UP / 粘链接，允许未开播）
  - 5/5 完成
- 以后每次打开：
  - 全部检查通过 → 直接进主页，不再显示向导
  - 哪一步失败 → 只弹那一步的修复卡片
  - 设置页有单项“修改”和“重新初始化”
- 如果 Termux 后端没起来，App 显示原生错误页：
  - “重新连接”
  - “打开 Termux”
  - 提示执行 `cd ~/tvstation && ./tvctl status`

## 前置条件

1. 手机已按 `termux-port/新手搭建.md` 装好 Termux 后端。
2. Termux 里 `./tvctl status` 能看到：
   - `run: tvstation: ...`
   - `run: tvcontrol: ...`
3. 手机和电视同一 WiFi。

## 构建

### 用 Android Studio（推荐给要改代码的人）

1. Android Studio 打开 `android-app/` 目录。
2. 等 Gradle Sync 完成（需要网络下载 Android Gradle Plugin）。
3. Run ▶ 到手机，或 Build → Build APK(s)。

### 用命令行

需要：

- JDK 17
- Android SDK（platform 34、build-tools 34.0.0）
- Gradle 8.7

```bash
cd android-app
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

APK 输出：

```
android-app/app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者把 APK 传到手机，用文件管理器安装（需要允许“安装未知应用”）。

## 包名 / 版本

- 包名：`com.tvstation.remote`
- 应用名：`B站轮播台`
- minSdk：24（Android 7.0）
- targetSdk：34
- 版本：0.2.0

## 当前限制

- 仍要装 Termux；App 不会自己启动 Termux 服务（只提供“打开 Termux”按钮）。
- 界面是 WebView；如果手机系统 WebView 太旧，页面可能提示“WebView 太旧”，
  去应用商店/系统更新里更新 **Android System WebView**。
- 控制台只监听 `127.0.0.1:8098`，同一 WiFi 的其它设备打不开（这是故意的）。
- 视频/合集/BV 播放是第二版；第一版只做直播间。

## 以后可以加

- 用 `RUN_COMMAND` intent 让 App 直接启动/重启 Termux 服务（需要 Termux 开
  `allow-external-apps` 并授予权限）。
- 把 WebView 逐屏换成原生界面。
- 加前台通知/快捷方式。
- 打包 release APK（签名）。
