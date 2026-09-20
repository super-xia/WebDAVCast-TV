# WebDAVCast TV — WebDAV 电视端播放器（自用）

> ⚠️ **个人自用项目**，按本人使用场景定制，非通用产品，代码仅供学习参考，不做维护承诺。

在 **Android TV / 电视盒子**（小米盒子、海信等安卓系统）上浏览并播放 WebDAV 服务器里的视频，**全程遥控器可操作**，无需鼠标键盘。

## 与手机版的关系

这是 [**WebDAVCast（手机版）**](https://github.com/super-xia/WebDAVCast) 的电视端姊妹项目。两者共用同一套核心逻辑，只是交互层不同：

| | 手机版 WebDAVCast | 电视版 WebDAVCast TV（本项目） |
|---|---|---|
| 目标设备 | 手机 / 平板 | Android TV / 电视盒子 |
| 交互方式 | 触摸手势 | 遥控器（焦点驱动） |
| 投屏 | SSDP + DLNA 直连 | 无（本身就是电视端） |
| 服务器地址 | 清单页动态解析 | 清单页动态解析（同源） |
| 播放器 | 手势控制条 | 遥控器长按快进/快退 |

核心的「服务器地址动态解析」和「WebDAV 目录浏览」实现完全一致，只有 `MainActivity` 的 UI/交互层和投屏模块不同。

## 功能

- **遥控器浏览**：焦点驱动的目录网格，方向键选文件，确定键进入/播放，返回键上一级
- **播放器**：
  - 长按左右方向键 = 连续快进/快退（渐进加速，越按越快，松开即停）
  - 菜单键唤出/隐藏控制栏
  - 倍速循环 1x / 1.25x / 1.5x / 2x / 0.75x
  - 控制栏：上集 / 播放暂停 / 下集 / 选集 / 倍速
  - 播放页右上角显示当前系统时间（透明底）
- **选集**：播放页列出当前目录所有视频，直接切集；已看过的显示播放进度
- **历史续播**：观看进度自动保存，下次续播；历史按目录聚合（同目录只留最新一条），从历史返回回首页
- **扫码填写设置**：电视上打字麻烦，设置页生成二维码，手机扫码填表回传（详见原理）

## 使用流程

1. **首次打开** → 进入设置页，需要填三项：**服务器清单页地址**、**WebDAV 用户名**、**WebDAV 密码**
   - 电视上打字不方便，点「📱 扫码填写」：电视起局域网 HTTP 服务并显示二维码，手机扫码打开网页填这三项，提交后电视自动收到
2. 点「查询可用的服务器」→ App 请求清单页，解析出当前所有可用服务器地址，遥控器选一台
3. 进目录翻视频（框式网格），聚焦文件名过长时流水灯滚动
4. 点视频进入播放页，遥控器菜单键唤控制栏、左右长按快进快退
5. 主界面按返回键退出软件；历史记录从顶部进入

## 原理

### 1. 服务器地址动态解析（为什么不用固定地址）

服务器没有公网 IP，靠 **STUN 打洞** 暴露服务，**端口和域名会随时漂移**。所以维护一个「**清单页**」——一个可配置的 URL，页面内容就是一串服务器地址：

```
http://1.2.3.4:8080/dav,家里NAS
http://5.6.7.8:9090/dav,另一台
```

App 启动时请求这个清单页（`AddressResolver.kt`），用正则锚点法解析出所有 `host:port/path,名称` 条目（兼容纯文本、HTML `<a href>`、一行多个混排），拿到**当下最新**的地址列表，手动选一台。地址漂移了，清单页改一下就行，App 无需升级。

### 2. WebDAV 目录浏览

`WebDavClient.kt` 发 **PROPFIND**（Depth: 1）+ **HTTP Basic 认证** 到服务器，解析返回的 XML（`resourcetype` / `getcontentlength` / `getlastmodified` / `displayname`）得到文件/文件夹列表。

### 3. 播放

Media3 **ExoPlayer** + **OkHttpDataSource** 直接带 Basic 认证拉流，自动处理 URL 编码、302 重定向。

### 4. 扫码填写设置（TV 版特有）

设置页内置 **NanoHTTPD** 起一个局域网 HTTP 服务，用 **ZXing** 生成二维码（内容为 `http://电视IP:端口/表单页`）。手机连同一 Wi-Fi 扫码打开网页，填三项后 POST 回传，电视收到即保存（`LanQrSync.kt`），彻底绕开电视上打字。

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleRelease
```

> release keystore 未随仓库提供（`.gitignore` 已排除 `keystore.properties` / `*.jks`）。没配 keystore 时 Gradle 回退 debug 签名，保证公开仓库可直接编译。

## 环境

- `compileSdk` / `targetSdk` 34，`minSdk` 26
- Kotlin + Jetpack Compose (Material3, BOM 2024.02.00)
- Media3 1.3.1（ExoPlayer + OkHttpDataSource）、OkHttp 4.12.0
- ZXing 3.5.1 + NanoHTTPD 2.3.1（扫码填写）、协程 1.7.3

## 文件结构

- `MainActivity.kt` — 设置 / 服务器选择 / 目录浏览 / 播放 / 历史，焦点驱动 UI
- `AddressResolver.kt` — 清单页解析 `host:port/path,名称`
- `WebDavClient.kt` — PROPFIND + Basic 认证目录树
- `BasicAuth.kt` — 认证头工具
- `LanQrSync.kt` — 扫码填写（NanoHTTPD + ZXing 二维码 + 局域网表单回传）
- `ServerEntry.kt` / `Prefs.kt` — 服务器条目 / 配置与播放历史
- `CtrlIcons.kt` — 控制条矢量图标

## 相关项目

- 📱 [WebDAVCast（手机版）](https://github.com/super-xia/WebDAVCast) — 触摸手势 + DLNA 投屏
- 📺 本项目 — 遥控器电视端
