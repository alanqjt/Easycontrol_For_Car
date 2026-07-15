# 易控-byd

[![GitHub license](https://img.shields.io/github/license/alanqjt/Easycontrol_For_Car.svg)](https://github.com/alanqjt/Easycontrol_For_Car/blob/main/LICENSE)
[![GitHub release](https://img.shields.io/github/release/alanqjt/Easycontrol_For_Car.svg)](https://github.com/alanqjt/Easycontrol_For_Car/releases/)
[![GitHub downloads](https://img.shields.io/github/downloads/alanqjt/Easycontrol_For_Car/total.svg)](https://github.com/alanqjt/Easycontrol_For_Car/releases/)

> 本项目基于易控车机版 1.6.0 开发，主要针对车机 UI、多窗口投屏、音频分路和导航播报体验进行优化。

本项目使用 [GPL-3.0](LICENSE) 开源许可证。可以免费使用、修改和分发，但必须遵守许可证要求。

最新正式版本：[易控车机版 2.0.0](更新包/2.0.0/README.md)。

## 主要功能

- USB、局域网连接手机并投屏到车机。
- 支持屏幕镜像和多应用流转。
- 支持导航、音乐等多个应用同时显示。
- 优化横屏车机界面、设备卡片和设置弹窗。
- 集成比亚迪车辆状态监听，可在倒车影像或 360 全景开启时隐藏投屏。
- 支持导航音频和媒体音频独立采集、编码、传输及播放。

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

- 荣耀部分机型当前仍可能无法通过 USB ADB 连接；问题尚未解决，可以暂时改用无线调试或局域网连接。
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
