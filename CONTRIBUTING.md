# 贡献指南 / Contributing

## 开发环境 / Development environment

- JDK 17
- Android SDK Platform 35
- Android Build Tools 36.x
- 使用仓库自带的 Gradle Wrapper / Use the checked-in Gradle Wrapper

提交前请运行： / Run before submitting:

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 源码约定 / Source conventions

- 按功能放入已有包，跨功能基础设施放入 `module/support`。 / Put features in the matching package and cross-feature infrastructure in `module/support`.
- 优先使用游戏原生命令和同步路径。 / Prefer native game commands and synchronization paths.
- 热路径不得加入逐帧或逐指令日志。 / Do not add per-frame or per-command logging to hot paths.
- 只保留解释约束、兼容性或非显然算法的注释，并同时提供中文和英文。 / Keep comments only for constraints, compatibility, or non-obvious algorithms, and write them in both Chinese and English.
- 不得提交游戏 APK、签名密钥、本机 SDK 路径、缓存或构建产物。 / Never commit game APKs, signing keys, local SDK paths, caches, or build outputs.

## 提交内容 / Pull requests

请说明行为变化、兼容性影响和验证结果；修复算法时应补充回归测试。 / Describe behavioral changes, compatibility impact, and verification results; algorithm fixes should include regression tests.
