# RWmiao Lua 脚本 API v1（v0.12-s4）

本文是 RWmiao 单位自动化脚本的完整开发规范。它不是 Lua 教程。脚本负责判断、状态机和战术；框架只负责按需提供只读快照、保存用户设置、限制可控单位并把请求转换为游戏原生命令。

## 1. 运行与性能模型

- 使用 Lua 5.2 兼容子集（LuaJ，纯 Java）。没有 `luajava/io/os/package/debug/require/load/loadfile/dofile`。
- 脚本只控制玩家在“单位脚本”面板中绑定给它的己方单位。脚本的 `units` 声明只决定按钮是否适用，不会自动控制全图同兵种。
- 在游戏中一次多选相同 `type_id` 的单位再应用脚本，这些单位会组成同一个 `group_id`，可用于编队、包围、扇形展开等协同逻辑。
- 总开关关闭、没有已加载脚本、没有已启用且已绑定单位时，脚本调度 Tick Hook 会被彻底卸载。最后一个单位死亡、`finish` 或 `exit` 后也会卸载。
- 只有回调到期才扫描全局单位表；只有 `data` 声明的分组才反射采集重数据。不要写 `data="all"`，除非确实需要全部数据。
- 每回调最多 250,000 条 Lua 指令、32 条原生命令、合计附加 256 个单位；连续 3 次异常/超限后本局停用。
- 所有决策使用模拟 Tick，不使用墙钟时间。推荐普通逻辑间隔 6～12 Tick；投射物规避可用 2～4 Tick。

## 2. 最小脚本

```lua
rw.script{
  api=1,
  id="tank_guard",
  name="坦克自动索敌",
  units={"tank"},                 -- 必填、大小写完全匹配；也可多选或写 "All"
  data={"position","combat"}   -- 仅声明真正会读取的数据
}

rw.on_tick(8,function(ctx)
  for _,u in ipairs(ctx:self_units()) do
    local enemy=ctx:nearest_enemy(u,{range=700})
    if enemy then ctx:attack(u,enemy) end
  end
end)
```

`rw.script{...}` 与至少一个 `rw.on_tick(...)` 都必须在文件顶层执行。导入器只接受扩展名为 `.lua` 的文件，可在系统文件选择器中一次多选（最多 32 个）；`.md`、`.txt` 等文件即使内容是 Lua 也会被拒绝。导入时会编译并验证；导入后的源码复制到模块私有目录，原文件移动、修改或删除不会影响已导入副本。

### 2.1 元数据字段

| 字段 | 必填 | 说明 |
|---|---:|---|
| `api` | 否 | 当前只能为 `1`。|
| `id` | 是 | 1～64 位稳定标识，只能用字母、数字、`.`、`_`、`-`。|
| `name` | 否 | UI 显示名，省略时使用 `id`。|
| `units` | 是 | 单个严格 ID、ID 数组，或精确字符串 `"All"`。可使用 `*`，但建议写 `All`。不匹配中文名。|
| `data` | 建议 | 数据分组数组。省略时为兼容旧脚本采集 `all`，性能最差。|
| `settings` | 否 | 设置项数组；声明后脚本开启时列表右侧出现带动画的设置按钮。|

## 3. 脚本设置

设置面板提供拖动条、输入框和开关三种组件。设置保存在模块私有目录，并在下一次回调中通过 `ctx.settings` 提供。

```lua
rw.script{
  api=1,id="configurable_kite",name="可调风筝",units={"tank"},
  data={"position","combat"},
  settings={
    {key="range",name="索敌半径",type="slider",default=750,min=100,max=2000,step=50,
      description="只搜索此距离内敌人"},
    {key="enabled_retreat",name="允许后撤",type="switch",default=true},
    {key="note",name="结束提示",type="input",value_type="text",default="任务结束"},
    {key="limit",name="最多目标数",type="input",value_type="number",default=3,min=1,max=20}
  }
}

rw.on_tick(6,function(ctx)
  local range=ctx.settings.range               -- number
  local retreat=ctx.settings.enabled_retreat   -- boolean
  local note=ctx.settings.note                 -- string
  local limit=ctx.settings.limit               -- number
end)
```

| `type` | Lua 值 | 必要字段 | 说明 |
|---|---|---|---|
| `slider` | number | `min,max` | 连续拖动条；`step` 可选。保存时仍执行 `min/max` 截断。|
| `input` | string 或 number | `value_type="text"` 或 `"number"` | 单行输入框；文本最长 256 字符。|
| `switch` | boolean | 无 | 开关。|

兼容旧脚本的 `boolean/number/text/choice` 声明仍可导入；新脚本使用上表三种组件。设置项最多 32 个，`key` 必须以字母开头且最长 48 个字符。

## 4. 数据分组

`identity` 是识别与安全所需的低成本基础信息。其他分组必须按用途声明：

| data 分组 | 提供内容与用途 |
|---|---|
| `identity` | ID、兵种、建筑判断、是否可下令、死亡/删除。|
| `team` | 队伍 ID/名称、敌我关系。|
| `position` | 世界坐标、高度、方向、碰撞半径；距离/范围/编队函数必须声明。|
| `health` | 生命、护盾及比例。|
| `movement` | 移动类型、真实速度、速度分量、加减速、转向速度、是否移动。|
| `combat` | 主射程、武器数量、当前攻击目标。|
| `weapons` | 每个武器槽的射程、炮塔方向、目标、装填/预热/冷却/就绪。|
| `orders` | 当前命令、目标/坐标、路点数、是否空闲。|
| `pathing` | `path_state/path_pending`；同时采集必要移动/命令摘要。|
| `transport` | 载体关系、容量和已装载单位 ID。|
| `build` | 建造进度、工厂、队列长度、可建造类型。|
| `production` | 生产队列项目及进度。|
| `actions` | 原生 Action ID 摘要。|
| `abilities` | 能力名称、说明、费用、当前可执行/锁定状态。|
| `damage` | 最近一次检测到生命+护盾下降的 Tick。|
| `selection` | 当前选中状态。通常无需声明，因为绑定关系已明确。|
| `map` | 地图宽高、静态地形格、原生动态寻路格、通行性和本地战争迷雾。|
| `resources` | 本地队伍资金、收入、倍率、单位数/上限。|
| `projectiles` | 当前投射物位置、来源和目标。成本高，只在规避脚本中使用。|
| `environment` | 仅保留由原生危险边界/区域标记形成的 `danger_zones`。已删除暂停和模拟速度等无决策价值字段。|
| `catalog` | 构建 `rw.unit_types` 固定属性目录；会额外采集兵种射程、移动、武器和 Action 摘要，仅在确实遍历目录时声明。|
| `all` | 全部分组，仅用于调试。|

未声明或当前版本不能可靠解析的字段为 `nil`，脚本不得把 `nil` 当成 0。框架内部可能用 0 保存未采集值，但 API 使用者必须按 `data` 契约访问。

## 5. ctx 全局快照

| 名称 | 类型 | 说明 |
|---|---|---|
| `ctx.tick` | integer | 当前模拟 Tick。|
| `ctx.multiplayer` | boolean | 是否处于多人网络对局。|
| `ctx.local_team` | integer | 本地玩家队伍 ID。|
| `ctx.settings` | table | 本脚本的已验证设置。|
| `ctx.map` | table | `map` 分组数据。|
| `ctx.local_player` | table | `resources` 分组数据。|
| `ctx.projectiles` | array | `projectiles` 分组数据。|
| `ctx.environment.danger_zones` | array | 危险区域，项含 `unit_id,type_id,x,y,radius,team_id`。|

### 地图字段

`ctx.map`：`width_tiles,height_tiles,tile_width,tile_height,width,height,visibility_grid_available`。

```lua
local tile=ctx:tile_at({x=300,y=500})
-- tile_x,tile_y,in_bounds,water,water_bridge,lava,cliff,resource_pool,
-- large_cliff_or_trees,blocks_buildings,land_blocked,
-- value,visible,fogged,explored,unexplored,available
local ok=ctx:is_passable({x=300,y=500},"LAND") -- LAND/AIR/WATER/HOVER
```

#### 战争迷雾

战争迷雾来自本地队伍当前的原生 `Q` 网格，不根据敌方单位是否存在来猜测。数值语义为：`0～4` 当前可见，`5～9` 已探索但当前被雾覆盖，`10` 未探索。没有 `map` 分组、网格不可用或坐标越界时，布尔快捷函数返回 `nil`，而不是误报 `false`。

```lua
local fog=ctx:fog_at({x=300,y=500})
-- fog.tile_x/tile_y/in_bounds/available/value
-- fog.visible/fogged/explored/unexplored

if ctx:is_fogged({x=300,y=500}) == true then ... end
if ctx:is_visible({x=300,y=500}) == true then ... end
```

单位位置也提供 `visible_to_local`、`fogged_to_local`、`explored_to_local`。`ctx:enemies()` 仍读取全局注册表，不会被战争迷雾过滤。

#### 原生寻路格与死胡同判断

`ctx:tile_at` 是地图静态地形。判断单位实际能否通过时应读取原生寻路层，它同时计入地形、建筑和动态阻挡对象：

```lua
local cell=ctx:path_tile_at({x=300,y=500},"LAND")
-- movement_type,available,in_bounds,grid_x,grid_y,width,height,world_to_grid
-- terrain_cost,building_cost,object_cost
-- terrain_blocked,building_blocked,object_blocked,passable

local passable=ctx:is_path_passable({x=300,y=500},"LAND")
-- 无法解析该移动层时返回 nil；越界返回 false
```

寻路网格使用 `grid_x/grid_y`，世界坐标与格子坐标的换算为：

```lua
local function grid_center(cell,gx,gy)
  return {x=(gx+0.5)/cell.world_to_grid,y=(gy+0.5)/cell.world_to_grid}
end
```

Lua 可以从候选格开始，对上下左右或八方向格调用 `is_path_passable`，自行进行有上限的 BFS/DFS：遍历耗尽且只剩入口的一侧表示该移动层上的封闭支路；搜索到预先定义的出口、宽阔区域或达到上限则不是已确认的死胡同。“死胡同”取决于单位的 `movement_type`、入口方向、单位半径和脚本定义，框架只提供真实格子数据，不替脚本固定战术判断。建议一次最多检查 128～512 格，并缓存同一回调内已经查询的格子。

### 队伍资源字段

`ctx.local_player`：`id,name,funds,income_rate,income_multiplier,unit_count,unit_cap`。这些是只读值，不能直接修改。

### 投射物字段

每项：`id,x,y,height,source_id,target_id`。投射物表可能很大；必须先用坐标做半径过滤，且不要每 Tick 全量排序。

## 6. 单位查询

所有查询既支持 `ctx:fn(...)` 也支持 `ctx.fn(...)`；文档统一使用冒号。

```lua
ctx:units()             -- 全局注册表中的活单位
ctx:self_units()        -- 本脚本实际绑定的己方单位，不是所有同兵种
ctx:enemies()           -- 敌方单位，不受战争迷雾过滤
ctx:allies()            -- 盟军/中立单位
ctx:selected()          -- 当前选中单位（需 selection）
ctx:get(id_or_unit)     -- 按全局对象 ID 查询
ctx:nearest_enemy(point_or_unit,filter)
ctx:within(point,radius,filter)
```

filter 支持：

```lua
{types={"mammothTank","experimentalTank"},range=900,
 relation="enemy",orderable=true,selected=false,building=false}
```

兵种匹配大小写敏感；`MammothTank` 不等于 `mammothTank`。

## 7. 单位字段词典

### 身份、队伍、空间、生存

| 字段 | 类型 | 分组 | 中文解释 |
|---|---|---|---|
| `id` | integer | identity | 全局对象 ID；命令目标和脚本状态表的稳定键。|
| `type_id` | string | identity | 原生/模组兵种 ID，严格区分大小写。|
| `type_name` | string | identity | 当前语言下显示名。|
| `building` | boolean | identity | 原生 `is building` 判断；也可用 `rw.is_building(unit)`。|
| `orderable` | boolean | identity | 是否属于可接收玩家命令的单位类。|
| `dead/deleted` | boolean | identity | 已死亡/已从对象系统删除。查询通常已过滤，用于 `get` 后复核。|
| `team_id/team_name` | number/string | team | 所属队伍。|
| `relation` | integer | team | `0` 自己、`1` 敌方、`2` 盟军或中立。|
| `relation_name` | string | team | `self/enemy/ally`，为旧脚本兼容保留。|
| `x,y,height` | number | position | 世界坐标与高度。|
| `position` | table | position | `{x,y,height}`。|
| `heading` | number | position | 单位真实朝向（角度）。|
| `radius` | number | position | 碰撞/占位半径。|
| `health/max_health` | number | health | 当前/最大生命。|
| `shield/max_shield` | number | health | 当前/最大护盾。|
| `health_ratio/shield_ratio` | number | health | 单项比例。无最大护盾时护盾比例为 0。|
| `combined_health_ratio` | number | health | `(health+shield)/(max_health+max_shield)`。|
| `health_missing/shield_missing` | number | health | 缺失值，兼容字段。|
| `selected` | boolean | selection | 当前是否被本地玩家选中。|
| `group_id` | string/nil | 运行时 | 本次批量绑定形成的编队 ID。|

### 移动、战斗、命令

| 字段 | 分组 | 中文解释 |
|---|---|---|
| `movement_type` | movement | 原生移动类型。|
| `max_move_speed` | movement | 当前动态状态下原生最大移动速度。|
| `real_speed` | movement | 由速度分量和原生移动因子得到的实际速度摘要。|
| `velocity_x/velocity_y` | movement | 当前速度分量。|
| `acceleration/deceleration/turn_speed` | movement | 原生加速、减速、转向速度。|
| `moving` | movement | 当前是否有实际运动量。|
| `path_state` | pathing | `idle/following/commanded`。|
| `path_pending` | pathing | 是否有路点或当前命令。|
| `attack_range` | combat | 主攻击距离 `l()`；不同武器用 `weapons[i].range`。|
| `weapon_count` | combat | 武器槽数量。|
| `target_id` | combat | 当前攻击/索敌目标 ID。|
| `current_order` | orders | 当前原生命令枚举文本。|
| `order_target_id/order_x/order_y` | orders | 当前命令目标或坐标。|
| `waypoint_count` | orders | 路点/命令数量。|
| `idle` | orders | 没有当前命令。|

`unit.weapons[i]` 字段：`index,range,turret_heading,target_id,reload_remaining,warmup,cooldown,ready`。不同兵种没有的槽位字段为 `nil`；不要猜测单位。

### 建造、生产、运输、能力

| 字段 | 分组 | 中文解释 |
|---|---|---|
| `build_progress` | build | 建造完成度，通常 0～1。|
| `factory` | build | 是否为原生工厂类。建筑请看 `building`。|
| `queue_size` | build | 生产队列长度。|
| `buildable_types` | build | 从原生 Action 解析出的可建造建筑/单位 ID。|
| `production_items` | production | 队列项数组：`count,progress,rate,action_id,type_id,target_id`。|
| `carrier_id/attached` | transport | 运输或附着父单位 ID/是否附着。|
| `transport_capacity` | transport | 可解析的装载槽位数。|
| `loaded_unit_ids` | transport | 已装载单位 ID 数组。|
| `actions` | actions | 原生 Action ID 字符串数组。|
| `abilities` | abilities | 每项 `id,name,description,cost,executable,locked`。|
| `last_damaged_tick` | damage | 最近检测到耐久下降的 Tick；不能可靠给出伤害来源，故未开放猜测字段。|
| `visible_to_local` | map | 单位位置对本地队伍是否可见。敌人仍会存在于全局查询中。|
| `fogged_to_local` | map | 单位所在格是否处于当前战争迷雾中。|
| `explored_to_local` | map | 单位所在格是否曾探索；未探索格为 `false`。|

## 8. 原生命令

所有命令只接受本脚本已绑定、仍存活、己方且 `orderable` 的单位。敌人/盟军只能读取或作为合法目标。`append` 默认 false。返回：

```lua
{accepted=true,command_id=123}
-- 或 {accepted=false,reason="..."}
```

| 调用 | 说明 |
|---|---|
| `ctx:move(unit_or_list,x,y,append)` | 移动。|
| `ctx:attack_move(unit_or_list,x,y,append)` | 攻击移动。|
| `ctx:attack(unit_or_list,target,append)` | 攻击指定单位。|
| `ctx:patrol(unit_or_list,x,y,append)` | 巡逻。|
| `ctx:guard(unit_or_list,target,append)` | 保护/跟随。|
| `ctx:repair(unit_or_list,target,append)` | 修理。|
| `ctx:reclaim(unit_or_list,target,append)` | 回收。|
| `ctx:enter(unit_or_list,transport,append)` | 让单位进入运输载体。|
| `ctx:load(transport_or_list,target,append)` | 让载体装载目标。|
| `ctx:build(builder_or_list,type_id,x,y,variant,append)` | 建造指定类型；variant 默认 1。|
| `ctx:action(unit_or_list,action_id[,x,y,append])` | 执行原生能力/生产 Action。|
| `ctx:stop(unit_or_list)` | 停止。|

只有 `accepted=true` 才能推进 Lua 状态机；这表示原生命令对象已成功创建，并不代表单位最终到达或动作完成。

## 9. 生命周期与本地提示

```lua
ctx:finish(unit_or_list) -- 只解除指定单位与当前脚本的绑定
ctx:finish()             -- 解除当前脚本的全部绑定
ctx:exit("包围任务完成") -- 解除全部绑定，并向自己聊天框显示消息
```

`exit` 消息最长 240 字符、换行会被替换；调用的是游戏本地聊天投递方法，不生成网络聊天包，其他玩家不可见。任务已完成、目标永久消失或继续轮询没有意义时应尽早 `finish/exit`，这样最后一个绑定消失后框架会卸载 Tick Hook。

单位死亡、删除、换队或对象 ID 消失时，框架会自动移除绑定。对局 Tick 回退会清空全部绑定、编队、受击历史与 Lua Tick 状态。

## 10. 编队组 API

同一次“多选同兵种 → 脚本管理 → 应用”产生一个组。不同脚本的组互不影响。

```lua
ctx:groups()          -- [{id,units,count,center}, ...]
ctx:group_of(unit)    -- 返回 group_id 或 nil
rw.centroid(units)    -- 返回 {x,y}；空数组返回 nil
```

包围示意：

```lua
rw.on_tick(10,function(ctx)
  for _,g in ipairs(ctx:groups()) do
    local enemy=ctx:nearest_enemy(g.center,{range=1000})
    if enemy then
      for i,u in ipairs(g.units) do
        local angle=(i-1)*360/g.count
        local p=rw.around(enemy,220,angle)
        ctx:move(u,p.x,p.y)
      end
    else
      ctx:exit("范围内已无目标")
      return
    end
  end
end)
```

## 11. 快捷函数与公开目录

| 名称 | 说明 |
|---|---|
| `rw.distance(a,b)` | 二维距离。|
| `rw.away(from,target,distance)` | 从目标反方向移动指定距离，返回点。|
| `rw.around(center,radius,angle_degrees)` | 圆周点。|
| `rw.centroid(units)` | 单位数组中心点。|
| `rw.is_building(unit)` | 原生建筑判断。|
| `rw.log(...)` | 写入 `RWmiaoLua` 日志。|
| `rw.catalog()` | 打印数据分组、字段、命令和生命周期接口。|
| `rw.data_groups/rw.unit_fields/rw.commands/rw.lifecycle` | 可遍历的全局目录表。|
| `rw.field_groups[field]` | 字段所属 data 分组。|
| `rw.command_signatures[name]` | 命令签名。|
| `rw.unit_types[type_id]` | 当前快照出现过的兵种固定属性摘要。|

地图查询函数（都必须声明 `data={"map"}`）：

| 名称 | 说明 |
|---|---|
| `ctx:tile_at(point)` | 读取世界坐标所在的静态地形格和战争迷雾字段。|
| `ctx:fog_at(point)` | 只读取战争迷雾格，适合高频可见性判断。|
| `ctx:is_fogged(point)` / `ctx:is_visible(point)` | 当前雾区/可见快捷判断；不可用时为 `nil`。|
| `ctx:path_tile_at(point,movement_type)` | 读取指定移动类型的原生动态寻路格。|
| `ctx:is_path_passable(point,movement_type)` | 真实寻路层通行判断；不可解析时为 `nil`。|
| `ctx:is_passable(point,movement_type)` | 优先使用原生寻路层；该层不可解析时退回静态地形规则。|

声明 `data={"catalog",...}` 后，`rw.unit_types[type_id]` 可含：`id,name,custom,movement_type,attack_range,max_move_speed,radius,max_health,max_shield,weapon_count,acceleration,deceleration,turn_speed,weapons,abilities,transport_capacity,actions,buildable_types`。它来自当前快照，不是安装时写死的数据表；未出现的模组兵种不会凭空存在。

## 12. 沙盒编辑器 All 单位 ID 表

脚本兵种 ID 表与房间选项中的单位列表使用同一运行时规则：

1. 读取 `game.units.cj.ae` 完整类型注册表；该注册表同时包含原版类型与已经加载的内置自定义类型。
2. 排除编辑器内部类型 `cj.I/v/q/R/H/W/X/Y/Z/N`。
3. 通过 `ce.d(type)` 创建单位，只保留 `game.units.bp` 可下令单位。
4. 排除 `test_tank` 与 `missing`。
5. 自定义类型仅保留 `custom.l.aF == true`，即 `[core]showInEditor` 生效的类型。
6. 使用原生 `game.units.ab` 比较器排序；UI 去重键不区分大小写，但保留首个类型的原始 ID 拼写。

### 12.1 原版类型（40 个）

| ID | 简体中文名称 |
|---|---|
| `extractor` | 资源抽取器 |
| `landFactory` | 陆军工厂 |
| `airFactory` | 空军基地 |
| `seaFactory` | 海军基地 |
| `commandCenter` | 指挥中心 |
| `turret` | 炮塔 |
| `antiAirTurret` | 防空炮塔 |
| `builder` | 建造者 |
| `tank` | 坦克 |
| `hoverTank` | 悬浮坦克 |
| `artillery` | 自行火炮 |
| `helicopter` | 直升机 |
| `airShip` | 拦截机 |
| `gunShip` | 武装直升机 |
| `missileShip` | 导弹舰 |
| `gunBoat` | 机枪艇 |
| `laserTank` | 激光坦克 |
| `hovercraft` | 登陆艇 |
| `ladybug` | 瓢虫 |
| `battleShip` | 战列舰 |
| `heavyTank` | 重型坦克 |
| `heavyHoverTank` | 重型气垫坦克 |
| `laserDefence` | 激光防御塔 |
| `dropship` | 运输机 |
| `repairbay` | 修复湾 |
| `NukeLaucher` | 核弹发射井 |
| `AntiNukeLaucher` | 反核防御 |
| `mammothTank` | 猛犸坦克 |
| `experimentalTank` | 实验坦克 |
| `experimentalLandFactory` | 实验工厂 |
| `fabricator` | 资源制造仪 |
| `attackSubmarine` | 潜水艇 |
| `builderShip` | 海上建造者 |
| `amphibiousJet` | 两栖喷气机 |
| `experimentalHoverTank` | 概念型悬浮坦克 |
| `turret_artillery` | 火炮升级炮塔 |
| `turret_flamethrower` | 火焰喷射炮塔 |
| `antiAirTurretT2` | T2 防空炮塔 |
| `turretT2` | T2 机枪塔 |
| `turretT3` | T3 重机枪塔 |

### 12.2 当前游戏包内置自定义类型（103 个）

| ID | 简体中文名称或配置显示名 |
|---|---|
| `aaBeamGunship` | AA激光射束战机 |
| `bomber` | 轰炸机 |
| `bugMeleeT31` | Killer alpha |
| `combatEngineer` | 战斗工程师 |
| `experiementalCarrier` | 航空母舰 |
| `experimentalDropship` | 飞行堡垒 |
| `experimentalGunship` | 实验悬浮型气垫船 |
| `experimentalSpider` | 实验型战斗蜘蛛 |
| `c_experimentalTank` | Experimental Tank |
| `extractorT1` | extractorT1 |
| `extractorT2` | 资源抽取器 T2 |
| `extractorT3` | 资源抽取器 T3 |
| `extractorT3_overclocked` | extractorT3_overclocked |
| `extractorT3_reinforced` | extractorT3_reinforced |
| `fabricatorT1` | fabricatorT1 |
| `fabricatorT2` | fabricatorT2 |
| `fabricatorT3` | fabricatorT3 |
| `fireBee` | 火蜂战机 |
| `heavyAAShip` | 重型防空舰 |
| `heavyBattleship` | 重型战舰 |
| `heavyInterceptor` | 重型拦截机 |
| `heavyMissileShip` | 重型导弹舰 |
| `heavySub` | 重型潜艇 |
| `c_helicopter` | c_helicopter |
| `c_interceptor` | c_interceptor |
| `laboratory` | Laboratory |
| `c_laserTank` | Laser Tank |
| `lightGunship` | 轻型武装直升机 |
| `lightSub` | 水下探测器 |
| `c_mammothTank` | c_mammothTank |
| `mechArtillery` | 火炮机甲 |
| `mechBunker` | 移动炮塔 |
| `mechFlame` | 喷火机甲 |
| `mechFlyingLanded` | mechFlyingLanded |
| `mechHeavyMissile` | 重型防空机械装甲 |
| `mechLaser` | 等离子机甲 |
| `mechLightning` | 特斯拉机甲 |
| `mechMinigun` | 机枪机甲 |
| `mechGun` | 基础机甲 |
| `mechMissile` | 防空机甲 |
| `mechEngineer` | 机械师 |
| `mechFactory` | 机械工厂 |
| `missileAirship` | 导弹飞艇 |
| `missileTank` | 防空导弹坦克 |
| `modularSpider_antiair` | 萨姆防空炮T1 |
| `modularSpider_antiairFlak` | 高射炮T2 |
| `modularSpider_antiairT2` | 萨姆防空炮T2 |
| `modularSpider_antinuke` | 反核装置 |
| `modularSpider_artillery` | 火炮装置T1 |
| `modularSpider_blink` | 瞬移模块 |
| `modularSpider_fabricator` | 资源制造装置T1 |
| `modularSpider_fabricatorT2` | 资源制造装置T2 |
| `modularSpider_gunturret` | 机枪T1 |
| `modularSpider_gunturretT2` | 机枪T2 |
| `modularSpider_laserdefense` | 激光防御装置 |
| `modularSpider_lightning` | 闪电炮塔T1 |
| `modularSpider` | 模块化蜘蛛 |
| `modularSpider_shieldGen` | 护盾核心 |
| `modularSpider_smallgunturret` | 等离子装置T1 |
| `modularSpider_smallgunturretT2` | 等离子装置T2 |
| `modularSpider_speed` | 速度模块 |
| `modularSpider_speedIncomplete` | modularSpider_speedIncomplete |
| `nautilusSubmarine` | 鹦鹉螺号 |
| `antiNukeLauncherC` | antiNukeLauncherC |
| `nukeLauncherC` | nukeLauncherC |
| `outpostT1` | 瞭望塔 |
| `outpostT2` | 瞭望塔 T2 |
| `plasmaTank` | 等离子坦克 |
| `creditsCrates` | 资金箱子 |
| `crystal_mid` | crystal_mid |
| `scout` | 侦察者 |
| `spyDrone` | 间谍无人机 |
| `c_artillery` | c_artillery |
| `heavyArtillery` | 重型火炮 |
| `c_tank` | c_tank |
| `c_antiAirTurret` | c_antiAirTurret |
| `antiAirTurretFlak` | T2 - 高射炮 |
| `c_antiAirTurretT2` | c_antiAirTurretT2 |
| `c_antiAirTurretT3` | c_antiAirTurretT3 |
| `c_turret_t1` | c_turret_t1 |
| `c_turret_t1_artillery` | c_turret_t1_artillery |
| `c_turret_t1_lightning` | c_turret_t1_lightning |
| `c_turret_t2_artillery` | c_turret_t2_artillery |
| `c_turret_t2_flame` | c_turret_t2_flame |
| `c_turret_t2_gun` | c_turret_t2_gun |
| `c_turret_t2_lightning` | c_turret_t2_lightning |
| `c_turret_t3_gun` | c_turret_t3_gun |
| `bugPickup` | bugPickup |
| `bugWasp` | bugWasp |
| `bugRangedT2` | bugRangedT2 |
| `bugBee` | bugBee |
| `bugExtractor` | bugExtractor |
| `bugFly` | bugFly |
| `bugGenerator` | bugGenerator |
| `bugMelee` | bugMelee |
| `bugMeleeLarge` | bugMeleeLarge |
| `bugMeleeSmall` | bugMeleeSmall |
| `bugNest` | bugNest |
| `bugRanged` | bugRanged |
| `bugSpore` | bugSpore |
| `bugTurret` | bugTurret |
| `bugGeneratorN` | Generator |
| `bugGeneratorNT2` | Generator |

`NukeLaucher`、`AntiNukeLaucher` 与 `experiementalCarrier` 均为资源中的实际拼写。ID 比较严格区分大小写。外部模组加载后会继续向 `cj.ae` 注册类型；脚本可通过声明 `data={"catalog"}` 并遍历 `rw.unit_types` 获取当前对局新增 ID。

## 13. 完整可调风筝示例

```lua
rw.script{
  api=1,id="mech_minigun_kite_mammoth_s1",name="机枪机甲风筝猛犸坦克",
  units={"mechMinigun"},data={"position","health","movement","combat","orders"},
  settings={
    {key="search_range",name="索敌范围",type="slider",default=900,min=200,max=1800,step=50},
    {key="keep_ratio",name="保持射程比例",type="slider",default=0.82,min=0.45,max=1.1,step=0.02},
    {key="stop_without_target",name="无目标时结束",type="switch",default=false}
  }
}

local target_types={mammothTank=true,c_mammothTank=true}
local last_order={}

rw.on_tick(6,function(ctx)
  local controlled=ctx:self_units()
  if #controlled==0 then return end
  local any=false
  for _,u in ipairs(controlled) do
    local enemy=ctx:nearest_enemy(u,{types={"mammothTank","c_mammothTank"},range=ctx.settings.search_range})
    if enemy then
      any=true
      local distance=rw.distance(u,enemy)
      local keep=math.max(90,u.attack_range*ctx.settings.keep_ratio)
      local result
      if distance<keep then
        local p=rw.away(u,enemy,math.max(55,u.attack_range*0.35))
        result=ctx:move(u,p.x,p.y)
      elseif distance<=u.attack_range then
        result=ctx:attack(u,enemy)
      else
        result=ctx:attack(u,enemy)
      end
      if result.accepted then last_order[u.id]=ctx.tick end
    end
  end
  if not any and ctx.settings.stop_without_target then ctx:exit("范围内没有猛犸坦克，脚本已退出") end
end)
```

注意：官方原版 All 表没有 `mechMinigun`，它是示例所用模组/改包兵种 ID。若实际 APK 的 `type_id` 不同，必须按 `rw.unit_types` 输出严格修改。

## 14. UI 与文件行为

- 模块“脚本”页：总开关、导入、单脚本开关、左侧删除、右侧设置。导入使用 Android 系统文件选择器，只允许 `.lua`，支持一次多选。
- 设置按钮仅在脚本开启且声明 `settings` 时显示，并带进入/退出动画。
- 游戏内“脚本管理”只在己方、可下令且严格匹配 `units` 的单位上出现。
- 首次进入游戏会在应用上下文可用后立即加载私有脚本并刷新“脚本管理”入口，无需先打开模块页保存。
- 多选只批量处理与点击单位 `type_id` 完全相同的己方单位。
- APK 根目录保留空 `Config/Scripts/.keep`；没有内置 Lua。若打包者在构建前向项目 `Config/Scripts` 放入 Lua，它们在模块新安装版本首次打开时复制到私有目录，用户仍可删除。
- 热重载采用新 VM 验证成功后替换；失败保留旧 VM。脚本运行时不反复读取导入源文件。

## 15. 编写检查表

1. `units` 使用 `type_id`，严格区分大小写；不要把中文名写进去。
2. `data` 只列实际读取分组；距离/编队必须有 `position`，攻击射程必须有 `combat`。
3. `on_tick` 优先 6～12 Tick；不要每 Tick排序全局单位或投射物。
4. 状态以 `unit.id` 或 `group_id` 为键，不保存 Java 对象。
5. 每次命令检查 `accepted`，不要把“提交成功”误当作“动作完成”。
6. 用 `building`/`rw.is_building` 判断建筑，不用速度为 0 推断。
7. 完成任务时调用 `finish/exit`，让框架自动降为零 Tick Hook 开销。

