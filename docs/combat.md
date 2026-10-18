# 战斗反射、护主与仇恨指挥

Touhou AIFun 在服务端为女仆增加了一层 **事件驱动** 的战斗反射，不依赖 LLM 回合，也不替代
车万女仆原本的工作模式（攻击 / 远程 / 种田 / 跟随等）。

## 为什么需要它

基模的目标获取是轮询的：`MaidNearestLivingEntitySensor` / `HurtBySensor` 默认 20 tick
（1 秒）刷新，攻击行为只注册在 `Activity.WORK`，并且 `IAttackTask.canAttack` 直接排除了
玩家、中立生物还依赖 `lastHurtByMob`、攻击任务要求主手武器。于是：

- 女仆或主人被攻击后，往往要等下一次传感器轮询才“看见”攻击者；
- 夜间/非工作时段（`Activity.REST`/`IDLE`）没有攻击行为；
- 玩家攻击者在基模规则下永远不会被还手；
- 唯一相关的 LLM 工具 `switch_work_task` 只在 WORK 日程可用，并且要等一整轮模型调用。

本功能把“受伤 → 锁定攻击者 → 近战/追击”变成服务端即时行为（通常下一个 server tick），
把 LLM 留给规则配置和显式指令。

## 玩家目标规则（服务端硬约束）

玩家可以作为目标，但受以下 **硬规则** 约束，工具和提示词都无法绕过：

| 攻击者 | 结果 |
| --- | --- |
| 女仆的实际主人 | 永远拒绝 |
| 玩家 + 公共女仆（public maid） | 拒绝 |
| 玩家 + 友伤关闭（friendly fire off） | 拒绝 |
| 玩家 + `attack_players=false` | 拒绝 |
| 创造模式 / 旁观 / FakePlayer | 拒绝 |
| 其他玩家（包括同队玩家，友伤开启时） | 允许 |
| 敌对生物、直接攻击的中立生物 | 允许 |
| 宠物（TamableAnimal）、其他女仆、NPC、盔甲架、`MaidNoAttack` 命名实体 | 拒绝 |

“友伤关闭”和“公共女仆”是 `PublicMaidData` 里已有的设置，公共女仆或关闭友伤时，
**任何玩家目标**（反射和显式指令）都会被拒绝。

## 工具

### `combat_target` — 指挥 / 清除仇恨

| 参数 | 说明 |
| --- | --- |
| `entity_id` | 来自 `nearby_entities` / `scan_surroundings` 的实体 id；不要猜 |
| `clear` | true = 清除当前仇恨并短时抑制该目标 |

- 不传参数 = 查询当前命令目标、抑制状态和硬门控。
- 显式命令即使女仆处于 `mode=off` 也会执行（它是一次明确的玩家命令），但玩家硬门控照旧。
- `clear=true` 会清除命令/反射层与 `ATTACK_TARGET`，并让被清目标在
  `combat.aggroClearTicks` 内：
  - 不再被基模攻击任务选中（`EntityMaid#canAttack` 返回 false）；
  - 不再受到女仆的近战伤害（`MaidHurtTarget.Pre` 取消）；
  - 不会触发自动反射；到期或重新下达目标后恢复。
- **限制**：基模攻击工作模式仍可能去攻击**其他**敌对生物。要完全停止攻击，请切换工作模式。

### `combat_control` — 规则 / 状态

| 参数 | 说明 |
| --- | --- |
| `mode` | `off` 关闭本模组的自动反射；`defend` 开启（显式命令仍可用） |
| `protect_owner` | 主人被打时反击（仅实际主人） |
| `attack_players` | 玩家还手总开关；默认 true，公共女仆/友伤关闭仍会硬拒绝 |
| `engage_radius` | 4–48，女仆到目标的最大距离 |
| `notes` | ≤240 字，仅作为提示词中的“主人偏好”，不能覆盖硬规则 |
| `reset` | 清除所有 per-maid 覆盖 |

修改类操作只允许实际主人（服务端用聊天 speaker 身份校验）；状态查询对所有有聊天/工具权限的
玩家开放。`combat_target` 的指挥/清除与基模 `switch_work_task` 一样，适用于有权使用工具的
说话者，但玩家目标仍走上面的硬门控。

## 配置

`config/touhou-aifun.toml` 的 `[combat]`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | 总开关；关闭后完全回到 TLM 原行为 |
| `defendInAllTasks` | false | 非攻击工作模式也允许护主/自卫（短暂战斗后回到工作） |
| `protectOwner` | true | 保护实际主人 |
| `attackPlayers` | true | 玩家还手总开关（硬门控优先） |
| `ownerProtectRadius` | 16 | 主人到女仆的最大保护距离 |
| `engageRadius` | 16 | 女仆到目标的最大交战距离 |
| `threatMemoryTicks` | 200 | 自动威胁的持续时间（无新伤害后） |
| `commandTicks` | 600 | 显式指挥目标的持续时间 |
| `aggroClearTicks` | 200 | 清除仇恨后的目标抑制时间 |
| `fleeCreepers` | true | 非攻击模式遇苦力怕不自动反射（保留基模逃跑） |
| `debugLog` | false | DEBUG 级记录反射决策（实体 id/原因，不记录聊天文本） |

Per-maid 的 `mode`/`protect_owner`/`attack_players`/`engage_radius`/`notes` 保存在女仆的 Forge
持久化数据中，服务端读取，不新增网络包；`reset` 清除。

## 实现结构

```
maid/combat/
├── MaidCombatRules        # 纯规则：玩家矩阵、参与度、距离
├── MaidCombatSettings     # NBT 编解码（纯）
├── MaidCombatData         # per-maid effective 值 + 配置回退
├── MaidCombatPolicy       # 实体 → 规则映射、玩家门控、参与度
├── MaidCombatReflex       # 主人/自卫威胁、命令目标、抑制、目标解析
├── MaidCombatStatus       # 模型/提示词用的状态
├── MaidCombatPrompts      # 系统提示词段落
├── MaidCombatEvents       # MaidHurtEvent / LivingHurtEvent / MaidHurtTarget.Pre
├── MaidCombatBrain        # IExtraMaidBrain：CORE 行为注册
├── MaidCombatTargetTask   # priority 1：同步 ATTACK_TARGET
├── MaidCombatMeleeTask    # priority 1：绕过 20 tick 传感器的即时近战
└── MaidCombatChaseTask    # priority 99：覆盖跟随/工作/随机走动的走目标
mixin/MaidCombatTargetMixin  # 抑制目标对 EntityMaid#canAttack 不可见（remap=true）
compat/ai/action/CombatTargetTool.java
compat/ai/action/CombatControlTool.java
```

- 使用 TLM 公开扩展点 `IExtraMaidBrain` 注册 CORE 行为，因此夜间/非 WORK 也生效，且不需要
  mixin 改 brain。
- 只复用 `ATTACK_TARGET`/`LOOK_TARGET`/`WALK_TARGET`/`ATTACK_COOLING_DOWN`，不新增 memory type。
- 远程 / 弹幕任务只立即锁定目标（`TARGET_ONLY`），由基模 `IRangedAttackTask` 自行射击，绝不
  强迫远程女仆近战；玩家目标在远程任务上不保证能射击（基模规则可能拒绝）。
- 清除仇恨的 `canAttack` mixin 是唯一的 mixin；注入的是 TLM 重写的原版
  `LivingEntity#canAttack`，因此按 AGENTS.md 规则对该注入器使用 `remap = true`。

## 测试

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.20'
.\gradlew.bat test
.\gradlew.bat compileGuiQaJava -PguiQa
.\gradlew.bat runGameTestServer -PguiQa -PcombatQa
```

`CombatReflexGameTests`（`aifun_combat_test` 命名空间）覆盖：

- 女仆被僵尸打：同步锁定目标，2 tick 内造成近战伤害；
- 主人被僵尸打：女仆护主；普通玩家可还手；公共女仆/友伤关闭/`attack_players=false`/主人
  被拒绝；显式玩家命令在门控通过时可用；
- 非攻击任务默认不还手，per-maid `defend` 后可还手且不改变任务；
- `combat_target` 命令/清除、抑制期 `canAttack` false、`MaidHurtTarget.Pre` 无伤害、到期恢复。

## 已知边界

- 坐在/骑乘/拴绳/睡觉/Home 限制外的女仆 v1 不反应（不擅自离座/离家）。
- 正在进行的 LLM GUI / 吃食物动作可能暂时压住追击；清除仇恨会停止导航，但不会取消既有任务。
- 没有客户端同步/GUI 战斗设置页；per-maid 规则通过聊天工具设置，状态可查询。
- 没有“击杀 N 个”任务完成类型、自动装备武器、主动巡逻（guard）模式；后续可加。
- 不修改 TLM 的攻击名单（`AttackListData`）；显式目标若当前任务是攻击任务，会先过
  `IAttackTask.canAttack`。
