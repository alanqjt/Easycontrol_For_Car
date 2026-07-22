# 易控-byd

[![GitHub license](https://img.shields.io/github/license/alanqjt/Easycontrol_For_Car.svg)](https://github.com/alanqjt/Easycontrol_For_Car/blob/main/LICENSE)
[![GitHub release](https://img.shields.io/github/release/alanqjt/Easycontrol_For_Car.svg)](https://github.com/alanqjt/Easycontrol_For_Car/releases/)


> 本项目基于易控车机版 1.6.0 开发，主要针对车机 UI、多窗口投屏、音频分路和导航播报体验进行优化。

本项目使用 [GPL-3.0](LICENSE) 开源许可证。可以免费使用、修改和分发，但必须遵守许可证要求。

最新正式版本：[易控车机版 3.0.2](更新包/3.0.2/README.md)。

## 主要功能

- USB、局域网连接手机并投屏到车机。
- 支持屏幕镜像和多应用流转。
- 支持导航、音乐等多个应用同时显示。
- 优化横屏车机界面、设备卡片和设置弹窗。
- 集成比亚迪车辆状态监听，可在倒车影像或 360 全景开启时隐藏投屏。
- 支持导航音频和媒体音频独立采集、编码、传输及播放。

## 3.0.2 更新说明

### 荣耀连接兼容

- 增强荣耀手机 USB ADB 握手、分包、短包和零长度完成事件处理。
- 荣耀 USB 会话支持先通过 USB 完成授权和地址发现，再切换到同一局域网内的 ADB TCP 传输，缓解部分车机 Host 与荣耀 USB gadget 的兼容问题。
- 荣耀兼容模式优先保证画面，使用 H.264、最高 1280、30 FPS、2 Mbps；当前不启用投屏音频。
- USB 切换期间保留 ADB 会话，避免系统重枚举导致连接被提前关闭。

### ADB 协议与无线调试

- 完善 ADB 协议版本和 `maxData` 协商、消息边界、magic、长度及旧协议 checksum 校验。
- 增加 Android 11+ 无线调试 `STLS`、TLS 1.3 和 `_adb-tls-connect._tcp` 服务发现支持。
- ADB AUTH、无线配对和 TLS 共用同一身份密钥，减少重复授权和配对后无法连接。
- 增加 ADB 密钥与协议单元测试，覆盖公钥编码、签名、握手和异常数据。

### USB 设备与默认参数

- 不再把 `/dev/bus/usb/...` 临时内核路径保存为设备 UUID，阻止荣耀重枚举时无限生成无效设备卡片。
- 启动时清理历史 USB 路径幽灵记录；真实手机序列号记录不受影响。
- 默认画质调整为最大尺寸 1920、40 FPS、8 Mbps；新增或编辑设备时默认展开高级选项。

## 3.0.0 更新说明

### 实机适配范围

- 重点适配车型：比亚迪宋 L DM-i。
- 重点适配车机：15.6 英寸中控屏。
- 已验证被控手机：小米 11、小米 15。
- 其他车型、屏幕尺寸和手机 ROM 尚未完整验证，兼容性不作保证。

### 音乐导航双窗口

- 内嵌模式新增“音乐导航”入口，一次启动导航与音乐两个应用流转窗口。
- 支持选择导航应用和音乐应用，默认使用高德地图与 QQ 音乐。
- 导航固定在左侧剩余区域；音乐按手机竖屏镜像宽度固定在右侧。
- 双窗口不可拖动，减少驾驶过程中误操作和布局漂移。
- 增加“一键关闭全部投屏”，关闭双窗口并返回首页。
- 应用流转工具栏默认收起，需要时再展开。

### 内嵌投屏和分屏

- 取消内嵌模式只能存在一个投屏的限制，支持导航和音乐双窗口。
- 优化全屏、左右分屏、竖屏及窗口尺寸变化时的设备卡片布局。
- 增加虚拟显示尺寸、方向、横屏应用流转和被控端导航栏处理日志。
- 优化高德地图在内嵌横屏、车机分屏场景中的显示区域计算。

### 音频与传输

- 继续使用导航、媒体两路采集和独立 `AudioTrack`，完善多投屏音频 owner 调度。
- 导航通道使用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，媒体通道使用 `USAGE_MEDIA`。
- 移除可能放大削波的导航音频增益，增加 PCM 削波、写入耗时和 underrun 诊断。
- 增加视频队列、解码帧率、传输吞吐和 Wi-Fi 阻塞诊断日志。
- 优化 USB 与 Wi-Fi 连接时的音视频写入及队列处理。

### ADB、构建与 UI

- 完善 ADB helper JAR 上传、大小和 SHA-256 校验、失败重试及启动日志。
- Android Studio Run、Debug、Release 自动重新编译并同步 server JAR。
- 统一首页、设置弹窗和选择对话框的圆角，移除按钮默认阴影与状态动画。
- 增加 `3.0.0` 标签自动创建 GitHub Release 并上传正式 APK 的发布流程。

## 2.0.0 更新说明

### 内嵌投屏与分屏适配

- 新增内嵌投屏模式，远端画面可以直接显示在 APP 主界面内。
- 内嵌模式限制为单路投屏，避免屏幕镜像和应用流转同时占用显示区域。
- 优化车机左右分屏、上下分屏、竖屏及窗口尺寸变化时的布局。
- 非内嵌模式下，悬浮投屏窗口会按当前 APP 可用区域限制大小。
- 增加一键关闭全部投屏、工具栏显示隐藏及分屏启动脚本。

### ADB 与构建稳定性

- 修复部分 USB ADB 重连后残留认证包干扰新连接的问题。
- ADB `WRTE` 数据改为等待远端 `OKAY` 后再发送下一帧。
- 修复异步上传复用缓冲区导致 server JAR 长度正确但内容损坏的问题。
- server JAR 上传后校验文件大小和 SHA-256，不一致时自动重传。
- Android Studio Run、Debug 和 Release 构建会自动编译并更新内置 server JAR。

### 导航和媒体双路音频

- 音频协议升级为 v2，每个音频帧携带 `navigation` 或 `media` 角色。
- 应用流转按目标应用 UID 定向采集，避免导航和音乐被混在同一路音频中。
- 导航音频使用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 播放。
- 音乐及其他媒体音频使用 `USAGE_MEDIA` 播放。
- 导航和媒体分别使用独立的编码器、传输标记、解码器及 `AudioTrack`。
- 导航 `AudioTrack` 持续复用，并完整写入 PCM，减少播报开头、结尾被截断的问题。

### 多窗口音频调度

支持以下三路同时存在，创建顺序不影响最终角色分配：

1. 导航应用流转负责 `navigation`。
2. 音乐应用流转负责 `media`。
3. 手机直接投屏预建导航、媒体两路音频，作为备用声源。

应用流转的 UID 定向音频优先于直接投屏。直接投屏保持静音待命，不会重复播放：

- 关闭导航应用流转后，直接投屏自动接管 `navigation`。
- 关闭音乐应用流转后，直接投屏自动接管 `media`。
- 两个应用流转都关闭后，直接投屏接管两路音频。
- 关闭直接投屏不会影响仍在运行的应用流转音频。

### 稳定性优化

- 修复 Opus 配置头字节序错误导致解码器无声的问题。
- 导航音频采用完整阻塞写入，媒体音频采用低延迟非阻塞写入。
- 音频和视频使用独立 socket 写锁，避免视频阻塞拖慢音频。
- 同一设备关闭任意投屏窗口后，自动重算多连接状态和音频 owner。
- Android Studio 执行 Run 或打包时，自动编译 server 并更新 `res/raw/easycontrol_server.jar`。
- 增加音频采集、编码、解码、角色判断和 owner 切换日志。


## 兼容性与已知限制

- 荣耀 USB 兼容仍属实验功能。USB 引导后需要手机与车机处于同一局域网，并允许 ADB TCP 5555 连接；部分 ROM 仍可能失败。
- 荣耀兼容模式当前限制为 H.264、最高 1280、30 FPS、2 Mbps，且不启用投屏音频。
- 基础音频采集要求被控手机运行 Android 12 或更高版本。
- 按 UID 采集和直接投屏双路拆分要求 Android 13 或更高版本。
- UID `AudioPolicy` 属于系统能力，不同手机厂商 ROM 的支持情况可能不同。
- 未识别为地图类别或常见导航包名的应用，默认按 `media` 处理。
- 当前内置识别高德地图、百度地图、腾讯地图、Google Maps 和 Waze。
- 如果日志出现 `Direct split audio unavailable, falling back to mixed media`，说明直接投屏双路采集失败，系统已回退为混合媒体音频。此时关闭应用流转后的角色接管无法保证继续分路。
- 不同车型、车机系统和手机 ROM 仍需实机验证。

## 音频日志

遇到无声、混音或角色错误时，可以只抓取音频日志：

```bash
DEVICE=192.168.0.112:5555
adb connect "$DEVICE"
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" logcat -v threadtime EasycontrolAudio:I '*:S' > easycontrol_audio_test.log
```

完成复现后按 `Ctrl+C` 停止。重点检查：

- `audio start decision`：当前连接是否启用音频。
- `audio capture filtered by uid`：应用流转是否按 UID 采集。
- `direct audio pipelines ready: media + navigation`：直接投屏是否成功建立双路音频。
- `audio owner retained`：高优先级应用流转保持对应角色。
- `audio owner released`、`audio owner changed`：关闭窗口后是否正确迁移音频角色。

## 使用说明

- 首次打开软件时会显示本地使用说明和隐私政策。
- 之后可以在软件设置中再次查看。
- 详细操作请阅读 [HOW_TO_USE.md](HOW_TO_USE.md)。
