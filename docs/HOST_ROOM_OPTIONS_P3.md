# v0.11-p3 房主游戏选项修复记录

## 修复内容

### 1. 原版对话框始终出现

p2 仅拦截原版 `ft.onClick`。在部分 ROM / LSPosed 组合中，匿名监听器方法 Hook 不稳定，导致点击仍直接进入原版 Dialog。

p3 在 `MultiplayerBattleroomActivity.onCreate` 完成后，读取原版 `gameOptions` 按钮的监听器并包裹一层：

- 开关开启且当前是房主：打开 M3 面板，不调用原版监听器。
- 开关关闭：调用已保存的原版监听器，恢复原生 Dialog。
- 非房主：调用原版监听器。
- 同时保留 `ft.onClick` Hook 作为另一层兼容路径。

开关状态每次点击重新读取，因此不需要重启游戏即可切换行为。

### 2. 环境菜单组件排版

- 环境页的“拓展游戏选项设置面板”改用 `SectionCard(compact = true)`，高度与“强制无雾”一致。
- 副文本改为：`为房间游戏选项设置提供更多自定义和额外功能`。

## 验证

- `:app:compileDebugKotlin`：成功。
- `:app:compileDebugJavaWithJavac`：成功。
- `:app:assembleRelease`：成功。
- 发布 APK：`C:\Users\dwh\Documents\Codex\2026-08-11\ni\发布\RWmiao-v0.11-p3.apk`。
- APK versionCode/versionName：`38 / 0.11-p3`。
- APK v2 签名校验：通过。
- 当前工作区没有连接 Android 设备，尚未进行实机 UI 或联机回归。

## p3-fix

报错文件确认是 `HostRoomOptionsDialog.onStart` 将 Dialog 的 `ContextThemeWrapper` 强制转换为 `Activity`。修复为保存并使用构造函数传入的房主 Activity，避免打开面板时崩溃。

- p3-fix APK：`C:\Users\dwh\Documents\Codex\2026-08-11\ni\发布\RWmiao-v0.11-p3-fix.apk`
- versionCode/versionName：`39 / 0.11-p3-fix`
- SHA-256：`85DD8B9020237C5EFF0FA2B82F354DCC4E02A4E864A62A847F25023883E765C5`
