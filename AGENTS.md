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
│   │   ├── qwen/        # 通义千问/百炼（LLM+STT+TTS），夺舍基模 "aliyun" 身份（未提交）
│   │   ├── openai/      # 通用兼容层：ReasoningCompatOpenAIClient（流式/取消/agent 中枢）
│   │   ├── fishaudio/ minimax/ siliconflow/   # 各 TTS 引擎
│   │   └── tts/         # TTSProgressiveSynthesis, SentenceTextSplitter, CustomVoiceHttpUtil, VoicePreset*
│   ├── ai/*/layout/     # 站点表单布局（TTS/STT 配置页 UI）
│   └── ai/*/response|request/  # 各家 API 的请求/响应 DTO
├── chat/
│   ├── ChatFlowManager.java      # 按 maid 管理请求代际/在途 future（中途打断核心）
│   └── ChatBubbleDisplayTime.java# 气泡存活时间按文本长度缩放（未提交）
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
- `AvailableSitesMixin`（未提交）：`AvailableSites.init` 时若 key `stepfun`/`stepfun_plan` 被存成普通 `LLMOpenAISite`（用户改配置导致类型漂移），重建为我方 StepFun* 站点；并把 `stepfun_plan` 重排到 `stepfun` 后面（展示顺序）。

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
- 仅对我方 client 生效；车万女仆内置其它 LLM client 无法捕获 future，退回"跑完丢弃"。

### 流式 TTS（StreamingTtsReply，仅网络 TTS）
- 独占网络 TTS 文本回复的收尾，**不走** `callback.onSuccess`（否则重复合成）。首句 `ensureStarted` 只做 TTS 接管（`beginTtsTakeover` + `sendInterruptTts`，**不画气泡**）；`finish` 补完剩余句 + `addAssistantHistory` + `showChatBubble(完整 chatText)`（显示/广播最终气泡只能在 finish 做，否则聊天框文本截断 + 双气泡）。
- **顺序管理**：客户端 `QueuedTTSPlaybackManager` FIFO。服务端流水线并行合成 + 重排缓冲：`pump()` 最多 `MAX_CONCURRENT_SYNTHESIS`(=3) 在途，每句分配递增 `nextDispatchIndex`，`onSynthesized(index,data,gen)` 存入 `readyAudio`，从 `nextSendIndex` 起把连续就绪的句子按序冲刷（失败/静音句存 `EMPTY_AUDIO` 占位跳过）。**合成可并行超前，发送严格按句序** → 客户端到达顺序 == 句序。代际门控在发送前查 `ttsGeneration==capturedGeneration`；`ensureStarted` 在锁内设 `generation`（防并行 dispatch 抢到未设的 0）。
- 文本流：同语言单段整段即 ttsText；跨语言等出现 `---` 且后半段有真文本，按 `SentenceTextSplitter` 切句入队。

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
- TTS 走自定义 PCM 流播放（`AIFunPcmAudioStream`），不经基模解码。

### Anthropic 协议 DeepSeek（`compat/ai/anthropic/`，Anthropic 模式）
- `AnthropicShared.API_TYPE = "anthropic"`，defaultSite id 同 `"anthropic"`（**必须等于注册键**：基模列表 map 按注册键存、按钮按 `site.id()` 操作，不一致会导致编辑/启用/删除静默失效；显示名经 lang 键 `ai.touhou_little_maid.chat.site.anthropic.name`），默认端点 `https://api.deepseek.com/anthropic`（client 自动拼 `/v1/messages`），默认模型 `deepseek-v4-flash`/`deepseek-v4-pro`。
- 协议：`POST /v1/messages`，`x-api-key` 认证（`anthropic-version` 头 DeepSeek 忽略但照发）；`system` 是**顶层字段**；assistant 工具调用是 `tool_use` 块、工具结果是 `user` 消息里的 `tool_result` 块（连续 tool 消息合并为一条）；响应块 `text`/`thinking`/`tool_use`/`server_tool_use`/`web_search_tool_result`。
- **联网搜索是服务端 server tool**：请求 `tools` 前置声明 `{"type":"web_search_20250305","name":"web_search","max_uses":3}`，DeepSeek 自己执行搜索、同一轮返回 `server_tool_use`+`web_search_tool_result`，客户端**跳过**这两个块（结果已在上下文，后续 text 块即答案）。**必须用 `20250305` 版本**：`20260209`+ 依赖 code execution，DeepSeek 不支持。OpenAI 兼容端点无此能力——这是该模式存在的意义。**搜索轮可能无文本**（模型先发起搜索、本轮未作答）：client 会保留未配对的 `server_tool_use` 块并在下一轮请求中原样回传（Anthropic server tool 语义），自动续轮最多 3 次；空文本且无待续工具时才走基模 `CHAT_TEXT_IS_EMPTY` 兜底。
- 实现：`AnthropicLLMSite`（anthropic 包）+ `AnthropicCompatLLMClient`（**openai 包内**，因 `ReasoningOpenAIResponseChat`/`StreamingTtsReply`/`StreamingDisplay` 是 package-private）。流式把 Anthropic SSE 事件翻译成 `StreamChunk` 喂 `StreamAccumulator` 归一化（usage 转 OpenAI 形状：prompt/completion），走与 OpenAI 相同的显示/TTS/取消链路。**请求体必须带 `"stream": true`**（LLM_STREAMING 时）：漏带会让端点返回普通 JSON，SSE 消费者整行忽略 → 空文本报错（踩过的坑）。**不传 `thinking` 字段**：Anthropic 协议默认非思考，显式 `{"type":"disabled"}` 不是合法值可能 400，让服务端默认行为真实可见（用户可观察女仆思考开没开）。
- 系统提示词：`PapiReplacerMixin` 注入末尾按 `site.getApiType()=="anthropic"` 附加 `touhouAIFun$webSearchGuidance()`（何时搜索、基于结果作答、禁止编造、仍守输出格式契约）。
- 冒烟脚本：`scripts/smoke-deepseek-anthropic.ps1 -ApiKey sk-xxx`（验证 /v1/messages 基本聊天 + web_search server tool 返回结构）。

### Qwen / 百炼（`compat/ai/qwen/`，未提交，夺舍基模 "aliyun" 身份）
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

- `AIFunNetwork` 统一注册；`AIFunSettingsMessage`（4 bool 服务端同步）、`AIFunTTSStreamMessage`（流式音频逐句下发，PLAY_TO_CLIENT + TRACKING_ENTITY）、`AIFunTTSInterruptMessage`（打断旧语音）。

## 已知坑 / 排查

- **光影下气泡文字消失**（Oculus/IterationRP）：不是本模组，是 **AcceleratedRendering** 的加速文字与光影不兼容。改 `config/acceleratedrendering-client.toml` 的 `[accelerated_text_rendering] default_pipeline = "ACCELERATED" → "VANILLA"`（或 feature_status=DISABLED）。判据：改 `Font.DisplayMode` 完全无效 = 不是深度问题。曾尝试的 `EntityGraphicsMixin` 改 DisplayMode 已删除（无效）。
- **本环境 gradlew 可编译**：需设 `JAVA_HOME=C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.16+8`（系统 PATH 的 java 是 1.8，勿用）且**在线模式**（offline 缺 Forge 依赖缓存）。`gradlew build` 全流程（compileJava/reobfJar/refmap）可跑。
- 本仓库是 Windows + 中文环境，与用户沟通用中文。

## 未提交的工作（写本文件时）

git status：修改 `TouhouAIFun`、`CustomVoiceScreen`、`LittleMaidCompat`、`EmotionControlPrompts`、`ReasoningCompatOpenAIClient`、`StreamingTtsReply`、`StepFunTTSFormLayout`、`ChatBubbleManagerMixin`、`PapiReplacerMixin`、`LLMSiteEditorScreenMixin`、`TTSSiteEditorScreenMixin`、`touhou_aifun.mixins.json`；新增 `chat/ChatBubbleDisplayTime`（气泡存活时间按长度缩放，下限基模默认、上限 120s）、`compat/ai/qwen/` 全套、`AvailableSitesMixin`。尚未提交，改动是否完整需验证。

本次新增（同批未提交）：`compat/ai/anthropic/`（`AnthropicLLMSite` + `AnthropicShared`，Anthropic 协议 DeepSeek 选项）、`compat/ai/openai/AnthropicCompatLLMClient`（/v1/messages 协议 + 流式 SSE + web_search server tool）、`PapiReplacerMixin` 联网搜索提示词段落、`LLMSiteEditorScreenMixin`/`LittleMaidCompat` 注册与恢复分支、`lang` 键 `anthropic`、`textures/gui/ai_chat/anthropic.png`（复制自基模 deepseek.png）、`scripts/smoke-deepseek-anthropic.ps1`。`gradlew build` 已验证通过；运行时验证需用户本地 runClient + 真实 API key。
