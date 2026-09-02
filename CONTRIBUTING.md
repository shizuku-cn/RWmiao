# 贡献指南 / Contributing

[中文](#中文) · [English](#english)

---

## 中文

### 开发环境

- **JDK 17**
- **Android SDK Platform 35**
- **Android Build Tools 36.x**
- **Gradle**：请使用仓库自带的 Gradle Wrapper。

在提交 Pull Request 之前，请务必在本地运行以下命令进行验证：

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows (PowerShell)：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

### 源码约定

- **代码组织**：新功能应按业务逻辑放入已有的对应包中；跨功能的底层基础设施请放入 `module/support`。
- **原生复用**：开发时应优先复用游戏原生的命令、渲染与同步路径。
- **性能性能**：严禁在热路径（Hot Paths）中加入逐帧或逐指令的日志输出。
- **注释规范**：仅在解释设计约束、兼容性问题或非显然算法时保留注释，且注释必须同时提供中文和英文版本。
- **文件过滤**：切勿提交游戏 APK、签名密钥、本机 SDK 路径、本地缓存或任何构建产物。

### 提交内容

- **合并请求 (PR)**：请在 PR 描述中清晰说明行为变化、兼容性影响以及测试验证结果。
- **回归测试**：修复核心算法或逻辑漏洞时，应在 `app/src/test` 中补充对应的 JVM 回归测试用例。

---

## English

### Development Environment

- **JDK 17**
- **Android SDK Platform 35**
- **Android Build Tools 36.x**
- **Gradle**: Please use the checked-in Gradle Wrapper.

Before submitting a Pull Request, make sure to run the following validation command locally:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows (PowerShell):

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

### Source Conventions

- **Code Organization**: Place new code into existing packages based on functionality. Cross-feature infrastructure should go into `module/support`.
- **Native Reuse**: Prioritize using native game commands, rendering, and synchronization paths wherever possible.
- **Performance**: Do not add per-frame or per-command logging to performance-critical hot paths.
- **Code Comments**: Keep comments only for explaining design constraints, compatibility details, or non-obvious algorithms. These comments must be written in both Chinese and English.
- **Repository Hygiene**: Never commit game APKs, signing keys, local SDK paths, caches, or build artifacts.

### Pull Requests

- **PR Description**: Clearly describe the behavioral changes, compatibility impacts, and verification results in your Pull Request.
- **Regression Tests**: When fixing core algorithms or logic bugs, you are required to implement matching JVM regression tests under `app/src/test`.
