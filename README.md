# RWmiao

[中文](#中文) · [English](#english)

## 中文

RWmiao 是面向《铁锈战争》的 LSPosed 功能模块。项目通过运行时类契约识别兼容的游戏包，并尽量复用游戏原生命令、渲染与同步路径。

### 源码结构

| 目录 | 内容 |
|---|---|
| `app/src/main/java/.../app` | 独立模块应用、启动器图标与激活状态 |
| `app/src/main/java/.../config` | 设置键与默认值 |
| `app/src/main/java/.../module` | LSPosed 入口和游戏进程功能 |
| `module/drawing` | 战斗信息绘制 |
| `module/freebuild`、`freeselection` | 自由建造与自由选择 |
| `module/path`、`support/path` | 分段指令、智能寻路与寻路算法 |
| `module/lobby`、`network`、`proxy` | 联机大厅、网络信息与代理 |
| `module/script` | Lua 自动化运行时与原生命令网关 |
| `module/smartbuild` | 智能建造序列化 |
| `app/src/main/java/.../ui` | Compose 设置界面和运行时面板 |
| `app/src/test` | JVM 回归测试 |
| `tools/action-payload` | 目标类加载器动作载荷的源码、桩与构建脚本 |
| `docs` | 面向用户和脚本作者的稳定文档 |

### 构建

需要 JDK 17、Android SDK Platform 35，以及 Android Build Tools 36.x。仓库包含 Gradle Wrapper，可直接执行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

正式构建默认不包含开发者签名。发布者应在自己的 CI 或本机安全地配置签名，不要把密钥提交到仓库。

动作载荷的已构建 DEX 位于 `app/src/main/assets/rwmiao_actions.dex`。修改 `tools/action-payload/src` 后，在 Windows PowerShell 中执行：

```powershell
.\tools\action-payload\build.ps1
```

脚本从 `JAVA_HOME` 和 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`/`local.properties`）寻找工具链，不需要原版游戏 APK。

### 说明

- 应用 ID 保持为 `com.shizuku.rwmiao`，用于兼容已有安装。
- LSPosed 使用动态作用域；需要在管理器中为目标游戏包启用模块。
- 仓库不包含游戏 APK、签名密钥、本机缓存或构建产物。
- Lua API 参见 [`docs/SCRIPTING_API.md`](docs/SCRIPTING_API.md)。
- 贡献规范参见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## English

RWmiao is an LSPosed feature module for Rusted Warfare. It detects compatible game packages through runtime class contracts and reuses native command, rendering, and synchronization paths where possible.

### Build

Install JDK 17, Android SDK Platform 35, and Android Build Tools 36.x, then run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Release builds intentionally contain no repository-owned signing key. Configure signing securely in your own release environment. The repository excludes proprietary game APKs, signing material, machine caches, and generated APKs.

The action payload can be rebuilt on Windows with `tools/action-payload/build.ps1`; it uses the checked-in compile-time stubs and does not require a game APK.

See [`docs/SCRIPTING_API.md`](docs/SCRIPTING_API.md) for the Lua API and [`CONTRIBUTING.md`](CONTRIBUTING.md) for repository conventions.
