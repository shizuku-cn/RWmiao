# RWmiao 模块页 UI 依赖下载清单

v0.11-r3 继续使用 Compose Material 3 `Switch` 做目标进程模块页可行性测试。
包装器延迟到 `onAttachedToWindow` 后再加入 `ComposeView` 并创建组合内容；模块页提供独立的
`LifecycleOwner` 与 `SavedStateRegistryOwner`，因此游戏 Activity 不需要实现
AndroidX 生命周期/保存状态接口。
`androidx.lifecycle:lifecycle-runtime:2.9.3` 是这条目标进程 Compose 链路的必要桥接依赖。
`androidx.savedstate:savedstate:1.3.1` 用于提供保存状态 owner；注入 owner 会先恢复空
Bundle，再进入 `CREATED`，避免 `SavedStateRegistry.Recreator` 抛异常。
完整的模块 Activity 设置页仍按 `COMPOSE_M3_PLAN.md` 作为后续 UI 设计阶段。

## 固定版本

版本按当前工程的构建基线固定，避免直接使用需要更高工具链的最新 Compose：

- Android Gradle Plugin：`8.9.0`
- Gradle：`9.3.0`
- `compileSdk`：`35`
- Kotlin：`2.0.21`
- Compose BOM：`2025.08.00`
- Compose 核心库：由 BOM 解析为 `1.9.0`
- Material 3：由 BOM 解析为 `1.3.2`
- Material 3 内部所需 Compose UI/runtime/foundation/animation-core：由 BOM 自动解析

## 必须下载的直接依赖

```text
androidx.compose:compose-bom:2025.08.00
androidx.compose.material3:material3                 # BOM -> 1.3.2
```

不再直接声明 `androidx.compose.animation:animation`；Material 3 `Switch` 所需的
动画能力由 Compose Material 3 的传递依赖提供。当前测试页也不需要
`activity-compose`、Lifecycle Compose、Material Icons Extended、Tooling 或 UI Test。

## 官方下载入口

以下页面是 Google Maven 的官方制品页。进入后下载该版本下的 `pom`、`module`
和 `aar`；带 `-android` 的制品也要保留，不能改名。

- [Compose BOM 2025.08.00](https://maven.google.com/web/index.html#androidx.compose:compose-bom:2025.08.00)
- [Material 3 1.3.2](https://maven.google.com/web/index.html#androidx.compose.material3:material3:1.3.2)
- [Material 3 Android 1.3.2](https://maven.google.com/web/index.html#androidx.compose.material3:material3-android:1.3.2)

构建插件来自 Maven Central：

- [Kotlin Android Gradle Plugin marker 2.0.21](https://repo1.maven.org/maven2/org/jetbrains/kotlin/android/org.jetbrains.kotlin.android.gradle.plugin/2.0.21/)
- [Compose Compiler Gradle Plugin marker 2.0.21](https://repo1.maven.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/2.0.21/)
- [Kotlin Gradle Plugin 2.0.21](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/)
- [Kotlin Stdlib 2.0.21](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/)

## 构建插件也必须准备

Compose 源码需要 Kotlin；Compose Compiler Gradle Plugin 的版本必须和 Kotlin
保持一致。因此还要准备以下 Maven/Gradle Plugin 依赖：

```text
org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.0.21
org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.21
org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21
org.jetbrains.kotlin:kotlin-stdlib:2.0.21
```

## 可选：开发预览和 UI 自动化（当前不进入生产包）

如果要在 Android Studio 里使用 `@Preview` 或写 Compose UI 测试，再下载：

```text
androidx.compose.ui:ui-tooling-preview                 # BOM -> 1.9.0
androidx.compose.ui:ui-tooling                          # BOM -> 1.9.0
androidx.compose.ui:ui-test-junit4                      # BOM -> 1.9.0
androidx.compose.ui:ui-test-manifest                    # BOM -> 1.9.0
```

- [Compose UI Tooling Preview 1.9.0](https://maven.google.com/web/index.html#androidx.compose.ui:ui-tooling-preview:1.9.0)
- [Compose UI Tooling 1.9.0](https://maven.google.com/web/index.html#androidx.compose.ui:ui-tooling:1.9.0)
- [Compose UI Test JUnit4 1.9.0](https://maven.google.com/web/index.html#androidx.compose.ui:ui-test-junit4:1.9.0)
- [Compose UI Test Manifest 1.9.0](https://maven.google.com/web/index.html#androidx.compose.ui:ui-test-manifest:1.9.0)

## 交付格式

不要只下载一个 `material3.aar`。请把 **Google Maven + Maven Central 的完整依赖
闭包** 保留原目录结构，压缩成一个 zip 发回：

```text
local-maven/
  androidx/...
  org/jetbrains/kotlin/...
  ...
```

每个坐标至少保留对应的 `pom`、`module` 元数据，以及 `aar`/`jar` 文件；不要重命名
Compose 的 `*-android` 工件。收到后放入工程根目录：

```text
C:\Users\dwh\Documents\Codex\2026-08-11\ni\local-maven
```

然后我会把 `settings.gradle` 改成离线优先，并继续完成模块页迁移、编译和 UI 验证。

## 不需要下载

本次单页设置页不需要 `navigation-compose`、`constraintlayout-compose`、Coil、
Accompanist、Room 或第三方 UI 主题库。

参考：

- <https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler>
- <https://developer.android.com/develop/ui/compose/bom/bom-mapping>
- <https://developer.android.com/jetpack/androidx/releases/compose-material3>
- <https://developer.android.com/jetpack/androidx/releases/activity>
