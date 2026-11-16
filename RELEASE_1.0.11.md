WebDAVCast TV v1.0.11 — 支持 Android 6.0（小米盒子3S 等）+ ≤8GB 机器不落盘缓存

## 兼容性：最低系统从 Android 8.0 降到 Android 6.0

- `minSdk 26 (Android 8.0) → 23 (Android 6.0)`
- 之前在同版本盒子上提示「解析包错误」，实测就是 `INSTALL_FAILED_OLDER_SDK`，**与本机 CPU 架构无关**（APK 内没有任何 native `.so`，纯 Kotlin/Java，所有 ABI 通用）
- 已在 Android 6.0（API 23）模拟器实测：安装成功、启动 1.2s、WebDAV 列目录、H.264 播放画面正常推进

## 新功能：小存储设备关闭磁盘缓存

- 检测 `/data` 总容量 **≤8GB** 时完全不启用缓存，纯网络播放（缓存一多会把小盒子撑满）
- 大容量机器行为不变：仍按容量分档 + 剩余空间收紧，天花板 1GB
- 启动日志一行说明：`已关闭(总X.XGB≤8GB, 不落盘)`

## 实测（Android 6.0 模拟器，结构化验证）

- v1.0.10 装 Android 6.0 → `Failure [INSTALL_FAILED_OLDER_SDK]`（复现用户报的解析包错误）
- v1.0.11 同环境 → `Success`；ExoPlayer 1.3.1 在 SDK 23 初始化成功，`OMX.google.h264.decoder` 出画，进度 0:01/0:06
- ≤8GB 机器日志：`本机存储已关闭(总1.9GB≤8GB, 不落盘), 不启用缓存` → `缓存不可用, 本次降级纯网络播放`，播放正常

versionCode 15 / versionName 1.0.11
