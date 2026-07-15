# 易控车机版 2.0.0

发布日期：2026-07-15

## 安装包

- 文件：[EasyControl-Car-2.0.0-release.apk](https://github.com/alanqjt/Easycontrol_For_Car/releases/download/2.0.0/EasyControl-Car-2.0.0-release.apk)
- 应用包名：`top.eiyooooo.easycontrol.app.byd`
- 版本：`2.0.0`
- 版本代码：`200`
- APK 大小：约 2.8 MB
- SHA-256：`1b028c1e3856a52be0290befdd2d311a751dc7a8d1fa39a8885051ed11ed6e35`

可以通过 ADB 覆盖安装：

```bash
adb install -r EasyControl-Car-2.0.0-release.apk
```

## 本次更新

- 新增内嵌投屏模式，支持在 APP 主界面内直接显示远端画面。
- 优化左右分屏、上下分屏、竖屏和动态窗口尺寸适配。
- 增加一键关闭全部投屏、内嵌工具栏显示隐藏和分屏启动脚本。
- 导航音频和媒体音频按 UID 独立采集、编码、传输和播放。
- 导航使用独立常驻 `AudioTrack`，减少导航播报开头和结尾被截断。
- 优化 USB ADB 握手、数据确认、断开重连和异常日志。
- 修复 server JAR 异步上传时缓冲区被覆盖，导致文件内容损坏的问题。
- server JAR 上传后校验大小和 SHA-256，校验失败时自动重新上传。

## 已知问题

- 荣耀部分机型通过 USB ADB 连接仍可能失败，该问题在 2.0.0 中尚未解决。
- 荣耀 USB 连接失败时，可以暂时使用无线调试或局域网连接。
- 不同手机 ROM、车机系统和比亚迪车型的兼容性仍需分别实机验证。
- 基础音频采集要求被控手机为 Android 12 或更高版本；按 UID 分路建议 Android 13 或更高版本。

## 构建校验

- 构建任务：`./gradlew :app:assembleRelease`
- 构建类型：Release，已启用 R8 混淆和资源压缩。
- APK Signature Scheme v1：通过。
- APK Signature Scheme v2：通过。
- 签名证书 SHA-256：`f8a12dc91188ac1e6ff76a4b6fdc7bc3bddb8b3e631803ce71c31d17c36bcf57`

校验安装包：

```bash
shasum -a 256 EasyControl-Car-2.0.0-release.apk
```

输出应为：

```text
1b028c1e3856a52be0290befdd2d311a751dc7a8d1fa39a8885051ed11ed6e35
```
