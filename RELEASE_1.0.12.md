WebDAVCast TV v1.0.12 — 修复小米盒子看一会儿自动进屏保

## 修复：播放/浏览时被系统屏保盖住

- 现象：小米盒子（Mi Box）进入 App 后过一段时间自动进入系统屏保，画面被盖掉
- 原因：App 没有向系统声明「需要保持亮屏」。盒子按「整机无操作」计时，播放页只出画面、不收遥控器按键，到点就被判定空闲 → 拉起屏保
- 修复：Activity 窗口挂 `FLAG_KEEP_SCREEN_ON`（`window.addFlags`）
  - App 在前台可见期间（列表页、播放页、暂停中）系统都不判定空闲，不再进屏保
  - 退到桌面或切到别的 App 时该标记自动失效，仍按盒子自己的屏保设置正常屏保
- 顺带：播放状态改为监听 `onIsPlayingChanged`，暂停/播放图标的切换不再等 300ms 轮询

## 兼容性

- minSdk 23（Android 6.0，含小米盒子3S），targetSdk 34，纯 Kotlin/Java 无 native 库，全 ABI 通用
- 1.0.11 的改动（≤8GB 机器不落盘缓存、Android 6.0 支持）保持不变

versionCode 16 / versionName 1.0.12
