# Touhou AIFun 上下文管理复核与修复（2026-10-17）

复核范围：当前工作树（HEAD `be95848` + 48 个未提交的 GUI/视觉改动），重点检查
`chat/context/`、`chat/agent/`、`compat/ai/openai/`、`vision/scan/` 以及真实运行日志
`run/logs/latest.log`（2026-10-15）与历史日志。

结论：分层记忆、工具快照、Anthropic 尾部化缓存、提取原子性等骨架是有效的，**但当前实现
确有系统性缺陷，且与用户报告的三个症状一一对应**：

1. 压缩后失忆：每批最多 8192 token 的原文只压成一段 ≤600 code point 的 episode，且旧
   episode 只在词法命中时才注入，零命中的查询完全看不到任何旧记忆；提取响应校验还比提示词
   里告知模型的约束严格，失败后静默退避、原文最终被安全上限删除。
2. 上下文增长快、消耗大：98% 的 token 是输入重放；任务执行共用 98,304 的输入预算，实测
   压缩阈值约 69K，27.4K 的峰值从未触发压缩；每轮重发全量容器快照与全量历史；ChatGPT 路由
   的缓存前缀在 system 之后即断（命中率 31%，Anthropic 路由同场景 82.7%）。
3. 常用方块记不住位置：架构里根本没有"地点记忆"这一层。扫描坐标只活 10 tick / 5 分钟，
   跨轮工具结果被截到 512 code point，长期提取提示词明确禁止把场景观察写入 facts，而
   GUI 定位每次都从当前已加载区块重新搜索"最近容器"。

本轮已按"先修正确性、再减少无效重放"的顺序落了一批代码（见文末"本轮已修复"），并保留
了第二阶段需要设计确认的项目。

---

## 一、实测数据（真实会话，非估算）

`run/logs/latest.log` 中 37 条 `AIFun usage` 记录（2026-10-15，同一女仆）：

| 用途 | 请求 | 输入 tokens | 缓存命中 | 未命中 | 输出 |
| --- | ---: | ---: | ---: | ---: | ---: |
| CHAT | 11 | 107,758 | 44,928 | 62,830 | 1,414 |
| TASK_EXECUTION | 26 | 465,841 | 133,376 | 332,465 | 8,043 |
| **合计** | **37** | **573,599** | **178,304（31.1%）** | **395,295** | **9,457** |

- 一次"继续/开箱"级闲聊请求：输入 10,127–11,035 token，缓存恒定 3,712–3,968。
- 一个失败任务两代共 205,114 输入 token，约为全部 9 次闲聊（94,763）的 2.2 倍。
- 任务执行单轮输入从 14,513 递增到 27,423，缓存仅 3,712–16,000。

单次闲聊请求体（`[touhou_little_maid/]` 落盘的真实 body）：

| 组成 | 字符 | 约 token | 占比 |
| --- | ---: | ---: | ---: |
| system（人物设定 + 全部规则） | 18,261 | 3,712 | 58% |
| tools（9 个 schema） | 5,145 | 1,536 | 24% |
| 当前 user + `<context>` | 743 | ~220 | 3% |
| runtime context / 任务状态 / 格式提醒 | 1,493 | ~515 | 8% |
| 历史消息 | 0 | 0 | 0% |

也就是说：真实人物设定只有约 600 字符（≈150 token），**AIFun 自己在 system 里追加的规则
约 10,900 字符（≈2,600 token）**：web 搜索 1,678、日期 512、实体动作 769、GUI 操作 1,867、
视觉 2,602、说话关系 1,270、格式契约 3,172（后者是替换基模原段，不是净增）。

工具循环请求体（2026-10-11 日志，同构建真实 GUI 循环）：

| 组成 | 字符 | 约 token | 占比 |
| --- | ---: | ---: | ---: |
| system | 18,713 | 3,800 | 23% |
| tools（14 个，含 gui_batch） | 11,561 | 3,450 | 21% |
| **open_gui 全量容器快照** | **14,070** | **4,200** | **25%** |
| gui_batch 内第二份快照 | 8,219 | 2,450 | 15% |
| thinking（逐轮重发） | 3,925 | 870 | 5% |
| 历史对话 | 2,084 | 1,040 | 6% |

---

## 二、症状一：压缩后失忆

### 2.1 信息损失是结构性的

`AIFunMemoryManager` 的提取链路：

- 提取批次上限 `MAX_EXTRACTION_BATCH_TOKENS = 8192`（估算 token），但产出只有**一段**
  ≤600 code point 的 episode + 若干 ≤240 字的 fact（`MemoryEpisode` 上限、`isValidDelta`、
  `applyExtraction` 里的 `limit(..., 600)`）。
- 满批中文约 4,700 字，压成 600 字，存留率约 12.6%；英文批次可到约 3 万字符，存留率约 2%。
- 提取成功后**立即删除原文**（`applyExtraction` 末尾 `memory.turns().removeIf(...)`），
  之后只有这段摘要和 facts 能回来。

### 2.2 旧 episode 召回零命中就没有回退

`LocalMemoryRetriever.rank` 对每个 episode 计算 `textRelevance`，`<= 0` 直接跳过；上层只注入
`topEpisodes(..., 6)`。换一种说法、用代词、闲聊、问"继续"时，都可能与所有旧 episode 没有
CJK bigram/trigram 或结构化实体交集 → **一条旧 episode 都不注入**。
（低信息承接语扩展只加入最近 user turn 和 open loop，不包含旧 episode。）

### 2.3 提取失败会静默吞掉原文

- 提示词没有告诉模型 240/300/600 字符和 importance 0..3 的限制，但 `isValidDelta` 对任一
  越界**整批拒绝**。
- `parseDelta` 要求 4 个数组和 episode 的 3 个字段全部存在；模型省略空数组、缺 `importance`
  都判非法。
- 失败只 `markExtractionFailure` 退避，没有可读日志；原文保留到 `MAX_TURNS_SAFETY = 64` 后
  被 `trimRawTurns` 删除，且删除时没有日志。
- 结果是"模型输出稍自由 → 记忆系统长期不产出 → 64 轮后原文也没了"。

### 2.4 即时纠错会误删 fact

`ImmediateMemoryReconciler.matches` 原本只要 query token 与 fact/episode 有**任意一个**
token 重合就删除；`isCorrection` 又对"其实/不再/不喜欢"等常见词触发。例如
"其实我今天想挖矿" 只要与某条 fact 共享"今天"就会把该 fact 删掉，而 fact 是唯一常驻注入层，
删掉后不会自动恢复。

### 2.5 本轮已修复

- episode 上限 600 → 1200 code point，提取提示词改为保留"主线、约定、物品/数量、地点、
  未完成事项"，允许摘要内分点；同时明确写出 240/300/1200/0..3 的限制并要求空字段写空数组。
- 零词法命中时回退注入最近 2 条 episode（`withRecencyFallback`），避免"换个说法就失忆"。
- `parseDelta` 容忍缺失的空数组 / 空 episode 字段；结构合法但没有 durable 变化的响应不再
  记为失败，不清退避、不删除原文。
- 提取 delta 被拒时记录 warn；安全上限丢弃未提取轮次时记录 warn。
- 即时纠错改为：明确"忘掉"允许单 token 命中；一般纠正必须共享至少 2 个有意义 token 才删除。
- 提取提示词要求复用已有 fact id、不要重复新增，并把"不要写普通方块坐标"明确交给运行时
  地点记忆负责。

---

## 三、症状二：上下文增长快、消耗大

### 3.1 预算实际上没有生效

`ba43183`（agent runtime）把共享的 `contextInputBudgetTokens` 默认值从 **24,576 调到 98,304**。
但 agent 的压缩条件仍是 `estimate > target * 0.8 → 压到 60%`：

- `target ≈ 98,304 - schema/图片预留 ≈ 86K`，触发点约 **69K**。
- 实测峰值 27.4K，**从未触发压缩**（而旧设计在 24K 预算下约 19K 就开始压缩）。
- 估算器系统性高估约 1.9 倍（CHAT 估算 12,241 vs 实际 6,355），且校准只允许 `>=1.0`，
  进一步把压缩阈值推后。

### 3.2 每轮重放全量快照与全量历史

- `open_gui/inspect_gui` 总是返回 `snapshot_kind=full`，投影保留每个槽位；该快照在后续每一轮
  都全量重发（唯一一份 full 快照不会被 `AgentContext.compact` 降级）。
- 工具循环每轮重发 system + tools + 全部历史 + 全部工具结果，只有 system 前缀可缓存。
- 任务 checkpoint 每轮重建，`completed_net_transfers`（≤256 项）、`recent_evidence`
  （4×2048 字符）等字段无界重发。

### 3.3 缓存前缀只有 Anthropic 路由做了尾部化

`AnthropicPromptLayout` 把逐轮变化的 system（记忆、checkpoint、提醒）移到 tool_result 之后的
runtime context，实测 CHAT 命中 82.7%、循环内命中随轮次增长（11,136→16,000）。
而 OpenAI / ChatGPT Responses 路由仍原位序列化这些逐轮变化的 system 消息，实测缓存恒定
3,712–3,968（仅 system 部分），TASK 命中率只有 21%。

### 3.4 本轮已修复

- 新增**任务执行独立预算** `[agentRuntimeV1].executionContextBudgetTokens`，默认 **32,768**：
  agent 的 80%/60% 压缩在约 26K 开始生效，不再等到 69K；普通聊天仍使用
  `[llm].contextInputBudgetTokens`（对聊天而言它只是上限，不影响固定成本）。
- 地点记忆的注入放在受保护的 memory declaration 内，不额外增加消息条数；无地点时成本为 0。

### 3.5 第二阶段（尚未实施，需要设计确认）

1. 把 OpenAI/ChatGPT 路由也改成"稳定前缀 + 尾部 runtime context"，与 Anthropic 一致；
   `AgentContext.compact` 不再改写已发送过的历史消息（append-only）。
2. `open_gui/inspect_gui` 成功后下一轮即降级为非空槽 + 增量 + `result_ref`，只有当前打开的
   revision 保留 full；预计每轮省 3.5–4.2K token（GUI 循环的 25%）。
3. 按能力/场景裁剪 system 固定段：vision 关闭时去掉 2,602 字符；前台 CHAT 去掉 GUI 操作段
   （agent runtime 开启时前台本来不允许 GUI 工具，细节已在 `AgentTaskInstructions.POLICY`）；
   预计每请求省 350–600 token。
4. 前台闲聊只挂 2–3 个核心工具；GUI schema 按阶段惰性加载，任务结束释放。
5. checkpoint 改增量（只发自上次以来的变化），transfers/evidence 摘要化。
6. 重新评估 `outputBudgetTokens=65,536`、`maxToolRounds=256` 的默认值；估算器校准允许
   小于 1.0 的因子。
7. 预算感知模型的真实上下文窗（站点/模型声明 context window）。

---

## 四、症状三：常用方块记不住位置

### 4.1 现状：坐标只活在当前回答链和短缓存里

| 数据 | 存哪 | 活多久 | 后续轮次可见 |
| --- | --- | --- | --- |
| 本轮 scan JSON（origin + 相对坐标） | callback messages / TaskResultStore | 本轮；任务内经 read_task_result | 仅本轮 |
| pose 扫描缓存 | `VisionScanCache.CACHE` | **10 tick**，且 key 含姿态/位置/区域版本 | 同姿态才透明命中 |
| 已存 scan 分页 | `OBSERVATIONS` | 32 条 / 5 分钟 | 需 observation_id |
| 截图 + scan 快照 | `ObservationSnapshotCache` | 10 张 / 1 小时 | review_observation（vision 默认关闭） |
| 最近轮工具结论 | `ConversationTurn.toolOutcomes` | 512 code point / 轮 × 最近 8 轮 | 坐标通常已被截掉 |
| facts / loops / episodes | 女仆 NBT | 跨存档 | 但提取提示词禁止写入地点 |
| target binding / 决策观察 | TaskCallback | 一个任务代际 | 否 |

### 4.2 直接原因

- 跨轮请求由 `rebuildVisibleMessages` 重建，只重放最近 8 轮原文；`important_blocks` 位于 scan
  JSON 的靠后位置，512 字符的头尾截断保不住。
- 长期记忆层没有结构化地点字段，提取提示词明确禁止把场景观察写 facts。
- 每个任务代际强制 `freshWorld` 并要求本代 scan；`TargetBindingCache` 按代际隔离。
- `MaidGuiSessionManager` 每次 open 都从当前已加载区块枚举容器，或从 maid 位置重跑 BFS；
  交互半径 12（上限 16），提示词还鼓励 `target=nearest`；仓库没有远距离移动工具。

### 4.3 本轮已修复：durable landmark memory

新增 `LandmarkPlace` / `LandmarkMemory` 和 `MaidMemoryState.landmarks()`（上限 32 条/女仆）：

- **写入**（服务端线程、方块 id 一律从真实世界读取，绝不采信模型）：
  - `MaidGuiSessionManager` 成功打开一个方块（非实体、非物品槽）时；
  - `scan_surroundings` 成功完成时，对 `important_blocks` 中带 block entity 且当前确实有
    `MenuProvider` 的方块（≤16 个）。
- **验证**：注入前检查同维度；区块已加载时比对当前 block id，不一致即删除；未加载区块保留，
  下次加载后再验证。
- **注入**：
  - 普通聊天：追加到受 `ContextBudgetPlanner` 保护的 memory declaration（最多 8 行，约
    160–220 token；无地点时为 0），格式含 `registry_id @ x,y,z`、距离、使用次数、多久以前；
  - 任务执行：checkpoint 增加结构化 `known_landmarks`（≤8 行）和"优先用绝对坐标、16 格外
    先走近"的指引；
  - 提示词：GUI 操作段新增一条"优先使用 known landmarks 坐标，`nearest` 只用于未知目标"。
- **持久化**：随女仆 NBT 的 gzip JSON 保存；旧存档缺字段解码为空列表；清空记忆一起清除。
- **成本**：无地点时 0；每请求 ≤8 行，约 0.2% 预算；写入不做模型调用。

限制（有意保留）：

- 交互半径仍为 16 格且没有远距离移动工具，所以 landmark 解决"记不住/重复搜索"，不解决
  "太远到不了"；模型被要求诚实说明需要先走近或由主人带路。
- 未加载区块的 landmark 只能标为未验证，不能强行读取方块状态。
- 如果玩家拆掉并换了方块，验证阶段会删除旧记录。

---

## 五、已经做对、不建议回退的部分

- 提取成功前不删原文；`applyExtraction` 在服务端线程原子执行，写入完成后才删批次；失败不会
  误删。
- 记忆持久化：gzip ByteArrayTag + revision 编码缓存 + CORRUPT/UNSUPPORTED_SCHEMA 备份，避免
  NBT 字符串上限和静默覆盖。
- 基模阻塞式历史摘要已关闭，不会与 AIFun 提取双重摘要；提取 callback 不注册普通请求、不写
  历史、不显示气泡。
- 快速打断时最近 3 条未答输入以 `### Recent interrupted user message` 注入，并参与召回扩展。
- BM25 + CJK bigram/trigram + 结构化实体 + 索引缓存本身正确可用；预算规划器保护
  system/当前 user/open loop/工具协议，淘汰顺序为 episode → 旧轮次 → interrupted → fact。
- Anthropic 路由的前缀尾部化有效（命中率 82.7%），可作为其他协议改造的模板。
- 工具目录/快照按 TreeMap 排序并每轮冻结；`load_tool_schema` 不重复巨大 enum。
- 工具结果有 result_ref + read_task_result 分页；关闭批次可以摘要（只是阈值太高）。

---

## 六、本轮修改清单

| 文件 | 修改 |
| --- | --- |
| `chat/context/LandmarkPlace.java` | 新增：结构化地点记录（dimension/blockId/x,y,z/label/lastSeen/hitCount/source） |
| `chat/context/LandmarkMemory.java` | 新增：upsert/去重/容量淘汰/按维度排序/距离/时间描述/渲染 |
| `chat/context/MaidMemoryState.java` | 新增 `landmarks()` 列表 |
| `chat/context/MemoryStateCodec.java` | 校验 `landmarks()` 非空；旧 payload 解码为空列表 |
| `chat/context/AIFunMemoryManager.java` | episode 1200 上限、召回回退、提取提示词与限制、landmark 写入/验证/注入、未提取轮次丢弃告警 |
| `chat/context/MemoryExtractionCallback.java` | 容忍缺失空数组/episode 字段；合法空 delta 记为 no-op 成功；拒绝时告警 |
| `chat/context/ImmediateMemoryReconciler.java` | 纠正必须 ≥2 个有意义的 token 重合，避免误删 |
| `maid/gui/MaidGuiSessionManager.java` | 成功打开方块后写入 landmark |
| `compat/ai/vision/ScanSurroundingsTool.java` | 扫描到的容器写入 landmark |
| `vision/scan/EnvironmentScanResult.java` | 新增 `originSnapshot()` |
| `chat/agent/TaskCallback.java` | checkpoint 注入 `known_landmarks` + 指引 |
| `mixin/PapiReplacerMixin.java` | GUI 指引增加 landmark 优先规则 |
| `config/TouhouAIFunConfig.java` | 新增 `[agentRuntimeV1].executionContextBudgetTokens`（默认 32768） |
| `chat/agent/AgentContext.java` | 任务执行使用独立预算 |
| `src/test/.../ContextMemoryTest.java` | 新增 7 项：landmark 编解码/合并/淘汰/渲染、召回回退、误删防护、提取容错与新上限 |

验证命令（Java 17）：

```powershell
.\gradlew.bat test --tests "com.wjx.touhou_aifun.chat.context.ContextMemoryTest" --console=plain
.\gradlew.bat test build --no-daemon --console=plain
```

（本轮验证结果见文末。）

---

## 七、剩余风险与建议的下一步

1. **真实运行时验证**：单测只能证明纯 Java 规则。需要在游戏内验证：打开熔炉后下一轮聊天
   能看到 landmark；扫描后任务 checkpoint 出现 known_landmarks；拆掉方块后注入前被删除。
2. **阶段二改造**（缓存前缀 + 快照降级 + 固定提示词裁剪）收益最大，但会改变请求形状，建议
   单独做 A/B，并用 `scripts/agent-token-report.py` 对比命中率与输入 token。
3. **提取质量**需要一次真实 20 轮会话观察：episode 1200 字是否够、是否还出现整批拒绝。
   日志里现在会明确记录拒绝原因和 64 轮丢弃。
4. **安全**：`run/config/touhou-aifun.toml` 的 `[agentRuntimeV1.decisionService.perplexity].apiKey`
   是明文，未纳入本次范围，但建议改为只从服务端配置/环境变量读取，避免随整合包或截图泄露。
5. 关闭后台提取（`backgroundMemoryExtraction=false`）时仍只保留原文、不产生长期记忆；如果
   这是玩家可选项，界面/文档应明确说明"关闭后不会积累长期记忆"。

---

## 八、本轮验证结果

V2 第二批完成后：

- 全仓 `gradlew.bat test build --no-daemon`：**402 项测试，0 failure、0 error、0 skipped**；
  `compileJava`、Mixin AP/refmap、`reobfJar`、`Touhou-AIFun-0.3.jar` 全部通过
  （`BUILD SUCCESSFUL`，jar 约 1.60MB）。
- `ContextMemoryTest` 40 项，覆盖压缩策略阈值、多 episode 解析、landmark/tool-history/env
  编解码、召回回退、误删防护、提取容错等。
- `git diff --check` 无空白错误（仅有 Windows LF/CRLF 转换提醒）。
- **未做真实游戏内运行验证**（本轮不启动客户端/服务端）：landmark 的写入/验证/注入、
  提取的多段 episode 实际质量、475K 预算下的压缩触发与 15K 目标、工具历史查询，
  都需要进游戏复测。运行时项见第七节第 1–3 条与 9.5。

---

## 九、按用户建议的 V2：预算驱动压缩 + 游戏语义 + 工具历史（2026-10-17 第二批）

本批把压缩从"轮次驱动"改成"预算驱动"，并把游戏环境的记忆分类写进压缩产物。

### 9.1 预算与压缩时机

- `[llm].contextInputBudgetTokens` 默认 **475,000**（范围 8,192–2,097,152）。新增
  `[llm].compressionTargetTokens` 默认 **15,000**。
- 新增纯 Java `CompressionPolicy`：
  - 可见上下文 < 70% 预算：不压缩；
  - ≥ 70%：只有"任务较难"（存在未关闭事项或近期工具有结果）或"下一轮明显放不下"时才压缩；
  - ≥ 90%：强制压缩；
  - 压缩后目标为 `compressionTargetTokens`（且不超过预算一半）。
- 不再以 16 轮/最近 8 轮作为提取触发条件；原文按预算保留（安全上限仍为预算 token 数，
  防止提取长期失败时无限增长）。`recentTurns` 只表示压缩后保留多少轮原文。
- 提取批次上限改为 `min(400K, 预算 × 0.85)`；长批次允许 1–6 个按时间分段
  episode，每个 ≤2,500 字符（总计约 15K 字符），而不是一段 600 字摘要。
- 提取提示词新增游戏分类：

  `[对话主线]`、`[玩家目标]`（打 Boss/做物品/建造/探索）、`[委托与项目]`（生产资料、
  工具、生产对象、方法、结果、经验教训）、`[战斗与交互目标]`（最近攻击/采集/打开的目标，
  带位置，过时则丢弃）、`[环境与地点]`（生物群系、维度、基地/路线、常用方块坐标）、
  `[资源与物品]`、`[机制与经验]`、`[关系与偏好]`。

  fact 的 `kind` 限定为 `player_goal / project / commission / preference / relationship /
  environment / interaction_target / combat / route / resource / mechanic / lesson / identity`。
  新增可选 `environment` 字段（≤600 字），作为单条滚动环境摘要注入。
- 注入侧：facts 按 token 预算渲染（≤5,000 估算 token，importance 优先），episode 召回
  上限 6,000 token；压缩后记忆总量落在 15K 目标以内。
- fact 数量上限 64→96；open loop 满 24 时不再整批拒绝，而是丢弃放不下的新事项。

### 9.2 工具调用历史（最近 100 条 + 查询入口）

- 新增 `ToolCallRecord` / `ToolCallHistory`：每条包含 tool、status、gameTime 和
  ≤100 code point 的操作摘要；`MaidMemoryState.toolCalls` 保存最近 100 条，随 NBT 持久化。
- 前台工具结果（`LLMCallbackMixin`）和后台任务工具结果（`TaskCallback.addToolResult`）
  提交后各记录一次；被新请求取代的前台结果不记录。
- 新增核心工具 `read_tool_history`：支持 `offset/limit/tool`，按最新优先分页返回，
  不会被每轮注入。提示词与工具目录默认不占用 token，只有模型主动查询时才读取。

### 9.3 环境摘要

- `scan_surroundings` 成功后写入滚动环境摘要：维度、扫描原点、生物群系、可见重要方块
  （≤8）、附近实体（≤6），≤600 字；下一次请求以 `### Environment memory` 注入。
- 地点记忆沿用上一批的 landmark：开箱/工作台成功与扫描容器时写入，注入前按真实 block id
  校验。

### 9.4 兼容与迁移

- 旧存档缺 `landmarks/toolCalls/environment` 字段时解码为空，不触发损坏状态。
- **旧配置文件不会被自动改写**：`run/config/touhou-aifun.toml` 里已有的
  `contextInputBudgetTokens = 98304` 会继续生效。要使用 475K 默认值，请手动改为
  `contextInputBudgetTokens = 475000`（或删除该行/配置文件让其重新生成）。
- `[agentRuntimeV1].executionContextBudgetTokens`（默认 32,768）控制后台任务执行预算，
  与聊天预算分离，避免任务把 475K 全部重放。

### 9.5 成本提示

预算放宽后，单轮重放的输入 token 会增加，节省来自前缀缓存（Anthropic 路由已实测
82.7% 命中）与更少的压缩事件。建议改完后用
`python scripts/agent-token-report.py run/logs/latest.log` 对比命中率、未命中输入和费用；
若所用模型实际上下文不足 475K，请把该值降到模型上限以下（预算只影响压缩时机，
不会强制发送不存在的历史）。
