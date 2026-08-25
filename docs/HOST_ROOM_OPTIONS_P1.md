# v0.11-p1 房主游戏选项迁移记录

## 基线

- 模块基线：当前工作区的 `v0.11-r11` 源码。
- 目标游戏基线：`RustedWarfare-1.15-mod1`，动态类前缀仍通过 `RWmiaoModule` 解析。
- 新功能只新增 `HostRoomOptionsFeature` 和 `HostRoomOptionsDialog`，不修改已有绘制、生产优化、自动增援、路径和脚本功能的实现。

## 已实现

1. 房主点击 `MultiplayerBattleroomActivity.gameOptions` 后，替换原按钮监听器；房主不会再进入原版 `ft` 对话框。
2. 用 Compose Material 3 对话框承载原版可兼容选项：初始资金预设、初始单位原有模式、战争迷雾、AI 难度、团队布局、禁用核弹、共享控制。
3. 自定义资金增长倍率：写入原版 `aA.h`，仍沿用原版 packet 106/115 的 `float` 字段。
4. 自定义单位上限：同步写入主机网络对象的 `ay/az`，并走原版 packet 115。
5. 自定义房间人数：调用原版 `game.p.b(max, true)` 扩容；当前版本上限为原版静态硬上限 100，不需要改 `p.j` 的数据结构。
6. 全局 Ban 单位：输入单位 `type ID`，在主机的 `gameFramework.e.i()` 命令校验点拒绝 `build` 命令。因为主机在广播前校验，未修改客户端也不能通过正常生产命令绕过。

## 明确不实现

- 任意自定义初始资金：原版网络字段 `aA.c` 是预设索引，不是任意金额。把金额塞进未知索引会让未修改客户端按另一套金额解码，导致锁步状态不一致。
- 任意自定义初始单位组合：原版 `aA.g` 只携带标准模式或单个类型编码，任意列表/数量/位置需要新增同步协议；未修改客户端不会解析。
- 编队按钮数：按当前需求不制作。

## Ban 输入约定

输入游戏实际的 `unit type ID`，多个 ID 可用逗号、分号或空格分隔，匹配不区分大小写。例如：

```text
tank, artillery
```

当前 p1 是 ID 输入版，尚未加入单位注册表的可视化筛选列表。Ban 集合按网络会话弱引用保存，应用后只在房主运行时作为权威校验。

## 人数上限边界

- `game.p.c` 默认 10，`game.p.b` 已支持扩容到 `game.p.e=100`；公共房间元数据和 packet 115 都读取当前 `p.c`。
- 降低人数时，如果被降低区间存在已占用槽位，面板会拒绝应用。
- 共享控制位图仍是 `short`，槽位 16 以上存在原版位宽限制；p1 没有改写该原版协议，因此大于 16 人时应避免依赖共享控制。
- 超过 100 需要进一步修改 `game.p.e` 及所有固定 10/100 相关逻辑，不能只改面板输入范围。

## 迁移到正式版

迁移时优先复制以下三个改动：

1. `module/HostRoomOptionsFeature.java`
2. `module/HostRoomOptionsDialog.kt`
3. `RWmiaoModule.java` 中 `hostRoomOptionsFeature` 的注册代码

然后根据正式版 DEX 重新核对：`gameOptions`、`gameFramework.e.i()`、`ae.aA/ay/az`、`game.p.b/c/j`、`gameFramework.j.ba` 这些成员的名称和参数。正式版若保留相同 Rusted Warfare 类契约，不需要改同步包格式。

## 验证记录

- `:app:assembleDebug`：成功。
- `:app:assembleRelease`：成功。
- 发布 APK：`发布/RWmiao-v0.11-p1.apk`。
- 发布 APK versionCode/versionName：`34 / 0.11-p1`。
- APK v2 签名校验：通过。
- 当前工作区没有连接 Android 设备，未进行实机 UI/联机回归；下一步应在房主+未修改客户端的实际房间中验证：人数扩容、倍率同步、Ban 命令拒绝和原版选项保留。
