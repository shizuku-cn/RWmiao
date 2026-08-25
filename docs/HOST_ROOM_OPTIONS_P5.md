# 房间游戏选项扩展 p5

## 变更

- `HostRoomOptionsFeature.java` 重命名为 `RoomOptions.java`。
- `HostRoomOptionsDialog.kt` 重命名为 `RoomOptions.kt`。
- 下拉选项改为从目标游戏的原生资源、`ae.d()/ae.c()` 和原生翻译接口读取，不再在 M3 层硬编码中文选项。
- 初始单位使用原版动态列表，因此包含实验性初始单位和可用的自定义初始单位。
- 恢复资金倍率、房间人数、单位上限的快捷值按钮，但不再显示多余的“xx快捷值”说明文字。
- 左右两列分别使用独立的垂直滚动状态；底部取消/应用栏固定。
- 全局 Ban 单位改为沙盒编辑器 All 页相同的 `game.units.cj.ae` 注册表、过滤条件和 `game.units.ab` 排序，Ban ID 与实际单位类型 ID 保持一致。
- 资金增长倍率取消上下限，允许负数和 `0`；仍拒绝 NaN/Infinity，避免无法序列化的输入污染房间状态。
- 单位上限进入游戏后同步刷新 `game.i.by/bz` 与各队伍统计缓存，继续覆盖网络重载点。

## 版本

- `versionCode`: `42`
- `versionName`: `0.11-p5`

## 验证边界

- 已通过 `:app:compileReleaseKotlin` 与 `:app:compileReleaseJavaWithJavac`。
- 最终 APK 需要重新构建、复制到 `发布`、签名/对齐检查并重算 SHA-256。
- 当前没有连接 Android/ADB 设备，因此沙盒 All 列表显示、房主 Ban 的真实建造拦截、多人联机同步和单位上限进局效果仍需真机复测。
