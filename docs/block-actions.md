# 方块交互、建造队列与材料协商

本文档描述 AIFun 后台任务执行模型的方块能力。所有动作都在服务器线程执行，主聊天模型只读状态，
不直接操作世界。

## 1. 设计原则

- **模拟型优先**：放置走真实右键/`BlockItem.place`，食物走 TLM `IMaidEdibleBlock` 扩展；
  破坏走与 `EntityMaid.destroyBlock` 等价的完整流程。放置路径绝不直接 `setBlock`。
- **状态后置校验**：每次动作后等待 1 tick 再读实际方块/方块实体/邻居/库存，不假设动作必然成功。
- **服务端权威**：距离、活动范围、区块加载、保护、GUI/动作租约都由服务器复核；不使用客户端状态。
- **单动作租约**：每个女仆同时最多一个物理动作；GUI 打开会取消进行中的方块动作。

## 2. `block_action`（executor-only）

```json
{"action":"interact|place|mine","x":0,"y":64,"z":0,
 "coordinate_space":"world|maid_relative","face":"up|down|north|south|east|west",
 "facing":"north|south|east|west|up|down",
 "item":"minecraft:oak_planks","tool":"minecraft:iron_pickaxe",
 "expected":"minecraft:oak_stairs[facing=north,half=bottom]","strict":false,
 "timeout_seconds":20}
```

- `interact`：右键方块/使用物品，支持主手/副手；若打开菜单，关闭并返回
  `menu_opened`，应改用 `open_gui`。交互非幂等（拉杆/按钮/活板门）。**不传 `expected`
  时按一次性动作成功返回**，不再错误地拿空气去校验结果；需要确认最终状态时必须传
  `expected`。
- `place`：`position` 是**目标方块位置**，`face` 是希望的支撑点击面（默认 up）。
  工具会依次尝试 `face` 与其余可用的支撑面，用真实射线命中；目标已满足时直接成功（不消耗）。
  成功返回实际方块、消耗数量与（零食柜）type。朝向由 `expected` 的方块状态属性或
  `facing` 参数确定（见 2.1），不再跟随女仆身体朝向。期望状态里未知的方块/属性/属性值
  在动作开始前就被拒绝，不会先改世界再报错；非方块物品返回
  `item_is_not_placeable_block`，物品与期望方块不匹配返回 `expected_block_item_mismatch`。
- `mine`：目标方块位置；`tool` 省略时自动选择最佳工具。默认即时破坏；配置
  `miningMode=simulated` 时按硬度/工具计算 tick 进度。掉落进入女仆背包，满则掉世界。
  不可破坏/受保护/活动范围外直接拒绝。目标已是空气时返回 `already_satisfied`。
- 结果字段：`success/status/error/detail/action/position/face/expected_block/actual_block/
  previous_block/item/item_consumed/attempts/drops/snack_cabinet_type/orientation/recovery_hint`。

### 零食柜上放蛋糕

1. 目标位置 = 零食柜上方空气格；把蛋糕放在女仆背包/主手。
2. `block_action(place, x/y/z=目标, item=minecraft:cake, face=up)`。
3. 若支撑方块属于 TLM `MAID_SNACK_STAND_BLOCK` 且物品满足
   `IMaidEdibleBlock.canPlaceAsFood/shouldPlaceTo`，走 `placeAsFood`（内部 `BlockItem.place`）。
4. 等待 1 tick 校验：蛋糕方块存在、`CakeBlock.BITES==0`、零食柜 `TYPE != NONE`、
   方块实体仍在；失败且来源被扣 1 时补回物品，再回退真实右键。
5. 仍不一致时返回 `verification_mismatch` 和实际状态，由建造执行器交决策模型/LLM。

### 2.1 方块朝向控制

女仆的 Brain AI 会持续调整身体朝向，旧实现把她的实时 yaw/pitch 复制给假玩家，导致同一台阶
每次放下的朝向都不同。现在放置朝向是确定性的：

1. **默认**：假玩家看向真实点击点（`look_at_hit`），与女仆当前身体/视线方向无关。
2. **expected 状态驱动**：`expected`（以及建造队列 step 的 `block`）里写出的朝向属性
   `facing`/`axis`/`rotation`/`half` 等由规划器求解。规划器用**物品自己的
   `BlockItem.getPlacementState`** 预测实际会放下的方块状态（不只是期望方块的
   `getStateForPlacement`），枚举候选 yaw（22.5° 一处，覆盖 16 向 banner）与俯仰 ±90°，
   并逐个可达支撑面比较预测状态，选第一个满足全部列出属性的（旋转 + 点击面）组合。
   因此楼梯/箱子/熔炉/活塞/原木轴/活板门/上下半砖都能按状态放置；每个支撑面还会尝试
   不同点击位置，`half=top` 这类由点击位置决定的状态会自动改用侧面支撑的上半部。
   **告示牌/火把/头颅/珊瑚扇等 standing+wall 变体物品**也按物品真实逻辑预测变体朝向，
   不会出现“预测墙挂却放下立式”的错变体。
3. **显式 `facing` 参数**：自动并入 expected：方块有 `facing` 属性时写 `facing=<值>`；
   没有时若有 `axis` 属性，up/down→`axis=y`、水平→对应轴。方块两者都没有时返回
   `facing_not_supported`（不再假装已生效）；与 `expected` 冲突返回 `facing_conflict`，
   该方块不接受该方向值返回 `invalid_facing_for_block`。
4. 放置后仍读实际方块状态并按 expected 校验；`orientation` 字段返回
   `yaw/pitch/source/satisfied/requested_facing/predicted_block`。`satisfied` 在动作有结果时
   按**实际世界状态**重新计算（成功路径为 true，未通过校验、超时、被阻止等为 false），
   不再只是规划器的预测；`predicted_block` 保留规划器当时的预测便于对比。
5. **安全守卫**：期望状态无法由求解器产生时不会先乱放再报错——
   - 期望方块不是手持物品能放下的方块（含 wall 变体判定）→ `expected_block_item_mismatch`；
   - 墙挂变体没有可验证的朝向组合 → `orientation_unreachable`，不做世界修改；
   - 支撑方块会被原版替换（例如拿同种半砖点已有半砖顶面会变双砖、雪层/蜡烛同理）
     → 不使用该支撑面；全部面都不可用时返回 `needs_support`，不误改已有方块；
   - 期望状态里未知方块/属性/非法属性值在动作开始前直接失败，不消耗物品。

`source` 取值：`look_at_hit`（默认看向点击点）、`facing`（显式参数）、`expected_facing`
（expected 的 facing 属性反推）、`yaw_grid`（22.5° 候选网格）、`look_up`/`look_down`
（垂直朝向候选）。

## 3. `build_queue`（executor-only）

### design

```json
{"action":"design","structure_name":"小墙","coordinate_space":"maid_relative",
 "origin":{"x":0,"y":0,"z":0},"order":"bottom_up",
 "steps":[
   {"kind":"place","x":0,"y":0,"z":0,"block":"minecraft:cobblestone","item":"minecraft:cobblestone"},
   {"kind":"place","x":1,"y":0,"z":0,"block":"minecraft:oak_stairs[facing=east,half=bottom]"},
   {"kind":"mine","x":1,"y":0,"z":0,"tool":"minecraft:iron_pickaxe"},
   {"kind":"interact","x":2,"y":0,"z":0,"item":"minecraft:lever","required":false}
 ]}
```

- 步骤坐标相对 origin；origin 默认女仆当前方块位置，`world` 表示绝对坐标。
- **place 的 `block` 必须是完整状态**：其中 `facing`/`axis`/`half`/`rotation` 等朝向属性由
  放置规划器确定性执行并逐项校验（见 2.1）；省略朝向就等于放弃对该朝向的控制。未知方块、
  未知属性或非法属性值在 `design` 保存时直接拒绝。
- **墙挂变体需要显式 `item`**：`minecraft:oak_wall_sign`、`minecraft:wall_torch` 等方块本身
  没有对应物品，默认映射会报 `place_block_has_no_item`；写法为
  `block=minecraft:oak_wall_sign[facing=north]` + `item=minecraft:oak_sign`（火把/头颅/珊瑚扇同理）。
- `face` 仍然只是首选支撑面：当期望状态需要另一个面（墙挂变体、`half=top` 等）时规划器可以
  改用其他可达面；找不到满足状态的面时退回原首选并返回实际状态。
- `order`：`bottom_up`（Y 升序再按到 origin 距离）/`layers`（仅 Y）/`as_given`。
- 单次 ≤256 步，每 design ≤4096，每女仆 ≤8 个 design；重复 place 位置、place+mine 同位置拒绝。
- 返回 `design_id`、`step_count`、`required_item_counts`、`missing_from_maid`、`warnings`。
- 同一 design 可用 `append=true` 追加（被完成条件引用后不可再改）。

### 其他 action

| action | 说明 |
|---|---|
| `status` | 只读分页：design/queue/步骤/缺料/最近决策/material_policy/pending_owner_question |
| `verify` | 只读核对 design 的 live world 期望状态，返回不一致步骤 |
| `request_materials` | 返回缺口（允许策略时）或触发主模型问主人（ask_owner） |
| `reorder`/`skip`/`reset` | 调整待执行步骤；required place/mine 不能 skip |
| `pause`/`resume` | 暂停/恢复队列；run 也会因 max_steps/max_seconds 自动暂停 |
| `run` | 执行 active/design_id design，每次最多 max_steps 步、max_seconds 秒 |
| `clear`/`delete_design` | 清执行状态（保留 design）/删除未引用的 design |

`run` 前置条件：callback 必须是 `TaskCallback`；当前任务 `completion` 必须包含
`kind=structure` 且 `structure_id` 等于该 design；维度一致、owner 一致、无打开 GUI、
无进行中的方块动作。否则返回 `completion_required_before_build` /
`design_not_in_completion` / `gui_open_close_first` / `action_busy` 等。

## 4. 完成条件 `structure`

```json
{"kind":"structure","dimension":"minecraft:overworld","structure_id":"<design id>"}
```

或小结构内联（≤64）：

```json
{"kind":"structure","dimension":"minecraft:overworld","strict":false,
 "blocks":[{"x":1,"y":64,"z":2,"block":"minecraft:oak_planks"},
           {"x":1,"y":64,"z":3,"expect":"air"}]}
```

- design 引用：place 期望其 `block`，mine 期望空气，interact 不参与；仅 `required` 步骤。
- 只读、不加载区块；未加载 → unmet（`unloaded`）；design 不存在 → `structure_design_missing`。
- `strict` 只表示比较列出的全部属性；未列出的属性不比较。

## 5. 材料处理

`material_policy` 在任务检查点/foreground 状态中可见：

| 值 | 行为 |
|---|---|
| `ask_owner`（默认） | 不自行取材；缺料时由主聊天模型问主人一次 |
| `self_gather` | 可从女仆背包/世界采集、挖掘、种植、合成；不可从容器取物 |
| `self_source` | 在 self_gather 基础上可从容器取物，但只限当前缺口物品/数量 |
| `denied` | 不取材、不再问；任务暂停并报告缺口 |

- 主模型通过 `task_control(action=enqueue|amend|guide|resume, material_policy=...)` 设置；
  只有实际主人可设置，普通“帮我建”必须保持 `ask_owner`。
- 未显式设置时，缺料前 `MaterialAuthorizationDecision` 检查主人原文是否明确许可；低置信/
  不可用一律按 `ask_owner`。
- 缺料流程：`build_queue(request_materials/run)` 返回 `missing_items`（含 item/display_name/
  required/have/for_step）→ 策略允许时执行模型使用 `scan_surroundings`、`block_action`、
  `query_recipes`、`open_gui`/`gui_action craft_recipe` 补料后重跑；未授权时暂停并
  `pendingOwnerQuestion`。
- `self_source` 的 GUI 转入由运行时守卫：只允许补当前 build 缺口，超出或无关物品返回
  `build_material_withdrawal_not_authorized`。

## 6. 所有者协商与播报来源

- 任务完成/失败/普通暂停：`detailed` 固定模板；`brief` 由主聊天模型旁路小请求（人设+最近
  对话）生成；`silent` 不播报。执行模型只写 `outcome`/narration，**不直接说话**。
- 需要玩家决定（缺材料/权限/未知阻塞）：运行时暂停任务并记录 `pendingOwnerQuestion`；
  主聊天模型生成问句，写入 assistant history 和前台系统消息；玩家回答经主模型
  `task_control` 回写。执行模型只收到结构化事实与 `material_policy`/guidance。
- `request_owner_input`（executor-only）用于非材料阻塞：提交 kind/facts/options 后暂停，
  不写玩家台词。

## 7. 配置

```toml
[blockActions]
timeoutSeconds = 20
maxAttempts = 2
requireCorrectToolForDrops = false
simulateToolDamage = true
miningMode = "instant"        # instant|simulated

[buildQueue]
maxStepsPerDesign = 4096
maxStepsPerCall = 256
runMaxSeconds = 600
stateDecision = true
stateMinConfidence = 0.7
materialAuthorizationMinConfidence = 0.8
maxMaterialRounds = 3
maxDesigns = 8
```

## 8. 错误码（主要）

`wrong_thread`、`maid_unavailable`、`movement_blocked`、`gui_open_close_first`、
`action_busy`、`unloaded`、`outside_restriction`、`target_changed`、`target_occupied`、
`needs_support`、`unreachable`、`missing_item`、`missing_tool`、`needs_correct_tool`、
`unbreakable`、`protected`、`menu_opened`、`placement_failed`、`placement_redirected`、
`verification_mismatch`、`item_is_not_placeable_block`、`expected_block_item_mismatch`、
`unknown_block`、`unknown_block_property`、`invalid_block_property_value`、
`invalid_facing`、`facing_only_for_place`、`facing_conflict`、`invalid_facing_for_block`、
`facing_not_supported`、`orientation_unreachable`、
`timeout`、`cancelled`、`owner_decision_required`、`materials_denied`、
`completion_required_before_build`、`design_not_in_completion`、`missing_items`、
`needs_llm`、`design_in_use`、`materials_denied`、`build_material_withdrawal_not_authorized`。

## 9. 测试

- 纯单测：`AgentTaskStateStructureCompletionTest`、`BuildStateDecisionTest`、
  `MaterialAuthorizationDecisionTest`、`BuildMaterialPolicyTest`、`BuildQueueStateTest`、
  `TaskAnnouncementsOwnerQuestionTest`。
- GameTest：`BlockActionGameTests`（真实放置、零食柜蛋糕、工具挖掘）、
  `BlockOrientationGameTests`（expected facing 不受女仆朝向影响、`facing` 参数、原木
  `axis` 选侧面支撑、`half=top` 选侧面上半点击、无 expected 的 interact、墙挂火把变体、
  非方块物品/未知方块/未知属性拒绝、半砖 redirect 守卫、`facing_not_supported`）、
  `BuildQueueGameTests`（design/run/structure 核验、带朝向楼梯设计、fake native judge
  判别、材料策略门控）。运行：
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.20'
.\gradlew.bat test
.\gradlew.bat test build -PguiQa -PbuildQa -PguiQaRunId=buildqa runGameTestServer
.\gradlew.bat test build -PguiQa -PguiQaRunId=build-actions runGameTestServer
.\gradlew.bat build
```
2026-10-19 验证：Forge 47.2.0 与 47.4.20 各通过 626 项 JUnit、168 项必需服务器 GameTest
（含 `aifun_build_test` 18 项：BlockAction 3、BlockOrientation 11、BuildQueue 4），
`build`/`reobfJar` 成功。真实决策服务（JEV/Perplexity）与实机材料问答/建造手感仍需客户端复验。
