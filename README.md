<p align="center">
  <img width="1024" alt="Rust Miao Development Environment product preview" src="https://github.com/user-attachments/assets/43b8573e-dc56-4cfe-9064-808e2548c0cb" />
</p>

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

需要 JDK 17、Android SDK Platform 35，以及 Android Build Tools 36.x。构建前请设置 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`）指向 Android SDK；也可以在本机生成 `local.properties`，该文件已被 `.gitignore` 忽略，不能提交。仓库包含 Gradle Wrapper，可直接执行：

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

### 许可证

本项目源代码采用 GNU Affero General Public License v3.0（AGPL-3.0-only），详见 [`LICENSE`](LICENSE)。第三方组件及其许可证见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## English

RWmiao is an LSPosed feature module for Rusted Warfare. It detects compatible game packages through runtime class contracts and reuses native command, rendering, and synchronization paths where possible.

### Source Structure

| Directory | Content |
|---|---|
| `app/src/main/java/.../app` | Standalone module application, launcher icon, and activation status |
| `app/src/main/java/.../config` | Setting keys and default values |
| `app/src/main/java/.../module` | LSPosed entry point and game process features |
| `module/drawing` | Combat information rendering |
| `module/freebuild`, `freeselection` | Free build and free selection |
| `module/path`, `support/path` | Segmented commands, smart pathfinding, and pathfinding algorithms |
| `module/lobby`, `network`, `proxy` | Multiplayer lobby, network information, and proxy |
| `module/script` | Lua automation runtime and native command gateway |
| `module/smartbuild` | Smart build serialization |
| `app/src/main/java/.../ui` | Compose settings interface and runtime panel |
| `app/src/test` | JVM regression tests |
| `tools/action-payload` | Source code, stubs, and build scripts for target classloader action payloads |
| `docs` | Stable documentation for users and script authors |

### Build

Install JDK 17, Android SDK Platform 35, and Android Build Tools 36.x. Before building, set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to your Android SDK, or create a local `local.properties` file (this file is ignored by `.gitignore` and must not be committed). 

The repository includes a Gradle Wrapper. You can run it directly:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Official release builds intentionally contain no developer signing key by default. Release publishers should configure signing securely in their own CI or local environment and must not commit the private keys to the repository.

The pre-built DEX of the action payload is located at `app/src/main/assets/rwmiao_actions.dex`. After modifying `tools/action-payload/src`, execute the following in Windows PowerShell to rebuild it:

```powershell
.\tools\action-payload\build.ps1
```

The script locates toolchains via `JAVA_HOME` and `ANDROID_SDK_ROOT` (or `ANDROID_HOME`/`local.properties`), and does not require the original game APK.

### Notes

- The Application ID is kept as `com.shizuku.rwmiao` to maintain compatibility with existing installations.
- LSPosed uses dynamic scopes; you need to enable the module for the target game package within the manager.
- The repository excludes proprietary game APKs, signing material, machine caches, and build artifacts.
- See [`docs/SCRIPTING_API.md`](docs/SCRIPTING_API.md) for the Lua API.
- See [`CONTRIBUTING.md`](CONTRIBUTING.md) for repository contribution guidelines.

### License

The project source code is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0-only). See [`LICENSE`](LICENSE) for details. Third-party components and their licenses are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
