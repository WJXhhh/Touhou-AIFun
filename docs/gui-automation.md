# 女仆 GUI 操作与加工等待

女仆使用自己的 `FakePlayer` 打开服务端菜单，操作自己的物品。支持附近方块、实体以及主手、副手和背包里的可打开界面的物品。搜索默认 12 格，上限 16 格；遵守活动范围，只访问已加载区域，寻路上限 400 tick。

方块交互距离由 `config/touhou-aifun.toml` 的 `[guiAutomation] blockInteractionReach` 控制，默认 3.0 格，可设 1.0–6.0 格；搜索半径 `max_distance` 不改变此距离。开界面和吃方块食物共用“女仆眼睛到方块实际可点击表面”的距离与视线判定，完整方块、玻璃等可点击障碍会挡住射线。寻路使用同一规则选择站位，并考虑台阶、箱子等非整数地面高度；执行前重新检查，右键使用射线命中的实际位置与面，不隔墙开箱。部分表面被挡住时会尝试其他表面采样点；箱子顶盖被堵等原版限制仍生效。此配置不改变实体交互距离。

GUI 与方块动作共用单一 `MaidActionLease`：打开 GUI 会取消正在进行的方块动作/吃食物动作；方块动作开始时若 GUI 已打开则返回 `gui_open_close_first`，不允许在菜单打开期间放置/挖掘。详细方块契约见 [block-actions.md](block-actions.md)。

2026-10-18 验证：Java 17，Forge 47.2.0 / 47.4.20 各通过 402 项 JUnit 和 110 项必需服务器 GameTest，`build`、`reobfJar` 完成。新增九项测试覆盖可配置距离、高处/侧面开箱、实际 Forge 命中位置与面、部分表面可见、石头/玻璃完全遮挡、真实绕墙导航、半砖站位和蛋糕食用/遮挡。既有导航终点/箱顶站位回归适配新距离；箱盖受阻夹具显式清空周围空间，避免其他障碍掩盖顶盖限制。日志为 `build/block-reach-release-qa.log`、`build/block-reach-47420-qa.log`。测试使用实际服务器世界和菜单，未新增真实 LLM API 或客户端人工验收；发行 jar 不含 QA 类。

寻路前检查导航缓存涉及的区块；范围未完全加载时返回 `path_region_not_loaded`，不会为操作界面主动加载区块。刚生成或落地前暂时无法生成路径时，在原寻路期限内重试。

## 玩家用法

直接向女仆提出任务，例如「把铁矿和煤放进熔炉烧一下」「等烧完拿回来」「放进去就走，不用等」。主模型需要支持工具调用。GUI 工具通过现有工具目录和按需 schema 加载提供。

| 策略 | 默认行为 |
| --- | --- |
| `NO_WAIT` | 确认机器开始加工后关闭界面；不取产物，不安排返回。无法确认启动时明确返回未确认。 |
| `AUTO` | 本次目标预计不超过 60 秒时等待并取回；超过 60 秒则启动后离开。 |
| `UNTIL_GOAL` | 等待并取回目标数量；默认累计上限 15 分钟。 |

「不用等」优先于同一条指令中隐含的取回要求。「最多等十秒」等明确上限会从玩家文本读取，并与工具参数和服务端上限取较短值。后续对话立即取消旧会话。停止按钮取消本次任务；旧模型请求不能自行重新打开菜单。

女仆 AI 设置页的「查看操作界面」显示只读预览、等待策略、加工状态、预计时间和最近操作。关闭预览会保留任务；「停止操作」关闭会话，归还临时物品，保留机器内的投料。

原版及已适配菜单的结构化操作不依赖主人客户端。陌生模组机器必须先查看实际界面；截图及按位置点击要求主人在线、同维度且客户端加载目标。开启视觉功能后，GUI 图像沿用已有主模型/独立视觉模型路由；六面环境观察保持原有行为。

## 工具协议

所有工具执行在服务端，每个女仆最多一个会话，同一会话内串行操作。请求绑定聊天回调、会话 UUID 和工具动作 ID；执行中及已完成的重复请求不会重复改变物品。

| 工具 | 参数与结果 |
| --- | --- |
| `open_gui` | `target` 方块名称/注册 ID，或 `x/y/z`，或 `entity_uuid`，或 `item_slot`；`coordinate_space` 默认 `world`，可显式采用 `maid_relative`；`wait_policy`；返回 `session_id` 和首次槽位观察。 |
| `inspect_gui` | `session_id`；可选 `visual=true`、`hover_x/hover_y` 查看原生悬停说明；返回槽位、背包引用、光标、控件和加工状态。图像观察另有 `frame_id`、`layout` 和逻辑 GUI 尺寸。 |
| `query_recipes` | 不需要会话。传输出 `item` 注册名或 `recipe_id`，可选目标 `count`、分页 `offset/limit`；读取服务端当前加载的工作台配方、真实材料判定、材料示例、背包可用数量和配方冲突。 |
| `gui_action` | `transfer`、`click_slot`、`quick_move`、`button`、`rename`、`trade`、`backpack_to_cursor`、`cursor_to_backpack`，以及视觉 `click/widget/type/key/scroll`。 |
| `wait_gui` | `item`、本次目标总 `count`，可选 `output_slot`、`max_wait_seconds`。目标包含已经取回的数量；修复后重复等待不重置预算。`NO_WAIT` 只检查启动，不收集产物。 |
| `close_gui` | 正常关闭并归还光标及临时输入。返回 `dropped_count` 表示背包满时在女仆位置掉落的数量。 |

标准菜单的背包窗口索引 0 对应主手，1..35 对应可用背包 0..34，40 对应副手。未解锁槽位禁用，不默认使用盔甲或饰品。`backpack_slots` 和独立背包动作可以访问其余已解锁存储。打开背包里的终端时暂时换到主手，关闭后还原两处物品及 NBT。

`open_gui` 的 `x/y/z` 默认是世界方块坐标，不能直接填入环境扫描的 `relative_position`。`coordinate_space=maid_relative` 表示相对于工具执行时女仆所在方块的整数偏移；女仆已经移动时应重新观察。三个坐标必须同时提供，缺失或小数坐标会被拒绝。只需打开最近箱子时可传 `target=minecraft:chest` 并省略坐标。

同时提供 `target` 和坐标时，服务端先核对实际方块，错误目标返回 `target_block_mismatch`，不右键其他方块。打开失败会提供世界坐标 `target_position`、`maid_position`、`actual_block` 和手持物信息；方块交互后仍未建立菜单时，`no_server_menu` 还附带 `interaction_result`、`menu_provider_present`。原版箱子上方被挡住等正常开箱限制继续生效，不绕过限制直接打开菜单。

菜单槽位 ID 与背包索引是不同的编号，必须使用观察结果。转移前复查源、目的槽位及光标，其他玩家改变物品后返回 `stale_slots_reinspect`。普通加工进度变化不会让整个槽位观察失效。工作台和交易等产物按完整配方批次取回；要求拆分不可插回的产物会返回 `indivisible_output_stack`，防止无声多取或遗失余量。熔炉产物支持精确数量，并保留原版取物、经验和 Forge 回调。

`rename` 支持铁砧文本，但不借用玩家经验。需要玩家经验时返回 `player_experience_not_bridged`，不绕过原版取物限制。末影箱这种玩家专属存储返回 `player_storage_not_bridged`。

## 工作台配方与自动合成

合成前先调用 `query_recipes`，使用当前服务器 `RecipeManager` 的结果，包括数据包和整合包脚本替换后的配方。支持原版及模组物品、有序/无序工作台配方、材料标签和 Forge 自定义 `Ingredient.test`（包括 NBT 判定）。返回材料定义及有限示例；示例不代表只接受这些物品。`can_fill_one_craft` 只判断当前已解锁女仆背包能否满足一轮，不代表整个目标批次材料足够；材料不足时不会替模型编造配方。输入相同而被其他配方优先匹配时返回 `shadowed_by_recipe`。

打开原版工作台或其 `CraftingMenu` 子类后，调用 `gui_action(action="fill_recipe", recipe_id="...")` 可从女仆背包准备一轮材料，保留实际产物预览，不取成品。已有格内材料先安全归还背包，再按配方布局填入；手持物品不作为自动材料来源。`gui_action(action="craft_recipe", recipe_id="...", count=N)` 每 tick 执行一轮填料、原版取物和背包交付，直到本次新交付数量达到目标。两种动作使用独立 `gui_action`，不放入只接受同步槽位动作的 `gui_batch`。

`count` 是本次要新增的产物数量，不包含背包原有数量。原版最小批次可能多产，例如要求 5 根木棍会执行两轮得到 8 根，结果明确给出 `requested_count/crafted_count/surplus_count/crafts_executed`。缺料、背包满、打断或任务改要求时返回实际已交付数量；续做需减去已有 `crafted_count`。同一动作 ID 的重试不会再次合成。每次取物仍由真实 `ResultSlot.onTake` 消耗材料、返还容器并调用 Forge/模组回调；关闭时归还格内剩余材料。

执行前检查背包、光标和格子观察是否过期，预留产物和容器返还空间；不足时不通过丢物腾空间。运行中配方对象发生替换会停止并要求重新查询。支持 `close_after/complete_task`，只有实际进入女仆背包的产物才计入任务交付凭据。

本功能只自动操作工作台配方。机器专属配方类型、超出 3×3 的配方、没有公开材料/布局的特殊动态配方会明确报告不支持；模组物品本身不需要逐项适配。原版及模组特有的权限、解锁和菜单限制继续生效。

## 等待规则

服务器每 20 tick 检查一次机器，不循环调用模型或截图。计时采用游戏 tick，低 TPS 或游戏暂停不会提前消耗实际游戏时间。

- `AUTO` 已知短任务：预算为预计 tick × 1.5 + 100 tick，范围 300..1800 tick；已知长任务只确认启动后离开。
- `AUTO` 未知时长：最多观察 600 tick；返回已经开始但尚未完成，或无法确认加工状态。
- `UNTIL_GOAL`：采用 `guiAutomation.maxWaitSeconds`，默认 900 秒。初始预计时间到期不会直接退出仍有进展的机器。
- 预算属于聊天任务；重新打开、领取一次产物和补燃料不重置预算。非等待操作累计最多 6000 tick，实际动作最多 64 次。服务端观察不计动作次数。
- 有可靠进度时，无进展容忍时间为 `max(300 tick, 适配器更新间隔 × 2)`。燃料减少、动画或其他槽位改变不算加工进展。
- 已知阻塞经过 60 tick 启动宽限、连续两次确认后返回原因。缺燃料、输出堵塞等允许模型用现有材料修复，再继续原目标。

`completed` 必须表示目标实际进入女仆存储。截止仍加工返回 `still_processing` 和已取回数量；未知状态返回 `progress_unknown`；阻塞返回 `blocked_missing_fuel`、`blocked_output_full`、`blocked_inventory_full` 等原因。不会仅以输入槽变空判定交付完成。

熔炉适配覆盖熔炉、烟熏炉和高炉，读取真实进度、总时长、燃烧状态、配方和输入输出。估算采用同步总时长或实际配方 cooking time，计入本次目标全部数量。先观察新产物，再收集，避免连续配方进度回绕造成错误停滞。

## ProjectE 转化桌

安装 ProjectE 1.20.1 PE1.0.1 时自动启用可选适配；未安装时不加载其兼容类，也不把 ProjectE 打包进本模组。女仆先实际走到并打开 `projecte:transmutation_table`，再通过 `gui_action` 执行：

| action | 参数与结算 |
| --- | --- |
| `projecte_search` | 可选精确 `item` 或 `text`（注册名/服务端物品名称），`offset/limit` 分页。查询主人当前已学知识，包括余额不足的物品；返回 `projecte_results`、当前单价、可支付数量和 NBT 版本 `selection`。 |
| `projecte_deposit` | `backpack_slot`、`count`（1..64）。从女仆已解锁背包取出指定数量，按 ProjectE 当前回收价增加主人的 EMC，并通过原生学习逻辑处理 NBT、全知识宝典和可取消的 `PlayerAttemptLearnEvent`。 |
| `projecte_exchange` | 精确 `item`、`count`（1..4096 个新增产物）；多个已学 NBT 版本时需传搜索返回的 `selection`。按实时购买价扣除主人 EMC，精确放入女仆背包，返回 `exchanged_count/emc_spent`。 |

例如：「把你背包里的两颗钻石投入转化桌，用我的 EMC 兑换 70 个炼金煤炭，放回你的背包并关闭界面」。模型只能投入主人任务授权的物品和数量，不能擅自烧掉其他物品凑钱。动作完成条件记录实际投入及兑换产物入包的转移事实，可与 `close_after/complete_task` 一起使用。

`adapter_state` 明确标记 `account=owner`、主人 UUID、实时 EMC（十进制字符串，避免大整数损失）和知识状态。原生假玩家菜单用于实际打开与会话生命周期；搜索和结算直接访问主人的 ProjectE capability，不复制余额或知识到假玩家。主人必须在线且存活；换主人、离开/拆除转化桌、背包观察过期时拒绝操作。

余额、知识、整合包修改的 EMC、回收损耗及 NBT 保留规则均以 ProjectE 当前规则为准。不会动主人的背包、转化桌持久输入/锁定槽或其中储能物品；本适配仅使用主人账户余额。没有 EMC 的物品不会被烧掉；知识不足、余额不足、背包放不下完整目标时在扣款前拒绝兑换。异常部分入包只结算实际产出，剩余预留 EMC 退回；重试须扣除 `exchanged_count`，相同动作 ID 不重放。

转化桌使用上述服务端语义操作，普通槽位点击与视觉输入会被拒绝，避免操作假玩家的独立账户。`inspect_gui` 返回账户状态；`visual=true` 会明确说明当前使用语义适配，不生成不准确的原版界面截图。手持转化终端、遗忘知识和克莱因之星充放电未接入这些动作。

开发验证使用真实 ProjectE jar 和独立测试目录 `build/projecte-qa-server`：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.20'
.\gradlew.bat test build -PguiQa -PprojecteQa runGameTestServer
# 显式启用既有站点的真实模型完整流程；凭据仅在内存读取，不写进报告。
.\gradlew.bat test build -PguiQa -PprojecteQa -PprojecteLiveQa runGameTestServer
```

真实模型报告为 `build/projecte-qa-server/projecte-live-result.json`。测试专用 NBT 标签仅在 `src/projecteQa/resources`，用于验证整合包允许保留的 NBT 版本，不进入发布 jar。

2026-10-17 真实模型验证：DeepSeek `deepseek-flash` 经 9 次模型请求完成投入 5 颗钻石、主人学会钻石、搜索并兑换 70 个炼金煤炭入包、保持双手为空、关闭界面及任务完成核验。主人最终余额为 5120 EMC，与实时回收/购买价格一致。一次不受支持的 `gui_batch` 被拒绝后，模型改用单独的 `gui_action` 自行完成；schema 已明确 ProjectE 动作不能放入 `gui_batch`。测试初版固定投料不足时被余额校验拒绝，正式夹具改为由 ProjectE 当前价格计算足够投料，不假定模组物品价格。

最终回归：390 项单元测试通过；ProjectE 专项覆盖 8 项真实菜单/背包/账户测试（`build/projecte-final-qa.log`，另有未开启真实 API 时直接成功的占位测试）。覆盖投入学习、原生主人学习事件及取消、模组物品多堆叠兑换、已学 NBT 版本、全知识分页、重复动作不重放、满背包/余额不足/无 EMC/过期观察/拆桌/换主人/离线拦截，以及模拟模组在第二堆插入时抛异常后仅扣除首堆 EMC、退款和按剩余数量重试。真实模型验证日志为 `build/projecte-live-qa.log`。未安装 ProjectE 的独立回归 `build/projecte-absent-regression.log` 通过全部 100 项必需 GameTest，验证可选类隔离及原有工作台、箱子等流程。

## 模组兼容接口

标准菜单槽位自动复用 `AbstractContainerMenu` 的点击逻辑。Forge `NetworkHooks.openScreen` 的额外初始化字节会保存并传给原菜单工厂；客户端创建独立 `LocalPlayer`、菜单和原 Screen，在渲染线程后台绘制。玩家、当前界面、渲染目标、矩阵及相关渲染状态在 `finally` 恢复。

原 Screen 产生的标准点击、菜单按钮、交易、铁砧名称和关闭请求转换成女仆服务端操作。自绘按钮通过原 Screen 坐标事件操作。`EditBox` 可以设置文本；其他文本控件可按坐标聚焦并发送原 `charTyped` 事件。原生 GUI 实例上的局部输入状态保留到后续观察。截图布局过期需要重新观察。

陌生菜单打开时，在视觉启用后自动获取原 Screen 的截图；未获取界面前拒绝投料和点击，返回 `unfamiliar_machine_inspect_gui_visual_first`。视觉关闭、主模型拒绝图片且独立识图也失败时，同样保留该拦截；生成图片本身不能证明模型已经看懂。槽位来自真实菜单，`accepted_backpack_items` 只检测当前背包材料能否通过原生 `Slot.mayPlace`，不能据此猜测配方或断言产物。支持原版 Inventory 以及 Forge 的 InvWrapper、RangedWrapper、CombinedInvWrapper 嵌套包装，按实际存储身份识别女仆背包、禁用未解锁格并核算转移。

原生 Forge PLAY 自定义消息可自动桥接：原 Screen 生成的 C2S 经女仆专用连接交给原模组已注册的处理器，sender 为女仆 FakePlayer；专用连接只复制主人连接中不可变的模组/通道协商元数据，供原模组判断对端通道是否存在；发给该连接的 S2C 状态在独立 LocalPlayer、菜单及方块实体副本上应用。标准 DataSlot、槽位、额外初始化数据和方块实体更新标签一并镜像。不会把专用包送进主人的正常连接，观察与输入结束后恢复玩家上下文。只允许当前菜单命名空间或该菜单已收到的状态通道，排除 Minecraft/Forge/FML/AIFun 控制通道；未注册的协议仍明确报错。消息及镜像均有大小上限，丢失同步时要求重新打开。

自定义消息操作等待服务端排队处理，再读取权威槽位和女仆存储，返回 `result_verification`；实际转移才形成任务凭据。预览也确认已应用的状态序列，避免无限累计。未知加工进度保持 UNKNOWN；模型应辨认输出格，`wait_gui(item,count,output_slot)` 按实际产出精确收集，不能以点击成功或原料消失认定完成。

通用桥接覆盖使用原生菜单和标准 Forge PLAY 网络处理的机器。纯客户端界面、另行登录/私有传输、依赖真实玩家身份或未同步的客户端全局状态仍可能需要适配；未知机器配方不会自动变成工作台配方。ProjectE 这种明确借用主人账户的功能继续采用语义适配。需要补充结构化动作或额外状态时，可在 common setup 注册适配器，操作 `session.actor` / `session.menu()`：

```java
MaidGuiAdapters.register(new MaidGuiAdapter() {
    public boolean supports(AbstractContainerMenu menu) { return menu instanceof YourMenu; }
    public boolean supportsChannel(ResourceLocation channel) { return CHANNEL.equals(channel); }
    public boolean customPacket(MaidGuiSession session, ResourceLocation channel, FriendlyByteBuf data) {
        // 校验消息 ID、长度、范围、权限，随后使用女仆身份执行。
        return true;
    }
});
```

`controls` 提供结构化控件；`action` 提供模组动作；`initializationData` 可补菜单初始化数据。`clientState` 提供专用 S2C 状态，并在 client setup 用 `GuiClientRuntime.registerAdapter(MaidGuiClientAdapter)` 应用到独立菜单和 Screen。不会把该状态重放到玩家界面。

`transientInventory` 只声明菜单关闭时返还的临时输入。机器、实体和物品自身的持久存储不能声明为临时物品，否则会造成重载复制。内置保存处理光标、工作台输入、村民交易支付物和常见原版临时加工格；排除合成/交易预览产物。

加工状态单独注册 `MaidGuiProcessAdapter`，返回 `STARTING/RUNNING/COMPLETED/BLOCKED/UNKNOWN`，可附单调累计进度、总量、累计产出、剩余 tick 和更新间隔。缺能量、暂停、材料不符等由适配器报告。不从未知菜单的任意 `DataSlot` 推断加工含义。

兼容结果分别记录槽位、渲染、按钮和加工状态。通用桥接不保证所有第三方界面均兼容；地图、JEI 或纯客户端设置页面未纳入机器操作，也不为女仆授予主人的权限或经验。

## 开发验证

Java 17，在线 Gradle。正常发布构建不包含测试机器、终端和开发测试代码。

### 通用机器桥接验收

测试依赖仅由 `-PmachineQa` 加载：Iron Furnaces 4.1.8、Mekanism 10.4.16.80、Thermal Expansion 11.0.1.29（Foundation 11.0.6.70 / CoFH Core 11.0.2.56）。生产代码没有这三台机器的专用适配器。使用独立 `build/machine-qa-client/saves/AIFun-Machine-QA` 世界，不操作玩家存档。电动机器由测试夹具通过各面的 Forge Energy capability 提供外部电源；配方消耗、加工 tick、原生界面和取物均来自实际模组。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.20'
# 第一次运行前先生成通用 QA 世界；准备脚本下载官方 Mekanism 测试包并复制隔离存档。
.\gradlew.bat -PguiQa runGameTestServer
.\scripts\prepare-machine-qa.ps1
.\gradlew.bat -PguiQa -PmachineQa runClient
# 显式使用已有启用站点进行真实模型验收；密钥只在内存中读取。
.\gradlew.bat -PguiQa -PmachineQa -PmachineLiveQa runClient
```

工具夹具报告为 `build/machine-qa-client/machine-client-result.json`，真实截图为 `machine-0/1/2-before.jpg` 和 `machine-0/1/2-running.jpg`。报告区分 `public_tool_fixture`（代码选择槽位）与 `real_model_native_gui`（实际模型接收图片并选择动作），缺少报告或未完成会使 Gradle 失败。2026-10-17 工具夹具已分别验证三台机器的完整流程：女仆背包投放 3 个粗铁，铁炉另投 1 个煤，全部消耗原料并精确取回 3 个铁锭，双手保持为空。

同日 DeepSeek `deepseek-flash` 真实模型验收全部通过，图像实际路由到主模型，由模型选择打开菜单、投料和取货动作，最终核对背包、空手、关闭菜单和运行时完成状态：

| 真实机器 | 模型请求数 | 实际结果 |
| --- | --- | --- |
| `ironfurnaces:iron_furnace` | 17 | 粗铁 3→铁锭 3；部分完成条件格式和过期会话被拒绝，模型修正后完成 |
| `mekanism:energized_smelter` | 5 | 粗铁 3→铁锭 3；无工具错误 |
| `thermal:machine_furnace` | 8 | 粗铁 3→铁锭 3；无工具错误 |

单项真实模型报告为 `machine-live-ironfurnaces-iron_furnace.json`、`machine-live-mekanism-energized_smelter.json`、`machine-live-thermal-machine_furnace.json`；本次汇总另保留为 `machine-live-result.json`，日志 `build/machine-live-run1.log`。这三项流程主要使用原生槽位投料与取物，不把它们当作所有机器设置按钮的覆盖；未适配的自定义按钮消息往返另由原生协议 GUI 夹具验收。

通用 GUI 测试另覆盖未注册适配器的原生 C2S/S2C 消息、女仆 sender、嵌套 Forge 背包包装、未解锁格、无关协议拒绝，以及真实后台菜单中的回复同步和玩家上下文恢复。测试通过也不表示所有模组、服务商模型或光影组合已经验证。

本次回归：390 项单元测试、101 项必需服务端 GameTest 通过；Forge 47.2.0 / 47.4.20 的客户端检查均通过原生自定义消息往返、标准/自绘控件、过期帧、文本、背包、预览关闭和停止、玩家上下文恢复。47.4.20 上另外重跑三种真实机器的完整投料/取货流程，全部通过，且验证未看界面前的投料被拒绝。ProjectE 专项 9 项通过（含未启用真实模型时的占位测试，实际菜单/账户测试 8 项），主人账户适配保持可选。

日志：`build/native-bridge-release-isolated.log`、`build/native-bridge-client-regression6.log`、`build/native-bridge-final-47420.log`、`build/native-bridge-machines-47420.log`、`build/native-bridge-projecte-regression.log`。47.4.20 机器夹具报告另保存为 `build/machine-qa-client/machine-47420-result.json`；首次工具夹具报告为 `machine-fixture-result.json`。客户端检查发现的问题已修复，包括未执行动作时错误标为部分成功，以及人工重放包必须明确指定 Forge PLAY 方向；测试按钮布局也移到了窗口可见范围。

反复复用服务器测试世界会累积未终结的测试任务，达到 64 条档案上限后会返回 `world_task_archive_full`。本次最终回归使用 `-PguiQaRunId=native-bridge-final-20261017` 新建隔离目录 `build/gui-qa-native-bridge-final-20261017`；需要独立重复运行时使用新的 run id，保留旧测试目录，不放宽生产档案上限。

2026-10-17：`gradlew test build -PguiQa -PcraftingLiveQa runGameTestServer` 通过 389 项单元测试和 100 项必需 GameTest。新增工作台测试覆盖空九宫格开始的实际背包填料、模组有序/无序配方、标签交叉分配、Forge NBT 材料、桶返还、缺料/满包、中断、幂等重试、运行时替换配方，以及合成产物入包后的任务完成核验。另修正测试夹具对 Forge 配置文件异步热重载的竞争，固定假决策服务快照，不修改生产配置读取方式。

`craftingLiveQa` 是显式启用的单次真实模型测试，使用已有 `run/config/touhou_little_maid/sites/llm.json` 凭据，只在内存读取。DeepSeek `deepseek-flash` 实测经过 5 次模型请求，调用扫描、配方查询、任务计划、打开工作台和 `craft_recipe`，将背包 6 块木板及 3 个铁锭合成为 6 个测试模组物品，验证入包、手持为空、关闭菜单和任务完成。模型首次提交的完成条件格式被拒绝后自行修正；本次成功不代表所有模型的规划都已验证。无凭据的默认测试不会调用真实 API。

本地报告：`build/gui-qa-server/crafting-live-result.json`；完整回归日志：`build/crafting-live-verification.log`。发布 jar 已检查包含合成服务，不包含测试模组、测试配方或测试代码。

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.16+8'
.\gradlew.bat test build
.\gradlew.bat -PguiQa runGameTestServer
.\gradlew.bat '-Pforge_version=47.4.20' -PguiQa test runGameTestServer
```

测试服务器目录为 `build/gui-qa-server`，客户端为 `build/gui-qa-client`。自动客户端检查使用专门的 `AIFun-GUI-QA` 存档，可从停止后的测试世界复制 `level.dat` 和 `region` 来准备；不使用玩家现有存档：

```powershell
$destination = 'build/gui-qa-client/saves/AIFun-GUI-QA'
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item 'build/gui-qa-server/world/level.dat' $destination
Copy-Item 'build/gui-qa-server/world/region' $destination -Recurse -Force
.\gradlew.bat '-Pforge_version=47.4.20' -PguiQa -PguiQaAuto runClient
```

客户端需要可用的图形环境。结果写入 `gui-qa-client-result.txt`；自动验证任务在结果失败或缺失时使 Gradle 失败。另有真实后台截图 `gui-qa-capture.jpg` 和预览截图 `gui-qa-preview.png`。

测试覆盖 NBT 精确转移、并发槽位变更、配方消耗、精确熔炉取物、背包满掉落、关闭归还、临时输入保存重载、连续熔炼、无需等待启动确认、寻路占用、终端换手还原、交易回调和铁砧经验限制。客户端测试覆盖额外初始化数据、标准/专用/自绘按钮、未知包隔离、过期帧、文本输入、连续产物收集、只读预览、关闭预览、停止及玩家上下文恢复。等待单元测试覆盖明确期限、未知时长、慢速进度、累计预算和阻塞宽限。

单人测试和独立 GameTest 服务器使用真实 Forge 菜单与网络；实际服务商的 LLM 如何规划复杂任务、具体第三方模组组合以及光影环境需要另行验证。

### 2026-10-03 验证结果

使用 Java 17.0.16、在线 Gradle 完成验证。全仓 202 项单元测试通过，失败、错误和跳过均为 0。

| 环境 | 服务端菜单测试 | 单人客户端自动检查 |
| --- | --- | --- |
| Forge 47.2.0 | 11 / 11 通过 | 通过 |
| Forge 47.4.20 | 11 / 11 通过 | 通过 |

默认 Forge 47.2.0 的 `build`、`reobfJar` 完成，发布产物为 `build/libs/Touhou-AIFun-0.3.jar`。检查确认 GUI Mixin 的 SRG 映射进入 `touhou_aifun.refmap.json`，配置显式引用该 refmap，熔炉配方类型访问转换包含在 jar 中。开发测试模组和测试机器未进入发布产物。

验证日志为 `build/gui-qa-release-final.log` 与 `build/gui-qa-47420-final.log`。47.4.20 使用隔离环境；未提供实际整合包，因此不将这些结果视为具体第三方模组组合的兼容认证。

箱子定位修复追加验证：默认 Forge 47.2.0 的全仓 232 项单元测试、23 项 GameTest 全部通过，`build` 和 `reobfJar` 完成，日志为 `build/gui-chest-target-fix.log`。新增验收覆盖相对坐标开箱、错误目标拦截和被挡住的箱子诊断。此追加验证未包含 Sol/Luna 的真实 API 对比。
