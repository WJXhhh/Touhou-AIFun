# Touhou AIFun 分层上下文管理复核记录

日期：2026-08-07  
复核范围：上下文管理提交 `518fed8` 及其在当前 `HEAD`（`bcfd467`）中的实际状态  
复核性质：代码审查、分批修复、构建与单元测试验证；后文保留每轮发现及修复状态

## 结论

当前实现的总体架构与原计划方向基本一致：已经具备独立记忆状态、分层消息重建、本地召回、token 预算、后台提取、请求代际以及工具 schema 瘦身等主要骨架。

但它目前还不能视为达到原计划的验收标准。主要问题不是“少几个功能”，而是若干关键链路存在时序竞态、提取触发不可达、NBT 容量风险，以及工具循环绕过预算规划等正确性问题。`gradlew test` 和 `gradlew build` 通过只能说明当前代码可编译、现有少量测试通过，不能证明打断、长对话、工具循环和存档安全。

## 最高优先级问题

### 1. Anthropic 的服务端工具续轮可能夺走新请求

位置：`AnthropicCompatLLMClient.retryTurn`

服务端 `web_search` 需要续轮时，代码异步创建了一个新的普通 `LLMCallback`。如果 A 请求安排续轮后，玩家先发起了 B 请求，而 A 的续轮 runnable 随后才执行，这个新 callback 可能绑定到 B 的当前 turn，取消 B 的 future，并取代最新 callback。最终 A 的答案甚至可能被提交到 B 的轮次。

此外，这种重建 callback 的方式会丢失原 callback 的已加载工具 schema 集合，并可能创建额外的等待气泡。

应让 Anthropic 续轮复用原 `RequestState`、`turnId`、callback 与工具选择快照；执行续轮前再次检查 superseded，不能把协议续轮注册成一次新的普通聊天请求。

### 2. “16 轮触发后台提取”实际上不可达，原文会先被裁掉

位置：`AIFunMemoryManager.beginTurn`、`trimRawTurns`、`shouldExtract`

- `beginTurn` 会立即裁剪原文。
- `trimRawTurns` 把已完成原文限制为最近 12 轮。
- `shouldExtract` 却要求未压缩已完成轮次达到 16 轮。

正常短对话中，完成轮次会在达到触发阈值前被删掉，因此 16 轮条件基本无法成立。现有 75% 条件又主要估算用户文本，没有覆盖 system、assistant、事实、事项、事件和工具 schema，会显著低估下一轮输入。

更严重的是，提取失败或处于退避期间，裁剪仍可能删除尚未成功提取的原文，违反“失败时保留原文”的计划要求。

建议允许待提取原文暂时增长到触发阈值，成功应用提取结果后再收敛到 12 轮；75% 判断应复用完整的下一轮预算估算。

### 3. 用单个 NBT StringTag 保存整个状态，容量不足

位置：`MaidAIChatDataMixin`、`MemoryStateCodec`

当前实现先把整个 `MaidMemoryState` 序列化为 JSON，再放入一个 `CompoundTag.putString`。NBT 字符串序列化受 modified UTF-8 长度限制，单项大约不能超过 65,535 字节。

计划容量仅 128 条、每条 600 字符的中文事件就可能超过 200 KB，尚未计算事实、事项、近期轮次和工具结论。达到容量上限时可能在女仆或世界保存过程中抛异常，属于存档安全风险。

应改为结构化 `CompoundTag`/`ListTag`，或使用有明确总上限的分块/压缩字节数组，并增加超过 64 KB 的保存与重新加载测试。

### 4. 一进入工具循环，24K 规划器就被整体跳过

位置：`ReasoningCompatOpenAIClient`、`AnthropicCompatLLMClient`

两套客户端目前仅在消息中不存在 `Role.TOOL` 时调用上下文规划器。工具结果一旦出现，后续 agent 轮次就不再做预算裁剪。超长工具返回很容易直接把请求推过模型上下文限制。

原计划要求的是把当前尚未完成的 tool call/result 组视为不可拆分的原子组，在保留它的同时依次裁剪历史事件、旧轮次和低优先级事实，而不是绕过规划器。

工具 schema 的预算也不是按实际序列化内容计算：当前估算主要使用目录摘要和每工具固定开销，没有完整计算参数 schema、enum，以及 Anthropic 独立的 `web_search` 声明。

### 5. 新请求开始后，旧流式 TTS 仍可能继续播放

位置：`ChatFlowManager.beginTurn`、`StreamingTtsReply.onSynthesized`

新 turn 开始时会替换普通请求并取消 LLM future，但没有立即推进 TTS generation、发送 TTS interrupt 或清理旧实时气泡。已经并行发出的最多三项 TTS 合成，只按 TTS generation 检查，不检查 callback 是否 superseded，因此 A 被 B 打断后，A 的迟到音频仍可能继续下发，直到 B 的答案真正开始接管 TTS。

旧的等待/实时气泡也可能残留，与 B 的思考气泡同时存在。

新 turn 建立时就应失效旧 TTS 代际、停止客户端播放并移除旧 callback 对应的临时气泡，而不是等新答案返回后再接管。

### 6. 工具调用历史写入仍存在跨线程 TOCTOU 竞态

位置：`LLMCallbackMixin.onFunctionCall`、基模 `LLMCallback.onFunctionCall`

文本完成路径已经回到服务端线程再提交，但工具调用路径主要依赖 ThreadLocal guard。基模会在 HTTP 完成线程上立即把 assistant tool-call 写入历史。

可能出现这样的时序：A 检查时尚未 superseded；B 随后写入 user history；A 再写入 assistant tool-call，从而得到 `user B → assistant tool-call A`。

检查代际、写入工具调用历史和安排工具执行应放进同一个服务端线程事务式步骤中，确保顺序一致。

### 7. 压缩工具结论没有进入未来轮次上下文

位置：`ConversationTurn.addToolOutcome`、`AIFunMemoryManager.rebuildVisibleMessages`

工具结果会被截断后记录到 `toolOutcomes`，但重建近期轮次时只注入 user/assistant 文本，完全忽略工具结论。因此后续轮次既不回放原工具协议，也看不到计划中的压缩结论。

当前实现还是“每个工具结果最多 512 字符、最多多条”，而计划更接近“该轮形成一个最多 512 字符的工具结论”。应明确聚合规则并在后续上下文中注入这一条结论。

## 召回、生态和规范性问题

### 本地召回不是计划中固定的 BM25/实体评分

位置：`LocalMemoryRetriever`

当前实现更接近集合重合加 IDF 权重，没有 BM25 的词频与文档长度归一化。CJK 还额外加入 unigram，弱相关的单字重合可能产生噪声。实体识别会把普通多字符英文词也当作完整实体给予高额加分，使常见英文词获得不合理的 `+2`。

事实也没有按原设想参与查询特征构造，目前主要是当前问题和未关闭事项参与查询。

### 第三方工具 trigger 调用方式可能不兼容生态

位置：`ToolContextSelector.isTriggered`

选择器以 `tool.trigger(maid, null)` 探测工具，但基模 `ITool` 的契约允许工具读取实际 `ChatCompletion`。第三方工具若使用该参数，可能抛异常并被静默隐藏。

OpenAI 与 Anthropic 共享同一个 selector 是正确方向，但应传递兼容的实际请求上下文，或提供明确且安全的目录枚举接口。

### 其他偏差和不严谨点

- 已关闭事项目前会在裁剪时被全部删除，而不是仅在超限时按低重要度、最旧优先淘汰。
- 只读 legacy summary 主要在写 NBT 时同步，运行期间原历史界面可能看不到最新展示摘要。
- 计划配置名是 `llm.recentTurns`，实现使用 `llm.memoryRecentTurns`，可能造成文档和配置兼容性偏差。
- 语义冲突未被本地强制处理：若提取模型新增纠正后的 fact 却漏发 delete，旧、新冲突事实可能同时注入。
- 一次后台提取批次没有明确 token 上限，积累后提取请求本身也可能超过模型上下文。
- 完成轮次和被打断轮次混在同一批时，只要其中存在一个 interrupted，聚合 episode 可能整体被标成“请求被打断、未执行”，误标正常事件。
- importance 超出 0–3 时当前倾向于 clamp，而不是按严格 schema 拒绝结果。
- 规划器依赖消息文本前缀识别事实/事件类型；普通历史内容若恰好以这些前缀开头，可能被误裁并造成 user/assistant 配对破坏。
- `ContextTokenEstimator` 没有完整计算 `LLMMessage.toolCalls()` 的工具名和参数，工具组预算会继续被低估。
- 多个按 maid 保存的静态 map 缺少明确的女仆卸载清理路径，长期运行并接触大量女仆后可能保留 callback/future 引用。
- `clearAllChatMemory` 清理数据时没有同步取消普通请求与后台提取；迟到回复可能在清空后重新写入历史或显示气泡。
- AIFun 历史中包含自身的 reasoning 编码尾部和 `chat---tts` 双段封装；切换到基模原生站点时，需要验证不会把内部封装原样发送给模型。

## 与原计划的符合度

| 模块 | 结论 | 说明 |
| --- | --- | --- |
| 请求代际与轮次 | 部分符合 | exact runtime type、防重复注册等骨架正确，但 Anthropic 续轮、工具历史和 TTS 仍有竞态 |
| AIFun v1 记忆/NBT | 部分符合 | 独立 key、schema、迁移框架存在，但单 StringTag 无法承载计划容量 |
| 24K 上下文规划 | 部分符合 | 初始普通请求可规划，但工具轮跳过规划，schema/tool call 估算不足 |
| 后台结构化提取 | 未达到计划 | 16 轮条件被 12 轮裁剪阻断，75% 估算不完整，失败时原文也可能被删 |
| 本地相关性召回 | 近似实现 | 有确定性本地排序，但不是计划规定的 BM25/实体特征和固定评分 |
| 工具与技能瘦身 | 部分符合 | 两协议共用 selector、技能 top-8 描述等正确；第三方 trigger 与实际 schema 预算有问题 |
| 测试与验收 | 未达到计划 | 当前只有少量纯 Java 测试，关键并发、存档、连续 20 轮和运行时场景未覆盖 |

## 已确认实现正确或方向合理的部分

- 消息层级顺序总体正确：角色 system、记忆声明、事实/事项、相关事件、近期完整轮次，随后由基模加入当前 `<context>` 和用户消息。
- 基模完整 history 与旧 compressed summary 不再直接参与实际请求。
- 当前版本通过 `HistorySummaryManagerMixin` 关闭了基模阻塞式摘要路径。
- 普通请求采用 exact runtime type 判断，`MemoryExtractionCallback`、历史摘要和知识类 callback 不会登记成普通聊天请求。
- `LLMCallbackMixin` 已有一次性注册保护，避免构造链重复登记请求。
- 独立 NBT key、schemaVersion、迁移以及清空框架已经存在。
- token 估算器、按 `apiType + model` 的 EWMA 校准和安全系数方向正确。
- 后台提取 callback 关闭工具、气泡、TTS 和普通请求登记，也不会覆盖普通聊天的 `lastChatTokenUsage`；实际 provider token 仍会计入额度。
- JSON 增量结果会校验基本字段、引用 ID、批次 turn ID 与 revision；非法结果通常不会应用状态。
- OpenAI 与 Anthropic 共用同一工具选择器，Anthropic server `web_search` 仍保持独立声明。
- 技能目录保留全部技能名，只给相关度最高的 8 个完整 description，`use_skill` enum 仍包含全部技能。

## 本轮验证结果

- `gradlew test --no-daemon`：通过，现有 5 个测试全部成功。
- `gradlew build --no-daemon`：通过，编译、refmap、reobf jar 均成功。
- 现有测试主要覆盖 token 粗估、单个召回样例、interrupted 状态、空增量和 codec 往返；没有覆盖原计划列出的关键验收场景。

## 建议修复顺序

### 第一阶段：先保证时序与存档正确

1. 建立真正贯穿普通请求、工具循环和 Anthropic 续轮的每 maid `RequestState`，续轮不得新建普通 callback。
2. 把完成检查、工具历史写入和工具调度统一放到服务端线程，并进行原子式 turn 校验。
3. 新 turn 开始时立即失效旧 TTS、停止音频并清理旧实时气泡。
4. 修正提取触发与原文保留顺序，失败和退避期间不得删除未提取原文。
5. 把单 StringTag 持久化改为结构化或安全分块格式。

### 第二阶段：补齐真实预算闭环

6. 每一个工具 agent 轮次都运行规划器，把未完成工具组作为不可拆分单元。
7. 使用实际序列化后的工具 schema 和 tool call 参数估算 token。
8. 将每轮聚合后的最多 512 字符工具结论明确注入未来上下文。

### 第三阶段：提升召回质量与生态兼容

9. 按计划实现确定性的 BM25、CJK bigram/trigram 和严格实体提取。
10. 每 turn 固定工具目录/已加载 schema 快照，并用兼容的 completion 上下文调用第三方 trigger。
11. 修正 closed loop 淘汰、summary 同步、配置键兼容和冲突 fact 策略。

### 第四阶段：补验收测试

至少补充以下可重复测试：

- A/B 非流式、流式和工具调用交错打断。
- Anthropic `web_search` 续轮与新请求竞争。
- 旧 TTS 合成迟到后不能下发音频。
- 两名女仆并发时 turn、工具集合和记忆隔离。
- 连续 20 轮触发提取、失败退避及过期 revision。
- 超过 64 KB 的记忆保存、加载和清空。
- 超长工具结果下的 24K 规划与原子工具组保留。
- AIFun 与基模原生站点切换后的历史格式兼容。

## 当前判断

这套实现适合继续在现有附属模组内修正，不需要修改 `E:\forge-dev\touhou` 基模。Mixin、附加 NBT、客户端请求构造和 AIFun 自有状态已经能够覆盖大多数落点；但在完成上述第一、第二阶段之前，不建议把它标记为“按原计划完成”或直接依赖其进行长时间正式存档。

---

## 第二轮增补复核：遗漏、性能与游戏手感

第二轮沿实际游玩链路重新检查了：快速连续发言、工具对世界的副作用、跨语言 TTS 历史、后台整理与主聊天竞争、原生站点切换、历史界面、多人服务器负载，以及“记住/忘掉/继续上次话题”的实际体验。

新增结论是：第一轮列出的时序和持久化问题之外，当前设计还存在若干玩家能直接感知的缺口。其中“旧工具仍继续执行”“原生客户端的工具预算不正确”“正式历史混入翻译段和 reasoning”“记忆纠正生效过慢”应提升到第一阶段处理。

## 第二轮新增高优先级问题

### 1. 打断只丢弃旧结果，不能阻止旧工具继续改变世界

位置：基模 `LLMCallback.executeToolBatch` / `executeSingleToolCall`，AIFun `LLMCallbackMixin.addToolResult`

当前 superseded 检查主要发生在 LLM 返回、工具结果写入和视觉工具完成处。基模的多工具批次却会按顺序继续执行：A 的第一个工具运行期间玩家发出 B 后，第一个工具的返回可以被丢弃，但批次仍可能启动第二、第三个工具。

这意味着旧指令虽然“不再回答”，仍可能继续切换跟随状态、工作任务、日程或执行其他第三方世界操作。对玩家而言会表现为“明明已经打断，女仆还在照上一句话做事”，比历史错序更明显。

同一批次产生的 side callback 也缺少统一的 turn 取消令牌；基模单子代理分支还可能直接调用 `addToolHistory`，绕过当前只覆盖 assistant history 的 guard。

建议：

- `RequestState` 增加 cancellation token，并在每个工具调用前、异步工具完成后、启动 side callback 前检查。
- superseded 后停止批次中尚未开始的工具，不再启动下一项。
- 对已产生不可逆世界副作用的工具定义清晰语义：不能回滚，但必须阻止后续动作。
- 给第三方工具提供可选取消回调；没有取消能力时至少做到结果隔离和后续批次停止。
- 同时 guard `addToolHistory`，而不只 guard 两个 `addAssistantHistory` 重载。

### 2. 基模原生 LLM 客户端的工具 schema 没被正确计入 24K

位置：`LLMCallbackMixin`、`AIFunMemoryManager.rebuildVisibleMessages`、基模 `LLMOpenAIClient`

普通 callback 构造时，无论实际站点是不是 AIFun 客户端，都会使用 `ToolContextSelector.schemaBudget`。这个预算代表 AIFun 的“核心工具 + 紧凑目录 + 按需 schema”。

但基模原生客户端保持原有逻辑，会直接注册所有已触发工具的完整 schema，并不会使用 AIFun selector。因此：

- 原生站点可能发送远大于预留值的完整工具集合。
- 原生客户端没有 AIFun 当前的真实 `prompt_tokens` 校准入口。
- 工具较多的整合包中，所谓 24K 输入目标可能失效并直接触发 provider 上下文错误。

这与“原生客户端获得分层历史，但保留自己的工具构造逻辑”的计划并不矛盾；缺少的是按真实客户端构造方式计算预算。

建议按客户端分支计算实际序列化 schema：AIFun OpenAI/Anthropic 使用共同 selector；基模原生客户端按它实际会发送的全量工具计算。不能用 AIFun 的 4K–8K 固定预留替代原生工具预算。

### 3. 近期 assistant 原文包含显示文本、TTS 翻译和 Base64 reasoning，浪费大量上下文

位置：`LLMCallbackMixin.touhouAIFun$finishAcceptedTurn`、`StreamingTtsReply.finish`、`ReasoningOpenAIResponseChat.toString`、`ReasoningContentCodec`

正式轮次当前保存的是 `response.toString()`，不是单独的显示语言答复。该字符串可能同时包含：

- `chatText---ttsText`；
- 跨语言时的一整份翻译；
- `[[TLM_REASONING_CONTENT_BASE64:...]]` reasoning 尾部。

结果是最近 8 轮上下文可能把每条回复近似发送两遍，并额外携带 Base64 膨胀后的推理文本。它直接损害本项目最初追求的“低消耗、快响应、对有效历史保持注意力”。切换到基模原生客户端时，原生客户端还不会解码这层封装。

当前工具链内保留 reasoning 和双段协议有兼容价值，但工具链结束后不应把整份 wire envelope 当作跨 provider 的长期 assistant 文本。

建议把轮次拆成：

- `assistantChatText`：唯一参与未来普通上下文的正式显示语言回答；
- 可选 `ttsText`：只用于当轮朗读/调试，不进入未来上下文；
- 可选短 `reasoningMetadata`：默认不持久化，不进入未来普通对话；
- 当前未完成工具组仍在 callback messages 内保留 provider 所需 reasoning/tool protocol。

这项调整通常会比继续微调 token 估算器更直接地降低输入消耗。

### 4. 活跃聊天会让后台提取长期饥饿

位置：`MaidMemoryState.revision`、`AIFunMemoryManager.applyExtraction`、`recordPromptCalibration`、`beginTurn`

提取快照校验使用一个全局 revision。开始新 turn、完成回复、更新 token 校准、失败退避等多种变化都会推进同一个 revision。

因此只要玩家在提取完成前继续说下一句话，旧提取结果就会被判过期。玩家持续聊天时，每次提取都有较大概率被下一轮使其失效，记忆整理可能一直无法成功。token 校准这类与事实内容无关的变化也会让语义提取失效，校验粒度过粗。

另有一个小竞态：`MemoryExtractionCallback.onFailure` 先把“失败状态更新”提交到服务端线程，却立即在当前线程移除 `EXTRACTION_RUNNING`。两者之间可能短暂允许同一女仆启动另一项提取，破坏“最多一个任务在途”。

建议：

- 拆分 `semanticRevision`、`turnRevision`、`telemetryRevision`，token 校准不应使事实提取过期。
- 应用时校验批次 turn IDs 仍存在、相关 fact/loop 版本未冲突，而不是要求整个 state 完全没有任何变化。
- 新聊天只使冲突记录失效；无冲突的旧批次仍可合并。
- `finishExtraction` 与失败计数更新放入同一个服务端线程 `finally`。
- 主聊天到来时可以暂停或取消低优先级提取并保留原文，避免与玩家请求争抢 provider 限流和连接。

### 5. 基模 history 仍持久化完整工具原文，存档和历史界面会继续膨胀

位置：基模 `MaidAIChatData.addToolHistory` / `writeToTag`，`HistoryAIChatScreen`

AIFun 的新请求虽然不再回放基模完整 history，但基模仍会保存最多 512 条历史消息，其中包括完整工具返回、tool call 参数、双段回答和 reasoning 编码。

所以“未来请求不发送工具原文”并没有解决以下问题：

- 女仆实体 NBT 仍可能被单个超长工具结果撑大；
- `SyncMaidAIDataMessage` 会把 history 发送到客户端，打开/同步 AI 页面时产生大包；
- 历史界面会尝试排版超长 JSON、`---` 翻译段和 Base64 reasoning，可能卡顿且可读性很差；
- 单条原始字符串仍可能碰到 NBT 字符串自身的长度限制。

建议保持 callback 自身的当轮工具协议完整，但写入 legacy history 时改为面向展示的截断结果；更理想的是为历史 UI 提供 AIFun 的只读轮次视图，工具只显示名称、状态和可展开短摘要。reasoning 编码及 TTS 翻译不应出现在玩家历史正文中。

### 6. AIFun 记忆跟随基础 AI 配置包一起同步到客户端

位置：`MaidAIChatDataMixin.writeToTag`、基模 `SyncMaidAIDataMessage`

`TouhouAIFunMemory` 被写进与基模配置相同的 `CompoundTag`。基模在同步女仆 AI 数据到客户端时也调用该 `writeToTag`，因此完整服务端长期记忆会随设置同步包一起传输。

即使修正单 StringTag 限制，128 个事件、事实、原文轮次和工具信息仍会放大设置界面的网络包、客户端内存和 JSON 解码开销。客户端实际上只需要展示摘要与近期可见历史，不需要完整提取状态、校准值和 source IDs。

建议把“服务端持久化状态”和“客户端只读展示 DTO”分开。若受限于不能修改基模网络包，可由附属模组增加精简同步消息，或至少不给客户端写入 token 校准、提取状态和完整 episode 正文。

### 7. “改口、忘掉、任务完成”生效至少滞后若干轮

位置：`AIFunMemoryManager.extractionBatch`、`buildMemoryMessages`

后台提取会保留最近 8 个完成轮次不压缩。这意味着玩家刚说出的：

- “我不喜欢红茶了”；
- “忘掉刚才那件事”；
- “那个任务已经完成，不用再提醒”；

不会立即更新或删除已有 fact/open loop，要等该轮离开最近 8 轮后才可能进入提取批次。期间旧 fact/open loop 仍以 SYSTEM/DEVELOPER 级消息注入，协议优先级甚至高于最近 user 历史；一句“最新用户优先”的文字声明不能完全消除角色层级带来的偏向。

这会导致女仆口头答应“记住了/忘掉了”，下一轮却仍按旧记忆回答，是明显的信任损伤。

建议把“近期增量维护”和“旧事件压缩”拆开：

- 明确的纠正、忘记、取消、完成语句触发小型定向增量处理或本地临时 suppression。
- recent 原文仍保留，但已被当前纠正命中的旧 fact 暂停注入，直到提取确认。
- 未完成事项的关闭可以即时记录候选状态，不必等待 episodic compaction。
- 长期可增加只读记忆页或自然语言 `查看记忆/忘掉某项` 能力；无需第一版就做完整编辑 GUI。

### 8. 流式 TTS 没有遵守“按句流式”开关，而且可能提前朗读工具前言

位置：`StreamingTtsReply`、`ReasoningCompatOpenAIClient.acceptStreamLine`、`AnthropicCompatLLMClient.acceptStreamLine`

只要 LLM SSE 与网络 TTS 可用，`StreamingTtsReply` 就会按已完成句子提前合成；它没有检查 `TTS_SENTENCE_STREAMING`。玩家在设置中关闭“按句流式”后，普通 TTS 路径会尊重设置，但 LLM 流式路径仍会提前逐句朗读，配置语义不一致。

此外，模型可能先输出一句“我来看看”，随后才产生 tool call。工具块到达之前，累计器暂时认为“没有工具调用”，这句前言可能已经开始合成甚至播放；随后工具执行，最终答案又朗读一次。流中途失败时，也可能已经说出一段未被正式提交的草稿。

建议提供两种明确模式：

- 稳健模式：有工具能力的轮次等本轮确定为纯文本后再朗读，或只做最终 TTS。
- 低延迟模式：允许首句提前播放，但设置中明确说明“草稿可能在工具调用/失败前先说出”。

无论哪种模式，`TTS_SENTENCE_STREAMING=false` 都应真正关闭 SSE 早期逐句合成。

## 第二轮新增兼容性与质量问题

### 额外 SYSTEM 消息可能被静默丢弃

`rebuildVisibleMessages` 只保留 `original.get(0)` 作为角色 prompt。若其他附属模组或未来 TLM 版本在原消息列表中加入额外 system/developer guardrail、玩法信息或兼容提示，它们会全部消失。

更稳妥的方式是保留所有“非基模摘要”的前导 SYSTEM 消息，并只精确排除已知的 legacy compressed summary。这样既满足不回放旧摘要，也不破坏其他附属的消息扩展。

### 第三方工具目录在大型整合包中仍可能很大

当前目录为每个可用扩展工具发送名称和最长约 180 字符摘要，`load_tool_schema` 自身的参数 enum 又重复列出全部工具名。工具数很多时，目录和 enum 本身就可能达到数千 token，固定 4K–8K schemaBudget 仍不可靠。

可以采用自适应策略：高相关的 1–3 个扩展工具直接预载完整 schema；其余发送名称或短摘要；极大目录用 `search_tool_catalog`/分页目录，而不是把所有内容每轮重复发送。

### 工具描述与触发器被重复计算

一次 agent 轮次中，目录构造、schema 预算、实际请求构造和 usage 校准会多次调用第三方 `trigger` / `summary` / `parameters`。这不仅增加服务端主线程耗时，第三方 hook 若有状态或依赖实际 `ChatCompletion`，还可能在几次调用间给出不同结果。

应在 turn 开始时生成不可变 `ToolCatalogSnapshot`：一次触发、一次 schema 序列化、一次 token 估算，OpenAI 与 Anthropic 都消费同一快照；资源重载只影响下一 turn。

### 提取输出语言可能破坏中文召回

记忆提取提示没有要求 episode/keywords 保留原对话语言和精确实体。若主模型把中文事件总结成英文，本地 CJK bigram/trigram 查询将很难把它召回；Minecraft registry ID、玩家称呼或物品名被翻译后也会失去完整实体加分。

提取协议应明确：摘要优先使用原对话主要语言；keywords 同时保留原词；玩家名、数字、`namespace:id`、带下划线标识符必须逐字复制。

### 最近轮次配置范围与实际保留量矛盾

`llm.memoryRecentTurns` 允许 2–32，但 `trimRawTurns` 固定只保留 12 个已完成轮次。因此配置为 13–32 不会真正注入相应数量，属于“看似生效、实际无效”的配置体验。

应将范围收敛到 2–12，或让原文安全保留量至少为 `max(12, configuredRecentTurns)`；后一种更符合现有配置范围，但需要重新评估 NBT 上限。

### Unicode 截断可能切坏 emoji

`limit`、`ConversationTurn.addToolOutcome` 和 `displaySummary` 使用 UTF-16 `substring` 截断，而长度校验部分使用 code point。emoji 等补充平面字符可能在边界被切成孤立代理项，显示为乱码并污染 JSON/NBT。

所有容量限制应统一按 code point 截断，并在最终序列化前校验 UTF-8 字节数。

### 损坏或未来版本记忆会被静默重置

`MemoryStateCodec.decode` 遇到非法 JSON、字段缺失或未知 schemaVersion 时直接返回空状态，没有日志或备份。存档损坏和版本降级会表现为“女仆突然失忆”，难以排查，并可能在下次保存时覆盖原数据。

建议记录一次明确警告，把无法读取的原 payload 保留为备份 tag，并对未来 schemaVersion 采取“只读保留、不覆盖”策略。

### 接口与计划仍有小偏差

计划中的 `AIFunMemoryAccess` 包含读取、替换、清除状态；当前接口只有 get/set，清理由 Mixin 注入单独完成。功能可以工作，但不利于测试和统一生命周期管理。建议补齐 `clear`，并让清空操作同时负责请求取消、提取取消、TTS 失效与静态 map 清理。

## 服务端性能与响应速度优化

以下不一定是 correctness blocker，但对“首句快、低 tick 抖动、低消耗”很有价值。

### 1. 为本地召回建立 revision 缓存

`LocalMemoryRetriever` 每轮重新为最多 128 个 episode 分词并统计 document frequency。满容量中文事件可能达到数万字符，这些 HashSet/bigram/trigram 分配发生在聊天发起的服务端线程上。

建议按 memory semantic revision 缓存：episode token 集、实体集、文档长度、DF/IDF 和倒排索引。查询时只对当前问题分词并访问候选 posting；事件新增/删除时增量更新。

### 2. 预算裁剪改成一次计算和增量扣减

当前存在两次规划：`rebuildVisibleMessages` 先固定预留 6144，callback/client 又按 schemaBudget 再裁一次。第一轮已经删除的事实/事件无法在第二轮预算较宽时恢复，短问题也会无谓损失上下文。

同时 `ContextBudgetPlanner` 每删除一项都会重新扫描整份消息估算 token。应在“当前 user/context 已加入、实际 tool snapshot 已生成”后只规划一次，并缓存每个原子单元的 token，删除时从 running total 扣减。

### 3. 在构建前先选择，而不是先生成全部再裁

当前会先把最多 64 个 fact、24 个 loop、6 个 episode 全部变成 `LLMMessage`，再由规划器逐项删除。可以先根据可用预算选出记录，再序列化消息，减少字符串和消息对象分配。

### 4. 缓存持久化表示

长期状态每次 `writeToTag` 都重新编码完整 JSON，并在同步路径重复执行。修正结构化 NBT 后，可以按 persistence revision 缓存不可变 tag；状态未改变时直接复制缓存。token 校准不要触发 semantic index 重建。

### 5. 给后台提取增加全局低优先级队列

当前只限制“同一女仆一个任务”，一个玩家或服务器上的多名女仆仍可同时触发提取，导致 API 并发、429、费用尖峰和主聊天首 token 延迟。

建议全服限制少量并发（例如 2–4），主聊天优先；TPS 较低、owner 离线、剩余额度不足或 provider 正在限流时延后提取。提取批次还应有独立 token 上限，并记录实际成本。

## 游戏体验与手感建议

### 1. 对短承接语做查询扩展

“继续”“然后呢”“就刚才那个”本身没有足够关键词。本地召回和技能 top-8 目前主要依据当前原文，容易漏掉真正的话题。

可以在检测到短句、代词或低信息量查询时，加入最近 1–2 个 user turn、当前 open loop 的关键词和最近明确实体作为低权重扩展；不要把天气、坐标等 `<context>` 噪声加入。

### 2. 对高置信工具意图自动预载 schema

严格的 `load_tool_schema` 每次会增加一个完整 LLM 往返。对于“看看周围”“扫描方块”“观察这个建筑”等明确请求，先加载 schema 再执行视觉工具会让玩家感到停顿很长。

建议用本地轻量规则/特征为高置信的 1–2 个扩展工具直接附带 schema，仍保留目录和 `load_tool_schema` 作为兜底。工具少时还应比较“发送全部 schema”与“多一次模型往返”的总 token/延迟，动态选择更便宜的方案。

### 3. 工具状态文本本地化并面向玩家

`load_tool_schema`、`scan surroundings`、`observe surroundings` 等 invocation summary 当前是技术英文。建议气泡显示本地化的动作状态，例如“正在准备工具说明”“正在查看周围”“正在核对附近方块”，隐藏 schema、tool id 等实现术语。

### 4. 让记忆行为可解释但不打扰聊天

无需把后台整理做成气泡，但历史页可以显示简短状态：长期事实数量、未完成事项数量、最近一次整理是否成功，以及“清空会同时停止在途对话”的明确提示。调试模式可显示本轮召回了哪些 episode 和预算报告，普通玩家默认隐藏。

### 5. 记忆标签降低过度确定性

模型生成的 fact 当前被标成 `Stable fact` 并以高权限消息注入，但它可能是提取模型的误总结。建议区分：用户明确陈述、工具确认结果、模型推断、legacy summary；只有前两类可作为高可信记忆。普通提取结果使用“可能的对话记忆”措辞，并保留 source turn IDs 供调试。

### 6. 预算应感知模型能力

全局配置允许 4K–131K，但不同站点/模型的实际上下文窗和输出上限不同。建议站点模型条目可声明 context window；有效输入目标取 `min(用户配置, 模型上限 - 输出预留)`。若未知则使用保守默认，并在固定内容超限时给管理员明确日志，而不是让 provider 以模糊 400 错误失败。

### 7. 后台成本应透明且不抢最后额度

后台提取计入玩家额度符合成本约束，但玩家当前看不到这部分消耗。接近 token 上限时，提取还可能吃掉最后额度，使下一句正常聊天被拒绝。

应为后台任务保留额度门槛：剩余额度不足以覆盖一次保守估算时不启动；统计中区分“聊天”和“记忆整理”消耗。后台请求若使额度越界，不应在已经付费后才丢弃结果并只报普通聊天错误。

## 第二轮后的修复优先级调整

建议将整体顺序调整为：

1. 统一 `RequestState` 与取消令牌：覆盖 LLM、Anthropic 续轮、工具批次、side callback、TTS 和 clear-memory。
2. 停止 superseded 工具的后续世界操作，并补 `addToolHistory` 隔离。
3. 修正提取触发、细粒度 revision 和活跃聊天饥饿。
4. 改安全 NBT，并分离服务端完整状态与客户端展示同步。
5. 只持久化/注入 provider-neutral `assistantChatText`，移除未来上下文中的 TTS 翻译和 reasoning。
6. 对 AIFun 与原生客户端分别按实际序列化工具 schema 做一次性预算。
7. 实现即时纠正/忘记/事项关闭的候选更新，避免 8 轮记忆反悔。
8. 让流式 TTS 严格服从设置，并提供稳健/低延迟策略。
9. 再做 BM25、倒排缓存、承接语查询扩展和自适应工具预载。
10. 最后补历史页可读性、状态提示、成本统计与本地化。

## 第二轮最终判断

这些新增问题仍然可以只在 `E:\forge-dev\touhou-aifun` 内解决。大多数落点是已有 Mixin、AIFun callback、附加持久化、工具 selector 和自有网络消息；没有发现必须修改基模源码才能继续的硬阻塞。

不过，“不修改基模”不等于可以忽略基模行为：原生客户端的全量工具构造、基模 history 持久化/同步、工具批次执行以及历史 GUI 都必须作为兼容契约纳入测试。若先完成上述前六项，正确性、首句延迟、token 消耗和玩家对“打断/记忆”的信任感都会有明显提升。

---

## 修复进度（2026-08-07，第一批）

本轮已开始按上述优先级落代码，当前完成并通过编译/单元测试的项目如下：

- Anthropic `web_search` 空文本续轮改为复用原 callback、turnId、等待气泡和工具集合，不再创建新的普通请求。
- `onFunctionCall` 与文本完成一样回到服务端线程后再做 superseded 检查和基模历史写入，关闭 `user B → assistant tool-call A` 的跨线程窗口。
- 在基模每个尚未开始的 `onSingleCall` 前增加 superseded 门控；旧批次已经在执行的单项无法通用回滚，但后续工具不再继续启动。
- 新 turn 和“清空聊天记忆”会立即推进 TTS generation、发送客户端中断、清理在途等待气泡并取消视觉采集；清空后的迟到提取结果不会污染新状态。
- 后台提取不再在达到 16 轮前把完成原文裁成 12 轮；75% 条件开始计算 assistant、工具结论、事实、事项和事件，而不只计算 user 文本。
- 提取应用改为验证批次 turn IDs 与引用对象是否仍存在，普通新聊天和 token 校准不再仅因全局 revision 变化使旧批次作废。
- 提取失败的状态更新和 `EXTRACTION_RUNNING` 释放现在位于同一服务端线程 finally；提取批次增加 8192 估算 token 上限。
- `TouhouAIFunMemory` 从单 StringTag 改为 gzip ByteArrayTag，并兼容读取初版 StringTag；新增超过 65,535 UTF-8 字节的往返测试。
- AIFun durable turn 只保存玩家可见 `chatText`；TTS 翻译和 Base64 reasoning 不再进入未来普通上下文。
- 基模 legacy assistant history 同样剥离 reasoning/TTS envelope；legacy tool history 只保存 Unicode 安全的 512 code point 展示副本，callback 当前工具链仍保留完整结果。
- 每轮工具结论合并为一个最多 512 code point 的记录，并随近期 assistant turn 注入未来上下文。
- AIFun OpenAI/Anthropic 的每个 agent 工具轮都重新运行预算规划器；当前未完成 tool call/result 组仍不可拆分。
- token 估算开始计入 tool call id、工具名和 arguments。
- 原生基模客户端使用全量注册工具 schema 的保守序列化预算；AIFun 客户端继续使用共同紧凑 selector。
- 移除了固定预留 6144 后再裁一次的双重规划，现在等当前 `<context>`、用户消息和客户端类型已知后只做一次权威规划。
- 关闭 `tts.sentenceStreaming` 后，SSE 路径不再偷偷提前逐句朗读；迟到音频发送前同时检查 callback superseded 和 TTS generation。
- 保留其他附属或未来 TLM 添加的前导 SYSTEM 消息，只精确排除基模 legacy compressed summary。
- 提取提示新增“保持原对话语言、精确保留玩家名/数字/namespace:id/下划线标识符”要求。

验证：`gradlew cleanTest build --no-daemon` 使用指定 Java 17 通过，`ContextMemoryTest` 共 7 项、0 failure、0 error；Mixin AP、refmap、reobf jar 与 `Touhou-AIFun-0.3.jar` 均生成成功。

仍未解决或只部分解决：已经开始执行的第三方工具无法通用撤销副作用；完整服务端记忆仍跟随基模 AI 配置 tag 同步到客户端；即时“忘掉/纠正/关闭事项”、真正 BM25、工具目录快照/缓存、全服后台提取队列和运行时 API 冒烟尚待后续批次处理。

---

## 第三轮复核与第二批修复：召回、即时纠错和运行时手感

本轮在第一批修复上继续沿玩家实际输入检查，重点覆盖“刚改口下一句是否还反悔”“继续/刚才那个能否接上话题”“中文短词是否产生召回噪声”“后台整理是否会覆盖玩家刚完成的事项”“客户端清空是否误跑服务端逻辑”以及旧存档迁移质量。

### 本轮新增并已修复的问题

#### 1. 明确纠正、遗忘和事项完成原本仍需等待后台提取

新增纯本地、确定性的 `ImmediateMemoryReconciler`，在建立新 turn 前先处理高置信控制语句：

- “其实我不喜欢红茶了，我更喜欢咖啡”会立即暂停注入命中的旧偏好 fact；新偏好仍由近期原文和后台提取正式建立，客户端不会本地臆造事实。
- “忘掉/不要记住/别记住某项”会立即移除命中的 fact/episode，并关闭命中的未完成事项。
- “已经完成了/不用了/取消”会立即关闭命中的 open loop；在只有一个未完成事项且用户明确说“已经完成了”时允许无主语关闭。
- 明确保护“不要忘记/别忘记/don't forget”，不会将其误识别为遗忘。
- 将单独出现的“不是”从宽泛纠正条件改为更明确的“不是 X，而是 Y/是 Y”结构，避免“你不是说……吗”这类疑问句误删记忆。

该策略刻意保守：只删除或关闭已有记录，不直接把自然语言规则解析出的新内容写成高可信长期事实。这样能让玩家的改口立即生效，同时避免本地规则误总结。

#### 2. 召回实现原先并非真正 BM25，ASCII 还可能重复计权

`LocalMemoryRetriever` 已改为确定性的 BM25：`k1=1.2`、`b=0.75`，文本分归一化后乘 4，再叠加严格结构化实体、importance、open-loop 相关性和 turn recency。

同时修正：

- 中文/日文/韩文按 bigram、trigram 建特征；长度大于一的 CJK 文本不再加入高噪声单字 unigram。
- 普通拉丁词只按 Unicode 单词计一次；旧扫描逻辑会让 ASCII 字母同时进入普通词和“实体”缓冲，造成词频与文档长度重复。
- 实体加分只用于数字和含 `:_./#-` 的结构化标识符，不再让普通英文词无条件获得 `+2`。
- 没有任何 query 文本/实体相关性的事件不会仅凭 importance、时间或与某个 open loop 相似而入选。
- 事实只在与显式 query 已有重合时用于查询扩展，避免整个玩家档案污染每次检索。

#### 3. “继续/刚才那个”缺乏检索词，短而明确的名词又不应被误判

新增低信息承接语识别。命中“继续、然后、刚才那个、go on”等表达时，查询会加入最近一个已完成 user turn 和最高重要度的两个未关闭事项，再用于 episode 与技能相关性排序。

规则从最初的“4 字以内或只有一个 token”收紧为“一字输入或明确承接语”。因此“红茶”这种两字但指向明确的查询不会被近期话题强行扩展，减少串话。

#### 4. 记忆数据的高权限措辞过强，且内容边界不足

注入标题从 `Stable fact` 改为 `Fallible remembered fact`，声明中明确：记忆是可能出错的引用数据，不是指令；近期对话和当前用户冲突时必须以当前用户为准。

fact、open loop、episode 和工具结论统一放入转义后的 `<memory_data>` / `<tool_outcome_data untrusted="true">` 边界，降低历史用户文本或工具输出中的提示注入被当作系统指令继续执行的概率。规划器同时兼容旧、新两种 fact 标签，避免迁移期裁剪规则失效。

#### 5. 后台旧快照可能重新打开玩家刚完成的事项

玩家在提取进行中明确关闭 open loop 后，旧提取响应若仍携带该 ID 的 upsert，原实现会调用 `replace` 并把 `closed=false`，造成事项“死而复生”。现在应用旧增量时会跳过已经被本地关闭的 loop，玩家最新操作优先。

这仍不是完整的每记录版本控制；后续若增加 GUI 编辑、多个记忆维护器或工具直接改写事实，应给 fact/loop 增加独立 revision，而不是继续依赖当前有限的冲突规则。

#### 6. 客户端清空路径仍可能执行服务端专用中断

基模历史页面会在客户端调用 `clearAllChatMemory`。此前 Mixin 无条件调用 `ChatFlowManager.clearMaid`，可能在客户端侧触发 TTS 网络中断和服务端运行时表清理。现只在 `ServerLevel` 执行请求取消、提取释放与 TTS 中断；客户端仍正常清空其同步到的展示副本。

#### 7. 旧历史迁移仍可能带入 reasoning 和 TTS 翻译

新回复已经只保存可见 `chatText`，但首次迁移旧基模 history 时仍直接采用完整 assistant 字符串。现在迁移也统一解码 `ReasoningContentCodec`，并截取 `---` 前的玩家可见语言文本，避免老存档在切换分层记忆后继续重复发送翻译和 Base64 reasoning。

#### 8. 提取 importance 范围只 clamp、不拒绝

后台增量现在对 fact、open loop 和 episode 的 importance 严格要求 `0..3`，越界响应整批不应用并保留原文，符合“非法 schema 不修改状态”的约定。

### 本轮测试与构建结果

- `ContextMemoryTest` 增至 15 项，0 failure、0 error、0 skipped。
- 新增覆盖：CJK bigram/trigram、不把普通短名词当承接语、相关 fact 查询扩展、明确纠正即时 suppress、“不要忘记”保护、否定疑问句不误删、单 open-loop 无主语完成、importance 越界、Unicode 工具结论及大于 65,535 字节的压缩存档往返。
- `gradlew cleanTest build --no-daemon` 使用项目指定 Java 17 成功。
- Forge 构建完成 `compileJava`、Mixin AP、refmap、`reobfJar` 和最终 jar 生成。
- `git diff --check` 无空白错误；输出仅有 Windows 工作区的 LF/CRLF 转换提醒。

## 第三轮后仍需继续处理的疏漏与优化

以下项目尚未宣称完成，应作为下一批工作和运行时验收重点。

### 正确性与生态兼容

1. **第三方工具触发契约仍不严谨。** `tool.trigger(maid, null)` 仍可能让依赖真实 `ChatCompletion` 的第三方工具抛异常并被隐藏。应在真实请求构造阶段形成一次性的 `ToolCatalogSnapshot`，让触发、目录、schema、预算和实际发送共享同一结果。
2. **已开始执行的工具副作用无法通用撤销。** 当前能阻止旧批次后续工具启动，但不能回滚已经执行的世界修改。可为 AIFun 自有工具增加可取消检查；对第三方工具只能明确“不回滚已完成动作，阻止后续动作”的边界。
3. **完整服务端记忆仍会随基模 AI tag 同步客户端。** gzip 解决 NBT 单字符串上限，但没有解决客户端不需要 episodes、source IDs、校准值和提取退避状态的问题。应拆成服务端持久化 payload 与精简展示 DTO。
4. **细粒度并发版本只完成一部分。** 当前按 batch turn IDs、引用 ID 和“已关闭 loop 不重开”合并；`expectedRevision` 已不再作为粗粒度一票否决，但参数仍保留。未来可替换为 fact/loop 独立版本快照并清理这个过渡接口。
5. **静态运行时表缺少女仆卸载/世界关闭生命周期清理。** 每名曾聊天女仆最多仍可能留下 latest callback/turn/TTS generation 等小状态。需要在实体移除或服务器停止事件中清理，避免长期大型服务器缓慢积累。
6. **损坏或未来版本 payload 的可恢复性仍不足。** 解码失败会退回空状态；应记录明确警告并保留原始备份 tag，避免下一次保存覆盖唯一可恢复数据。

### 性能、费用与响应速度

1. **BM25 每轮仍重建全部文档特征和 DF。** 128 条长 episode 时会在发起聊天的服务器线程产生较多临时 Map/String。应按语义 revision 缓存文档特征、实体集、长度、DF 和倒排表，仅对 query 分词。
2. **工具目录与 schema 仍被多次计算。** `compactDirectory`、预算、OpenAI 请求和 Anthropic 请求可能重复执行第三方 `trigger/summary/parameters`。不可变 snapshot 能同时降低 tick 抖动并避免有状态 hook 多次调用产生不一致。
3. **第三方工具目录很大时仍会每轮重复发送。** 可按本地相关度直接预载 1–2 个高置信 schema，其余只发名称；极大目录考虑 `search_tool_catalog` 或分页，动态比较“一次发送 schema”和“多一次 load 往返”的成本。
4. **后台提取只有每女仆限流，没有全服低优先级队列。** 多女仆同时达到阈值仍会造成 provider 并发、429 和正常聊天首 token 变慢。建议全服 2–4 并发、普通聊天优先，并在余额/TPS/限流异常时延后。
5. **预算尚未感知模型真实上下文窗。** 有效预算应取 `min(用户配置, 模型上下文上限 - 输出与协议预留)`；未知模型保守处理，并给管理员可读日志。
6. **持久化仍会对同一 revision 重复 gzip。** 可缓存最近一次编码后的 byte[]，只在 persistence revision 变化时重新序列化；telemetry 与 semantic revision 最好分离。

### 玩家手感与可观察性

1. **高置信工具意图可减少一次 schema 加载往返。** “看看周围/扫描附近方块/观察建筑”等明确话术可以本地预载对应扩展工具；低置信时仍走 `load_tool_schema`。
2. **工具状态文字仍偏技术化。** 将 schema/tool id 隐藏为本地化动作气泡，例如“正在查看周围”“正在准备观察能力”，能显著改善等待感受。
3. **后台成本和记忆状态不可见。** 历史页可只读显示事实数、未完成事项数、上次整理是否成功；调试模式再显示召回 episode 与预算报告，普通玩家不显示内部细节。
4. **本地纠错规则需要真实语料回归。** 当前规则为保守中文/英文启发式，仍需用常见聊天语料验证否定、转折、引用别人话语和反问。误删比漏删更伤信任，因此默认应继续偏向漏判。
5. **运行时冒烟仍未完成。** 需要真实 API key 分别验证 OpenAI/Anthropic 普通聊天、连续 20 轮、web_search 续轮、工具调用、快速 A/B 打断、关掉按句 TTS、保存重载与两名女仆并发隔离。

## 当前第三轮判断

这套实现已经从“有完整骨架但关键路径不可靠”推进到“主要时序、预算、存档和本地召回链路可编译且有纯 Java 回归保护”。第二批修复对实际手感最直接的收益是：改口不再至少等八轮、短承接语更容易接回原话题、中文召回噪声更低、旧事项不会被后台结果重新打开。

但仍不建议仅凭单元测试宣称全部验收完成。下一批最值得优先投入的是 `ToolCatalogSnapshot`（同时解决生态一致性、预算精度和性能）、服务端/客户端记忆同步拆分、全服后台提取队列，以及真实运行时的快速打断与 20 轮冒烟。上述工作仍可全部限制在 `E:\forge-dev\touhou-aifun` 附属模组内完成，不要求修改基模源码。

---

## 第四轮修复：每 turn 工具目录快照

本轮完成了第三轮列为最高优先级的 `ToolCatalogSnapshot`。修改仍只位于 AIFun 附属模组，不改变基模工具注册表或第三方工具接口。

### 已修复的工具生态问题

1. **不再调用 `tool.trigger(maid, null)`。** AIFun 会根据该 callback 的实际 system/user/assistant/tool 消息构造一个非空兼容 `ChatCompletion`，满足基模 `ITool.trigger` 的正式契约。依赖当前对话内容的第三方工具不再因空参数异常而被静默隐藏。
2. **每个 turn 只评估一次第三方 hook。** 首次构造快照时依次执行一次 `trigger`、`summary` 和 `parameters`，冻结工具实现、摘要、OpenAI schema、Anthropic schema 和估算 token。后续 agent 工具轮不再重复调用这些 hook。
3. **OpenAI 与 Anthropic 消费同一个冻结结果。** 两个客户端不再各自遍历 `ToolRegister` 和重新生成参数；目录、实际 schema、schema token 预算及 usage 校准都来自同一个 callback 快照。
4. **资源或世界状态不会让单轮工具目录中途漂移。** 已触发的工具、目录顺序和 schema 在当前 callback 生命周期内保持不变；下一次普通聊天才重新捕获注册表。
5. **加载集合仍按 callback 隔离。** 核心工具按固定顺序稳定注册，扩展工具只有存在于该快照且被当前 callback 的 `load_tool_schema` 请求后才加入。另一个 callback/女仆的 requested set 不会参与选择。
6. **预算改为真实冻结 schema。** 不再用“摘要加固定 96 token、最低 4K–8K”近似 AIFun 工具。预算取 OpenAI/Anthropic 序列化 schema 中较大的估算值，加紧凑目录、Anthropic web search 和相邻提醒的保守预留。
7. **`load_tool_schema` 不再重复巨大 enum。** 工具名以本轮目录为权威来源，元工具参数只要求精确填写目录 ID；执行时再次确认目标属于当前快照。这样避免大型整合包中目录与 enum 重复占用 token，也避免参数构造再次触发全部第三方 hook。
8. **生命周期有明确释放。** 正常完成、失败、被 supersede、清空记忆或新 callback 替换旧 callback 时，都会释放对应快照；加载状态和冻结的工具引用不会长期跨 turn 保留。

如果第三方工具自身的 `trigger/summary/parameters` 抛异常，该工具只在当前 turn 被跳过并记录包含工具 ID 的警告，不再无提示消失。下一 turn 会重新尝试，资源重载或短暂状态错误可以自然恢复。

### 本轮验证

- 新增纯 Java `ToolSelectionPolicy`，验证核心工具顺序稳定、只加载 requested 且 available 的扩展工具，以及新 turn 的空 requested set 不继承上一轮加载结果。
- `ContextMemoryTest`：16 项，0 failure、0 error。
- `gradlew cleanTest build --no-daemon`：成功。
- Mixin AP、refmap、`reobfJar` 和最终 jar：成功。
- 全仓搜索已无 `tool.trigger(maid, null)`；AIFun 两协议的实际工具构造不再自行遍历并重复选择。
- `git diff --check`：无空白错误，仅 Windows LF/CRLF 提醒。

### 此模块仍需运行时验证的边界

- 某些第三方 `trigger` 可能强依赖具体 `ChatCompletion` 子类或私有字段；当前提供的是与基模 OpenAI 客户端相同的公共消息语义，需要在大型工具整合包中实际冒烟。
- schema 冻结能阻止单轮漂移，但无法让已经开始的第三方世界操作支持回滚。
- 极大量扩展工具时，快照会在 turn 开始一次性生成所有可用扩展 schema；这消除了重复开销，但首次请求仍可能有一次 tick 峰值。后续可分成“目录摘要先冻结、optional schema 按本轮 load 懒生成并缓存”，前提是仍能保证预算和两协议一致。

第四轮后，原审查文档中的“工具目录快照/缓存尚未实现”已经解决。下一优先项调整为：全服后台提取低优先级队列、服务端记忆与客户端展示同步拆分、静态状态生命周期清理，以及真实 API/游戏运行时验收。

---

## 第五轮修复：全服后台记忆提取调度

原实现只保证“同一女仆最多一个提取任务”，多名女仆可在同一时间全部调用主 LLM。长时间服务器中，这会造成 API 并发尖峰、429、后台 token 突增，并与玩家普通聊天争抢连接和 provider 吞吐。

本轮新增纯 Java `BackgroundTaskQueue<UUID>` 并接管提取启动：

- 全服后台提取最多同时 2 个，额外任务按 FIFO 等待。
- 同一 maid UUID 在 active 或 pending 时不能重复入队。
- 只要存在任意普通聊天 callback，队列不启动新的后台任务。
- 普通聊天结束/失败/被打断并释放 active request 后，会主动泵下一项后台任务。
- 若任务已经获得槽位但在真正回到服务器线程前新聊天到来，它会退回队首，不与前台请求争抢启动时机。
- 提取的 eligibility、批次、当前站点和女仆存活状态在真正启动时重新校验，排队期间清空或配置变化不会发出陈旧请求。
- 清空记忆只移除尚未启动的 pending 项；已经发出的 HTTP 请求继续占据 active 槽位，直到真实 callback 结束。这样不会出现“统计上释放了槽位，网络上旧请求仍在跑”的隐性超并发。
- 启动逻辑统一回到 Minecraft 服务器线程；异常会记录日志、更新退避并可靠释放槽位。
- queue pump 增加重入保护，同步失败/立即完成不会递归拉起整条队列造成深调用栈。

本轮新增 3 个调度测试，覆盖 FIFO 与并发上限、前台 admission 关闭时等待，以及已预留任务回退队首。当前 `ContextMemoryTest` 共 19 项，0 failure、0 error；`gradlew cleanTest build --no-daemon`、refmap 与 reobf jar 全部通过。

### 仍存在的调度边界

- 已经发出的后台 HTTP 请求暂未在新普通聊天到来时主动取消；现在保证“不再启动新的后台任务”，并将并发限制为 2。若要进一步保证首 token 优先，需要让两个 AIFun client 单独登记 background future，并在前台请求开始时可取消/重新排队。
- 并发值 2 当前为内部保守常量，没有增加 COMMON 配置或 GUI。若服务器运营场景差异很大，可后续增加 `llm.backgroundMemoryConcurrency`，但默认仍应保持低值。
- 当前只感知 AIFun 维护的普通 callback；基模或其他附属绕开这条 callback 状态机发起的后台网络请求无法被统一调度。

第五轮后，“多女仆后台提取无全服限流”和“后台在普通聊天活跃时继续起新请求”已经解决。下一项继续处理完整记忆随基模配置包同步客户端的问题，以及 maid/world 生命周期中的静态状态释放。

---

## 第六轮修复：同步包瘦身与运行时生命周期

### 完整记忆不再进入基模设置同步包

新增 `SyncMaidAIDataMessageMixin`，只在基模 `SyncMaidAIDataMessage(EntityMaid, ServerPlayer)` 构造完成后，从该消息独立的 `configData` 副本中移除 `TouhouAIFunMemory`。

效果：

- 实体/世界 NBT 保存仍通过 `MaidAIChatData.writeToTag` 写入完整 gzip memory，长期事实和提取状态不会丢失。
- 打开 AI 设置/历史界面时，网络包不再携带 episodes、source turn IDs、token 校准和待提取原文。
- 基模 legacy history、精简 compressed summary、站点设置和 token 用量仍正常同步，原历史界面无需新增网络协议即可继续工作。
- 客户端不参与 LLM 上下文构造，因此不需要完整服务端记忆副本。

同时将稳定 NBT key 提升为 `AIFunMemoryAccess.MEMORY_TAG`，并补齐计划里原本缺少的 `clearMemoryState` 接口，清空生命周期不再由 Mixin 直接替换私有字段。

### 女仆卸载和服务器停止会释放静态状态

新增 Forge `ChatRuntimeLifecycle`：

- 服务端女仆离开 level 时，取消普通 HTTP future，释放 callback、turn binding、requested tools、tool snapshot、视觉请求和未启动的提取队列项。
- 使用仅包含 UUID 的 retired tombstone 拒绝卸载后的迟到 callback，不再为了代际判断长期保留整个 callback/manager/entity 对象。
- tombstone 不在单纯重新载入 chunk 时提前解除；只有女仆真正开始新 turn/注册新普通 callback 才解除，因此旧维度/旧 chunk 的迟到回复不会在实体重载后被误接纳。
- 服务器停止时取消所有普通 future，并清空请求表、TTS generations、callback turns、工具选择、全部快照、后台队列和线程局部 guard。
- 已发出的后台提取仍在其 callback 真正结束时释放 active 槽位；服务器停止则统一清空，不保留跨世界引用。

### 第六轮验证

- `gradlew cleanTest build --no-daemon`：成功。
- `ContextMemoryTest`：19 项，0 failure、0 error。
- 新 Mixin、生命周期类、后台队列和工具快照均实际进入最终 reobf jar。
- `touhou_aifun.mixins.json` 从最终 jar 读取并通过 JSON 解析，refmap 同时存在。
- `git diff --check` 无空白错误。

### 尚需运行时确认

- Mixin AP/构建只能证明目标类和字节码描述符可编译，仍需 `runClient` 打开女仆 AI 页面，确认精简 tag 可正常构造历史界面，并在保存重载后确认服务端完整记忆仍在。
- 实体卸载事件会主动取消在途普通聊天，这是有意的安全语义；需要实际测试远离女仆导致区块卸载、维度切换及女仆死亡时不会残留气泡或错误提示。

第六轮后，审查中“完整记忆随设置包同步”“静态 map 无卸载清理”“AIFunMemoryAccess 缺 clear”三项已落实修复。剩余主要工作集中在：损坏 payload 备份与编码缓存、BM25 索引缓存、模型上下文上限、主动取消后台 HTTP，以及真实 API/游戏冒烟验收。

---

## 第七轮修复：存档损坏保护与编码缓存

`MemoryStateCodec` 现在返回明确的 `DecodeStatus`：`VALID`、`EMPTY`、`CORRUPT`、`UNSUPPORTED_SCHEMA`，不再把所有异常都悄悄折叠成同一个空状态。

### 损坏与未来版本处理

- JSON 根类型、显式数字 `schemaVersion`、当前版本号和必需集合都会校验；缺失版本不再因为 Java 字段默认值而被误认为 v1。
- gzip 损坏、超过 8 MiB 解压安全上限、非法 JSON或缺失字段会标记为 `CORRUPT` 并记录可诊断警告。
- 原始损坏 Tag 会保存在独立 `TouhouAIFunMemoryBackup`；当前版本可继续从 legacy history 迁移出可用 v1，下一次保存不会覆盖唯一恢复线索。
- 遇到未来/未知 schema 时标记为 `UNSUPPORTED_SCHEMA`，原始 payload 继续原样保留在主 `TouhouAIFunMemory`，并复制到 backup；当前旧版本不会擅自降级覆盖未来版本数据。
- 玩家明确“清空聊天记忆”时主数据和 backup 一起清除，符合清空操作的直觉与授权范围。
- 设置界面同步包同时剥离主 memory 和 backup，损坏的大 payload 不会通过 GUI 网络包传给客户端。

### gzip 编码缓存

`MaidAIChatDataMixin` 按 memory revision 缓存最近一次 gzip byte[]：

- 状态没有变化时，实体保存和其他重复 `writeToTag` 只复制缓存字节，不再重新执行全量 Gson + gzip。
- 读取有效 ByteArrayTag 后直接复用原压缩 payload 作为初始缓存。
- `set/clear` 或任意正常 `touch()` 推进 revision 后自动重新编码。
- 写入 NBT 时复制数组，避免外部 Tag 意外修改缓存内容。

本轮新增 codec 状态测试，覆盖损坏 gzip、未来 schema 和缺失 schemaVersion。当前 `ContextMemoryTest` 共 20 项，0 failure、0 error；完整 Forge 构建、refmap 与 reobf jar 通过。

### 仍需注意

- backup 当前只保留一份原始 Tag，没有轮转多个历史版本；它用于防止静默覆盖，不是完整备份系统。
- revision 仍同时包含语义状态和 token calibration，校准变化会使 gzip 缓存失效。后续拆分 persistence/semantic/telemetry revision 可进一步减少重编码和 BM25 索引重建。
- 真正的恢复工具/GUI 尚未实现；管理员需要借助 NBT 工具读取 backup。至少现在日志和原数据都在，不再表现为无迹可寻的“突然失忆”。

第七轮后，存档容量、损坏可诊断性、未来版本保护和重复 gzip 开销已有完整防线。下一批适合继续做 BM25 语义索引缓存与模型上下文上限约束。

---

## 第八轮修复：召回索引缓存、原生工具续轮预算与剩余规范偏差

### BM25 事件索引缓存

`LocalMemoryRetriever` 现在按 `MaidMemoryState` 使用弱引用缓存以下不可变索引：episode token frequency、文档长度、document frequency 和平均文档长度。

- 每次 query 仍独立分词和评分，但不再反复处理最多 128 条旧 episode 正文。
- 缓存以 episode ID、summary、keywords 和顺序的内容指纹失效；新增、删除、替换或排序事件都会重建。
- 新 turn、token calibration 或 fact/open-loop 更新不会无谓重建 episode 文本索引。
- `WeakHashMap` 的 value 不引用 state/episode list，女仆状态被回收后索引可随 key 自动释放。
- 新增回归测试确认 episode 内容改变后不会返回陈旧缓存结果。

### 基模原生客户端的工具 agent 续轮重新规划

之前原生客户端只在普通 `LLMCallback` 构造时规划一次；超长 tool result 加入同一个 callback 后，下一次 `client.chat(nextCallback)` 不会再次经过构造器。现在：

- 初次构造时计算原生全量工具 schema 的保守预算，并按 callback 缓存。
- 每次 `addToolResult` 完成后，若当前站点不是 AIFun OpenAI/Anthropic client，则再次运行共同 `ContextBudgetPlanner`。
- 当前 assistant tool calls 和 tool results 仍作为不可拆分协议组保护，只淘汰 episode、旧近期轮次和低优先级 fact。
- 不在工具线程重新调用所有第三方 `summary/parameters`，而是复用首轮 schema 预留。
- callback 完成、失败、替换、清空、卸载或服务器停止时释放该预算缓存。

至此，“所有站点获得分层历史”和“工具循环不能绕开 24K 规划”同时覆盖 AIFun 与基模原生 client；第三方按需 schema 仍只属于 AIFun 两协议，符合最初范围。

### 已关闭事项和 interrupted episode

- closed open-loop 不再每次 `trimMemory` 全部删除；只有总事项数超过 24 时，才按低 importance、最旧优先淘汰 closed 项。未关闭事项仍绝不静默删除。
- 单次提取 batch 只选择同一种 turn status，不再把正常完成轮次和 interrupted 轮次合成一个 episode 后整体标记“请求被打断、未执行”。
- 删除已经失去语义的 `expectedRevision` 参数；当前并发合并契约明确使用 batch IDs、引用 ID、对象当前状态和局部冲突规则，不再留下“看似校验全局 revision、实际未使用”的接口。

### 配置键和实际保留量

- COMMON 键从实现偏差 `llm.memoryRecentTurns` 修正为计划约定的 `llm.recentTurns`。
- 后台提取关闭时，原文保留量改为 `max(12, configured recentTurns)`；配置允许的 13–32 不再出现“界面/文件可设置但实际最多只有 12”的假生效。
- 后台提取开启时仍允许原文增长到提取阈值并受 64 轮 safety cap 保护。

第八轮新增缓存失效测试；当前 `ContextMemoryTest` 共 21 项，完整 `gradlew cleanTest build --no-daemon`、Mixin AP、refmap 和 reobf jar 通过。

### 第八轮后剩余重点

- 模型 context window 元数据和有效预算上限尚未建立；当前仍以用户 24K 目标为准，未知小上下文模型可能在固定内容/工具组较大时由 provider 拒绝。
- 已发出的后台提取 HTTP 尚未在普通聊天到来时主动取消并安全重排。
- 高置信扩展工具自动预载、本地化工具状态、调试预算报告属于后续手感增强。
- 最关键的剩余证据仍是真实 Forge 运行时与 API 冒烟，而不是继续增加只覆盖纯 Java 的单元测试。

---

## 第九轮完成度审计补修

对最初实施计划逐项回查时又修正了三个容易被普通 happy-path 测试漏掉的细节：

1. **工具 schema 超过整个目标预算时不再凭空增加 2K。** `ContextBudgetPlanner` 原本使用 `max(2048, budget-schemaReserve)`，当不可裁的 schema 已大于用户预算时仍会额外放出 2048 message token。现在可用 message target 最低为 0：所有可裁层按顺序移除，角色规则、当前用户、未关闭事项和工具协议仍保留，并明确报告不可避免的 over-budget。
2. **旧 history 末尾未答消息正确迁移。** 带 tool calls 的 assistant 只作为中间协议，不再以空文本提前完成 turn；迁移循环结束后仍为 pending 的最后 user 或未完成工具链统一标为 `INTERRUPTED`。
3. **单子代理 placeholder 不再绕过代际检查。** 基模 `executeSingleToolCall` 有一条直接调用 `chatManager.addToolHistory` 的分支，不经过已门控的 `addToolResult`。新增定点 Redirect，仅在原 callback 仍是最新 turn 时允许这条 legacy placeholder 写入。

最终完整 `gradlew cleanTest build --no-daemon` 再次通过。至此源码层面的主要 correctness 项已基本闭合；剩余未证明部分主要是必须依赖真实游戏线程、网络和 provider 的运行时验收，以及可选的模型上限/后台主动抢占等增强。

---

## 第十轮真实 DeepSeek Anthropic API 冒烟

使用本地已启用站点和真实密钥执行网络验证，密钥未输出、未写入新文件。

结果：

- 基本非流式 `/v1/messages`：成功，返回 thinking + 中文 text，`stop_reason=end_turn`。
- `web_search_20250305` server tool：成功返回多组 `server_tool_use` 与 `web_search_tool_result`。本次 512 输出上限被搜索过程耗尽，`stop_reason=max_tokens`，因此证明 server tool 协议可用，但也表明“复杂最近新闻”问题需要更高输出上限或更少搜索次数才能稳定得到最终正文。
- SSE：成功观察到 `message_start`、`content_block_start/delta/stop`、`message_delta`、`message_stop` 等事件，streamed text 非空。
- 工具续轮：旧脚本硬编码了一个不含 thinking 的 assistant tool_use，DeepSeek 当前会返回 400：`content[].thinking ... must be passed back`。这不是模组实际客户端的消息形状。
- 进一步按 `AnthropicCompatLLMClient` 的真实语义复测：首轮获取 `thinking + tool_use`，把 thinking 文本与 tool_use 重建回 assistant，再附加 tool_result 和空 user 兜底；第二轮成功得到 `thinking + text`，`stop_reason=end_turn`。响应 thinking 块还包含 signature，但当前端点允许客户端只回传 thinking 文本。

已同步修正 `scripts/smoke-deepseek-anthropic.ps1`：第 4 步现在先真实取得 provider 的 thinking/tool_use，再按模组方式回传，不再用缺失 thinking 的人工请求制造假阴性。脚本通过 PowerShell AST 语法校验。

这次验证覆盖了协议端点和消息形状，但仍不等于 Forge 内部完整运行时：尚需 `runClient` 实际验证 callback/Mixin/TTS/气泡/工具执行与快速打断组合。

### 预算规划直接回归测试补齐

测试 classpath 增加 TLM 的 test-only 依赖后，可以直接构造真实 `LLMMessage/ToolCall`，不再只通过字符串估算器间接验证。新增测试证明：

- 预算不足时 episode 先于 fact 和固定对话数据淘汰。
- schema reserve 吃完整个目标时，角色规则、memory declaration、未关闭事项和当前 user 仍保留，并报告 target=0/over-budget。
- 当前 assistant tool-call + tool result 原子组不会被拆开，即使工具结果本身已大于预算。
- 最少保留最近 2 个完整 user/assistant 轮次，并始终保留当前 user。

当前 `ContextMemoryTest` 共 25 项，0 failure、0 error；增加 test-only 依赖不进入最终 mod jar。完整 Forge build 再次通过。

---

## 第十一轮 Forge 运行时与快速打断上下文空窗

### 运行时发现并修复的 Mixin 致命错误

第一次实际启动 `runClient` 时，客户端在资源加载阶段崩溃。根因不是 refmap 或 Forge 小版本，而是 `LLMCallbackMixin` 的单子代理工具历史 Redirect 选错了字节码方法：

- Java 源码逻辑位于 `executeSingleToolCall` 的异步 completion handler 中。
- javac 实际把 `MaidAIChatManager.addToolHistory` 调用下沉到了合成方法 `lambda$executeSingleToolCall$5`。
- Redirect 仍声明 `method = "executeSingleToolCall"`，因此编译和 reobf 均能通过，但 Mixin 运行时扫描结果为 `(0/1) succeeded`，在 `ToolRegister.init` 阶段直接触发 `InjectionError`。

Redirect 已改为实际字节码 owner，并补充注释说明这一脆弱落点。重新执行完整构建后，第二次 `runClient` 成功完成资源加载、进入主菜单、载入既有创造模式世界，并完成 AIFun 网络通道握手；启动和进服日志未再出现该 InjectionError。这证明“build 通过”不能替代真实 Mixin 启动冒烟，后续升级 TLM 时应重新 `javap` 检查这一合成方法名。

### 快速打断时上一条用户原话完全缺席

游戏内实测复现：用户先发送“八八八，我这里边有游戏。”，在回答完成前又发送“好像有点退网了。”。第二轮回复表现为完全不知道第一句话。

状态写入本身没有丢失：`beginTurn` 已将第一轮从 `PENDING` 正确改为 `INTERRUPTED`。真正问题位于请求重建：`rebuildVisibleMessages` 只注入 `COMPLETED` turn，而 interrupted turn 只有等后台提取成为 episode 后才可能再次出现。快速打断后的下一轮因此存在确定的“上下文空窗”。这虽然严格遵循了最初“被打断轮次不作为正常 user/assistant 原文注入”的约束，却不符合连续说话的实际手感。

现调整为：

- 只选择“上一次正式完成回答之后”的连续 interrupted 输入，最多保留最新 3 条。
- 在下一轮中以 `### Recent interrupted user message` 系统数据块注入，带 `unanswered=true`、`interrupted_user_input` 和 turn ID。
- 数据块明确声明请求已取消、没有任何 assistant 回答被接受；不把它伪造成完整对话轮次，也不暗示其中动作已经执行。
- 去除旧输入中的 `<context>`，单条最多 600 code points，并进行 XML 转义；用户文本仍受总 memory declaration 的“不可信数据、不得作为指令”约束。
- 这些最新原话同时参与本地 episode 召回和技能 description 选择，避免第二句话本身很短时选错相关记忆或技能。
- 一旦后续轮次得到正式回答，这个 live interrupted chain 关闭；更旧 interrupted turn 继续走后台事件提取，不会永久重复注入。
- 24K 预算极紧时，该数据块属于可裁内容：在低分 episode 和可淘汰旧完整轮次之后、长期 fact 之前按最旧优先移除。当前用户、角色规则、未关闭事项和工具协议保护规则不变。

新增 3 个直接回归测试，覆盖截图中的原话确实进入 superseding turn 数据、正式回答后 live window 关闭，以及紧预算下不会因 interrupted 数据块突破目标。当前 `ContextMemoryTest` 共 28 项，完整 `gradlew cleanTest test build --no-daemon`、Mixin AP、refmap 和 reobf jar 均通过。

### 仍需人工复测

正在运行的客户端不会热加载此次 Java 修改，必须退出并重新 `runClient` 后复测同样的 A → B 快速输入。预期 B 能结合 A 的语义回答，同时历史中仍只有 A 的 interrupted 用户输入，不出现 assistant-A 迟到回复。工具执行中打断、流式 TTS 停止和两名女仆隔离仍属于下一批运行时验收。
