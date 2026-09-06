# AGENTS.md

本文件适用于 `D:\ni\RWmiao` 项目目录，供后续 Codex、脚本代理和协作者执行开发、构建与提交操作时参考。

## 项目概况

- 项目：RWmiao，面向《铁锈战争》的 LSPosed 功能模块。
- Android application ID：`com.shizuku.rwmiao`。
- 许可证：GNU Affero General Public License v3.0，仅 GPL/AGPL 兼容的代码才能直接合入；第三方组件的许可证以 `THIRD_PARTY_NOTICES.md` 为准。
- GitHub 仓库：`https://github.com/shizuku-cn/RWmiao`。
- 主要分支：`main`；日常开发应使用独立功能分支并通过 Pull Request 合并。
- 当前已验证版本：`versionName 'v1.0'`，`versionCode 1`。后续 APK 若要覆盖升级已安装版本，发布前必须把 `versionCode` 增加到比旧版本更大的值。

## 重要安全边界

绝不能提交或上传以下内容：

- `keystore.properties`、`*.jks`、`*.keystore` 以及任何签名密码或私钥；
- 游戏 APK、专有反编译参考文件和本机专有资源；
- `local.properties`、本机 SDK/JDK 路径、`.gradle/`、`.kotlin/`、缓存和日志；
- `app/build/`、`*.apk`、`*.aab` 以及其他生成物；
- API token、访问令牌、个人数据或未公开的安全漏洞细节。

签名密钥只保存在本机或受保护的 CI Secret 中。Release 构建可以读取项目根目录下本机私有的 `keystore.properties`，但该文件已被 `.gitignore` 排除。任何代理在提交前都必须检查 `git status`，确认上述文件没有进入暂存区。

## 当前仓库经验

### 本地仓库和远程仓库

`D:\ni\RWmiao` 是保留本地历史的开发工作区，可能存在用户尚未提交的修改。开始工作前必须先执行：

```powershell
git status --short
git branch --show-current
git log -5 --oneline --decorate
```

不要使用以下命令覆盖用户工作：

```text
git reset --hard
git checkout -- .
git clean -fd
git push --force
```

除非用户明确要求且已经核对了具体目标。尤其不要因为构建失败而删除 `.gradle`、`.kotlin` 或整个 `build` 目录；这些缓存删除后会显著增加下一次构建时间。

此前远程 `main` 是正式上传基线，本地开发分支与它可能不是同一条历史。将一个新版本上传到 GitHub 时，不要把本地开发分支强制覆盖远程 `main`。应当：

1. 先获取远程 `main`，确认远程最新提交；
2. 在功能分支或干净暂存副本中合并当前确定要发布的源码；
3. 运行测试和构建；
4. 通过普通快进推送或 Pull Request 发布，拒绝强制推送；
5. 推送后用远程提交哈希再次核对。

协作开发时，贡献者可以在自己的功能分支提交并创建 Pull Request。即使贡献者有 Write 权限，也不应直接修改 `main`；仓库公开或套餐支持后，应启用分支规则，要求 Pull Request、CI 通过和维护者审核。

### 源码结构

- `app/src/main/java/.../app`：模块应用、启动器图标和激活状态。
- `app/src/main/java/.../config`：设置键、默认值和配置契约。
- `app/src/main/java/.../module`：LSPosed 入口及游戏进程功能。
- `module/drawing`、`freebuild`、`freeselection`、`path`：绘制、自由建造、自由选择和寻路功能。
- `module/lobby`、`network`、`proxy`：联机大厅、网络信息和代理功能。
- `module/script`：Lua 自动化运行时、快照和原生命令网关。
- `module/support/path`：跨功能的寻路基础设施。
- `app/src/main/java/.../ui`：Compose 设置界面和运行时面板。
- `app/src/test`：JVM 单元测试和回归测试。
- `tools/action-payload`：动作载荷的源码、编译桩和构建脚本。
- `tools/compat-mapping`：兼容性符号映射生成工具。
- `docs`：脚本 API 和面向使用者的稳定文档。

新增功能应放入已有功能包；跨功能基础设施放入 `module/support`。热路径中不要加入逐帧或逐指令日志。涉及兼容性、反射、类/字段映射或非显然算法时，必须补充清晰注释和回归测试。

## 构建环境

项目 Gradle 配置使用：

- Gradle Wrapper：`gradle-9.3.0`；优先使用仓库内的 `gradlew`/`gradlew.bat`，不要依赖全局 Gradle。
- Android SDK Platform：35。
- Android Build Tools：36.x；当前本机使用 `36.0.0`。
- Java/Kotlin 编译目标：17。
- README 推荐 JDK 17；当前本机 `D:\ni\toolchain\jdk-21` 已验证可以完成 Debug 和 Release 构建。

如果使用本机已经准备好的工具链，在 Windows PowerShell 中先设置：

```powershell
$env:JAVA_HOME = 'D:\ni\toolchain\jdk-21'
$env:ANDROID_SDK_ROOT = 'D:\ni\toolchain\android-sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:ANDROID_USER_HOME = 'D:\ni\android-user-home'
$env:GRADLE_USER_HOME = 'D:\ni\toolchain\gradle-home'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
```

其他机器只需把这些路径替换为实际 JDK 和 Android SDK 路径。也可以设置 `ANDROID_SDK_ROOT`/`ANDROID_HOME`，并让 `local.properties` 提供 `sdk.dir`；`local.properties` 是本机文件，不能提交。

## 构建 APK

### 日常最快的 Debug 构建

只需要安装或运行当前代码时，使用增量 Debug 构建：

```powershell
.\gradlew.bat --project-prop 'kotlin.compiler.execution.strategy=in-process' :app:assembleDebug
```

输出通常位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

`kotlin.compiler.execution.strategy=in-process` 可以避免某些受限环境无法创建 Kotlin daemon 临时文件的问题。普通本机如果权限正常，也可以省略该参数。

### 提交前测试和 Debug 构建

源码或功能有变化时，提交前运行：

```powershell
.\gradlew.bat --project-prop 'kotlin.compiler.execution.strategy=in-process' testDebugUnitTest assembleDebug
```

这会执行 JVM 单元测试并生成可安装的 Debug APK。

### Release 构建和校验

Release 签名只使用本机私有的 `keystore.properties` 和对应密钥。确认密钥文件存在后执行：

```powershell
if (-not (Test-Path -LiteralPath 'keystore.properties')) {
    throw 'keystore.properties is required for a signed Release build.'
}
.\gradlew.bat --project-prop 'kotlin.compiler.execution.strategy=in-process' assembleRelease
.\tools\verify-release.ps1 -ApkPath .\app\build\outputs\apk\release\app-release.apk
```

`verify-release.ps1` 会检查版本号、APK v2 签名、zipalign、LSPosed 入口、作用域、动作载荷和兼容性符号表。密钥保持一致很重要：同一个 application ID 的升级包必须使用同一签名密钥，否则 Android 不会把它当作可升级版本。

### 修改动作载荷后

只有修改 `tools/action-payload/src`、编译桩或载荷相关逻辑时，才需要重新生成 DEX：

```powershell
.\tools\action-payload\build.ps1
.\gradlew.bat --project-prop 'kotlin.compiler.execution.strategy=in-process' assembleDebug
```

脚本使用已提交的编译桩和 Android SDK，不需要原版游戏 APK。生成的 `app/src/main/assets/rwmiao_actions.dex` 是项目资源，应在确认内容后一起提交。

### 修改兼容性映射后

只有兼容性基线或映射生成逻辑变化时才重新生成：

```powershell
.\tools\compat-mapping\build.ps1
```

默认基线位于项目外部的本机参考目录。如果默认路径不存在，应显式传入 `-BaselineSmali`；不要把专有 APK 或反编译参考目录复制进仓库。

## 为什么构建很慢，以及如何缩短时间

第一次构建或切换 JDK、Android SDK、Gradle 缓存后，Gradle 需要配置项目、下载/转换依赖、编译 Kotlin/Java、处理资源、执行 DEX 和签名，耗时明显增加。当前本机缓存正常时，最近一次完整 Debug 测试构建约 1 分钟，Release 构建约 46 秒；冷启动可能更久。

遵守以下规则可以避免反复付出冷启动成本：

1. 日常只执行 `:app:assembleDebug`；不要每次修改都执行完整 Release、Lint 和全部测试。
2. 提交前或修改算法后再执行 `testDebugUnitTest assembleDebug`。
3. 不要随意执行 `clean`、`--refresh-dependencies`，也不要更换 `GRADLE_USER_HOME`；这些操作会破坏增量构建和依赖缓存。
4. 交互式开发时不要强制使用 `--no-daemon`，让 Gradle daemon 常驻可减少下一次启动时间。自动化或资源受限环境可以使用 `--no-daemon`。
5. 只改 README、文档或 GitHub 配置时，不需要构建 APK；只改测试时通常只需运行对应测试任务。
6. 如果依赖已经缓存，可以临时追加 `--offline`，避免等待网络；依赖未缓存时不要使用该参数。
7. 保持 JDK、SDK、Gradle 缓存路径稳定。当前环境曾因 Android 用户目录中的 `debug.keystore.lock` 无权限导致构建失败，使用工作区内可写的 `ANDROID_USER_HOME` 可以规避这个问题。

建议按以下频率执行：

| 目的 | 命令 | 频率 |
|---|---|---|
| 快速获得 APK | `:app:assembleDebug` | 每次需要安装测试时 |
| 验证功能和回归 | `testDebugUnitTest assembleDebug` | 提交前、算法变化后 |
| 生成可发布 APK | `assembleRelease` + `verify-release.ps1` | 发布前 |
| 生成动作 DEX | `tools/action-payload/build.ps1` | 修改载荷源码后 |
| 生成符号映射 | `tools/compat-mapping/build.ps1` | 修改兼容性基线或映射逻辑后 |

## 提交前检查清单

```powershell
git status --short
git diff --check
.\gradlew.bat --project-prop 'kotlin.compiler.execution.strategy=in-process' testDebugUnitTest assembleDebug
git status --short
```

确认以下事项后再提交：

- 行为变化、兼容性影响和测试结果已写入提交说明或 Pull Request；
- 新增算法有对应回归测试；
- 没有暂存签名文件、游戏 APK、本机配置、缓存或生成 APK；
- `versionCode` 在需要发布升级包时已经递增；
- 不使用强制推送，不直接覆盖协作者的分支或工作区；
- AGPL-3.0-only 和第三方许可证声明仍然完整。
