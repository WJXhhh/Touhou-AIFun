# Touhou AIFun (touhou_aifun)

Minecraft Forge 1.20.1 模组，车万女仆 (Touhou Little Maid, TLM) 的 AI 对话附属模组：
给女仆接入各家 LLM / TTS / STT 服务。原名 **Touhou StepFun / touhou_stepfun**，2026-06 改名为 Touhou AIFun。

## 基础信息

- 基模（依赖）：`E:\forge-dev\touhou` — `com.github.tartaricacid:touhou_little_maid`
- 本模组路径：`E:\forge-dev\touhou-aifun`，Java 包 **`com.wjx.touhou_aifun`**
- mod_id / 资源命名空间：`touhou_aifun`（assets 下同名）；jar 名 `Touhou-AIFun-<ver>.jar`
- Mixin 配置 / refmap：`touhou_aifun.mixins.json` / `touhou_aifun.refmap.json`；mixin 成员前缀 `touhouAIFun$`
- 主类 `com.wjx.touhou_aifun.TouhouAIFun`；远程仓库 https://github.com/WJXhhh/Touhou-AIFun (main)
- Forge：开发 47.2.0，整合包 47.4.20；映射 **official (Mojang)**
- 命名约定：品牌类用 **AIFun**；**StepFun 是真实服务商（阶跃星辰）**，`compat/ai/stepfun/` 下的 StepFun* 类保留不改
- 站点配置 JSON 在 `run/config/touhou_little_maid/sites/`（玩家侧在 `config/touhou_little_maid/sites/`）

## 目录结构

```
src/main/java/com/wjx/touhou_aifun/
├── TouhouAIFun.java               # 主类：mod 生命周期、注册
├── compat/
│   ├── LittleMaidCompat.java      # 注册覆盖核心（见下）
│   ├── ai/
│   │   ├── EmotionControlPrompts.java   # TTS 情绪标记提示词（3 模式 + MiMo 标签体系）
│   │   ├── mimo/        # MiMo（LLM+STT+TTS）：MimoEndpointResolver, MimoTTSClient...
│   │   ├── stepfun/     # 阶跃星辰（LLM+STT+TTS）：StepFunLLMClient, StepFunTTSClient...
│   │   ├── qwen/        # 通义千问/百炼（LLM+STT+TTS），夺舍基模 "aliyun" 身份
│   │   ├── openai/      # 通用兼容层：ReasoningCompatOpenAIClient（流式/取消/agent 中枢）
│   │   ├── fishaudio/ minimax/ siliconflow/   # 各 TTS 引擎
│   │   └── tts/         # TTSProgressiveSynthesis, SentenceTextSplitter, CustomVoiceHttpUtil, VoicePreset*
│   ├── ai/*/layout/     # 站点表单布局（TTS/STT 配置页 UI）
│   └── ai/*/response|request/  # 各家 API 的请求/响应 DTO
├── chat/
│   ├── ChatFlowManager.java      # 按 maid 管理请求代际/在途 future（中途打断核心）
│   └── ChatBubbleDisplayTime.java# 气泡存活时间按文本长度缩放
├── client/
│   ├── sound/           # QueuedTTSPlaybackManager（FIFO 播放）、AIFunStreamSoundManager、PCM 流
│   └── gui/             # CustomVoiceScreen, STTSiteDropdownWidget, TTSInstructionScreen...
├── config/TouhouAIFunConfig.java # COMMON config：LLM_STREAMING 等
├── mixin/               # 见「Mixin 兼容性」
└── network/             # AIFunNetwork + message/（AIFunSettingsMessage, AIFunTTSStreamMessage, AIFunTTSInterruptMessage）
```

## 注册覆盖机制（关键架构）

`SerializerRegister.register()` 是 `Map.put`：附属在 `for ILittleMaid EXTENSIONS` 循环里、**基模注册之后**执行，同名 key 直接覆盖基模站点。

- 在 `LittleMaidCompat` 注册的 `STT "aliyun"` 直接覆盖基模 `STTAliyunSite`（老 NLS 网关）。
- LLM 覆盖还要手动把 `DefaultLLMSite.ALIYUN` 重赋值为我方 site（仿 DEEPSEEK 写法），否则 `addDefaultSites()` 会用基模 openai 类型站点覆盖回 key，留下死注册。
- `AvailableSitesMixin`：`AvailableSites.init` 时若 key `stepfun`/`stepfun_plan` 被存成普通 `LLMOpenAISite`（用户改配置导致类型漂移），重建为我方 StepFun* 站点；并把 `stepfun_plan` 重排到 `stepfun` 后面（展示顺序）。

## 统一 LLM 中枢：ReasoningCompatOpenAIClient

StepFun / MiMo / Qwen / OpenAI 兼容 / DeepSeek 的 LLM 都继承它，自动获得：流式(SSE)、agent 工具调用保留、中途取消、TTS 情绪控制、`---` 双段契约。

### LLM 流式（SSE）
- 开关 `TouhouAIFunConfig.LLM_STREAMING`（全局，默认 true），UI 在服务商列表页底部（`AIChatSettingsLLMSiteScreenMixin` 注入），切换后 `AIFunSettingsMessage` 同步服务端。
- `ReasoningChatCompletion.enableStream()` 加 `stream:true` + `stream_options.include_usage`，`chatStreaming()` 用 `BodyHandlers.ofLines()`。
- `StreamAccumulator` 累加 content / reasoning_content / tool_calls（按 index 合并）/ usage，`buildResponse()` 用 JSON 重组再 GSON 解析成 `ReasoningOpenAIChatCompletionResponse`，喂给 `processChatResponse()`：有 tool_call → `onFunctionCall`（agent 循环照常，下一轮也流式）；纯文本 → `onTextCall`。
- 取消：consume 循环每行查 `ChatFlowManager.isSuperseded` / `shouldStopChat`，超代 break 关流；**循环结束后再查一次**，超代/女仆没了直接 return，不执行工具不朗读。

### 中途打断（ChatFlowManager，按 maid UUID）
三张 map：`LATEST_REQUEST`（maid→callback，判 superseded）、`TTS_GENERATION`（maid→int，TTS 代际）、`IN_FLIGHT`（maid→CompletableFuture）。

- **思考中被打断 → 主动取消**：`LLMCallbackMixin` `<init>` RETURN 注入 `registerRequest`，先 `cancelInFlight` 取消上一个在途 future（`cancel(true)` 断开底层 HTTP），再记为最新。**必须存原始 `sendAsync` future**，不能存 `.orTimeout/.whenComplete` 之后的，否则取消不了底层 HTTP。被取消触发 `onFailure`(CancellationException)，superseded 则 `ci.cancel()` 吞掉；竞态兜底：旧回复取消前完成 → `onSuccess` superseded → `addAssistantHistory` 保留进历史但不显示/不朗读。
- **语音播放中被打断**：最新回复 `onSuccess` 时 `beginTtsTakeover`（代际+1）+ `AIFunNetwork.sendInterruptTts` → 客户端 `QueuedTTSPlaybackManager.interrupt()` + `AIFunStreamSoundManager.interrupt()` 立即停旧音。
- **玩家按 STT 键（默认 X）开始语音对话 → 先终止女仆说话**：不等转写文本开启新一轮。`STTChatKeyMixin` 在 `tryToStart` HEAD 调 `SpeechMute.beginListening()`（客户端立刻 interrupt 两个播放管理器；录音期间 `SpeechMute.muted()` 让在途的旧 TTS 音频被丢弃，60s 安全上限）+ `AIFunNetwork.sendMaidSpeechStop(maidId)`；服务端 `AIFunMaidSpeechStopMessage` 校验女仆 owner 后调 `ChatFlowManager.interruptSpeech`（仅 TTS 代际+1：旧回复后续合成/发送被代际门控丢弃，文本回复仍照常完成入史，下一轮 `beginTurn` 会再 +1 让新回复正常出声）。松开按键 `sttStop` → `SpeechMute.endListening()`。
- 仅对我方 client 生效；车万女仆内置其它 LLM client 无法捕获 future，退回"跑完丢弃"。

### 流式 TTS（StreamingTtsReply，仅网络 TTS）
- 独占网络 TTS 文本回复的收尾，**不走** `callback.onSuccess`（否则重复合成）。首句 `ensureStarted` 只做 TTS 接管（`beginTtsTakeover` + `sendInterruptTts`，**不画气泡**）；`finish` 补完剩余句 + `addAssistantHistory` + `showChatBubble(完整 chatText)`（显示/广播最终气泡只能在 finish 做，否则聊天框文本截断 + 双气泡）。
- **顺序管理**：客户端 `QueuedTTSPlaybackManager` FIFO。服务端流水线并行合成 + 重排缓冲：`pump()` 最多 `MAX_CONCURRENT_SYNTHESIS`(=3) 在途，每句分配递增 `nextDispatchIndex`，`onSynthesized(index,data,gen)` 存入 `readyAudio`，从 `nextSendIndex` 起把连续就绪的句子按序冲刷（失败/静音句存 `EMPTY_AUDIO` 占位跳过）。**合成可并行超前，发送严格按句序** → 客户端到达顺序 == 句序。代际门控在发送前查 `ttsGeneration==capturedGeneration`；`ensureStarted` 在锁内设 `generation`（防并行 dispatch 抢到未设的 0）。
- 文本流：同语言单段整段即 ttsText；跨语言等出现 `---` 且后半段有真文本，按 `SentenceTextSplitter` 切句入队。
- **StepFun 官方流式会话（`StepFunStreamingReply` + `StepFunTTSStream`）**：站点为官方 `api.stepfun.com` 且模型属于 WS 列表时 `stepFunStreaming=true`，不再走 `batchWholeReply`。每句完成即以 `tts.text.delta` 追加进同一个 WS 会话（session 级 `instruction` 保留情绪上下文），服务端逐句合成回 PCM，`AIFunTTSStreamMessage` 边收边播 → 第一句可在 LLM 还没写完回复时出声。`pumpStepFun()` 在锁内按句顺序 feed（HTTP 路径的并行合成 + 重排缓冲在这里不需要）；**唱歌回复（`(唱歌)` 开头）例外**：完全绕过 WS，`finishSingingStepFun` 用整段文本走一次 HTTP 请求，保持整首歌的演唱连续性。播放已开始后失败只收尾不重试；**出声前**失败由 `StepFunStreamingReply` 用整段文本走一次 HTTP 兜底。

### 流式显示（StreamingDisplay，与 TTS 无关，每个流式回复都跑）
- 节流 120ms，尾部截断 160 字。思考阶段（`reasoning_content` 或未闭合 `<think>`）→ "思考中"气泡（`refreshWaitingChatBubble`）；答案阶段（`---` 前正文有内容）→ 切普通 `TextChatBubbleData`（`addChatBubble` 一次 + `setText` 原地更新, existTick 90s）。
- 用 `LLMCallbackAccessor` 把 callback 的 `waitingChatBubbleId` 同步指向实时气泡，finalizer 的 `addLLMChatText` 能干净替换（不留悬空气泡）。一旦 `---`+后半段有真文本 → `done` 停手交给 finalizer（submit 顺序保证最终文本最后落地）。

## LLM 响应语言：`---` 双段契约（PapiReplacerMixin）

`PapiReplacerMixin.replaceSetting` RETURN 注入，比较 `chatLanguage`（客户端语言）与 `ttsLanguage`（默认 `en_us`，`AIConfig.TTS_LANGUAGE`）：

1. **相同语言 → 单段契约**（`touhouAIFun$singleSegmentContract`）：只输出**一条**回复、不要 `---`、不要重复。显示/TTS 文本都从这一段派生（`ReasoningOpenAIResponseChat.singleSegment` + `firstSegment` 遇杂散 `---` 截断）。动机：消除"同一段写两遍第二遍漂移"。
2. **不同语言 → 两段契约**：Part1 = 聊天语言回复（显示），Part2 = **翻译**成 tts_language（朗读），对应基模 `OUTPUT_FORMAT_REQUIREMENTS_DIFFERENT_LANGUAGES`。

解析容错（`ReasoningOpenAIResponseChat`）：`LEADING_MARKER` 兼容全角 `（）`；`stripPartLabel` 去 `Part 1:`/`Part 2:` 标签；`normalizeRepeatedParts` 折叠擅自重复。**注意**：提示词里绝对不能出现"copy Part1"这种与"翻译"矛盾的要求——强模型（DeepSeek）最容易被内部矛盾带偏。

## TTS 情绪控制（3 模式）

逻辑在 `PapiReplacerMixin`（系统提示）+ `EmotionControlPrompts.turnReminder`（每轮末条提醒）。

- **支持判定** `EmotionControlPrompts.isSupported`：按 `ttsSite.getApiType()` + `getTTSModel()` 前缀。**坑**：站点有 "plan" 变体，apiType 是 `stepfun_plan`/`mimo_plan`，且模型名带 `:音色` 后缀（如 `stepaudio-2.5-tts:yuanqishaonv`）——已归一化掉 `_plan` 再匹配；模型 `startsWith("stepaudio-2.5-tts")` / `startsWith("mimo-v2.5-tts")` 容忍音色后缀。
- **3 模式**（两个 bool：`TTS_EMOTION_CONTROL` + `TTS_EMOTION_IN_TEXT`）：OFF / HIDDEN（括号只进 TTS 文本，气泡看不到）/ VISIBLE（气泡也显示）。按钮在 TTS 设置页（`AIChatSettingsTTSSiteScreenMixin`），循环切换。
- 情绪标记用 **ASCII 半角括号**；唱歌必须 `(唱歌)` 且表演独占整条回复；流式逐句合成时 `carryEmotion` 把当前 marker 带到后续无标记句，`splitAtMarkers` 让每个 marker 单独起块。MiMo 有自己的标签体系（`<mood>…</mood>` 风格 + 组合），见 `EmotionControlPrompts`（最近一次提交扩充了 MiMo 标签分类与组合）。
- `AIFunSettingsMessage` 带 4 个 bool（sentenceStreaming, llmStreaming, emotionControl, emotionInText）。

## TTS 切分（TTSProgressiveSynthesis）

`SentenceTextSplitter.SENTENCE_ENDINGS` 切分点含句末符 + **逗号/读点**（`，,、`），让 TTS 更早出声。受 `TTS_SENTENCE_STREAMING` 配置控制。

## 畅聊模式（免按键音量门控 + JEV/Perplexity 判定）

新增 `client/voice/`、`chat/freechat/`、`compat/ai/stt/BufferedSTTClient`：

- **触发链**：`FreeChatController`（ClientTick）→ `VadCapture` 独占 16kHz 麦克风 → `VadSegmenter`
  音量门控/端点 → `PcmWav` → 选中 STT 站点的 `BufferedSTTClient.transcribe` → C2S
  `AIFunFreeChatUtteranceMessage` → `FreeChatGate`（服务端主线程校验/自回声/打断）→ 异步
  `NativeDecisionProvider` → 回主线程 `MaidAIChatManager.chat`。
- **前置**：`decisionService.enabled=true` + provider 为 jev/perplexity + Key 非空；否则
  `decision_unavailable`，绝不回退到后台执行 LLM。只支持本模组可缓冲转写的 STT（StepFun/
  StepPlan、MiMo、Qwen）；基模站点按键语音照旧。
- **UI**：`AIChatSettingsSTTConfigScreenMixin` 在 STT 配置页加畅聊开关、判定服务入口和
  295×30 的 `FreeChatGateMeterWidget`（实时 RMS、5 秒历史、拖动阈值、女仆说话时 1.5× 琥珀线、
  触发指示）。打开页面时 `startMonitor` 只监听麦克风；关闭后由 tick 自动 `stopMonitor` 并恢复
  畅聊。阈值保存到 `[stt].freeChatThreshold`。
- **TTS 期间照常采集**：没有采集抑制；只把起音阈值 ×1.5。`SelfEchoMatcher` 在决策前比较
  转写与候选女仆最近 2 条 assistant 历史（规范化后包含或 bigram Dice ≥ 0.8），命中
  `self_echo` 直接丢弃。
- **打断**：`[stt].freeChatBargeIn` 三档。`after_asr`（默认）在自回声检查通过后、决策前对候选
  女仆 `ChatFlowManager.interruptSpeech` + `AIFunNetwork.sendInterruptTts`；`immediate` 由客户端
  在起音时停本地播放并发现有 `AIFunMaidSpeechStopMessage`；`off` 等新回合接管。打断只静音 TTS，
  不取消文本回复。
- **决策**：`FreeChatDecision` 纯函数构造 4 个 choice：`target`（maid_i/other_player/
  other_entity/environment/ambient/unknown）、`validity`（meaningful/too_short_incoherent/
  background）、`maid_related`（direct/relevant/maid_general/unrelated/unclear）、
  `intervention`（answer/brief_ack/clarify/ignore）。候选直呼不看 related；`other_player` 要求
  direct/relevant 且置信 ≥ `freeChatPlayerRelatedConfidence`（默认 0.85）；其他实体/方块/环境
  门槛 `freeChatOtherRelatedConfidence`（默认 0.7）。通用门槛 `freeChatMinConfidence`（0.7）。
- **上下文**：`FreeChatContext` 在 `chat()` 前按 maid+message 身份挂起 ambient facts，
  `MaidAIChatManagerMixin.normalChat` 取到并在 `<context>` 内插入；不写入聊天历史。`player_view`
  是客户端上报的玩家视角实体摘要（候选女仆、准星目标、其他玩家/实体、方向/距离/视野/朝向），
  服务端清洗截断；最近 3 条环境转写和候选 `recent_reply` 用于判断玩家对话话题相关性。
- **网络版本 21**：ID 31 C2S utterance、32 S2C feedback。`FreeChatGate` 有每玩家 800ms 限流、
  全局 `DecisionRequestGate` 共享 10/s、永久错误 10s 退避；日志不记录转写/场景/最近对话。
- **坑**：不要用 `MicrophoneManager.startRecord` 做畅聊（静态录音状态与 STT 键冲突）；
  按键语音时 `STTChatKeyMixin` 会让畅聊暂停。扬声器外放会把女仆 TTS 录进来，建议耳机或
  `after_asr`/`off`。`AIFunStreamSoundManager.isSpeaking()` 跟踪 PCM 流是否仍在播放，不能只看
  收到 END。

## Provider 集成要点

### 通用约束
- **音频格式必须 MP3**：基模 `MaidAISoundInstance` 只解 MP3/Opus/Vorbis；wav 会被当 Ogg 解 → `OggPacket null` 崩溃（StepFun 的 wav 不崩是因为走自定义 PCM 流，不经基模解码）。
- 各家 TTS 默认格式先查文档，别信"wav 兼容"。

### MiMo（`compat/ai/mimo/`）
- 端点：按量付费 `https://api.xiaomimimo.com/v1`；Token Plan 中国 `https://token-plan-cn.xiaomimimo.com/v1`。
- **`api_type` 必须写 `"mimo"`**，不能写 `"openai"`——否则 `MimoEndpointResolver` 不触发，`tp-` key 发到按量端点返回 401。Resolver 自动检测 `tp-` 前缀重定向。
- TTS 请求必须 `"format": "mp3"`。默认音色：`mimo_default`（双语）/`default_zh`/`default_en`/`冰糖`/`茉莉`/`苏打`/`白桦`/`Mia`/`Chloe`/`Milo`/`Dean`。

### 阶跃星辰 StepFun（`compat/ai/stepfun/`）
- 有 `stepfun` 与 `stepfun_plan` 两种 apiType；LLM/TTS/STT 各成对（`StepFunPlanLLMSite` 等）。
- **TTS 两套通道**（配置 `[tts].stepfunStreaming`，默认开）：
  - **官方流式 WS**：`wss://api.stepfun.com/v1/realtime/audio?model=<model>`（Step Plan 为 `wss://api.stepfun.com/step_plan/v1/realtime/audio`），头 `Authorization: Bearer <key>`。事件流：`tts.connection.done` → 发 `tts.create`（`response_format: pcm`、`sample_rate: 24000`、`mode: sentence`、可选 `instruction`）→ `tts.response.created` → 若干 `tts.text.delta`（单条 ≤1000 字符）→ `tts.text.done`；服务端回 `tts.response.audio.delta`（`data.audio` = base64 **裸 16-bit LE 单声道 PCM**，实测 24000Hz；`status` 可忽略）与 `tts.response.audio.done`（**其中 audio 是完整音频，绝不能再播/拼**）。PCM 经 `AIFunTTSStreamMessage`（START/DATA/END）实时下发，客户端 `AIFunPcmAudioStream` 边收边播，不经基模解码。
  - **HTTP 兜底**：`POST /v1/audio/speech`，`response_format: mp3`（基模 `MaidAISoundInstance` 只认 MP3/Opus/Vorbis）。
- WS 触发条件：host 必须是官方 `api.stepfun.com`（自定义 URL/代理仍走 HTTP）+ 模型在 `stepaudio-3-tts`/`stepaudio-2.5-tts`/`step-tts-2`/`step-tts-mini`/`step-tts-vivid` 内。连接/握手失败（401、代理阻断 WS 等）且尚未出声 → 自动回落 HTTP；已出声后失败只结束当前 PCM 流（避免重复朗读）。
- 气泡：流式成功不再调用 `callback.onSuccess`（否则会重复发音频），改为经 `TTSCallbackAccessor` 读 `chatText`/`waitingChatBubbleId` 复刻基模气泡；`StreamingTtsReply` 自己的回调 chatText 为空，气泡仍由 `showChatBubble` 统一负责。
- `instruction` 长度：`stepaudio-3-tts` 500 字、`stepaudio-2.5-tts` 200 字；两个模型都支持文本内联 `()` 不发音指令（情绪标记）。**唱歌（`(唱歌)` 开头）统一走整段 HTTP 请求**（`SINGING_INSTRUCTION`），不进入 WS 流式路径，保持整首歌的演唱连续性。
- 冒烟脚本：`scripts/smoke-stepfun-streaming.ps1`（读本地 `run/config/.../sites/tts.json` 的 stepfun key，逐句发 `tts.text.delta`，断言首块音频到达并保存可播放 wav）。

### Anthropic 协议 DeepSeek（`compat/ai/anthropic/`，Anthropic 模式）
- `AnthropicShared.API_TYPE = "anthropic"`，defaultSite id 同 `"anthropic"`（**必须等于注册键**：基模列表 map 按注册键存、按钮按 `site.id()` 操作，不一致会导致编辑/启用/删除静默失效；显示名经 lang 键 `ai.touhou_little_maid.chat.site.anthropic.name`），默认端点 `https://api.deepseek.com/anthropic`（client 自动拼 `/v1/messages`），默认模型 `deepseek-v4-flash`/`deepseek-v4-pro`。
- 协议：`POST /v1/messages`，`x-api-key` 认证（`anthropic-version` 头 DeepSeek 忽略但照发）；`system` 是**顶层字段**；assistant 工具调用是 `tool_use` 块、工具结果是 `user` 消息里的 `tool_result` 块（连续 tool 消息合并为一条）；响应块 `text`/`thinking`/`tool_use`/`server_tool_use`/`web_search_tool_result`。
- **联网搜索已从 Anthropic 主会话解耦**：模型侧只暴露普通函数工具 `web_search(query)`；`compat/ai/web/` 提供稳定工具协议、运行时和可替换 provider。当前 `DeepSeekNativeWebSearchProvider` 参考 DSH，独立发起一次辅助 `/v1/messages` 请求并使用 `web_search_20250305`，再把 `web_search_tool_result` 映射为统一的 `WebSearchResult`。因此 OpenAI、OpenCode Go、StepFun、MiMo 等任何支持函数调用的主 LLM 都可使用搜索，不要求主会话采用 Anthropic 协议。当前 provider 复用已配置的 `anthropic` 站点 URL/API key 作为搜索凭据；未来增加其他搜索 provider 不应改模型侧工具。
- 实现：`AnthropicLLMSite`（anthropic 包）+ `AnthropicCompatLLMClient`（**openai 包内**，因 `ReasoningOpenAIResponseChat`/`StreamingTtsReply`/`StreamingDisplay` 是 package-private）。流式把 Anthropic SSE 事件翻译成 `StreamChunk` 喂 `StreamAccumulator` 归一化（usage 转 OpenAI 形状：prompt/completion），走与 OpenAI 相同的显示/TTS/取消链路。**请求体必须带 `"stream": true`**（LLM_STREAMING 时）：漏带会让端点返回普通 JSON，SSE 消费者整行忽略 → 空文本报错（踩过的坑）。**不传 `thinking` 字段**：Anthropic 协议默认非思考，显式 `{"type":"disabled"}` 不是合法值可能 400，让服务端默认行为真实可见（用户可观察女仆思考开没开）。
- 系统提示词：`PapiReplacerMixin` 注入末尾按 `site.getApiType()=="anthropic"` 附加 `touhouAIFun$webSearchGuidance()`（何时搜索、基于结果作答、禁止编造、仍守输出格式契约）。
- 冒烟脚本：`scripts/smoke-deepseek-anthropic.ps1 -ApiKey sk-xxx`（验证 `/v1/messages` 基本协议及 DeepSeek 原生搜索 provider 所需的 server-tool 返回结构）。

### Qwen / 百炼（`compat/ai/qwen/`，夺舍基模 "aliyun" 身份）
- `QwenShared.API_TYPE = "aliyun"`，图标用 `SerializableSite.defaultIcon("aliyun")`，显示名沿用"阿里云"；中国站 `dashscope.aliyuncs.com`。
- **三类端点**：
  - LLM + STT：OpenAI 兼容 `…/compatible-mode/v1/chat/completions`（STT 用 `qwen3-asr-flash`，body 为 chat+`input_audio`，换模型不变请求方式）。
  - TTS 合成：DashScope 原生 `…/api/v1/services/aigc/multimodal-generation/generation`，body `{"model","input":{"text","voice"}}`，**响应是 `output.audio.url`（需二次 GET 下载字节）**；client 对 `audio.url` 和 `audio.data` 兜底。
  - 音色克隆/设计：`…/api/v1/services/audio/tts/customization`（由合成 URL 经 `CustomVoiceHttpUtil.replacePath` 派生），`action:create` 返回 `output.voice`。
- 模型常量在 `QwenShared`（target_model 必须与合成 model 完全一致）：预置 `qwen3-tts-flash`；克隆 = 合成 `qwen3-tts-vc-2026-01-22` + 注册 `qwen-voice-enrollment`；设计 = 合成 `qwen3-tts-vd-2026-01-26` + 注册 `qwen-voice-design`（传 `voice_prompt`）。**带日期版本号，阿里更新要成对改**。
- TTS 必须 `parameters.response_format = "mp3"`（qwen3-tts 默认 wav，基模会崩）。429 `Throttling.RateQuota`：基模流式 TTS 按句并发易触发，`QwenTTSClient` 有指数退避重试（MAX_RETRIES=3, 基准 700ms）。
- 热词（`vocabulary_id`）只支持 fun-asr/paraformer 且仅实时识别(WebSocket)/录音文件模式生效——**qwen3-asr-flash 兼容模式不支持热词**，已决定不做。
- 覆盖 STT "aliyun" 后，旧 aliyun 配置（app_key 字段）无法被新序列化器解析，用户需重建站点。

## Mixin 兼容性（跨 Forge 版本 remap 规则，血泪教训）

dev 用 official 映射，本番用 SRG 名，桥梁是编译期生成的 **refmap**。之前"全部 `remap = false`"的方针是**错的**，曾导致整合包 `InvalidAccessorException: No candidates were found matching addRenderableWidget` 崩溃。

- **目标是原版 MC 类**（`Screen`/`SoundManager` 等）→ `remap = true`（默认），靠 refmap。
- **目标是其他 mod 类**（TLM 的 `AIChatSettings*`/`LLMCallback`/`PapiReplacer`/`AvailableSites` 等）→ `remap = false` 正确（mod 类不混淆）。
- **坑 1**：`@Mixin(remap=false)` 目标是 mod 类，但注入方法是它**重写/继承的原版方法**（`init`/`render`/`tick`/`mouseClicked`…）时，本番是 SRG 名 → 在该注入器单独加 `remap = true`（如 `@ModifyConstant(method = "init", ..., remap = true)`）。判据：method 名是原版 Screen 方法 → 注入器 remap=true；是 mod 自有方法（initContent/buildModels…）→ remap=false。
- **坑 2**：`@Mixin(remap=false)` 注入 mod 方法，但 `@At(INVOKE, target=...)` 指向原版方法（如 `Font.drawInBatch`）→ method 选择器不 remap，`@At(..., remap = true)` 单独开。
- **泛型 @Accessor/@Invoker 走不通**（Mixin AP 无法为泛型生成 refmap）→ 删 invoker，改用 **Access Transformer** 设 public 后直接调用（`META-INF/accesstransformer.cfg`，reobfJar 会把调用重映射）。
- **refmap 配置**：build.gradle 用 mixingradle 0.7.+，且**必须手动**在 `touhou_aifun.mixins.json` 加 `"refmap": "touhou_aifun.refmap.json"`——`add sourceSets.main` 不会写进 mixins.json，否则运行时报 `No refMap loaded` 全部注入找不到目标。
- SRG 名在同一 MC 版本（1.20.1）下跨 Forge 小版本稳定，问题根因是 remap=false 而非版本漂移。

## 网络消息（network/）

- `AIFunNetwork` 统一注册；`AIFunSettingsMessage`（4 bool 服务端同步）、`AIFunTTSStreamMessage`（流式音频逐句下发，PLAY_TO_CLIENT + TRACKING_ENTITY）、`AIFunTTSInterruptMessage`（打断旧语音）、`AIFunMaidSpeechStopMessage`（PLAY_TO_SERVER，玩家按下 STT 键开始录音时请求服务端停止女仆当前说话）、`AIFunFreeChatUtteranceMessage`（PLAY_TO_SERVER，转写 + 场景 + 候选 + 最近环境转写）、`AIFunFreeChatFeedbackMessage`（PLAY_TO_CLIENT，固定原因码）。**网络版本 21**；升版后两端必须一起更新。

## 战斗反射 / 护主 / 仇恨指挥（maid/combat）

服务端事件驱动，不经过 LLM，也不替换工作模式。核心链路：
`MaidCombatEvents`（`MaidHurtEvent` / `LivingHurtEvent` / `MaidHurtTarget.Pre`）→ `MaidCombatReflex`
（owner/self 威胁 + 显式命令 + 清除抑制三层，目标优先级 owner > command > self）→
`MaidCombatBrain implements IExtraMaidBrain` 注册 CORE 行为（priority 1 `MaidCombatTargetTask`/
`MaidCombatMeleeTask`，priority 99 `MaidCombatChaseTask`）。CORE 行为让夜间/非 WORK 日程也能还手；
近战任务绕过基模 `NEAREST_VISIBLE_LIVING_ENTITIES` 的 20 tick 传感器，远程/弹幕任务只锁目标
（`TARGET_ONLY`），绝不强迫远程女仆近战。

**玩家硬规则**（`MaidCombatRules` + `MaidCombatPolicy`）：玩家可还手，但永远排除实际主人；
公共女仆（`PublicMaidAccess.isPublic`）或友伤关闭（`!isFriendlyFireAllowed`）时拒绝一切玩家目标；
`attackPlayers=false` 进一步全禁；创造/旁观/FakePlayer 拒绝；宠物/NPC/女仆/`MaidNoAttack` 拒绝。
同队玩家在友伤开启时可按“只排除主人”的规则还手。

**清除仇恨**：`mixin/MaidCombatTargetMixin` 在 `EntityMaid#canAttack` HEAD 返回 false；
`EntityMaid` 是 mod 类但 `canAttack` 重写了原版 `LivingEntity#canAttack`，**按 AGENTS.md 的坑 1
对该注入器单独用 `remap=true`**。`MaidHurtTarget.Pre` 取消伤害；抑制到 `aggroClearTicks`。
基模攻击模式仍可能攻击其他敌对生物；要全停请切工作模式。

**数据/工具**：`MaidCombatData` 把 per-maid 规则存在 `maid.getPersistentData()` 的
`TouhouAIFunCombat`，不新增网络包；`combat_target` 指挥/清除目标（玩家门控照旧），
`combat_control` 规则/状态（改规则仅实际主人，经 `AgentRuntime.ownerSpeaker`，状态任何有工具权限
的说话者）。两个工具注册在 `LittleMaidCompat.registerAITool`，并加入
`ToolContextSelector.CORE_TOOLS`、`AgentExecution.foregroundTool`、`ToolExecutionPolicy` 豁免；
`PapiReplacerMixin` 追加 `MaidCombatPrompts.all(maid)`（guidance/status/notes）。配置在 `[combat]`。

**测试**：`src/test/java/.../maid/combat/*`（规则/NBT/状态/提示词纯测试）；`CombatReflexGameTests`
（`src/guiQa`，`@GameTestHolder("aifun_combat_test")`，`-PguiQa -PcombatQa`）。4 个 GameTest 必须
放在**独立 batch**：`MaidCombatReflex` 的 map 是静态的，同 batch 并发会互相清状态；且不要用
`QaConfigScope` 改全局配置（并发批次会互相恢复）。`GameTestHelper.makeMockPlayer()` 的 mock 会被
玩家门控判为创造模式；测试 fixture 直接 `new Player(...) { isCreative()/isSpectator() 返回 false }`
才走普通玩家路径。

## 已知坑 / 排查

- **同一句女仆台词在聊天栏出现两次**：TLM 的 `ChatBubbleManager.addLLMChatText` 同时做两件事——替换头顶气泡 + 给主人发一条 `<女仆名> 文本` 聊天栏消息。任何"先手动加气泡（立刻显示）→ TTS 成功/失败回调再加一次"的流程都会二次发聊天栏消息。任务公告 `AgentRuntime.deliverNotification`（bubbleId=-1）就是典型：手动 `addLLMChatText` + `manager.tts(...)` 回调各加一次。`ChatBubbleManagerMixin` 原有去重只处理 `waitingChatBubbleId >= 0`（气泡已被移除的迟到回调），公告用 -1 绕过去了。现由 `chat/ChatLineDedup` 在 mixin HEAD 统一 claim `(maid UUID, bubbleId, text)`：正 id 用 60s 窗口、-1 公告用 15s 文本窗口，重复调用直接 cancel（只跳过聊天栏/气泡重复，音频链路不受影响）。
- **光影下气泡文字消失**（Oculus/IterationRP）：不是本模组，是 **AcceleratedRendering** 的加速文字与光影不兼容。改 `config/acceleratedrendering-client.toml` 的 `[accelerated_text_rendering] default_pipeline = "ACCELERATED" → "VANILLA"`（或 feature_status=DISABLED）。判据：改 `Font.DisplayMode` 完全无效 = 不是深度问题。曾尝试的 `EntityGraphicsMixin` 改 DisplayMode 已删除（无效）。
- **本环境 gradlew 可编译**：`JAVA_HOME` 指 JDK 17（本机为 `C:\Program Files\Java\jdk-17.0.20`；系统 PATH 的 java 是 1.8，勿用）且**在线模式**（offline 缺 Forge 依赖缓存）。`gradlew build` 全流程（compileJava/reobfJar/refmap）可跑。
- 本仓库是 Windows + 中文环境，与用户沟通用中文。

## 最近提交与验证状态（2026-10-18）

工作区目前干净（`git status` 无改动）。最近一批提交包含：

- **畅聊模式**：`client/voice/`（VadCapture/VadSegmenter/FreeChatController/GateMeter/PcmWav）、
  `chat/freechat/`（FreeChatGate/FreeChatDecision/SelfEchoMatcher/FreeChatContext）、
  `compat/ai/stt/BufferedSTTClient`、网络版本 21（ID 30 speech stop、31 C2S utterance、
  32 S2C feedback）、STT 配置页门控仪表盘；按下 STT 键先停女仆说话（`SpeechMute`）。
- **流式 TTS**：`StepFunTTSStream`（官方 WS 会话、PCM 转发、空闲看门狗、代际门控）+
  `StepFunStreamingReply`（逐句增量合成 + HTTP 兜底）、`TTSCallbackAccessor`、`ChatLineDedup`、
  `[tts].stepfunStreaming`。
- **战斗反射**：`maid/combat/`（规则/数据/策略/反射三层/状态/提示词/CORE 行为/事件）+
  `MaidCombatTargetMixin`、`combat_target`/`combat_control` 工具、`[combat]` 配置、`docs/combat.md`。
- **任务公告与阶段决策**：`[agentRuntimeV1].taskAnnouncements`（detailed/brief/silent）、
  `TaskAnnouncements`/`TaskAnnouncementPhrasing`/`TaskAnnouncementPrefetch`、`TaskStageChoices` 与
  `TaskDecisionRouter` 阶段迁移、`drop_items_to_owner` 工具（`MaidOwnerDropAction`/`MaidBackpackSpace`）。
- **QA**：`QaConfigScope`、`aifun_native_decision_test`/`aifun_combat_test`/
  `aifun_observation_regression_test`/`aifun_observation_live_test`/`aifun_stage_live_test`、
  `scripts/stage-decision-live-report.py`，`build.gradle` 对应 `-P...` 开关。

`gradlew build` / `gradlew test` 通过；GameTest 与真实服务商 API 的端到端行为仍需实机/真实 key 复验
（前置与坑见上文各功能小节）。
