# 女仆 GUI 操作与加工等待

女仆使用自己的 `FakePlayer` 打开服务端菜单，操作自己的物品。支持附近方块、实体以及主手、副手和背包里的可打开界面的物品。搜索默认 12 格，上限 16 格；遵守活动范围，只访问已加载区域，寻路上限 400 tick。

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

结构化槽位、按钮、重命名和交易操作不依赖主人客户端。截图及按位置点击要求主人在线、同维度且客户端加载目标。开启视觉功能后，GUI 图像沿用已有主模型/独立视觉模型路由；六面环境观察保持原有行为。

## 工具协议

所有工具执行在服务端，每个女仆最多一个会话，同一会话内串行操作。请求绑定聊天回调、会话 UUID 和工具动作 ID；执行中及已完成的重复请求不会重复改变物品。

| 工具 | 参数与结果 |
| --- | --- |
| `open_gui` | `target` 方块名称/注册 ID，或 `x/y/z`，或 `entity_uuid`，或 `item_slot`；`wait_policy`；返回 `session_id` 和首次槽位观察。 |
| `inspect_gui` | `session_id`；可选 `visual=true`；返回槽位、背包引用、光标、控件和加工状态。图像观察另有 `frame_id`、`layout` 和逻辑 GUI 尺寸。 |
| `gui_action` | `transfer`、`click_slot`、`quick_move`、`button`、`rename`、`trade`、`backpack_to_cursor`、`cursor_to_backpack`，以及视觉 `click/widget/type/key/scroll`。 |
| `wait_gui` | `item`、本次目标总 `count`，可选 `output_slot`、`max_wait_seconds`。目标包含已经取回的数量；修复后重复等待不重置预算。`NO_WAIT` 只检查启动，不收集产物。 |
| `close_gui` | 正常关闭并归还光标及临时输入。返回 `dropped_count` 表示背包满时在女仆位置掉落的数量。 |

标准菜单的背包窗口索引 0 对应主手，1..35 对应可用背包 0..34，40 对应副手。未解锁槽位禁用，不默认使用盔甲或饰品。`backpack_slots` 和独立背包动作可以访问其余已解锁存储。打开背包里的终端时暂时换到主手，关闭后还原两处物品及 NBT。

菜单槽位 ID 与背包索引是不同的编号，必须使用观察结果。转移前复查源、目的槽位及光标，其他玩家改变物品后返回 `stale_slots_reinspect`。普通加工进度变化不会让整个槽位观察失效。工作台和交易等产物按完整配方批次取回；要求拆分不可插回的产物会返回 `indivisible_output_stack`，防止无声多取或遗失余量。熔炉产物支持精确数量，并保留原版取物、经验和 Forge 回调。

`rename` 支持铁砧文本，但不借用玩家经验。需要玩家经验时返回 `player_experience_not_bridged`，不绕过原版取物限制。末影箱这种玩家专属存储返回 `player_storage_not_bridged`。

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

## 模组兼容接口

标准菜单槽位自动复用 `AbstractContainerMenu` 的点击逻辑。Forge `NetworkHooks.openScreen` 的额外初始化字节会保存并传给原菜单工厂；客户端创建独立 `LocalPlayer`、菜单和原 Screen，在渲染线程后台绘制。玩家、当前界面、渲染目标、矩阵及相关渲染状态在 `finally` 恢复。

原 Screen 产生的标准点击、菜单按钮、交易、铁砧名称和关闭请求转换成女仆服务端操作。自绘按钮通过原 Screen 坐标事件操作。`EditBox` 可以设置文本；其他文本控件可按坐标聚焦并发送原 `charTyped` 事件。原生 GUI 实例上的局部输入状态保留到后续观察。截图布局过期需要重新观察。

未知专用包不会发送到玩家正常连接，返回 `custom_protocol_requires_adapter:<channel>`。专用协议需要在 common setup 中注册适配器，校验载荷后操作 `session.actor` / `session.menu()`：

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

兼容结果分别记录槽位、渲染、按钮和加工状态。未知模组不承诺完整视觉或专用协议兼容；独立客户端初始化、自绘及额外状态需要遵守上述接口。首版不操作地图、JEI 或纯客户端设置页面，也不为未桥接的权限/经验赋予玩家能力。

## 开发验证

Java 17，在线 Gradle。正常发布构建不包含测试机器、终端和开发测试代码。

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
