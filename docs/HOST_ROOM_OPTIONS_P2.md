# v0.11-p2 房主游戏选项面板记录

## 本阶段改动

1. `HostRoomOptionsDialog.kt` 已从 `module` 移到 `ui` 目录，使用 M3 主题。
   主题读取目标进程设置；若目标进程没有主题值，则回退到模块设置。
2. 新增环境页开关“拓展游戏选项设置面板”。
   - 开启：只有自己是房主时拦截原版 `ft.onClick`，不创建原版 Dialog。
   - 关闭：继续执行原版监听器，恢复原生游戏选项对话框。
   - 非房主始终走原版逻辑。
3. 面板改为较窄窗口；内容区独立滚动，底部“取消 / 应用”固定，不再使用标题栏。
4. 保留原版预设选项：初始资金、初始单位、战争迷雾、AI 难度、团队布局、禁用核弹、共享控制。
5. 房间人数、单位上限、资金倍率提供快捷值，同时保留自定义数值输入。
6. Ban 单位改为从内置 `cj.values()` 和自定义单位注册表 `custom.l.d` 读取的可选单位列表，并支持搜索；列表显示名称，ID 仅作为辅助信息，不再要求手填 ID。

## 同步与单位上限修复

- 初始资金、初始单位仍只使用原版选项，不加入任意金额或自定义编队协议。
- 资金倍率、单位上限、房间人数继续写入原版字段并沿用原版同步路径，未修改客户端可以解析这些原版字段。
- 原 p1 只写 `ae.ay/az`，但游戏开始时 `ae.a(boolean)` 会从 `SettingsEngine.teamUnitCapHostedGame` 重新覆盖它们；p2 同时更新该源字段，因此单位上限不会在开始游戏时被重置。
- Ban 不新增网络包。房主在 `gameFramework.e.i()` 的 `build` 命令校验点拒绝指定单位，因此未修改客户端也不能通过正常生产命令绕过；Ban 集合按当前网络会话保存在房主进程。

## 范围边界

- 当前面板人数范围为 10～100；目标游戏已有 `game.p.e=100` 的扩容边界。超过 100 仍需要继续修改目标 APK 的固定槽位、人数检查和同步逻辑，p2 不伪装成已实现。
- 任意初始资金、任意初始单位组合仍未制作：原版字段和未修改客户端没有对应协议。
- 编队按钮数量按需求不制作。

## 迁移入口

正式版迁移时优先复制：

- `app/src/main/java/com/shizuku/rwmiao/module/HostRoomOptionsFeature.java`
- `app/src/main/java/com/shizuku/rwmiao/ui/HostRoomOptionsDialog.kt`
- `app/src/main/java/com/shizuku/rwmiao/ui/main/Environment.kt`
- `SettingsContract.KEY_EXTENDED_GAME_OPTIONS`
- `SettingsScreen.kt` 的环境页注册

然后按正式版 DEX 重新核对 `appFramework.ft.f180a`、`gameFramework.e.i()`、`game.units.cj.values()`、`game.units.custom.l.d`、`gameFramework.j.ae` 和 `SettingsEngine.teamUnitCapHostedGame`。

## 验证

- `:app:compileDebugKotlin`：成功。
- `:app:assembleDebug`：成功。
- 实机和跨客户端联机回归需要 Android 设备；当前工作区无 ADB 设备，尚未宣称 UI 或联机行为已实机验收。
