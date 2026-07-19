# 易控车机版 3.0.0

发布日期：2026-07-19

## 适配范围

- 重点适配车型：比亚迪宋 L DM-i。
- 重点适配车机：15.6 英寸中控屏。
- 已验证被控手机：小米 11、小米 15。
- 其他车型、屏幕尺寸和手机 ROM 尚未完整验证，兼容性不作保证。

## 安装包

- 文件：[EasyControl-Car-3.0.0-release.apk](https://github.com/alanqjt/Easycontrol_For_Car/releases/download/3.0.0/EasyControl-Car-3.0.0-release.apk)
- 仓库内文件：[EasyControl-Car-3.0.0-release.apk](EasyControl-Car-3.0.0-release.apk)
- 应用包名：`top.eiyooooo.easycontrol.app.byd`
- 版本：`3.0.0`
- 版本代码：`300`
- APK 大小：2,988,687 字节，约 2.9 MB
- SHA-256：`ad94cfe7e55bb9181e14d4ce5cfc30208e277bb266632b29ad8fdf4e64002d33`

可以通过 ADB 覆盖安装：

```bash
adb install -r EasyControl-Car-3.0.0-release.apk
```

## 本次更新

### 音乐导航双窗口

- 内嵌模式新增“音乐导航”按钮，一次启动导航与音乐两个应用流转窗口。
- 支持分别选择导航应用和音乐应用，默认使用高德地图与 QQ 音乐。
- 导航窗口固定在左侧剩余区域；音乐窗口按手机竖屏镜像宽度固定在右侧。
- 两个窗口不可拖动，避免布局偏移。
- 增加“一键关闭全部投屏”，关闭双窗口并返回首页。
- 每个投屏自己的工具栏默认收起，需要时再展开。

### 内嵌投屏与分屏适配

- 取消内嵌模式只能存在一个投屏的限制，允许音乐导航双窗口同时运行。
- 优化全屏、左右分屏、竖屏和动态窗口尺寸下的设备列表布局。
- 优化高德地图内嵌横屏的虚拟显示尺寸、方向及显示区域计算。
- 增加被控端系统导航栏隐藏策略和详细诊断日志。
- 非内嵌模式继续按手机原始画面比例显示，避免错误拉伸。

### 导航和媒体音频

- 保留导航、媒体两路独立采集、编码、传输和播放。
- 导航使用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，媒体使用 `USAGE_MEDIA`。
- 完善多投屏并存时的音频 owner 和角色调度。
- 移除可能造成削波的导航音频额外增益。
- 增加 PCM 削波、`AudioTrack` 写入耗时和 underrun 统计，便于定位破音和卡顿。

### 音视频传输与连接

- 增加视频队列、解码帧率、传输吞吐和 socket 阻塞诊断。
- 优化 USB 与 Wi-Fi 场景下的音视频队列及写入处理。
- 完善 ADB helper JAR 上传、大小和 SHA-256 校验、失败重试与启动日志。
- Android Studio Run、Debug、Release 自动重新编译并同步 server JAR。

### UI

- 统一首页、设备卡片、设置弹窗和应用选择弹窗的圆角样式。
- 移除按钮默认阴影、按压阴影和状态动画，避免圆角底部出现灰边。
- 优化车机分屏后的设备卡片尺寸和文字显示。

## 已知问题

- 荣耀部分机型通过 USB ADB 连接仍可能失败，可以暂时使用无线调试或局域网连接。
- 高德地图内嵌横屏、被控端导航栏隐藏效果受手机 ROM 和系统能力影响，其他手机仍可能显示异常。
- Wi-Fi 投屏可能受热点负载、射频干扰、车机网络栈和手机 ROM 影响而卡顿；追求稳定性时建议使用 USB。
- 导航音频已使用导航用途请求音频焦点，但能否压低车机原生音乐取决于比亚迪车机音频策略，不保证所有系统版本生效。
- 基础音频采集要求被控手机为 Android 12 或更高版本；按 UID 分路建议 Android 13 或更高版本。

## 构建校验

- 构建任务：`./gradlew :app:assembleRelease --stacktrace`
- 构建类型：Release，已启用 R8 混淆和资源压缩。
- APK Signature Scheme v1：通过。
- APK Signature Scheme v2：通过。
- 签名证书 SHA-256：`f8a12dc91188ac1e6ff76a4b6fdc7bc3bddb8b3e631803ce71c31d17c36bcf57`

校验安装包：

```bash
shasum -a 256 EasyControl-Car-3.0.0-release.apk
```

输出应为：

```text
ad94cfe7e55bb9181e14d4ce5cfc30208e277bb266632b29ad8fdf4e64002d33
```
