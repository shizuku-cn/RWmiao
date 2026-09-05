# 贡献指南 / Contributing

[中文](#中文) · [English](#english)

---

## 中文

### 开发环境

在开始贡献代码前，请确保您的本地环境满足以下要求：

- **JDK 17**
- **Android SDK Platform 35**
- **Android Build Tools 36.x**
- **Gradle Wrapper**：请直接使用仓库自带的包装器。

在提交拉取请求（Pull Request）之前，请务必在本地运行以下命令进行完整测试与构建：

```bash
./gradlew testDebugUnitTest assembleDebug
```

### 源码约定

- **模块组织**：新功能请按其职责放入已有的对应包中；跨功能的基础设施和通用工具请放入 `module/support`。
- **技术选型**：优先使用游戏原生命令和同步路径，避免过度设计。
- **日志规范**：热路径（如每帧刷新的逻辑、频繁执行的指令）内**严禁**加入逐帧或逐指令的日志输出。
- **代码注释**：只保留解释约束、兼容性或非显然算法的必要注释，且注释必须**同时提供中文和英文说明**。
- **环境安全**：严禁将游戏原版 APK、私人签名密钥、本机 SDK 绝对路径、临时缓存或任何构建产物提交到代码仓库中。

### 提交内容 (PR)

在发起 Pull Request 时，请在描述中清晰说明以下内容：
1. 本次提交带来的**行为变化**。
2. 对现有系统的**兼容性影响**。
3. 您的本地**验证与测试结果**。

*注意：如果是针对核心算法或逻辑缺陷的修复，必须在 `app/src/test` 中补充对应的回归测试用例。*

---

## English

### Development Environment

Before contributing code, please ensure your local environment meets the following requirements:

- **JDK 17**
- **Android SDK Platform 35**
- **Android Build Tools 36.x**
- **Gradle Wrapper**: Please use the project's checked-in wrapper.

Make sure to run the following test and build command locally before submitting your changes:

```bash
./gradlew testDebugUnitTest assembleDebug
```

### Source Conventions

- **Module Organization**: Place new features into matching existing packages. Cross-feature infrastructure and utilities should go into `module/support`.
- **Implementation**: Prefer native game commands and synchronization paths wherever possible.
- **Logging**: Do not add per-frame or per-command logging to hot paths to avoid performance regression.
- **Comments**: Keep comments only for essential constraints, compatibility issues, or non-obvious algorithms. These comments **must be written in both Chinese and English**.
- **Repository Cleanliness**: Never commit game APKs, signing keys, local SDK paths, temporary machine caches, or any build outputs.

### Pull Requests

When creating a Pull Request, please provide a clear description covering:
1. The **behavioral changes** introduced by your code.
2. The **compatibility impact** on the existing codebase.
3. Your **verification and testing results**.

*Note: Any fixes made to algorithms or logic bugs should always be accompanied by a regression test case inside `app/src/test`.*
