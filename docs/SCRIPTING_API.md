# RWmiao Lua 脚本 API v1（v0.18）

[中文](#中文) · [English](#english)

---

## 中文

本文是 RWmiao 单位自动化脚本的完整开发规范。它不是 Lua 教程。脚本负责判断、状态机和战术；框架只负责按需提供只读快照、保存用户设置、限制可控单位并把请求转换为游戏原生命令。

## 1. 运行与性能模型

- 使用 Lua 5.2 兼容子集（LuaJ，纯 Java）。没有 `luajava/io/os/package/debug/require/load/loadfile/dofile`。
- 脚本只控制玩家在"单位脚本"面板中绑定给它的己方单位。脚本的 `units` 声明只决定按钮是否适用，不会自动控制全图同兵种。
- 在游戏中一次多选相同 `type_id` 的单位再应用脚本，这些单位会组成同一个 `group_id`，可用于编队、包围、扇形展开等协同逻辑。
- 总开关关闭、没有已加载脚本、没有已启用且已绑定单位时，脚本调度 Tick Hook 会被彻底卸载。最后一个单位死亡、 `finish` 或 `exit` 后也会卸载。
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

`rw.script{...}` 与至少一个 `rw.on_tick(...)` 都必须在文件顶层执行。导入器只接受扩展名为 `.lua` 的文件，可在系统文件选择器中一次多选（最多 32 个）； `.md`、 `.txt` 等文件即使内容是 Lua 也会被拒绝。导入时会编译并验证；导入后的源码复制到模块私有目录，原文件移动、修改或删除不会影响已导入副本。

### 2.1 元数据字段

| 字段         | 必填 | 说明                                                      |
| :--------- | :- | :------------------------------------------------------ |
| `api`      | 否  | 当前只能为 `1`。                                              |
| `id`       | 是  | 1～64 位稳定标识，只能用字母、数字、 `.`、 `_`、 `-`。                     |
| `name`     | 否  | UI 显示名，省略时使用 `id`。                                      |
| `units`    | 是  | 单个严格 ID、ID 数组，或精确字符串 `"All"`。可使用 `*`，但建议写 `All`。不匹配中文名。 |
| `data`     | 建议 | 数据分组数组。省略时为兼容旧脚本采集 `all`，性能最差。                          |
| `settings` | 否  | 设置项数组；声明后脚本开启时列表右侧出现带动画的设置按钮。                           |

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

| `type`   | Lua 值           | 必要字段                             | 说明                                    |
| :------- | :-------------- | :------------------------------- | :------------------------------------ |
| `slider` | number          | `min,max`                        | 连续拖动条； `step` 可选。保存时仍执行 `min/max` 截断。 |
| `input`  | string 或 number | `value_type="text"` 或 `"number"` | 单行输入框；文本最长 256 字符。                    |
| `switch` | boolean         | 无                                | 开关。                                   |

兼容旧脚本的 `boolean/number/text/choice` 声明仍可导入；新脚本使用上表三种组件。设置项最多 32 个， `key` 必须以字母开头且最长 48 个字符。

## 4. 数据分组

`identity` 是识别与安全所需的低成本基础信息。其他分组必须按用途声明：

| data 分组       | 提供内容与用途                                                           |
| :------------ | :---------------------------------------------------------------- |
| `identity`    | ID、兵种、建筑判断、是否可下令、死亡/删除。                                           |
| `team`        | 队伍 ID/名称、敌我关系。                                                    |
| `position`    | 世界坐标、高度、方向、碰撞半径；距离/范围/编队函数必须声明。                                   |
| `health`      | 生命、护盾及比例。                                                         |
| `movement`    | 移动类型、真实速度、速度分量、加减速、转向速度、是否移动。                                     |
| `combat`      | 主射程、武器数量、当前攻击目标。                                                  |
| `weapons`     | 每个武器槽的射程、炮塔方向、目标、装填/预热/冷却/就绪。                                     |
| `orders`      | 当前命令、目标/坐标、路点数、是否空闲。                                              |
| `pathing`     | `path_state/path_pending`；同时采集必要移动/命令摘要。                          |
| `transport`   | 载体关系、容量和已装载单位 ID。                                                 |
| `build`       | 建造进度、工厂、队列长度、可建造类型。                                               |
| `production`  | 生产队列项目及进度。                                                        |
| `actions`     | 原生 Action ID 摘要。                                                  |
| `abilities`   | 能力名称、说明、费用、当前可执行/锁定状态。                                            |
| `damage`      | 最近一次检测到生命+护盾下降的 Tick。                                             |
| `selection`   | 当前选中状态。通常无需声明，因为绑定关系已明确。                                          |
| `map`         | 地图宽高、静态地形格、原生动态寻路格、通行性和本地战争迷雾。                                    |
| `resources`   | 本地队伍资金、收入、倍率、单位数/上限。                                              |
| `projectiles` | 当前投射物位置、来源和目标。成本高，只在规避脚本中使用。                                      |
| `environment` | 仅保留由原生危险边界/区域标记形成的 `danger_zones`。已删除暂停和模拟速度等无决策价值字段。             |
| `catalog`     | 构建 `rw.unit_types` 固定属性目录；会额外采集兵种射程、移动、武器和 Action 摘要，仅在确实遍历目录时声明。 |
| `all`         | 全部分组，仅用于调试。                                                       |

未声明或当前版本不能可靠解析的字段为 `nil`，脚本不得把 `nil` 当成 0。框架内部可能用 0 保存未采集值，但 API 使用者必须按 `data` 契约访问。

## 5. ctx 全局快照

| 名称                             | 类型      | 说明                                            |
| :----------------------------- | :------ | :-------------------------------------------- |
| `ctx.tick`                     | integer | 当前模拟 Tick。                                    |
| `ctx.multiplayer`              | boolean | 是否处于多人网络对局。                                   |
| `ctx.local_team`               | integer | 本地玩家队伍 ID。                                    |
| `ctx.settings`                 | table   | 本脚本的已验证设置。                                    |
| `ctx.map`                      | table   | `map` 分组数据。                                   |
| `ctx.local_player`             | table   | `resources` 分组数据。                             |
| `ctx.projectiles`              | array   | `projectiles` 分组数据。                           |
| `ctx.environment.danger_zones` | array   | 危险区域，项含 `unit_id,type_id,x,y,radius,team_id`。 |

### 地图字段

`ctx.map`： `width_tiles,height_tiles,tile_width,tile_height,width,height,visibility_grid_available`。

```lua
local tile=ctx:tile_at({x=300,y=500})
-- tile_x,tile_y,in_bounds,water,water_bridge,lava,cliff,resource_pool,
-- large_cliff_or_trees,blocks_buildings,land_blocked,
-- value,visible,fogged,explored,unexplored,available
local ok=ctx:is_passable({x=300,y=500},"LAND") -- LAND/AIR/WATER/HOVER
```

#### 战争迷雾

战争迷雾来自本地队伍当前的原生 `Q` 网格，不根据敌方单位是否存在来猜测。数值语义为： `0～4` 当前可见， `5～9` 已探索但当前被雾覆盖， `10` 未探索。没有 `map` 分组、网格不可用或坐标越界时，布尔快捷函数返回 `nil`，而不是误报 `false`。

```lua
local fog=ctx:fog_at({x=300,y=500})
-- fog.tile_x/tile_y/in_bounds/available/value
-- fog.visible/fogged/explored/unexplored

if ctx:is_fogged({x=300,y=500}) == true then ... end
if ctx:is_visible({x=300,y=500}) == true then ... end
```

单位位置也提供 `visible_to_local`、 `fogged_to_local`、 `explored_to_local`。 `ctx:enemies()` 仍读取全局注册表，不会被战争迷雾过滤。

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

Lua 可以从候选格开始，对上下左右或八方向格调用 `is_path_passable`，自行进行有上限的 BFS/DFS：遍历耗尽且只剩入口的一侧表示该移动层上的封闭支路；搜索到预先定义的出口、宽阔区域或达到上限则不是已确认的死胡同。"死胡同"取决于单位的 `movement_type`、入口方向、单位半径和脚本定义，框架只提供真实格子数据，不替脚本固定战术判断。建议一次最多检查 128～512 格，并缓存同一回调内已经查询的格子。

### 队伍资源字段

`ctx.local_player`： `id,name,funds,income_rate,income_multiplier,unit_count,unit_cap`。这些是只读值，不能直接修改。

### 投射物字段

每项： `id,x,y,height,source_id,target_id`。投射物表可能很大；必须先用坐标做半径过滤，且不要每 Tick 全量排序。

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

兵种匹配大小写敏感； `MammothTank` 不等于 `mammothTank`。

## 7. 单位字段词典

### 身份、队伍、空间、生存

| 字段                              | 类型            | 分组        | 中文解释                                            |
| :------------------------------ | :------------ | :-------- | :---------------------------------------------- |
| `id`                            | integer       | identity  | 全局对象 ID；命令目标和脚本状态表的稳定键。                         |
| `type_id`                       | string        | identity  | 原生/模组兵种 ID，严格区分大小写。                             |
| `type_name`                     | string        | identity  | 当前语言下显示名。                                       |
| `building`                      | boolean       | identity  | 原生 `is building` 判断；也可用 `rw.is_building(unit)`。 |
| `orderable`                     | boolean       | identity  | 是否属于可接收玩家命令的单位类。                                |
| `dead/deleted`                  | boolean       | identity  | 已死亡/已从对象系统删除。查询通常已过滤，用于 `get` 后复核。              |
| `team_id/team_name`             | number/string | team      | 所属队伍。                                           |
| `relation`                      | integer       | team      | `0` 自己、 `1` 敌方、 `2` 盟军或中立。                      |
| `relation_name`                 | string        | team      | `self/enemy/ally`，为旧脚本兼容保留。                     |
| `x,y,height`                    | number        | position  | 世界坐标与高度。                                        |
| `position`                      | table         | position  | `{x,y,height}`。                                 |
| `heading`                       | number        | position  | 单位真实朝向（角度）。                                     |
| `radius`                        | number        | position  | 碰撞/占位半径。                                        |
| `health/max_health`             | number        | health    | 当前/最大生命。                                        |
| `shield/max_shield`             | number        | health    | 当前/最大护盾。                                        |
| `health_ratio/shield_ratio`     | number        | health    | 单项比例。无最大护盾时护盾比例为 0。                             |
| `combined_health_ratio`         | number        | health    | `(health+shield)/(max_health+max_shield)`。      |
| `health_missing/shield_missing` | number        | health    | 缺失值，兼容字段。                                       |
| `selected`                      | boolean       | selection | 当前是否被本地玩家选中。                                    |
| `group_id`                      | string/nil    | 运行时       | 本次批量绑定形成的编队 ID。                                 |

### 移动、战斗、命令

| 字段                                     | 分组       | 中文解释                                  |
| :------------------------------------- | :------- | :------------------------------------ |
| `movement_type`                        | movement | 原生移动类型。                               |
| `max_move_speed`                       | movement | 当前动态状态下原生最大移动速度。                      |
| `real_speed`                           | movement | 由速度分量和原生移动因子得到的实际速度摘要。                |
| `velocity_x/velocity_y`                | movement | 当前速度分量。                               |
| `acceleration/deceleration/turn_speed` | movement | 原生加速、减速、转向速度。                         |
| `moving`                               | movement | 当前是否有实际运动量。                           |
| `path_state`                           | pathing  | `idle/following/commanded`。           |
| `path_pending`                         | pathing  | 是否有路点或当前命令。                           |
| `attack_range`                         | combat   | 主攻击距离 `l()`；不同武器用 `weapons[i].range`。 |
| `weapon_count`                         | combat   | 武器槽数量。                                |
| `target_id`                            | combat   | 当前攻击/索敌目标 ID。                         |
| `current_order`                        | orders   | 当前原生命令枚举文本。                           |
| `order_target_id/order_x/order_y`      | orders   | 当前命令目标或坐标。                            |
| `waypoint_count`                       | orders   | 路点/命令数量。                              |
| `idle`                                 | orders   | 没有当前命令。                               |

`unit.weapons[i]` 字段： `index,range,turret_heading,target_id,relod_remaining,warup,coldown,ready`。不同兵种没有的槽位字段为 `nil`；不要猜测单位。

### 建造、生产、运输、能力

| 字段                    | 分组         | 中文解释                                                      |
| :-------------------- | :--------- | :-------------------------------------------------------- |
| `build_progress`      | build      | 建造完成度，通常 0～1。                                             |
| `factory`             | build      | 是否为原生工厂类。建筑请看 `bulding`。                                 |
| `queue_size`          | build      | 生产队列长度。                                                   |
| `buildable_types`     | build      | 从原生 Action 解析出的可建造建筑/单位 ID。                               |
| `production_items`    | production | 队列项数组： `cunt,progress,rae,acion_id,ype_id,target_id`。 |
| `carrier_id/attached` | transport  | 运输或附着父单位 ID/是否附着。                                         |
| `transport_capacity`  | transport  | 可解析的装载槽位数。                                                |
| `loaded_unit_ids`     | transport  | 已装载单位 ID 数组。                                              |
| `actions`             | actions    | 原生 Action ID 字符串数组。                                       |
| `abilities`           | abilities  | 每项 `id,name,descripton,cst,executable,loked`。          |
| `last_damaged_tick`   | damage     | 最近检测到耐久下降的 Tick；不能可靠给出伤害来源，故未开放猜测字段。                      |
| `visible_to_local`    | map        | 单位位置对本地队伍是否可见。敌人仍会存在于全局查询中。                               |
| `fogged_to_local`     | map        | 单位所在格是否处于当前战争迷雾中。                                         |
| `explored_to_local`   | map        | 单位所在格是否曾探索；未探索格为 `false`。                                 |

## 8. 原生命令

所有命令只接受本脚本已绑定、仍存活、己方且 `orderable` 的单位。敌人/盟军只能读取或作为合法目标。 `append` 默认 false。返回：

```lua
{accepted=true,command_id=123}
-- 或 {accepted=false,reason="..."}
```

| 调用                                                      | 说明                   |
| :------------------------------------------------------ | :------------------- |
| `ctx:move(unit_or_lit,x,y,append)`                     | 移动。                  |
| `ctx:attack_move(unit_or_lit,x,y,append)`              | 攻击移动。                |
| `ctx:attack(unit_or_lit,target,append)`                | 攻击指定单位。              |
| `ctx:patrol(unit_or_lit,x,y,append)`                   | 巡逻。                  |
| `ctx:guard(unit_or_lit,target,append)`                 | 保护/跟随。               |
| `ctx:repair(unit_or_lit,target,append)`                | 修理。                  |
| `ctx:reclaim(unit_or_lit,target,append)`               | 回收。                  |
| `ctx:enter(unit_or_lit,transport,append)`              | 让单位进入运输载体。           |
| `ctx:load(transport_or_lit,target,append)`             | 让载体装载目标。             |
| `ctx:build(builder_or_lit,type_id,x,y,ariant,append)` | 建造指定类型；variant 默认 1。 |
| `ctx:action(unit_or_lit,acion_id[,x,y,append])`       | 执行原生能力/生产 Action。    |
| `ctx:stop(unit_or_lit)`                                | 停止。                  |

只有 `accepted=true` 才能推进 Lua 状态机；这表示原生命令对象已成功创建，并不代表单位最终到达或动作完成。

## 9. 生命周期与本地提示

```lua
ctx:finish(unit_or_lit) -- 只解除指定单位与当前脚本的绑定
ctx:finish()             -- 解除当前脚本的全部绑定
ctx:exit("包围任务完成") -- 解除全部绑定，并向自己聊天框显示消息
```

`exit` 消息最长 240 字符、换行会被替换；调用的是游戏本地聊天投递方法，不生成网络聊天包，其他玩家不可见。任务已完成、目标永久消失或继续轮询没有意义时应尽早 `finish/exit`，这样最后一个绑定消失后框架会卸载 Tick Hook。

单位死亡、删除、换队或对象 ID 消失时，框架会自动移除绑定。对局 Tick 回退会清空全部绑定、编队、受击历史与 Lua Tick 状态。

## 10. 编队组 API

同一次"多选同兵种 → 脚本管理 → 应用"产生一个组。不同脚本的组互不影响。

```lua
ctx:groups()          -- [{id,units,cunt,center}, ...]
ctx:group_of(unit)    -- 返回 group_id 或 nil
rw.centroid(units)    -- 返回 {x,y}；空数组返回 nil
```

包围示意：

```lua
rw.on_tick(10,function(ctx)
  for _,g in ipairs(ctx:groups()) do
    local enemy=ctx:nearest_enemy(g.cener,{range=1000})
    if enemy then
      for i,u in ipairs(g.units) do
        local angle=(i-1)*360/g.cunt
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

| 名称                                                       | 说明                   |
| :------------------------------------------------------- | :------------------- |
| `rw.distance(a,b)`                                       | 二维距离。                |
| `rw.away(from,target,distance)`                          | 从目标反方向移动指定距离，返回点。    |
| `rw.around(cener,radius,angle_degres)`                 | 圆周点。                 |
| `rw.centroid(units)`                                     | 单位数组中心点。             |
| `rw.is_building(unit)`                                   | 原生建筑判断。              |
| `rw.log(...)`                                            | 写入 `RWmiaoLua` 日志。   |
| `rw.catalog()`                                           | 打印数据分组、字段、命令和生命周期接口。 |
| `rw.data_groups/rw.unit_felds/rw.comands/rw.lifecycle` | 可遍历的全局目录表。           |
| `rw.field_groups[filed]`                                 | 字段所属 data 分组。        |
| `rw.command_signatures[name]`                            | 命令签名。                |
| `rw.unit_types[type_id]`                                 | 当前快照出现过的兵种固定属性摘要。    |

地图查询函数（都必须声明 `data={"map"}`）：

| 名称                                               | 说明                         |
| :----------------------------------------------- | :------------------------- |
| `ctx:tile_at(point)`                             | 读取世界坐标所在的静态地形格和战争迷雾字段。     |
| `ctx:fog_at(point)`                              | 只读取战争迷雾格，适合高频可见性判断。        |
| `ctx:is_fogged(point)` / `ctx:is_visible(point)` | 当前雾区/可见快捷判断；不可用时为 `nil`。   |
| `ctx:path_tile_at(point,movement_type)`          | 读取指定移动类型的原生动态寻路格。          |
| `ctx:is_path_passable(point,movement_type)`      | 真实寻路层通行判断；不可解析时为 `nil`。    |
| `ctx:is_passable(point,movement_type)`           | 优先使用原生寻路层；该层不可解析时退回静态地形规则。 |

声明 `data={"catalog",...}` 后， `rw.unit_types[type_id]` 可含： `id,name,custom,movement_type,atack_range,max_move_seed,radius,max_ealth,max_shield,weapon_count,acceleration,deceleration,turn_speed,weapons,abilities,transport_capacity,acions,buildable_types`。它来自当前快照，不是安装时写死的数据表；未出现的模组兵种不会凭空存在。

## 12. 沙盒编辑 All 单位 ID 表

脚本兵种 ID 表与房间选项中的单位列表使用同一运行时规则：

1. 读取 `game.units.cj.ae` 完整类型注册表；该注册表同时包含原版类型与已经加载的内置自定义类型。
2. 排除编辑器内部类型 `cj.I/v/q/R/H/W/X/Y/Z/N`。
3. 通过 `ce.d(type)` 创建单位，只保留 `game.units.bp` 可下令单位。
4. 排除 `test_tank` 与 `mising`。
5. 自定义类型仅保留 `custo.l.aF == true`，即 `[core]shoInEditor` 生效的类型。
6. 使用原生 `game.units.ab` 比较器排序；UI 去重键不区分大小写，但保留首个类型的原始 ID 拼写。

### 12.1 原版类型（40 个）

| ID                        | 简体中文名称  |
| :------------------------ | :------ |
| `extractor`               | 资源抽取器   |
| `landFactory`             | 陆军工厂    |
| `airFactory`              | 空军基地    |
| `seaFactory`              | 海军基地    |
| `commandCenter`           | 指挥中心    |
| `turret`                  | 炮塔      |
| `antiAirTurret`           | 防空炮塔    |
| `builder`                 | 建造者     |
| `tank`                    | 坦克      |
| `hoverTank`               | 悬浮坦克    |
| `artillery`               | 自行火炮    |
| `helicopter`              | 直升机     |
| `airShip`                 | 拦截机     |
| `gunShip`                 | 武装直升机   |
| `missileShip`             | 导弹舰     |
| `gunBoat`                 | 机枪艇     |
| `laserTank`               | 激光坦克    |
| `hovercraft`              | 登陆艇     |
| `ladybug`                 | 瓢虫      |
| `battleShip`              | 战列舰     |
| `heavyTank`               | 重型坦克    |
| `heavyHoverTank`          | 重型气垫坦克  |
| `laserDefence`            | 激光防御塔   |
| `dropship`                | 运输机     |
| `repairbay`               | 修复湾     |
| `NukeLaucher`             | 核弹发射井   |
| `AntiNukeLaucher`         | 反核防御    |
| `mamothTank`             | 猛犸坦克    |
| `experimentalTank`        | 实验坦克    |
| `experimentalLandFactory` | 实验工厂    |
| `fabricator`              | 资源制造仪   |
| `attackSubmarine`         | 潜水艇     |
| `builderShip`             | 海上建造者   |
| `amphibiousJet`           | 两栖喷气机   |
| `experimentalHoverTank`   | 概念型悬浮坦克 |
| `turret_artillery`        | 火炮升级炮塔  |
| `turret_flamethrower`     | 火焰喷射炮塔  |
| `antiAirTurretT2`         | T2 防空炮塔 |
| `turretT2`                | T2 机枪塔  |
| `turretT3`                | T3 重机枪塔 |

### 12.2 当前游戏包内置自定义类型（103 个）

| ID                               | 简体中文名称或配置显示名                   |
| :------------------------------- | :----------------------------- |
| `aaBeamGunship`                  | AA激光射束战机                       |
| `bomber`                         | 轰炸机                            |
| `bugMeleeT31`                    | Killer alpha                   |
| `combatEngineer`                 | 战斗工程师                          |
| `experiementalCarrier`           | 航空母舰                           |
| `experimentalDropship`           | 飞行堡垒                           |
| `experimentalGunship`            | 实验悬浮型气垫船                       |
| `experimentalSpider`             | 实验型战斗蜘蛛                        |
| `c_experimentalTank`             | Experimental Tank              |
| `extractorT1`                    | extractorT1                    |
| `extractorT2`                    | 资源抽取器 T2                       |
| `extractorT3`                    | 资源抽取器 T3                       |
| `extractorT3_overclocked`        | extractorT3\_overclocked       |
| `extractorT3_reinforced`         | extractorT3\_reinforced        |
| `fabricatorT1`                   | fabricatorT1                   |
| `fabricatorT2`                   | fabricatorT2                   |
| `fabricatorT3`                   | fabricatorT3                   |
| `fireBee`                        | 火蜂战机                           |
| `heavyAAShip`                    | 重型防空舰                          |
| `heavyBattleship`                | 重型战舰                           |
| `heavyIntercepor`               | 重型拦截机                          |
| `heavyMissileShip`               | 重型导弹舰                          |
| `heavySub`                       | 重型潜艇                           |
| `c_helicopter`                   | c\_helicopter                  |
| `c_intercepor`                  | c\_intercepor                 |
| `laboratory`                     | Laboratory                     |
| `c_laserTank`                    | Laser Tank                     |
| `lightGunship`                   | 轻型武装直升机                        |
| `lightSub`                       | 水下探测器                          |
| `c_mamothTank`                  | c\_mamothTank                 |
| `mechArtillery`                  | 火炮机甲                           |
| `mechBunker`                     | 移动炮塔                           |
| `mechFlame`                      | 喷火机甲                           |
| `mechFlyingLanded`               | mechFlyingLanded               |
| `mechHeavyMissile`               | 重型防空机械装甲                       |
| `mechLaser`                      | 等离子机甲                          |
| `mechLightning`                  | 特斯拉机甲                          |
| `mechMinigun`                    | 机枪机甲                           |
| `mechGun`                        | 基础机甲                           |
| `mechMissile`                    | 防空机甲                           |
| `mechEngineer`                   | 机械师                            |
| `mechFactory`                    | 机械工厂                           |
| `missileAirship`                 | 导弹飞艇                           |
| `missileTank`                    | 防空导弹坦克                         |
| `modularSpider_antiair`          | 萨姆防空炮T1                        |
| `modularSpider_antiairFlak`      | 高射炮T2                          |
| `modularSpider_antiairT2`        | 萨姆防空炮T2                        |
| `modularSpider_antinuke`         | 反核装置                           |
| `modularSpider_artillery`        | 火炮装置T1                         |
| `modularSpider_blink`            | 瞬移模块                           |
| `modularSpider_fabricator`       | 资源制造装置T1                       |
| `modularSpider_fabricatorT2`     | 资源制造装置T2                       |
| `modularSpider_gunturret`        | 机枪T1                           |
| `modularSpider_gunturretT2`      | 机枪T2                           |
| `modularSpider_laserdefense`     | 激光防御装置                         |
| `modularSpider_lightning`        | 闪电炮塔T1                         |
| `modularSpider`                  | 模块化蜘蛛                          |
| `modularSpider_shieldGen`        | 护盾核心                           |
| `modularSpider_smallgunturret`   | 等离子装置T1                        |
| `modularSpider_smallgunturretT2` | 等离子装置T2                        |
| `modularSpider_speed`            | 速度模块                           |
| `modularSpider_speedIncomplete`  | modularSpider\_speedIncomplete |
| `nautilusSubmarine`              | 鹦鹉螺号                           |
| `antiNukeLauncherC`              | antiNukeLauncherC              |
| `nukeLauncherC`                  | nukeLauncherC                  |
| `outpostT1`                      | 瞭望塔                            |
| `outpostT2`                      | 瞭望塔 T2                         |
| `plasmaTank`                     | 等离子坦克                          |
| `creditsCrates`                  | 资金箱子                           |
| `crystal_mid`                    | crystal\_mid                   |
| `scout`                          | 侦察者                            |
| `spyDrone`                       | 间谍无人机                          |
| `c_artillery`                    | c\_artillery                   |
| `heavyArtillery`                 | 重型火炮                           |
| `c_tank`                         | c\_tank                        |
| `c_antiAirTurret`                | c\_antiAirTurret               |
| `antiAirTurretFlak`              | T2 - 高射炮                       |
| `c_antiAirTurretT2`              | c\_antiAirTurretT2             |
| `c_antiAirTurretT3`              | c\_antiAirTurretT3             |
| `c_turret_t1`                    | c\_turret\_t1                  |
| `c_turret_t1_artillery`          | c\_turret\_t1\_artillery       |
| `c_turret_t1_lightning`          | c\_turret\_t1\_lightning       |
| `c_turret_t2_artillery`          | c\_turret\_t2\_artillery       |
| `c_turret_t2_flame`              | c\_turret\_t2\_flame           |
| `c_turret_t2_gun`                | c\_turret\_t2\_gun             |
| `c_turret_t2_lightning`          | c\_turret\_t2\_lightning       |
| `c_turret_t3_gun`                | c\_turret\_t3\_gun             |
| `bugPickup`                      | bugPickup                      |
| `bugWasp`                        | bugWasp                        |
| `bugRangedT2`                    | bugRangedT2                    |
| `bugBee`                         | bugBee                         |
| `bugExtractor`                   | bugExtractor                   |
| `bugFly`                         | bugFly                         |
| `bugGenerator`                   | bugGenerator                   |
| `bugMelee`                       | bugMelee                       |
| `bugMeleeLarge`                  | bugMeleeLarge                  |
| `bugMeleeSmall`                  | bugMeleeSmall                  |
| `bugNest`                        | bugNest                        |
| `bugRanged`                      | bugRanged                      |
| `bugSpore`                       | bugSpore                       |
| `bugTurret`                      | bugTurret                      |
| `bugGeneratorN`                  | Generator                      |
| `bugGeneratorNT2`                | Generator                      |

`NukeLaucher`、 `AntiNukeLaucher` 与 `experiementalCarrier` 均为资源中的实际拼写。ID 比较严格区分大小写。外部模组加载后会继续向 `cj.ae` 注册类型；脚本可通过声明 `data={"catalog"}` 并遍历 `rw.unit_types` 获取当前对局新增 ID。

## 13. 完整可调风筝示例

```lua
rw.script{
  api=1,id="mech_minigun_kite_mamoth_s1",name="机枪机甲风筝猛犸坦克",
  units={"mechMinigun"},data={"position","health","movement","combat","orders"},
  settings={
    {key="search_range",name="索敌范围",type="slider",default=900,min=200,max=1800,step=50},
    {key="keep_ratio",name="保持射程比例",type="slider",default=0.82,min=0.45,max=1.1,step=0.02},
    {key="stop_without_target",name="无目标时结束",type="switch",default=false}
  }
}

local target_types={mamothTank=true,c_mamothTank=true}
local last_order={}

rw.on_tick(6,function(ctx)
  local controlled=ctx:self_units()
  if #controlled==0 then return end
  local any=false
  for _,u in ipairs(controlled) do
    local enemy=ctx:nearest_enemy(u,{types={"mamothTank","c_mamothTank"},range=ctx.settings.search_range})
    if enemy then
      any=true
      local distance=rw.distance(u,enemy)
      local keep=math.max(90,u.atack_range*ctx.settings.keep_ratio)
      local result
      if distance<keep then
        local p=rw.away(u,enemy,math.max(55,u.atack_range*0.35))
        result=ctx:move(u,p.x,p.y)
      elseif distance<=u.atack_range then
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

- 模块"脚本"页：总开关、导入、单脚本开关、左侧删除、右侧设置。导入使用 Android 系统文件选择器，只允许 `.lua`，支持一次多选。
- 设置按钮仅在脚本开启D启且声明 `settings` 时显示，并带进入/退出动画。
- 游戏内"脚本管理"只在己方、可下令且严格匹配 `units` 的单位上出现。
- 首次进入游戏会在应用上下文可用后立即加载私有脚本并刷新"脚本管理"入口，无需先打开模块页保存。
- 多选只批量处理与点击单位 `type_id` 完全相同的己方单位。
- APK 根目录保留空 `Config/Scripts/.keep`；没有内置 Lua。若打包者在构建前向项目 `Config/Scripts` 放入 Lua，它们在模块新安装版本首次打开时复制到私有目录，用户仍可删除。
- 热重载采用新 VM 验证成功后替换；失败保留旧 VM。脚本运行时不反复读取导入源文件。

## 15. 编写检查表

1. `units` 使用 `type_id`，严格区分大小写；不要把中文名写进去。
2. `data` 只列实际读取分组；距离/编队必须有 `position`，攻击射程必须有 `combat`。
3. `on_tick` 优先 6～12 Tick；不要每 Tick排序全局单位或投射物。
4. 状态以 `unit.id` 或 `group_id` 为键，不保存 Java 对象。
5. 每次命令检查 `accepted`，不要把"提交成功"误当作"动作完成"。
6. 用 `building`/ `rw.is_building` 判断建筑，不用速度为 0 推断。
7. 完成任务时调用 `finish/exit`，让框架自动降为零 Tick Hook 开销。

---

## English

This document is the complete development specification for RWmiao unit automation scripts. It is not a Lua tutorial. Scripts are responsible for decision-making, state machines, and tactics; the framework is only responsible for providing read-only snapshots on demand, persisting user settings, restricting controllable units, and translating requests into raw game commands.

## 1. Runtime & Performance Model

- Uses a Lua 5.2-compatible subset (LuaJ, pure Java). No `luajava/io/os/pacage/debug/require/load/loadfile/dofile`.
- A script only controls the player's own units that are bound to it in the "Unit Scripts" panel. The script's `units` declaration only determines whether the button is applicable; it does not automatically control all units of the same type across the map.
- When you multi-select units of the same `type_id` in-game and apply a script, those units form a single `group_id`, which can be used for coordinated logic such as formations, encirclement, and fan-spread.
- When the master switch is off, no scripts are loaded, or no script is enabled with bound units, the script scheduling Tick Hook is completely unloaded. It is also unloaded after the last unit dies, or after `finish` or `exit`.
- The global unit table is scanned only when a callback fires; only the groups declared in `data` are reflected to collect heavyweight data. Do not write `data="all"` unless you truly need all data.
- Per callback: at most 250,000 Lua instructions, 32 native commands, and 256 additional units in aggregate; after 3 consecutive exceptions or limit violations, the script is disabled for the rest of the match.
- All decisions use simulated ticks, not wall-clock time. Recommended normal logic interval: 6–12 ticks; projectile dodging may use 2–4 ticks.

## 2. Minimal Script

```lua
rw.script{
  api=1,
  id="tank_guard",
  name="Tank Auto-Targeting",
  units={"tank"},                 -- Required, case-sensitive exact match; can also be an array or "All"
  data={"position","combat"}   -- Only declare what you actually read
}

rw.on_tick(8,function(ctx)
  for _,u in ipairs(ctx:self_units()) do
    local enemy=ctx:nearest_enemy(u,{range=700})
    if enemy then ctx:attack(u,enemy) end
  end
end)
```

Both `rw.script{...}` and at least one `rw.on_tick(...)` must execute at file top-level. The importer only accepts files with the `.lua` extension and supports multi-select in the system file picker (up to32); `.md`, `.txt`, and other files are rejected even if their content is valid Lua. Files are compiled and validated upon import; the imported source is copied to the module's private directory, and moving, modifying, or deleting the original file will not affect the imported copy.

### 2.1 Metadata Filds

| Fild        | Required | Description                                                                               |
| :--------- | :------- | :---------------------------------------------------------------------------------------- |
| `api`      | No       | Currently must be `1`.                                                                    |
| `id`       | Yes      | 1–64 character stable identifier; only letters, digits, `.`, `_`, `-` a lewd.        |
| `name`     | No       | UI display name; falls back to `id` if omitted.                                          |
| `units`    | Yes      | A single strict ID, an array of IDs, or the exact string `"All"`. `*` is alowed but `All` is recommended. Does not match Chinese names. |
| `data`     | Recommended | Array of data groups. Omitting it defaults to collecting `all` for backwards compatiblity — worst performance. |
| `settings` | No       | Array of setting entries; when declared, an animated settings button appears on the right side of the list when the script is active. |

## 3. Script Settings

The settings panel provides three component types: slider, input field, and switch. Settings are saved in the module's private directory and exposed via `ctx.setings` in the next callback.

```lua
rw.script{
  api=1,id="configurable_kite",name="Configurable Kite",units={"tank"},
  data={"position","combat"},
  settings={
    {key="range",name="Search Radius",type="slider",default=750,min=100,max=2000,step=50,
      description="Only search for enemies within this distance"},
    {key="enabled_retreat",name="Allow Retreat",type="switch",default=true},
    {key="note",name="End Message",type="input",value_type="text",default="Mission complete"},
    {key="limit",name="Max Targets",type="input",value_type="number",default=3,min=1,max=20}
  }
}

rw.on_tick(6,function(ctx)
  local range=ctx.settings.range               -- number
  local retreat=ctx.settings.enabled_retreat   -- boolean
  local note=ctx.settings.note                 -- string
  local limit=ctx.settings.limit               -- number
end)
```

| `type`   | Lua value        | Required fields                    | Description                                                         |
| :------- | :--------------- | :--------------------------------- | :------------------------------------------------------------------ |
| `slider` | number           | `min,max`                          | Continuous slider; `step` is optional. `min/max` clamping is still enforced on save. |
| `input`  | string or number | `value_type="text"` or `"number"`  | Single-line input field; text max 256 characters.                   |
| `switch` | boolean          | None                               | Toggle switch.                                                      |

Legacy script declarations using `boolean/number/text/choice` are still importable for backwards compatibility; new scripts should use the three component types above. A maximum of 32 setting entries is allowed. `key` must begin with a letter and be at most 48 characters long.

## 4. Data Groups

`identity` is the low-cost foundational information required for identification and safety. Other groups must be declared according to purpose:

| data group    | Content & Purpose                                                                                          |
| :------------ | :---------------------------------------------------------------------------------------------------------- |
| `identity`    | ID, unit type, building check, orderability, dead/deleed.                                                |
| `team`        | Team ID/name, friend-or-foe relationship.                                                                  |
| `position`    | World coordinates, height, heading, collision radius; required for distance/range/formation functions.    |
| `health`      | Health, shield, and their ratios.                                                                         |
| `movement`    | Movement type, true speed, velocity components, acceleration/deceleration/turn speed, whether moving.      |
| `combat`      | Primary range, weapon count, current attack target.                                                        |
| `weapons`     | Per-weapon-slot range, turret heading, target, reload/warmup/coldown/ready.                              |
| `orders`      | Current order, target/coordinates, waypoint count, whether idle.                                           |
| `pathing`     | `path_state/path_pending`; also collects necessary movement/order summaries.                              |
| `transport`   | Carrier relationship, capacity, and loaded unit IDs.                                                       |
| `build`       | Build progress, factory, queue size, buildable types.                                                      |
| `production`  | Production queue items and progress.                                                                 |
| `actions`     | Raw Action ID summaries.                                                                             |
| `abilities`   | Ability name, description, cost, current executable/locked state.                                    |
| `damage`      | Tick when the last health+shield drop was detected.                                                   |
| `selection`   | Current selection state. Usually not needed since the binding relationship is already clear.          |
| `map`         | Map width/height, static terrain tiles, native dynamic pathfinding tiles, passablity, and local fog of war. |
| `resources`   | Local team funds, income, multiplier, unit count/cap.                                                  |
| `projectiles` | Current projectile positions, sources, and targets. High cost — only use in dodging scripts.           |
| `environment` | Only retains `danger_zones` formed by native danger border/zone markers. Pause and simulation speed etc. removed — no decision-making value. |
| `catalog`     | Builds the `rw.unit_types` static attribute catalog; additionally collects unit type range, movement, weapon, and Action summaries. Only declare when actually iterating the catalog. |
| `all`         | All groups — for debugging only.                                                                          |

Fields not declared or not reliably parseable in the current version are `nil`; scripts must not treat `nil` as 0. The framework may internally store 0 for uncollected values, but API consumers must access data according to the `data` contract.

## 5. ctx Global Snapshot

| Name                           | Type    | Description                                                    |
| :----------------------------- | :------ | :------------------------------------------------------------- |
| `ctx.tick`                     | integer | Current simulation tick.                                       |
| `ctx.multiplayer`              | boolean | Whether in a multiplayer network match.                        |
| `ctx.local_team`               | integer | Local player team ID.                                          |
| `ctx.settings`                 | table   | Validated settings for this script.                            |
| `ctx.map`                      | table   | `map` group data.                                              |
| `ctx.local_player`             | table   | `resources` group data.                                        |
| `ctx.projectiles`              | array   | `projectiles` group data.                                     |
| `ctx.environment.danger_zones` | array   | Danger zones, each item containing `unit_id,type_id,x,y,radius,team_id`. |

### Map Filds

`ctx.map`: `width_tiles,height_tiles,tile_width,tile_height,width,height,visibility_grid_available`.

```lua
local tile=ctx:tile_at({x=300,y=500})
-- tile_x,tile_y,in_bounds,water,water_bridge,lava,cliff,resource_pool,
-- large_cliff_or_trees,blocks_buildings,land_blocked,
-- value,visible,fogged,explored,unexplored,available
local ok=ctx:is_passable({x=300,y=500},"LAND") -- LAND/AIR/WATER/HOVER
```

#### Fog of War

Fog of war comes from the local team's current native `Q` grid and does not guess based on the presence of enemy units. Value semantics: `0–4` currently visible, `5–9` explored but currently fogged, `10` unexplored. When the `map` group is absent, the grid is unavailable, or coordinates are out of bounds, boolean shortcut functions return `nil` rather than falsely reporting `false`.

```lua
local fog=ctx:fog_at({x=300,y=500})
-- fog.tile_x/tile_y/in_bounds/available/value
-- fog.visible/fogged/explored/unexplored

if ctx:is_fogged({x=300,y=500}) == true then ... end
if ctx:is_visible({x=300,y=500}) == true then ... end
```

Unit positions also expose `visible_to_local`, `fogged_to_local`, `explored_to_local`. `ctx:enemies()` still reads the global registry and is not filtered by fog of war.

#### Native Pathfinding Tiles & Dead-End Judgment

`ctx:tile_at` represents the map's static terrain. To determine whether a unit can actually pass, read the native pathfinding layer, which accounts for terrain, buildings, and dynamic blocking objects:

```lua
local cell=ctx:path_tile_at({x=300,y=500},"LAND")
-- movement_type,available,in_bounds,gid_x,gid_y,width,height,world_to_grid
-- terrain_cost,building_cost,object_cost
-- terrain_blocked,building_blocked,object_blocked,passable

local passable=ctx:is_path_passable({x=300,y=500},"LAND")
-- Returns nil when the movement layer cannot be resolved; returns false when out of bounds
```

Pathfinding grids use `grid_x/grid_y`. Conversion between world coordinates and grid coordinates:

```lua
local function grid_center(cell,gx,gy)
  return {x=(gx+0.5)/cell.world_to_grid,y=(gy+0.5)/cell.world_to_grid}
end
```

From a candidate tile, Lua can call `is_path_passable` on four-directional or eight-directional neighbors and perform its own bounded BFS/DFS: exhaustion with only the entry side remaining indicates a dead-end branch on that movement layer; reaching a pre-defined exit, open area, or the search limit means it is not a confirmed dead end. "Dead end" depends on the unit's `movement_type`, entry direction, unit radius, and script definition — the framework only provides true grid data and does not make fixed tactical judgments on behalf of the script. Recommended to inspect at most 128–512 tiles per call and cache tiles already queried within the same callback.

### Team Resources Filds

`ctx.local_player`: `id,name,funds,income_rate,income_multiplier,unit_count,unit_cap`. These are read-only values and cannot be modified directly.

### Projectile Filds

Each item: `id,x,y,height,source_id,target_id`. The projectile table may be large; you must filter by coordinate radius first and avoid full sorting every tick.

## 6. Unit Queries

All queries support both `ctx:fn(...)` and `ctx.fn(...)`; this document consistently uses the colon syntax.

```lua
ctx:units()             -- Live units in the global registry
ctx:self_units()        -- Own units actually bound to this script, not all units of the same type
ctx:enemies()           -- Enemy units, not filtered by fog of war
ctx:allies()            -- Allied / neutral units
ctx:selected()          -- Currently selected units (requires selection)
ctx:get(id_or_unit)     -- Look up by global object ID
ctx:nearest_enemy(point_or_unit,filter)
ctx:within(point,radius,filter)
```

Filter supports:

```lua
{types={"mammothTank","experimentalTank"},range=900,
 relation="enemy",orderable=true,selected=false,building=false}
```

Unit type matching is case-sensitive; `MammothTank` is not equal to `mammothTank`.

## 7. Unit Fild Dictionary

### Identity, Team, Spatial, Survival

| Fild                            | Type          | Group     | Explanation                                                          |
| :------------------------------ | :------------ | :-------- | :------------------------------------------------------------------ |
| `id`                            | integer       | identity  | Global object ID; stable key for command targets and script state tables. |
| `type_id`                       | string        | identity  | Native/mod unit type ID, strictly case-sensitive.                     |
| `type_name`                     | string        | identity  | Display name in the current language.                                |
| `building`                      | boolean       | identity  | Native `is building` determination; also `rw.is_building(unit)`.   |
| `orderable`                     | boolean       | identity  | Whether the unit belongs to a class that can receive player commands. |
| `dead/deleted`                  | boolean       | identity  | Dead / removed from the object system. Queries normally filter these; use after `get` for verification. |
| `team_id/team_name`             | number/string | team      | Belonging team.                                                      |
| `relation`                      | integer       | team      | `0` self, `1` enemy, `2` ally or neutral.                     |
| `relation_name`                 | string        | team      | `self/enemy/ally`, retained for legacy script compatiblity.   |
| `x,y,height`                    | number        | position  | World coordinates and height.                                  |
| `position`                      | table         | position  | `{x,y,height}`.                                               |
| `heading`                       | number        | position  | Unit's true facing direction (degrees).                        |
| `radius`                        | number        | position  | Collision / footprint radius.                                  |
| `health/max_health`             | number        | health    | Current / maximum health.                                      |
| `shield/max_shield`             | number        | health    | Current / maximum shield.                                      |
| `health_ratio/shield_ratio`     | number        | health    | Individual ratio. Shield ratio is 0 when max shield is absent. |
| `combined_health_ratio`         | number        | health    | `(health+shield)/(max_health+max_shield)`.                    |
| `health_missing/shield_missing` | number        | health    | Missing values, compatibility fields.                           |
| `selected`                      | boolean       | selection | Whether currently selected by the local player.                 |
| `group_id`                      | string/nil    | runtime  | The formation ID created by this batch binding.              |

### Movement, Combat, Orders

| Fild                                   | Group    | Explanation                                                              |
| :------------------------------------- | :------- | :---------------------------------------------------------------------- |
| `movement_type`                        | movement | Native movement type.                                                    |
| `max_move_speed`                       | movement | Native maximum movement speed under current dynamic state.                |
| `real_speed`                           | movement | Actual speed summary derived from velocity components and native movement factors. |
| `velocity_x/velocity_y`                | movement | Current velocity components.                                             |
| `acceleration/deceleration/turn_speed` | movement | Native acceleration, deceleration, turn speed.                         |
| `moving`                               | movement | Whether currently experiencing actual motion.                             |
| `path_state`                           | pathing  | `idle/following/comanded`.                                             |
| `path_pending`                         | pathing  | Whether there are waypoints or a current order.                          |
| `atack_range`                         | combat   | Primary attack distance `l()`; for different weapons use `weapons[i].range`. |
| `weapon_count`                         | combat   | Number of weapon slots.                                                  |
| `target_id`                            | combat   | Current attack/arget-acquisition target ID.                             |
| `current_order`                        | orders   | Current native order enum text.                                           |
| `order_target_id/order_x/order_y`      | orders   | Current order target or coordinates.                                     |
| `waypoint_count`                       | orders   | Number of waypoints/orders.                                      |
| `idle`                                 | orders   | No current order.                                                        |

`unit.weapons[i]` fields: `index,range,turret_heading,target_id,relod_remaining,warup,coldown,ready`. Slot fields absent for a given unit type are `nil`; do not guess about a unit.

### Build, Production, Transport, Abilities

| Fild                  | Group      | Explanation                                                                                    |
| :-------------------- | :--------- | :---------------------------------------------------------------------------------------------- |
| `build_progress`      | build      | Build completion, typically 0–1.                                                               |
| `factory`             | build      | Whether the unit is a native factory class. For buildings see `building`.       |
| `queue_size`          | build      | Production queue length.                                                         |
| `buildable_types`     | build      | Buildable building/unit IDs parsed from native Actions.                         |
| `production_items`    | production | Queue item array: `cunt,progress,rae,acion_id,ype_id,target_id`.             |
| `carrier_id/attached` | transport  | Transport or attachment parent unit ID / whether attached.                      |
| `transport_capacity`  | transport  | Resolvable transport slot count.                                   |
| `loaded_unit_ids`     | transport  | Array of loaded unit IDs.                                             |
| `actions`             | actions    | Array of native Action ID strings.                                      |
| `abilities`           | abilities  | Each item: `id,name,descripton,cost,executable,loked`.        |
| `last_damaged_tick`   | damage     | Tick when the last durability drop was detected; cannot reliably provide the damage source, so speculative fields are not exposed. |
| `visible_to_local`    | map        | Whether the unit's position is visible to the local team. Enemies still appear in global queries. |
| `fogged_to_local`     | map        | Whether the unit's tile is currently under fog of war.                             |
| `explored_to_local`   | map        | Whether the unit's tile has been explored; `false` for unexplored tiles.              |

##8. Native Commands

All commands only accept units that are bound to this script, still alive, own-team, and `orderable`. Enemies/allies can only be read or serve as valid targets. `apend` defaults to false. Returns:

```lua
{accepted=true,comand_id=123}
-- or {accepted=false,reason="..."}
```

| Call                                                      | Description                                 |
| :------------------------------------------------------ | :------------------------------------------ |
| `ctx:move(unit_or_lit,x,y,append)`                     | Move.                                      |
| `ctx:atack_move(unit_or_lit,x,y,append)`              | Attack-move.                              |
| `ctx:attack(unit_or_lit,target,append)`                | Attack specified unit.                      |
| `ctx:patrol(unit_or_lit,x,y,append)`                   | Patrol.                                    |
| `ctx:guard(unit_or_lit,target,append)`                 | Protect / follow.                          |
| `ctx:repair(unit_or_lit,target,append)`                | Repair.                                    |
| `ctx:reclaim(unit_or_lit,target,append)`               | Reclai.                                   |
| `ctx:enter(unit_or_lit,transport,append)`              | Make unit enter a transport.                |
| `ctx:load(transport_or_lit,target,append)`             | Make transport load a target.               |
| `ctx:build(builder_or_lit,type_id,x,y,ariant,append)` | Build the specified type; variant defaults to 1. |
| `ctx:action(unit_or_lit,acion_id[,x,y,append])`       | Execute a native ability/production Action. |
| `ctx:stop(unit_or_lit)`                                | Stop.                                      |

Only `accepted=true` should advance a Lua state machine; this indicates the native command object was successfully created — it does not mean the unit ultimately arrived or the action completed.

## 9. Lifecycle & Local Messages

```lua
ctx:finish(unit_or_lit) -- Unbind only the specified units from the current script
ctx:finish()             -- Unbind all units from the current script
ctx:exit("Encirclement mission complete") -- Unbind all and display a message in your own chat box
```

`exit` messages are at most 240 characters; line breaks are replaced. It uses the game's local chat delivery method — no network chat packet is generated and other players cannot see it. Call `finish/exit` as soon as a mission is complete, the target is permanently gone, or further polling is meaningless, so the framework can unload the Tick Hook once the last binding disappears.

When a unit dies, is deleted, changes teams, or its object ID disappears, the framework automatically removes the binding. A match tick rollback clears all bindings, formations, damage history, and Lua tick state.

## 10. Formation Group API

A single "multi-select same type → Script Management → Apply" produces one group. Groups from different scripts do not interfere with each other.

```lua
ctx:groups()          -- [{id,units,cunt,center}, ...]
ctx:group_of(unit)    -- Returns group_id or nil
rw.centroid(units)    -- Returns {x,y}; returns nil for an empty array
```

Encirclement example:

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
      ctx:exit("No targets within range")
      return
    end
  end
end)
```

##11. Utility Functions & Public Catalogs

| Name                                                       | Description                                    |
| :------------------------------------------------------- | :--------------------------------------------- |
| `rw.distance(a,b)`                                       | 2D distance.                                  |
| `rw.away(from,target,distance)`                          | Move a specified distance away from the target, returns a point. |
| `rw.around(cener,radius,angle_degres)`                 | Point on a circle.                            |
| `rw.centroid(units)`                                     | Centroid of a unit array.                     |
| `rw.is_building(unit)`                                   | Native building check.                         |
| `rw.log(...)`                                            | Write to the `RWmiaoLua` log.                 |
| `rw.catalog()`                                           | Print data groups, fields, commands, and lifecycle interfaces. |
| `rw.data_groups/rw.unit_felds/rw.comands/rw.lifecycle` | Iterable global catalog tables.                |
| `rw.field_groups[filed]`                                 | The data group a field belongs to.             |
| `rw.command_signatures[name]`                            | Command signature.                             |
| `rw.unit_types[type_id]`                                 | Fixed attribute summary for unit types that have appeared in the current snapshot. |

Map query functions (all require `data={"map"}`):

| Name                                             | Description                                                                 |
| :----------------------------------------------- | :-------------------------------------------------------------------------- |
| `ctx:tile_at(point)`                             | Read the static terrain tile and fog-of-war fields at the given world coordinate. |
| `ctx:fog_at(point)`                              | Read only fog-of-war tiles; suitable for high-frequency visibility checks.  |
| `ctx:is_fogged(point)` / `ctx:is_visible(point)` | Convenient fog zone / visibility check; returns `nil` when unavailable.     |
| `ctx:path_tile_at(point,movement_type)`          | Read the native dynamic pathfinding tile for the specified movement type.    |
| `ctx:is_path_passable(point,movement_type)`      | True pathfinding layer passability check; returns `nil` when the layer cannot be resolved. |
| `ctx:is_passable(point,movement_type)`           | Prefers the native pathfinding layer; falls back to static terrain rules when the layer cannot be resolved. |

After declaring `data={"catalog",...}`, `rw.unit_types[type_id]` may contain: `id,name,custom,movement_type,atack_range,max_move_seed,radius,max_ealth,max_shield,weapon_count,acceleration,deceleration,turn_speed,weapons,abilities,transport_capacity,actions,buildable_types`. It is derived from the current snapshot, not a hardcoded data table from install time; mod unit types that have not appeared will not exist out of thin air.

## 12. Sandbox Editor All Unit ID Table

The script unit type ID table uses the same runtime rules as the unit list in room options:

1. Read the `game.units.cj.ae` complete type registry; this registry contains both vanilla types and already-loaded built-in custom types.
2. Exclude editor internal types `cj.I/v/q/R/H/W/X/Y/Z/N`.
3. Create a unit via `ce.d(type)`, retaining only `game.units.bp` orderable units.
4. Exclude `test_tank` and `mising`.
5. For custom types, only retain those with `custo.l.aF == true`, i.e. types with `[core]shoInEditor` enabled.
6. Sort using the native `game.units.ab` comparator; the UI deduplication key is case-insensitive, but the original ID spelling of the first occurrence is preserved.

### 12.1 Vanila Types (40)

| ID                        | Simplified Chinese Name |
| :------------------------ | :---------------------- |
| `extractor`               | Resource Extractor                     |
| `landFactory`             | Land Factory                          |
| `airFactory`              | Air Base                             |
| `seaFactory`              | Naval Base                           |
| `comandCenter`           | Command Center                       |
| `turret`                  | Turret                               |
| `antiAirTurret`           | Anti-Air Turret                      |
| `builder`                 | Builder                             |
| `tank`                    | Tank                                |
| `hoverTank`               | Hover Tank                          |
| `artillery`               | Artillery                           |
| `helicopter`              | Helicopter                          |
| `airShip`                 | Interceptor                         |
| `gunShip`                 | Gunship                             |
| `missileShip`             | Missile Ship                        |
| `gunBoat`                 | Gunboat                             |
| `laserTank`               | Laser Tank                          |
| `hovercraft`              | Landing Craft                        |
| `ladybug`                 | Ladybug                             |
| `battleShip`              | Battleship                          |
| `heavyTank`               | Heavy Tank                          |
| `heavyHoverTank`          | Heavy Hover Tank                    |
| `laserDefence`            | Laser Defense Tower                 |
| `dropship`                | Dropship                            |
| `repairbay`               | Repair Bay                          |
| `NukeLaucher`             | Nuke Launcher                       |
| `AntiNukeLaucher`         | Anti-Nuke Defense                   |
| `mamothTank`             | Mamoth Tank                        |
| `experimentalTank`        | Experimental Tank                   |
| `experimentalLandFactory` | Experimental Factory                |
| `fabricator`              | Fabricator                          |
| `atackSubmarine`         | Submarine                          |
| `builderShip`             | Naval Builder                       |
| `amphibiousJet`           | Amphibious Jet                      |
| `experimentalHoverTank`   | Conceptual Hover Tank               |
| `turret_artillery`        | Artillery Upgraded Turret           |
| `turret_flamethrower`     | Flamethrower Turret                 |
| `antiAirTurretT2`         | T2 Anti-Air Turret                  |
| `turretT2`                | T2 Machinegun Turret                |
| `turretT3`                | T3 Heavy Machinegun Turret           |

### 12.2 Built-in Custom Types in the Current Game Package (103)

| ID                               | Simplified Chinese Name / Config Display Name |
| :------------------------------- | :------------------------------------------- |
| `aaBeamGunship`                  | AA Laser Beam Gunship                         |
| `bomber`                         | Bomber                                       |
| `bugMeleeT31`                    | Killer alpha                                 |
| `combatEngineer`                 | Combat Engineer                              |
| `experiementalCarrier`           | Aircraft Carrier                             |
| `experimentalDropship`           | Flying Fortress                              |
| `experimentalGunship`            | Experimental Hovercraft                     |
| `experimentalSpider`             | Experimental Combat Spider                   |
| `c_experimentalTank`             | Experimental Tank                            |
| `extractorT1`                    | extractorT1                                 |
| `extractorT2`                    | Resource Extractor T2                        |
| `extractorT3`                    | Resource Extractor T3                        |
| `extractorT3_overclocked`        | extractorT3\_overclocked                    |
| `extractorT3_reinforced`         | extractorT3\_reinforced                     |
| `fabricatorT1`                   | fabricatorT1                                 |
| `fabricatorT2`                   | fabricatorT2                                 |
| `fabricatorT3`                   | fabricatorT3                                 |
| `fireBee`                        | Fire Bee Fighter                             |
| `heavyAAShip`                    | Heavy AA Ship                                |
| `heavyBattleship`                | Heavy Battleship                             |
| `heavyIntercepor`               | Heavy Interceptor                            |
| `heavyMissileShip`               | Heavy Missile Ship                           |
| `heavySub`                       | Heavy Submarine                             |
| `c_helicopter`                   | c\_helicopter                                |
| `c_interceptor`                  | c\_interceptor                               |
| `laboratory`                     | Laboratory                                   |
| `c_laserTank`                    | Laser Tank                                   |
| `lightGunship`                   | Light Gunship                                |
| `lightSub`                       | Underwater Detector                          |
| `c_mamothTank`                  | c\_mamothTank                               |
| `mechArtillery`                  | Artillery Mech                              |
| `mechBunker`                     | Mobile Turret                                |
| `mechFlame`                      | Flame Mech                                  |
| `mechFlyingLanded`               | mechFlyingLanded                             |
| `mechHeavyMissile`               | Heavy AA Mechanical Armor                   |
| `mechLaser`                      | Plasma Mech                                  |
| `mechLightning`                  | Tesla Mech                                  |
| `mechMinigun`                    | Minigun Mech                               |
| `mechGun`                        | Basic Mech                                  |
| `mechMissile`                    | AA Mech                                     |
| `mechEngineer`                   | Mechanic                                    |
| `mechFactory`                    | Mech Factory                                |
| `missileAirship`                 | Missile Airship                             |
| `missileTank`                    | AA Missile Tank                             |
| `modularSpider_antiair`          | SAM AA Turret T1                             |
| `modularSpider_antiairFlak`      | Flak Turret T2                              |
| `modularSpider_antiairT2`        | SAM AA Turret T2                             |
| `modularSpider_antinuke`         | Anti-Nuke Module                            |
| `modularSpider_artillery`        | Artillery Module T1                          |
| `modularSpider_blink`            | Blink Module                               |
| `modularSpider_fabricator`       | Fabricator Module T1                        |
| `modularSpider_fabricatorT2`     | Fabricator Module T2                        |
| `modularSpider_gunturret`        | Machinegun T1                               |
| `modularSpider_gunturretT2`      | Machinegun T2                               |
| `modularSpider_laserdefense`     | Laser Defense Module                        |
| `modularSpider_lightning`        | Lightning Turret T1                         |
| `modularSpider`                  | Modular Spider                              |
| `modularSpider_shieldGen`        | Shield Core                                |
| `modularSpider_smallgunturret`   | Plasma Module T1                            |
| `modularSpider_smallgunturretT2` | Plasma Module T2                            |
| `modularSpider_speed`            | Speed Module                               |
| `modularSpider_speedIncomplete`  | modularSpider\_speedIncomplete               |
| `nautilusSubmarine`              | Nautilus                                  |
| `antiNukeLauncherC`              | antiNukeLauncherC                            |
| `nukeLauncherC`                  | nukeLauncherC                                |
| `outpostT1`                      | Outpost                                     |
| `outpostT2`                      | Outpost T2                                  |
| `plasmaTank`                     | Plasma Tank                                 |
| `creditsCrates`                  | Credit Crate                               |
| `crystal_mid`                    | crystal\_mid                                |
| `scout`                          | Scout                                      |
| `spyDrone`                       | Spy Drone                                  |
| `c_artillery`                    | c\_artillery                                |
| `heavyArtillery`                 | Heavy Artillery                             |
| `c_tank`                         | c\_tank                                     |
| `c_antiAirTurret`                | c\_antiAirTurret                            |
| `antiAirTurretFlak`              | T2 - Flak                                  |
| `c_antiAirTurretT2`              | c\_antiAirTurretT2                          |
| `c_antiAirTurretT3`              | c\_antiAirTurretT3                          |
| `c_turret_t1`                    | c\_turret\_t1                               |
| `c_turret_t1_artillery`          | c\_turret\_t1\_artillery                    |
| `c_turret_t1_lightning`          | c\_turret\_t1\_lightning                    |
| `c_turret_t2_artillery`          | c\_turret\_t2\_artillery                    |
| `c_turret_t2_flame`              | c\_turret\_t2\_flame                        |
| `c_turret_t2_gun`                | c\_turret\_t2\_gun                          |
| `c_turret_t2_lightning`          | c\_turret\_t2\_lightning                    |
| `c_turret_t3_gun`                | c\_turret\_t3\_gun                          |
| `bugPickup`                      | bugPickup                                    |
| `bugWasp`                        | bugWasp                                     |
| `bugRangedT2`                    | bugRangedT2                                 |
| `bugBee`                         | bugBee                                      |
| `bugExtractor`                   | bugExtractor                                |
| `bugFly`                         | bugFly                                      |
| `bugGenerator`                   | bugGenerator                                |
| `bugMelee`                       | bugMelee                                    |
| `bugMeleeLarge`                  | bugMeleeLarge                               |
| `bugMeleeSmall`                  | bugMeleeSmall                               |
| `bugNest`                        | bugNest                                     |
| `bugRanged`                      | bugRanged                                   |
| `bugSpore`                       | bugSpore                                    |
| `bugTurret`                      | bugTurret                                   |
| `bugGeneratorN`                  | Generator                                   |
| `bugGeneratorNT2`                | Generator                                   |

`NukeLaucher`, `AntiNukeLaucher`, and `experiementalCarrier` are the actual spellings found in the resources. ID comparisons are strictly case-sensitive. After external mods are loaded, they continue registering types into `cj.ae`; scripts can retrieve newly added IDs for the current match by declaring `data={"catalog"}` and iterating `rw.unit_types`.

## 13. Complete Configurable Kiting Example

```lua
rw.script{
  api=1,id="mech_minigun_kite_mamoth_s1",name="Minigun Mech Kites Mamoth Tank",
  units={"mechMinigun"},data={"position","health","movement","combat","orders"},
  settings={
    {key="search_range",name="Search Range",type="slider",default=900,min=200,max=1800,step=50},
    {key="keep_ratio",name="Keep Range Ratio",type="slider",default=0.82,min=0.45,max=1.1,step=0.02},
    {key="stop_without_target",name="End When No Target",type="switch",default=false}
  }
}

local target_types={mamothTank=true,c_mamothTank=true}
local last_order={}

rw.on_tick(6,function(ctx)
  local controlled=ctx:self_units()
  if #controlled==0 then return end
  local any=false
  for _,u in ipairs(controlled) do
    local enemy=ctx:nearest_enemy(u,{types={"mamothTank","c_mamothTank"},range=ctx.settings.search_range})
    if enemy then
      any=true
      local distance=rw.distance(u,enemy)
      local keep=math.max(90,u.atack_range*ctx.settings.keep_ratio)
      local result
      if distance<keep then
        local p=rw.away(u,enemy,math.max(55,u.atack_range*0.35))
        result=ctx:move(u,p.x,p.y)
      elseif distance<=u.atack_range then
        result=ctx:attack(u,enemy)
      else
        result=ctx:attack(u,enemy)
      end
      if result.accepted then last_order[u.id]=ctx.tick end
    end
  end
  if not any and ctx.settings.stop_without_target then ctx:exit("No Mamoth Tanks in range — script exited") end
end)
```

Note: `mechMinigun` is not in the official vanilla All table; it is a mod / modified-package type ID used in this example. If the actual APK's `type_id` differs, it must be strictly adjusted according to the output of `rw.unit_types`.

## 14. UI & File Behavior

- Module "Scripts" page: master switch, import, individual script toggle, left-side delete, right-side settings. Import uses the Android system file picker, only allows `.lua`, supports multi-select.
- The settings button appears only when the script is active and declares `settings`, with enter/exit animation.
- In-game "Script Management" only appears on own, orderable units that strictly match `units`.
- On first entering a game, private scripts are loaded and the "Script Management" entry is refreshed as soon as the application context becomes available — no need to open the module page and save first.
- Multi-select only batch-processes own units with a `type_id` identical to the clicked unit.
- The APK root directory retains an empty `Config/Scripts/.keep`; there is no built-in Lua. If a packager places Lua files into the project's `Config/Scripts` before building, they are copied to the private directory when the module is first opened on a fresh install version, and users can still delete them.
- Hot reload validates a new VM and replaces on success; on failure the old VM is preserved. Import source files are not re-read while a script is running.

## 15. Authoring Checklist

1. Use `type_id` for `units`, strictly case-sensitive; do not write Chinese names there.
2. `data` should only list groups actually read; `position` is required for distance/formation, `combat` for attack range.
3. Prefer 6–12 ticks for `on_tick`; do not sort global units or projectiles every tick.
4. Use `unit.id` or `group_id` as keys for state; do not persist Java objects.
5. Check `accepted` after every command; do not mistake "submitted successfully" for "action completed".
6. Use `building` / `rw.is_building` to identify buildings; do not infer from speed being 0.
7. Call `finish/exit` when a mission completes, so the framework can automatically reduce to zero Tick Hook overhead.
