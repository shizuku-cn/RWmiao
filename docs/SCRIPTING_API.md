# RWmiao 可执行 Lua 单位自动化 API v1

## 脚本最小结构

Lua 脚本是完整程序，框架不提供固定的“风筝/绕圈”行为。框架只在模拟 Tick 提供只读单位快照，并把 Lua 提交的操作转换为游戏原生同步命令。

```lua
rw.script{
  api=1,
  id="my_script",
  name="我的脚本",
  units={"mechMinigun","mechGun"}, -- 必填；也可写 units="All"
  data={"position","movement","combat"}
}

rw.on_tick(6,function(ctx)
  for _,u in ipairs(ctx:self_units()) do
    -- 判断和操作全部写在这里
  end
end)
```

`units` 是脚本唯一必须声明的单位范围，可以是一个兵种名、多个兵种名或 `All`。不要求声明敌方兵种。脚本仅能向本机所属单位下令。

## 查询

- `ctx:self_units()`：当前脚本声明范围内、且已为单位启用脚本的己方单位。
- `ctx:units()` / `ctx:enemies()` / `ctx:allies()`：全部、敌方、盟友单位。
- `ctx:selected()`：当前选中的单位。
- `ctx:get(id)`：按全局单位 ID 查询。
- `ctx:nearest_enemy(unit_or_point, filter)`：最近敌军。`filter` 可省略；支持 `range`、`type`、`types`。
- `ctx:within(unit_or_point, radius, filter)`：查询圆形范围内单位；过滤器还支持 `relation`、`orderable`、`selected`。

单位是普通 Lua table，可直接读取：

`id,type_id,type_name,team_id,team_name,relation,relation_name,x,y,height,heading,radius,position,health,max_health,health_ratio,health_missing,shield,max_shield,shield_ratio,shield_missing,combined_health_ratio,dead,deleted,selected,attached,orderable,custom,factory,movement_type,attack_range,weapon_count,waypoint_count,queue_size,target_id,carrier_id,current_order,order_target_id,order_x,order_y,build_progress,idle,actions`

`actions` 是该单位当前公开的原生 Action ID 列表。工厂生产、升级、展开等版本相关操作可由脚本枚举后交给 `ctx:action`，框架不猜测 ID 的含义。

## 原生命令

所有方法都返回 `{accepted, reason, command_id}`。第一个参数可以是单个单位、单位 ID，或它们的数组。最后一个 `append` 默认为 `false`。

```lua
ctx:move(unit, x, y, append)
ctx:attack_move(unit, x, y, append)
ctx:patrol(unit, x, y, append)
ctx:attack(unit, target, append)
ctx:guard(unit, target, append)
ctx:repair(unit, target, append)
ctx:reclaim(unit, target, append)
ctx:enter(unit, transport, append)       -- 坐上运输机/运输单位
ctx:load(transport, target, append)      -- 运输单位主动装载目标
ctx:build(builder, type_id, x, y, variant, append)
ctx:action(unit, action_id, x, y, append) -- 生产、升级及自定义特殊动作；坐标可省略
ctx:stop(unit)
```

框架不会替 Lua 选择目标、决定距离、保存状态或组合动作。`local state={}`、数学计算、循环、条件、有限状态机均由脚本自由实现。

## 数学辅助

- `rw.distance(a,b)`：二维距离。
- `rw.away(from,target,distance)`：从目标远离指定距离，返回 `{x,y}`。
- `rw.around(center,radius,angle_degrees)`：圆周点。

这些只是坐标计算，不会发出命令。

## 安全与管理

每个脚本使用独立 Lua VM；移除了 Java、反射、文件、网络、进程、`debug`、动态加载入口。每个回调最多 250,000 条 Lua 指令、32 条原生命令，每条最多 256 个单位；连续 3 次异常后本局停用。导入时会真实编译和初始化 Lua，错误会直接显示；热重载失败会继续保留旧 VM。

性能上，未给任何具体单位启用脚本时不会遍历全局单位注册表；同一模拟 Tick 的多个命令队列入口会被合并，并只在 Lua 回调真正到期时生成一次共享快照。反射字段和方法映射也会缓存。

## 后续可开放但必须先验证映射的数据

- 每个武器槽的炮塔方向、射程、装填进度、射击冷却和目标。
- 单位移动速度、加速度、转向速度及路径状态；不会把碰撞推离量误标成速度。
- 地图尺寸、地形格、可通行性、视野和战争迷雾可见性。
- 队伍资源、单位上限、生产队列项目及剩余进度。
- 运输容量、已装载单位列表、附着槽位和父子单位关系。
- 投射物、危险区域、伤害来源和最近受击 Tick。

只有在当前游戏版本确认字段和原生同步路径后才会加入；解析失败的数据应返回 `nil`，不能猜测字段语义。

模块页底部提供脚本总开关、逐脚本开关、长按删除和 Android 文件选择器导入。点击单位操作面板中的“脚本管理”，会弹出仅包含该单位可用脚本的多选对话框；勾选后点击“应用”，即可为该具体单位启停脚本。

完整风筝示例：`app/src/main/assets/examples/mech_minigun_kite_mammoth.lua`。

