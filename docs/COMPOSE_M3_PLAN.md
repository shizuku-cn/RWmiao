# Compose Material 3 设置页方案

## 结论

> v0.11-r3 在现有目标进程模块页中使用 Java-friendly 的 Material 3
> `Switch` 包装器只在 View 附着到窗口后才加入 `ComposeView` 并调用 `setContent`，
> 模块页同时为注入的子树提供独立 `LifecycleOwner` 和
> `SavedStateRegistryOwner`，避免游戏 Activity 缺少 Compose 所需的 ViewTree
> owner 导致闪退；注入 owner 在进入 `CREATED` 前先恢复空状态包，避免
> `SavedStateRegistry.Recreator` 中断目标 Activity 的 `addContentView`；完整设置页仍按下面的模块 Activity 方案实施。

可行，安排在 v0.11-UI-fix5 验证后的下一阶段。Compose UI 应由 RWmiao 模块自己的 Activity 承载，不应在 Rusted Warfare 的 `InGameActivity` 中直接创建 `ComposeView`。

## 下载与放置

推荐使用 Gradle 从 Google Maven 下载，不要手动把单个 AAR 塞进游戏 APK：

- Compose 配置文档：<https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler>
- Material 3 文档：<https://developer.android.com/develop/ui/compose/designsystems/material3>
- Compose 动画文档：<https://developer.android.com/develop/ui/compose/animation/introduction>
- Compose BOM：<https://developer.android.com/jetpack/androidx/releases/compose>

依赖声明放在 `RWmiao/app/build.gradle`，Gradle 会自动下载到用户 Gradle 缓存，不需要复制到 `app/src`。
如果必须离线构建，将下载的 Maven 仓库按原目录结构放到
`C:\Users\dwh\Documents\Codex\2026-08-11\ni\local-maven`，再在 `settings.gradle` 的 `repositories` 中加入该目录；不要只下载 `material3.aar`，因为 Compose 还需要 runtime、ui、foundation、animation、具体 Kotlin/Compiler 依赖。

当前项目使用 AGP 8.9.0、compileSdk 35。下一阶段应先固定一组兼容版本，再升级 Compose，不要直接复制要求更高 compileSdk/AGP 的最新示例。

## 推荐边界

```text
Rusted Warfare 进程
  RWmiaoModule（Hook 与只读绘制）
    └─ ESC 菜单入口 ──显式 Intent──> RWmiao 模块进程

RWmiao 模块进程
  SettingsActivity（ComponentActivity）
    └─ Compose Material 3 设置页
         └─ 模块私有 SharedPreferences

LSPosed API 102
  getRemotePreferences("rwmiao_settings")
    └─ 向目标进程提供只读设置快照
```

## 原因

- Compose、Material 3、Activity Compose 和生命周期依赖只在模块 Activity 中使用，避免污染游戏 Activity 的生命周期和视图树。
- 不在目标进程构造 Compose UI，可降低 AndroidX 版本冲突、资源解析和 SavedStateOwner 缺失风险。
- 设置归模块所有，不再依赖游戏包的私有偏好文件。
- 当前使用的 LSPosed API 102 AAR 已确认提供 `getRemotePreferences(String)`。

## 实施任务

1. 在 Gradle 中集中声明 Compose BOM、Material 3、Activity Compose，并启用 Compose 构建功能。
2. 新建 `app/ui/settings/SettingsActivity`、状态模型和 Material 3 主题。
3. 将设置键继续集中保留在 `config/SettingsContract`，避免 Hook 与 UI 各自维护字符串。
4. 模块 Activity 写模块私有 `rwmiao_settings`；Hook 侧改用 `getRemotePreferences` 读取。
5. 游戏内 ESC 菜单只负责启动显式 Activity；启动失败时保留轻量错误日志，不影响原游戏菜单。
6. 绘制循环使用缓存的不可变设置快照，通过偏好变更监听刷新，禁止每帧进行 Binder/磁盘读取。
7. 每个会影响模拟的功能必须走原生同步命令路径；设置页显示同步验证状态，但不能以禁用多人模式代替实现。

## 验收

- 单人对局可从 ESC 菜单打开、切换、保存并立即生效。
- 游戏重启、模块进程重启后设置保持一致。
- Compose Activity 关闭或崩溃不会影响游戏进程。
- 联网对局中的指令型功能只产生原生同步命令；纯显示功能不写同步模拟状态。
- Release 构建、R8/资源打包、LSPosed API 102 加载和升级安装均通过。
