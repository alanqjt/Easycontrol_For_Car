# 易控车机版 3.0.2

发布日期：2026-07-22

## 安装包

- 文件：[EasyControl-Car-3.0.2-release.apk](https://github.com/alanqjt/Easycontrol_For_Car/releases/download/3.0.2/EasyControl-Car-3.0.2-release.apk)
- 仓库内文件：[EasyControl-Car-3.0.2-release.apk](EasyControl-Car-3.0.2-release.apk)
- 应用包名：`top.eiyooooo.easycontrol.app.byd`
- 版本：`3.0.2`
- 版本代码：`302`
- APK 大小：2,997,718 字节，约 2.9 MB
- SHA-256：`75d2469c74f13d8b1ebd1852e774c9ac7e5e3f481676a8b14eaf654a00d42379`

可以通过 ADB 覆盖安装：

```bash
adb install -r EasyControl-Car-3.0.2-release.apk
```

## 本次更新

### 荣耀连接兼容

- 增强荣耀手机 USB ADB 握手、异步读写、短包、分包和零长度完成事件处理。
- 荣耀 USB 会话支持先通过 USB 完成授权和 Wi-Fi 地址发现，再切换到 ADB TCP 5555 传输。
- USB 切换期间保留 ADB 会话，避免设备重枚举时提前关闭连接。
- 荣耀兼容模式优先保证画面，使用 H.264、最高 1280、30 FPS、2 Mbps。

### ADB 协议与无线调试

- 完善协议版本和 `maxData` 协商，USB 使用兼容版本，TCP 最大接收窗口提升到 1 MiB。
- 校验 ADB 消息 magic、命令、长度和旧协议 checksum，异常包不再继续进入流处理。
- 支持 Android 11+ 无线调试 `STLS`、TLS 1.3 和 `_adb-tls-connect._tcp` 随机端口发现。
- ADB AUTH、无线配对和 TLS 共用同一 RSA 身份，减少重复授权。
- 增加 ADB 公钥、签名和协议解析单元测试。

### USB 设备列表

- `/dev/bus/usb/...` 临时内核路径不再作为持久 UUID。
- 荣耀暂时无法读取序列号时有限重试；仍失败则跳过，不创建无效 Item。
- APP 启动时清理历史 USB 路径幽灵记录，保留真实序列号设备。
- 其他无序列号设备改用稳定的 vendor/product 回退标识，避免总线编号变化产生重复记录。

### 默认画质与界面

- 默认最大尺寸调整为 1920。
- 默认最大帧率调整为 40 FPS。
- 默认最大码率调整为 8 Mbps。
- 新增或编辑设备时默认勾选并展开“高级选项”。

## 已知限制

- 荣耀 USB 兼容仍属实验功能。USB 引导后要求手机与车机处于同一局域网，并允许 ADB TCP 5555 连接。
- 荣耀兼容模式当前不启用投屏音频；画质限制为 H.264、最高 1280、30 FPS、2 Mbps。
- 荣耀 ROM、车机 USB Host 和网络环境差异较大，部分组合仍可能出现 `USB requestWait returned null` 或切换 ADB TCP 失败。
- 默认画质只用于新设备或尚未保存对应偏好的场景；已有设备的独立参数不会批量覆盖。

## 构建校验

- 构建任务：`./gradlew app:testDebugUnitTest app:assembleRelease --stacktrace`
- 构建类型：Release，已启用 R8 混淆和资源压缩。
- APK Signature Scheme v1：通过。
- APK Signature Scheme v2：通过。
- 签名证书 SHA-256：`f8a12dc91188ac1e6ff76a4b6fdc7bc3bddb8b3e631803ce71c31d17c36bcf57`

校验安装包：

```bash
shasum -a 256 EasyControl-Car-3.0.2-release.apk
```

输出应为：

```text
75d2469c74f13d8b1ebd1852e774c9ac7e5e3f481676a8b14eaf654a00d42379
```
