# RWmiao Lua 脚本规范

## 1. 文件与执行环境

- 脚本文件扩展名必须为 `.lua`。
- 脚本必须使用 UTF-8 编码。
- 脚本必须调用一次 `rw.script{...}`。
- 脚本必须注册至少一个 `rw.on_tick(interval, callback)`。
- 脚本不得访问 `io`、`os`、`package`、`debug`、`require`、`dofile`、`loadfile`、`load` 或 `luajava`。
- 单次回调最多执行 250000 条 Lua 指令。
- 单个脚本最多注册 32 个 Tick 回调。
- `interval` 的有效范围为 1 至 600 Tick。

## 2. 脚本声明

```lua
rw.script{
  api=1,
  id="example",
  name="示例",
  units={"unitTypeA", "unitTypeB"},
  data={"identity", "team", "position", "orders"},
  settings={}
}
```

字段规则：

| 字段 | 规则 |
|---|---|
| `api` | 必须为受支持的 API 版本；当前值为 `1` |
| `id` | 必须在所有脚本中唯一 |
| `name` | 应提供可读名称 |
| `units` | 必须列出脚本可控制的单位类型；可写字符串或数组 |
| `data` | 必须仅声明脚本实际读取的数据组 |
| `settings` | 可选；必须为设置项数组 |

脚本只能控制满足下列全部条件的单位：

- 单位属于本地玩家。
- 单位类型包含在 `units` 中。
- 单位已启用该脚本。
- 单位未被 `ctx:finish()` 解除。

## 3. 按需数据采集

运行时必须只采集当前到期脚本声明的数据组。未到期或处于暂停状态的脚本不得触发快照采集。查询型便利函数必须在被 Lua 调用时才计算结果。

`data` 支持：

`identity`、`team`、`position`、`health`、`movement`、`combat`、`weapons`、`orders`、`pathing`、`transport`、`construction`、`build`、`production`、`actions`、`abilities`、`damage`、`selection`、`map`、`resources`、`projectiles`、`environment`、`catalog`、`all`。

使用规则：

- 普通移动脚本不得声明 `projectiles`。
- 仅需要规避弹道的火炮类脚本应声明 `projectiles`。
- 不得为便利而声明 `all`。
- `find_path`、`reachable_area` 和 `find_escape` 必须按需调用，不得在每个单位的每个 Tick 无条件调用。

## 4. 设置项

```lua
settings={
  {key="enabled", name="启用", type="switch", default=true},
  {key="radius", name="半径", type="slider",
   default=200, min=50, max=500, step=10},
  {key="mode", name="模式", type="choice", default="safe",
   options={{value="safe", label="安全"}, {value="fast", label="快速"}}},
  {key="text", name="文本", type="input", value_type="text", default=""}
}
```

回调必须从 `ctx.settings` 读取当前设置值。

## 5. Tick 回调

```lua
rw.on_tick(3, function(ctx)
  -- callback
end)
```

`ctx` 固定字段：

| 字段 | 类型 |
|---|---|
| `tick` | number |
| `multiplayer` | boolean |
| `local_team` | number |
| `settings` | table |
| `map` | table |
| `environment` | table |
| `local_player` | table |
| `projectiles` | array |

## 6. 单位查询

```lua
ctx:units([filter])
ctx:self_units([filter])
ctx:enemies([filter])
ctx:allies([filter])
ctx:selected([filter])
ctx:get(id_or_unit)
ctx:nearest_enemy(point_or_unit[, filter])
ctx:within(point, radius[, filter])
ctx:groups()
ctx:group_of(unit_or_id)
```

过滤器支持：`types`、`relation`、`orderable`、`building`、`selected`、`range`。

返回的单位表只保证包含已声明数据组对应的字段。脚本必须允许可选字段为 `nil`。

### 6.1 基础字段

| 数据组 | 字段 |
|---|---|
| `identity` | `id`、`type_id`、`type_name`、`building`、`orderable`、`dead`、`deleted` |
| `team` | `team_id`、`team_name`、`relation`、`relation_name` |
| `position` | `x`、`y`、`height`、`position`、`heading`、`radius` |
| `health` | `health`、`max_health`、`health_ratio`、`health_missing`、`shield`、`max_shield`、`shield_ratio`、`shield_missing`、`combined_health_ratio` |
| `combat` | `attack_range`、`weapon_count`、`target_id` |
| `repair` | `is_repairing`、`repair_target_id`、`repair_automatic`、`repair_range`、`order_source`、`order_automatic`、`order_revision`、`order_issued_tick`、`order_command_id`、`order_script_id` |
| `selection` | `selected` |

`relation` 值：`0` 为己方，`1` 为盟友，`2` 为敌方。

### 6.2 移动字段

`movement` 提供：

- `movement_type`
- `max_move_speed`
- `real_speed`
- `velocity_x`、`velocity_y`
- `acceleration`、`deceleration`
- `turn_speed`
- `moving`
- `throttle`
- `speed_ratio`

`pathing` 提供：`path_state`、`path_pending`、`path_available`、`path_points`、`path_target_x/y`、`path_next_x/y`。

`path_points` 是当前单位原生指令队列中可读取的路点数组。每项至少包含 `x`、`y`、`index`；若原生路点包含指令类型或单位目标，还会包含 `order`、`target_id`。`path_next_x/y` 是队列中的第一项，`path_target_x/y` 是队列中最后一项。原生队列不可读取时，`path_available` 为 `false`；此时不得把最终目标点当作已确认的下一路点。

### 6.3 武器字段

`weapons` 为武器数组。每项可包含：

- `index`
- `range`
- `turret_heading`
- `target_id`
- `reload_remaining`
- `warmup`
- `cooldown`
- `ready`

### 6.4 指令字段

`orders` 提供：

- `current_order`
- `order_target_id`
- `order_x`、`order_y`
- `waypoint_count`
- `idle`
- `order_revision`
- `order_changed`
- `order_source`
- `order_automatic`
- `order_issued_tick`
- `order_command_id`
- `order_script_id`
- `last_player_order`
- `move_destination_x`、`move_destination_y`
- `next_waypoint_x`、`next_waypoint_y`
- `desired_heading`
- `chasing_target_id`

`orders` 还提供当前指令意图：

- `intent_type`：规范化意图，常见值为 `idle`、`move`、`chase`、`protect`、`build`、`reclaim`、`repair`、`patrol`、`stop`。
- `intent_target_id`：意图关联的单位 ID；没有单位目标时为 `nil`。
- `intent_target_role`：意图的原生角色，例如 `attack`、`guard`、`construct`、`reclaim` 或 `repair`。
- `is_chasing`、`chase_target_id`：是否正在追击单位及其目标。
- `is_protecting`、`protect_target_id`：是否正在保护或警戒单位及其目标。
- `is_building_order`、`build_target_id`：是否正在执行建造意图及其目标。
- `is_reclaiming`、`reclaim_target_id`：是否正在执行回收意图及其目标。

这些字段描述当前原生指令意图，不等同于单位已经移动或已经完成动作。判断单位是否真的在靠近目标，必须同时检查 `moving`、`velocity_x/velocity_y`、`real_speed` 和目标的相对位移。

### 6.5 维修字段

脚本读取维修状态时必须声明 `repair`。该分组提供：

- `is_repairing`：当前原生指令是否为带单位目标的维修指令。
- `repair_target_id`：当前维修目标 ID；没有目标时为 `nil`。
- `repair_automatic`：当前维修指令是否由游戏内置自动维修逻辑生成。
- `repair_range`：维修单位的原生维修搜索距离。
- `order_automatic`：当前原生指令是否带有游戏自动任务标记。

`repair` 必须只读取当前指令、自动任务标记、维修距离以及维修查询所需的坐标和生命值。不得读取武器槽、Action、生产队列、投射物或寻路层。

### 6.6 建造目标字段

脚本读取未完成建筑时必须声明 `construction`。该分组提供：

- `is_construction_site`：单位是否为建筑。
- `is_unfinished`：建筑是否仍未完成。
- `construction_progress`：当前建造完成度。
- `construction_state`：`not_building`、`unfinished` 或 `complete`。
- `construction_key`：当前对局内由单位 ID、类型、队伍和位置组成的稳定建造目标签名。

`construction` 不读取生产队列和完整 Action 列表。脚本仅需寻找未完成建筑时不得使用 `build`、`production` 或 `actions` 代替该分组。

`order_source` 值：

| 值 | 含义 |
|---|---|
| `player` | 当前变化未匹配任何脚本指令、未带游戏自动任务标记，且发生于己方可控单位 |
| `script` | 当前变化匹配框架记录的脚本指令 |
| `game` | 初始状态、游戏内置自动任务、自然状态或非己方指令状态 |

指令识别规则：

- `order_revision` 仅在观测到的指令签名变化时递增。
- `order_changed` 仅在当前快照发生指令签名变化时为 `true`。
- 脚本必须使用 `order_source` 和 `order_revision` 区分玩家新指令与自身指令。
- `last_player_order` 必须保存最近一次识别出的玩家指令。
- 框架不得保证识别无法形成单位指令状态的界面操作。

## 7. 地图与寻路

```lua
ctx:tile_at(point)
ctx:tile_rect(tile_x, tile_y, width, height[, step])
ctx:fog_at(point)
ctx:is_fogged(point)
ctx:is_visible(point)
ctx:path_tile_at(point[, movement_type[, radius]])
ctx:is_path_passable(point[, movement_type[, radius]])
ctx:is_passable(point[, movement_type[, radius]])
ctx:find_path(start, goal[, movement_type[, options]])
ctx:path_clearance(start, goal[, movement_type[, options]])
ctx:reachable_area(point[, movement_type[, options]])
ctx:is_dead_end(point[, movement_type[, options]])
ctx:find_escape(unit, threats[, options])
ctx:predict_trajectory(unit[, options])
ctx:find_safe_route(unit, threats[, options])
ctx:dynamic_route(unit, threats[, options])
ctx:threat_assessment(unit, threats[, options])
ctx:threat_retreat(unit, threats[, options])
ctx:repair_status(repairer)
ctx:repair_targets(repairer[, options])
ctx:construction_targets([options])
ctx:construction_check(builder, unfinished_building[, options])
ctx:construction_status(builder[, unfinished_building])
```

选项：

| 函数 | 选项 |
|---|---|
| `find_path` | `radius`、`max_nodes` |
| `path_clearance` | `radius`、`max_samples` |
| `reachable_area`、`is_dead_end` | `radius`、`max_nodes`、`escape_distance` |
| `find_escape` | `step`、`samples`、`safety`、`max_nodes` |
| `predict_trajectory` | `horizon`、`step` |
| `find_safe_route` | `horizon`、`forecast_step`、`step`、`samples`、`safety`、`max_nodes`、`max_threats`、`preserve_target`、`preserve_range` |
| `dynamic_route` | 与 `find_safe_route` 相同。|
| `threat_assessment` | `horizon`、`forecast_step`、`safety`、`max_threats`、`surround_radius`、`surround_trigger` |
| `threat_retreat` | `horizon`、`forecast_step`、`step`、`samples`、`safety`、`max_nodes`、`max_threats`、`preserve_target`、`preserve_range`、`surround_radius`、`surround_trigger` |
| `repair_targets` | `relation`、`max_results` |
| `construction_targets` | `relation`、`include_allies`、`max_results`、`origin`、`max_distance` |
| `construction_check` | `samples`、`max_approach`、`max_nodes`、`require_buildable_type`、`candidate_offset`、`max_path_checks` |

限制：

- `tile_rect` 最多返回 1024 个地块。
- `path_clearance.max_samples` 最大为 64。
- `reachable_area.max_nodes` 最大为 4096。
- `find_escape.samples` 的有效范围为 8 至 32。
- `find_escape` 最多对 6 个候选点执行路径验证。
- `predict_trajectory` 最多预测 600 Tick，单次最多返回 600 个采样点。
- 动态路线和威胁评估最多处理 12 个威胁；路线最多验证 6 个候选终点。
- `surround_radius` 只决定包围预警的观察范围；`surround_trigger` 决定进入包围几何判断的近距离。默认值分别为 3000 和 1200 世界距离。
- `threat_assessment` 只有在预测威胁将在预测窗口内进入安全线、已经处于安全线内，或形成近距离包围时才将 `retreat_required` 置为 `true`。远处静止单位不会单独触发撤退。
- `repair_status` 仅在维修者当前原生意图为维修且目标仍为己方或盟友受损单位时返回 `active=true`。返回字段为 `available`、`state`、`active`、`repairer_id`、`repair_range`、`target_id`、`distance`、`in_range`、`source`、`automatic`、`order_revision`、`order_issued_tick`、`order_command_id` 和 `order_script_id`；不存在的字段必须省略。
- `repair_status.source=player` 表示玩家手动维修指令；`automatic=true` 表示游戏内置自动维修任务。玩家手动维修目标必须优先于脚本自动选定目标。
- `repair_targets` 只返回维修距离内、存活、未附着、未满生命且通过游戏原生可维修判定的单位。`relation` 必须为 `self`、`ally`、`both` 或 `friendly`，默认值为 `both`；`max_results` 的有效范围为 1 至 256，默认值为 128。
- `repair_targets` 必须在 Lua 实际调用时才扫描候选单位。仅需等待游戏自动维修启动的脚本不得在空闲状态调用该函数。
- `find_safe_route` 会根据威胁的当前速度、推进因子、转向速度和起步加速度预测其位置，并对候选路线的沿途最小安全余量评分；它不是静态“离所有单位最远”的点选择器。
- `construction_targets` 只返回未完成建筑。`relation` 必须为 `self`、`ally`、`both` 或 `all`；默认值为 `self`。
- `construction_targets.origin` 可为单位或坐标表。指定后，结果必须按距该点由近到远排序；`max_distance` 必须在排序前过滤范围外目标。
- `construction_check` 必须检查建造者能否到达建筑外围的有效施工位置，不得要求建筑中心点对建造者可通行。该规则适用于建筑中心位于水面而建造者可从岸上施工的海军建筑。
- `construction_check.samples` 的有效范围为 8 至 24，`max_approach` 的有效范围为 40 至 360，`max_nodes` 的有效范围为 64 至 2048，`max_path_checks` 的有效范围为 1 至 8。默认单次调用最多执行 3 次受限路径搜索。
- `candidate_offset` 指定本次开始验证的候选施工位置。返回 `next_candidate_offset` 时，后续重试必须使用该值，避免重复检查同一批候选位置。
- `construction_check` 返回 `available`、`valid`、`reachable`、`reason`、`retryable`、`definitive`、`ignore_scope`、`construction_key`、`approach_x/y`、`checked_candidates`、`passable_candidates`、`visited_candidates`、`path_checks` 和可用时的 `path`。当游戏提供原生能力判断时，同时返回 `native_available=true` 和 `native_compatible`。
- 受限寻路达到 `max_nodes` 时必须返回 `reason=search_limit`、`retryable=true`；尚未验证全部候选施工位置时必须返回 `reason=search_incomplete`、`retryable=true`。两者均不得被框架报告为确定不可达。
- `ignore_scope=target` 表示目标本身已经无效；`ignore_scope=builder_target` 表示仅当前建造者与目标的组合无效；`ignore_scope=none` 表示不得永久忽略。
- `reason=unreachable`、`invalid_builder`、`invalid_target`、`target_complete`、`target_not_friendly` 或 `unsupported_type` 时，当前条件下不得下发续建指令。
- `native_compatible=false` 只作为原生能力诊断，不得覆盖已经通过外围施工位置和路径验证的 `valid=true`。某些游戏版本会在原生 Action 刷新期间暂时返回 `false`。
- 当路径层暂不可读取且 `native_compatible=true` 时，返回 `reason=native_only`、`valid=true` 和 `reachable=true`，允许直接尝试原生续建；当原生能力也不可用时，必须保留 `path_data_unavailable` 的可重试结果。
- `require_buildable_type=true` 时，脚本必须同时声明 `build` 数据组。建造者的 `buildable_types` 缺失或不包含目标类型时必须返回 `unsupported_type`。
- `available=false` 表示路径层不可读取；除非结果明确为 `reason=native_only` 且 `valid=true`，脚本不得把该结果当作可达。
- `construction_status` 返回 `state`、`active`、`acknowledged`、`target_id`、`construction_key`、`progress`、`target_valid`、`target_unfinished`、`distance`、`edge_distance`、`intent`、`intent_target_id`、`order_source`、`order_command_id`、`order_script_id`、`order_issued_tick` 和 `order_age`。不存在的字段必须省略。
- `construction_status.state` 必须为 `invalid_builder`、`no_target`、`invalid_target`、`target_complete`、`player_order`、`approaching`、`working` 或 `idle`。
- 返回表的 `available=false` 表示当前游戏版本未提供所需原生数据。
- 脚本必须为不可用结果提供保守回退。

## 8. 射击与运动计算

```lua
ctx:fire_solution(attacker, target[, weapon_index])
ctx:can_fire_at(attacker, target[, weapon_index])
ctx:range_margin(attacker, target[, weapon_index])
ctx:time_until_can_fire(attacker, target[, weapon_index])
ctx:predict_position(unit, ticks)
ctx:time_to_stop(unit)
ctx:braking_distance(unit)
ctx:time_to_heading(unit, angle)
ctx:intercept_point(chaser, target[, options])
```

`fire_solution` 返回：`available`、`weapon_index`、`range`、`distance`、`margin`、`reload_remaining`、`ready`、`targetable`、`can_fire`、`time_until_fire`。

`intercept_point` 选项：`speed`、`max_ticks`。返回点包含 `x`、`y`、`ticks`。

`predict_trajectory` 返回 `{available,unit_id,horizon,step,points,final}`。`points` 每项包含 `ticks,x,y,velocity_x,velocity_y,speed,heading,moving`。单位当前没有实际速度或推进量时，预测保持原地；不能仅因存在攻击目标或最终目标点就判定单位正在移动。

`threat_assessment` 返回每个威胁的当前距离、当前安全余量、预测安全余量、相对接近速度、`moving`、`approaching`、`will_enter_range` 和可用时的 `time_to_range`，并返回 `minimum_margin`、`nearest_id`、`active_count`、`approaching_count`、`surrounded`、`danger` 和 `retreat_required`。

`find_safe_route` 返回 `x/y`、候选方向、`minimum_margin`、`can_keep_firing`、`path`、`nodes_checked` 和 `route_required`。调用方应在威胁签名、单位路径或目标意图变化时重新规划；相同快照内的轨迹、地形和路线结果会复用缓存。

`threat_retreat` 是威胁判断与路线规划的组合函数。它在无需撤退时只返回评估结果，不执行路径搜索；需要撤退时才返回 `route`。`preserve_target` 与 `preserve_range=true` 用于要求路线尽可能保持对目标的攻击距离，但安全撤退优先级高于保持射程。

运动计算必须使用快照中的当前速度、加速度、减速度和转向速度。缺失参数时函数可以返回 `nil`。

## 9. 指令

```lua
ctx:move(units, x, y[, append])
ctx:steer(units, x, y[, options])
ctx:move_keep_target(units, x, y, target[, options])
ctx:attack_move(units, x, y[, append])
ctx:attack(units, target[, append])
ctx:patrol(units, x, y[, append])
ctx:guard(units, target[, append])
ctx:repair(units, target[, append])
ctx:assist_build(units, unfinished_building[, options])
ctx:reclaim(units, target[, append])
ctx:enter(units, target[, append])
ctx:load(units, target[, append])
ctx:build(units, type_id, x, y[, variant[, append]])
ctx:action(units, action_id[, x[, y[, append]]])
ctx:stop(units)
```

`units` 可为单位、单位 ID 或数组。

`steer` 与 `move_keep_target` 选项：

| 字段 | 默认值 | 限制 |
|---|---:|---:|
| `deadband` | 16 | 0 至 200 |
| `refresh_ticks` | 24 | 1 至 600 |

`steer` 必须在目标点移动超过 `deadband` 或距上次实际下令达到 `refresh_ticks` 时才发出原生移动指令。

`move_keep_target` 必须使用移动指令维持自动开火能力，不得转换为 `attack` 或 `attack_move`。目标无效时必须拒绝指令。

`assist_build` 必须先执行与 `construction_check` 相同的外围施工位置可达性验证，通过后直接向未完成建筑下发原生续建指令。原生能力判断只作为辅助诊断，不得覆盖已经通过路径验证的结果。该函数不得预先向建筑中心、施工位置或其他中间点下发移动指令。

`assist_build` 选项：

| 字段 | 默认值 | 限制或含义 |
|---|---:|---|
| `samples` | 12 | 8 至 24 |
| `max_approach` | 240 | 40 至 360 |
| `max_nodes` | 512 | 64 至 2048 |
| `candidate_offset` | 0 | 本次开始验证的候选施工位置，0 至 4095 |
| `max_path_checks` | 3 | 单次受限路径搜索次数，1 至 8 |
| `require_buildable_type` | false | 为 true 时，建造者的 `buildable_types` 必须包含目标类型 |
| `refresh_ticks` | 60 | 相同续建目标的最短重复下令间隔，1 至 600 |
| `respect_player` | true | 玩家正在执行其他指令时拒绝接管 |

`assist_build` 返回建造检查字段和通用指令字段。`validated_builders` 是本次通过检查的建造者数量，`rejected_builders` 是未通过检查的数量，`checks` 是每个请求建造者的独立检查结果。至少一个建造者通过检查时，顶层 `valid` 必须为 `true`。相同原生续建意图仍有效或仍处于刷新间隔内时，必须返回 `accepted=true`、`issued=false` 和 `command_reason=same_intent`。

所有指令返回：

| 字段 | 含义 |
|---|---|
| `accepted` | 框架是否接受请求 |
| `issued` | 是否实际向游戏发出原生指令 |
| `command_id` | 实际发出时的框架指令编号 |
| `reason` | 拒绝原因 |

脚本不得通过每 Tick 重复下达同一移动指令维持移动。持续转向必须使用 `steer` 或 `move_keep_target`。

## 10. 生命周期与暂停

```lua
ctx:finish([units])
ctx:exit([local_message])
ctx:suspend(ticks)
ctx:resume()
```

- `finish` 必须解除指定单位；省略参数时解除脚本当前控制的全部单位。
- `exit` 必须结束脚本当前控制状态，并可显示本地消息。
- `suspend` 必须保留 Lua 状态，并将脚本暂停 1 至 36000 Tick。
- 暂停期间脚本不得执行回调、构建快照或采集其数据组。
- 到达 `wake_tick` 后，脚本必须恢复一次正常回调以检测继续条件。
- 条件仍不满足时，脚本应再次调用 `suspend`。
- `resume` 必须清除当前暂停截止时间；它供已执行的脚本逻辑或框架主动恢复流程使用。
- 任意游戏状态条件不能在零检测成本下即时唤醒；要求即时响应的脚本必须缩短暂停周期。

低频等待规范：

```lua
rw.on_tick(3, function(ctx)
  if not activation_condition(ctx) then
    ctx:suspend(12)
    return
  end
  run_active_logic(ctx)
end)
```

## 11. 全局辅助函数

```lua
rw.distance(a, b)
rw.away(from, target, distance)
rw.around(center, radius, angle_degrees)
rw.is_building(unit)
rw.capabilities()
rw.catalog()
rw.log(...)
```

`rw.unit_types` 仅在声明 `catalog` 时填充。

## 12. 游戏数据释义

### 12.1 全局快照

| 名称 | 类型 | 对应的游戏数据 |
|---|---|---|
| `ctx.tick` | integer | 当前对局的模拟 Tick，不是现实时间。|
| `ctx.multiplayer` | boolean | 当前是否为多人网络对局。|
| `ctx.local_team` | integer | 本地玩家队伍 ID。|
| `ctx.settings` | table | 脚本设置界面验证后的当前值。|
| `ctx.map` | table | 地图尺寸、地块尺寸和战争迷雾能力。|
| `ctx.local_player` | table | 本地队伍资金、收入和单位上限。|
| `ctx.projectiles` | array | 当前游戏投射物；只供确实需要弹道规避的脚本使用。|
| `ctx.environment.danger_zones` | array | 原生危险区域；项含 `unit_id,type_id,x,y,radius,team_id`。|

`ctx.map` 字段：

| 字段 | 游戏含义 |
|---|---|
| `width_tiles/height_tiles` | 地图横向、纵向地块数量。|
| `tile_width/tile_height` | 单个地图地块的世界坐标尺寸。|
| `width/height` | 地图世界坐标宽度、高度。|
| `visibility_grid_available` | 本地队伍的原生战争迷雾网格是否可读取。|

`ctx.local_player` 字段：`id,name,funds,income_rate,income_multiplier,unit_count,unit_cap`。这些值均为只读游戏状态。

`ctx.projectiles` 每项可包含：`id,x,y,height,source_id,target_id`。该数组可能很大，脚本必须先按坐标范围筛选，不得无条件全量排序。

### 12.2 地形、战争迷雾与寻路层

`ctx:tile_at(point)` 返回静态地图地块：

- `tile_x/tile_y`：地图地块坐标。
- `in_bounds`：坐标是否位于地图内。
- `tile_id/tile_name`：可解析时的地块类型与显示名。
- `water/water_bridge/lava/cliff`：水面、水上桥、熔岩和悬崖标记。
- `resource_pool`：资源点地块。
- `large_cliff_or_trees`：大型悬崖或树林阻挡。
- `blocks_buildings/land_blocked`：建筑放置或陆地移动阻挡。
- `visible/fogged/explored/unexplored`：本地队伍的视野状态。

战争迷雾读取本地队伍的原生视野网格。数值 `0` 至 `4` 表示当前可见，`5` 至 `9` 表示已探索但当前被战争迷雾覆盖，`10` 表示未探索。网格不可用或坐标越界时，快捷布尔函数返回 `nil`，不得将 `nil` 当作 `false`。

`ctx:path_tile_at(point,movement_type,radius)` 返回单位实际使用的寻路层：

- `grid_x/grid_y`：寻路网格坐标。
- `width/height`：寻路网格尺寸。
- `world_to_grid`：世界坐标到寻路网格坐标的倍率。
- `terrain_cost/building_cost/object_cost`：地形、建筑、动态对象造成的通行代价。
- `terrain_blocked/building_blocked/object_blocked`：对应来源是否阻挡。
- `passable`：该移动类型和单位半径是否可通过。
- `clearance_radius/clearance_passable`：考虑单位体积后的净空判断。

`LAND`、`AIR`、`WATER`、`HOVER` 使用不同寻路层。静态地形可通行不代表动态寻路层一定可通行。

### 12.3 单位字段完整词典

| 字段 | 分组 | 对应的游戏数据 |
|---|---|---|
| `id` | identity | 全局对象 ID；用于命令目标和 Lua 状态键。|
| `type_id` | identity | 原生或模组兵种 ID，严格区分大小写。|
| `type_name` | identity | 当前游戏语言中的显示名。|
| `building` | identity | 游戏原生建筑判定。|
| `orderable` | identity | 单位是否属于可接收玩家命令的单位类。|
| `dead/deleted` | identity | 单位已死亡或已从游戏对象系统删除。|
| `team_id/team_name` | team | 单位所属队伍 ID 和名称。|
| `relation/relation_name` | team | 与本地队伍关系：`0/self`、`1/ally`、`2/enemy`。|
| `x/y/height` | position | 单位中心的世界坐标和高度。|
| `position` | position | `{x,y,height}` 坐标表。|
| `heading` | position | 单位底盘真实朝向，单位为角度。|
| `radius` | position | 单位碰撞或占位半径。|
| `health/max_health` | health | 当前与最大生命值。|
| `shield/max_shield` | health | 当前与最大护盾值。|
| `health_ratio/shield_ratio` | health | 当前生命或护盾比例。|
| `combined_health_ratio` | health | `(health+shield)/(max_health+max_shield)`。|
| `health_missing/shield_missing` | health | 当前缺失的生命或护盾值。|
| `movement_type` | movement | 单位原生移动类型。|
| `max_move_speed` | movement | 当前游戏状态下的最大移动速度。|
| `real_speed` | movement | 根据速度分量和原生移动因子得到的实际速度摘要。|
| `velocity_x/velocity_y` | movement | 当前世界坐标速度分量。|
| `acceleration/deceleration` | movement | 原生起步加速度和制动减速度。|
| `turn_speed` | movement | 单位底盘转向速度。|
| `moving` | movement | 当前是否存在实际运动量。|
| `throttle` | movement | 原生移动推进因子；可用于判断是否正在起步。|
| `speed_ratio` | movement | 当前推进速度相对最大速度的比例摘要。|
| `path_state` | pathing | `idle`、`following` 或 `commanded`。|
| `path_pending` | pathing | 是否存在当前移动命令或待执行路点。|
| `path_available` | pathing | 当前版本是否成功读取了原生单位路点队列。|
| `path_points` | pathing | 按原生队列顺序排列的路点数组。|
| `path_target_x/y` | pathing | 当前队列最后一个路点的世界坐标。|
| `path_next_x/y` | pathing | 当前队列第一个路点的世界坐标。|
| `attack_range` | combat | 单位主攻击距离；多武器单位应读取具体武器槽。|
| `weapon_count` | combat | 武器槽数量。|
| `target_id` | combat | 当前攻击或自动索敌目标 ID。|
| `current_order` | orders | 当前原生命令枚举文本。|
| `order_target_id` | orders | 当前命令的单位目标 ID。|
| `order_x/order_y` | orders | 当前命令目标点的世界坐标。|
| `waypoint_count` | orders | 当前路点或命令数量。|
| `idle` | orders | 当前没有原生命令。|
| `order_revision` | orders | 观测到的命令签名每次变化时递增。|
| `order_changed` | orders | 当前快照中命令是否刚发生变化。|
| `order_source` | orders | 命令来源：`player`、`script` 或 `game`。|
| `order_automatic` | orders/repair | 当前原生命令是否由游戏内置自动任务生成。|
| `order_issued_tick` | orders | 当前命令变化被记录的 Tick。|
| `order_command_id` | orders | 匹配到脚本命令时的框架命令编号。|
| `order_script_id` | orders | 发出匹配命令的脚本 ID。|
| `last_player_order` | orders | 最近一次玩家命令的类型、版本、目标和坐标。|
| `move_destination_x/y` | orders | 当前移动命令最终目标坐标。|
| `next_waypoint_x/y` | orders | 当前可读取的下一路点坐标。|
| `desired_heading` | orders | 从当前位置指向命令目标点的期望角度。|
| `chasing_target_id` | orders | 当前攻击命令正在追击的目标 ID。|
| `intent_type` | orders | 当前原生指令的规范化意图。|
| `intent_target_id` | orders | 当前意图关联的单位 ID。|
| `intent_target_role` | orders | 当前意图的原生角色。|
| `is_chasing/chase_target_id` | orders | 追击状态及追击目标。|
| `is_protecting/protect_target_id` | orders | 保护或警戒状态及保护目标。|
| `is_building_order/build_target_id` | orders | 建造状态及建造目标。|
| `is_reclaiming/reclaim_target_id` | orders | 回收状态及回收目标。|
| `is_repairing/repair_target_id` | repair | 是否正在执行维修意图及当前维修目标。|
| `repair_automatic` | repair | 当前维修任务是否由游戏内置自动维修逻辑生成。|
| `repair_range` | repair | 维修单位的原生维修搜索距离。|
| `is_construction_site` | construction | 单位是否为建筑。|
| `is_unfinished` | construction | 建筑是否尚未完成。|
| `construction_progress` | construction | 当前建造完成度。|
| `construction_state` | construction | `not_building`、`unfinished` 或 `complete`。|
| `construction_key` | construction | 当前对局内稳定的建造目标签名。|
| `build_progress` | build | 建造完成度，通常为 `0` 至 `1`。|
| `factory` | build | 是否属于游戏原生工厂类。|
| `queue_size` | build | 生产队列长度。|
| `buildable_types` | build | 从原生 Action 解析出的可建造单位或建筑 ID。|
| `production_items` | production | 队列项；可含 `count,progress,rate,remaining_ticks,action_id,type_id,target_id`。|
| `carrier_id/attached` | transport | 运输或附着父单位 ID，以及是否已附着。|
| `transport_capacity` | transport | 可解析的运输槽位数。|
| `loaded_unit_ids` | transport | 已装载单位 ID 数组。|
| `actions` | actions | 单位当前公开的原生 Action ID 数组。|
| `action_details` | actions | Action 的名称、说明、费用和目标类型。|
| `abilities` | abilities | 可含 `id,name,description,cost,executable,locked,cooldown`。|
| `last_damaged_tick` | damage | 最近检测到生命或护盾下降的 Tick。|
| `last_damage_amount` | damage | 最近一次快照间检测到的耐久下降量。|
| `last_damage_source_id` | damage | 游戏能够解析时的最近伤害来源 ID。|
| `visible_to_local` | map | 单位所在位置当前是否对本地队伍可见。|
| `fogged_to_local` | map | 单位所在位置当前是否被战争迷雾覆盖。|
| `explored_to_local` | map | 单位所在位置是否曾被探索。|
| `selected` | selection | 单位当前是否被本地玩家选中。|
| `group_id` | 运行时 | 本次批量绑定形成的脚本编队 ID。|

`unit.weapons[i]`：

| 字段 | 游戏含义 |
|---|---|
| `index` | 武器槽序号，从 1 开始。|
| `range` | 该武器槽实际射程。|
| `turret_heading` | 炮塔或武器槽朝向。|
| `target_id` | 该武器槽当前目标。|
| `reload_remaining` | 剩余装填时间。|
| `warmup` | 剩余或当前预热值。|
| `cooldown` | 武器冷却参数。|
| `ready` | 原生武器槽是否就绪。|

### 12.4 运行时兵种目录

声明 `data={"catalog"}` 后，`rw.unit_types[type_id]` 可包含：`id,name,custom,movement_type,attack_range,max_move_speed,radius,max_health,max_shield,weapon_count,acceleration,deceleration,turn_speed,weapons,abilities,transport_capacity,actions,buildable_types`。

该目录来自当前对局实际出现过的兵种，不是完整静态表。未在当前快照出现的外部模组兵种不会自动加入。

## 13. 沙盒编辑器 All 单位 ID 表

类型 ID 严格区分大小写。下表保留游戏资源中的实际拼写。

### 13.1 原版类型（40 个）

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

### 13.2 当前游戏包内置自定义类型（103 个）

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

`NukeLaucher`、`AntiNukeLaucher` 与 `experiementalCarrier` 是游戏资源中的实际拼写。外部模组加载后会继续注册新类型；脚本应使用 `data={"catalog"}` 和 `rw.unit_types` 获取当前对局出现的新 ID。

## 14. 性能规范

- 脚本必须声明最小数据组集合。
- 空闲脚本应使用 `ctx:suspend()` 进行低频轮询。
- 活跃控制应优先使用 2 至 6 Tick 的回调间隔。
- 全图单位扫描每次回调最多执行一次，并应复用结果。
- 局部威胁查询应使用 `ctx:within()`。
- 路径搜索必须设置满足需求的最小 `max_nodes`。
- 逃生点必须保持足够时间；不得因每次回调重新规划而重置单位起步。
- 原生指令去重必须使用 `issued` 判断实际下令。
- 自动续建脚本必须声明 `construction`，使用 `construction_targets` 获取候选建筑，并将 `construction_check` 或 `assist_build` 限制为每次回调最多一个未知目标。
- 自动续建脚本必须依据 `retryable` 和 `next_candidate_offset` 分段验证候选施工位置。`search_limit`、`search_incomplete` 和 `path_data_unavailable` 不得在首次出现时写入永久忽略集合。
- 自动续建脚本必须使用 `construction_status` 确认原生续建指令已经成为单位当前意图；不得仅以单位坐标变化推断指令是否生效。
- 自动续建脚本确认目标不可达后，必须使用 `construction_key` 在当前对局内跳过该目标；不得每 Tick 重新搜索同一不可达目标。
- 自动续建必须使用 `assist_build`；不得先向建筑中心或推算的施工点发送移动指令。
- 自动续建脚本每次回调最多下发 4 条指令；无候选目标且没有到期的待重试检查时必须使用 `suspend` 降低轮询频率。
- 当本次所有到期脚本只声明 `identity`、`team`、`position`、`movement`、`orders`、`pathing` 和 `construction` 时，框架只允许为已绑定单位采集完整运动与指令数据；其他单位只允许采集候选建筑筛选所需的轻量字段。
- 夹击、包围和动态威胁处理应调用 `threat_assessment` 或 `threat_retreat`；普通追击不得调用弹道数据。
- `find_safe_route` 应使用较短的 `step` 进行连续小幅修正；只有确实需要脱离包围圈时才提高 `step`。

## 15. 对局边界与缓存

框架区分“新对局”和“游戏同步”。当模拟 Tick 在同一同步代次内回退时，框架认定开始新对局，并重建每个脚本的 Lua VM；脚本全局表、局部闭包、单位状态表、暂停截止时间和运行时错误计数均被清空。新对局同时清空单位绑定、分组、指令去重、玩家指令识别、伤害历史和路径临时缓存。

当同步代次变化时，框架认定发生游戏同步或重连，只清空快照、指令和观测瞬态缓存，保留 Lua VM 与脚本全局状态。同步事件不会因为 Tick 变小而误清空脚本状态。

因此脚本不应自行用 Tick 回退猜测新对局；脚本只需要把单位状态保存在全局表中，框架会在新对局边界重建 VM。`ctx:suspend(ticks)` 只暂停回调、快照构建和数据采集，不退出脚本；到达唤醒 Tick 后才进行一次低成本条件检查。
