# 畅聊模式（免按键音量门控）

畅聊模式让玩家不用按 STT 键，直接对附近女仆说话。客户端只在麦克风音量越过门控时调用一次
语音识别（ASR）；每一条非空转写都会送到服务端的 **JEV / Perplexity 原生决策服务**，判断：

- 玩家在对谁说话（某位候选女仆、另一位玩家、其他实体、方块/环境、自言自语或背景声）；
- 内容是否有效（很短且过于没章法 → 无效）；
- 内容是否与女仆有关（玩家与玩家聊天时门槛更高）；
- 女仆该如何介入（直接回答 / 简短回应 / 问一句澄清 / 不介入）。

只有判定通过才会启动被选中女仆的正常 TLM 聊天回合。决策服务的 Key 只在服务端，不会下发
客户端；畅聊不会回退到后台执行 LLM。

## 前提

1. 在「女仆管理 → 后台执行模型 → 独立决策服务」中启用 **JEV** 或 **Perplexity**，并填入
   API Key。缺 Key、被停用、超时或返回非法结果时，畅聊不会回复。
2. 在「AI 聊天设置 → STT」中选择支持缓冲转写的站点：**StepFun / Step Plan、小米 MiMo、
   通义千问（Qwen）**。基模自带的 aliyun/player2/siliconflow/tencent STT 仍可用按键语音，
   但不支持畅聊。
3. 浏览器/系统允许 Minecraft 访问麦克风；在 STT 设置里选择正确的输入设备。

## 开启与门控仪表盘

「AI 聊天设置 → STT」页面下方有：

- **畅聊模式：开/关** 全宽开关；
- **判定服务** 按钮，直接打开 JEV / Perplexity 独立决策服务配置；
- **门控仪表盘**（295×30）：
  - 实时麦克风 RMS 与最近 5 秒历史柱；
  - 白色可拖动阈值线（门控阈值）；
  - 女仆正在说话时自动提高到 **1.5×** 的琥珀色阈值线；
  - 当前电平、触发指示、麦克风状态。

拖动白线即可调阈值，松手保存到 `config/touhou-aifun.toml` 的 `[stt].freeChatThreshold`。
打开 STT 页面时仪表盘会监听当前选择的麦克风（只测音量、不发 ASR）；关闭页面后畅聊按条件恢复。

默认配置（可在 config 文件进一步调整）：

| 配置 | 默认 | 说明 |
| --- | ---: | --- |
| `freeChat` | `false` | 总开关；UI 修改后立即保存 |
| `freeChatThreshold` | `700` | 起音 RMS；仪表盘可拖动 |
| `freeChatSilenceMillis` | `700` | 静音多久算一句话结束 |
| `freeChatMinSpeechMillis` | `250` | 更短的起音丢弃（只滤能量噪声） |
| `freeChatMaxUtteranceMillis` | `15000` | 单段最长，超长切段 |
| `freeChatPrerollMillis` | `300` | 起音前保留，避免吞字 |
| `freeChatBargeIn` | `immediate` | `immediate` / `after_asr` / `off` |
| `freeChatNotice` | `hud` | 反馈位置：`hud` / `chat` / `action_bar` / `off` |
| `freeChatMinConfidence` | `0.7` | 目标识别（对谁说）的通用置信门槛 |
| `freeChatSightMinConfidence` | `0.5` | 目标女仆在视野内（55°）或被视线命中时的较低门槛 |
| `freeChatQuestionFollowUpMinConfidence` | `0.35` | 女仆最近一句含问号、且她在视野内/被注视时的最低门槛 |
| `freeChatPlayerRelatedConfidence` | `0.85` | 对玩家说话时 `direct+relevant` 的概率质量下限 |
| `freeChatPlayerOffGazeRelatedGain` | `1.15` | 主人没盯着该玩家时，对玩家相关概率质量的增益（1.0 关闭） |
| `freeChatOtherRelatedConfidence` | `0.7` | 对其他实体/方块/环境的相关概率质量下限 |
| `freeChatInterventionIgnoreProbability` | `0.5` | 允许介入时 `ignore` 概率的上限 |

判定语义（与「置信度」字段的实际分布有关，2026-10 用真实 Perplexity Decider 标定）：

- **目标**分级用 `confidence`：通用门槛 `freeChatMinConfidence`（0.7）；目标女仆在视野内
  （55°）或被视线射线命中时降到 `freeChatSightMinConfidence`（0.5）；若她最近一句含
  `?`/`？`、且她在视野内/被注视，短回答降到 `freeChatQuestionFollowUpMinConfidence`
  （0.35）。只有 `maid_i` 目标享受这两档，`ambient`/其他玩家/实体不受影响。2026-10 实测中
  0.54–0.64 的纠正和追问因此不再被误杀；完全离屏的女仆仍按 0.7。
- **有效性**不设置信门槛：模型选择 `too_short_incoherent` / `background` 就过滤，选择
  `meaningful` 就交给后面的门槛，避免“短而清晰”的有效指令被低置信误杀；
- **相关性**用概率质量：`P(direct)+P(relevant)` 与阈值比较，而不是 `confidence` 字段
  （真实服务在 5 选 1 时 confidence 常只有 0.3–0.5，概率质量才反映真实倾向）；
- **视线增益**：判定目标是“另一位玩家”时，服务端检查主人是否真的看着对方（10° 视锥 +
  视线可达 + 16 格）。盯着玩家 = 明确的玩家对话，按 0.85 保守门槛；看女仆或看别处时，
  “在跟玩家聊天”的判断本身变弱、话题更可能与女仆有关，于是相关概率质量 ×
  `freeChatPlayerOffGazeRelatedGain`（默认 1.15，上限 1.0）。
- **介入方式**是软性的 4 选 1，实测即使“直接命令”其 `confidence` 也只有 0.35–0.55，
  因此不用通用置信门槛，而是看 `ignore` 的概率是否低于
  `freeChatInterventionIgnoreProbability`（默认 0.5），且模型没有直接选 `ignore`。
- **名字与 ASR 容错**：决策状态里每只候选女仆都带 `name`（实体名，即游戏里显示、玩家通常
  称呼的名字，等于 TLM 聊天系统提示用的名字），`player_view` 和 `target` 题干的候选描述里
  还会再出现一次；模型不知道的只有与实体名完全无关的私人昵称。转写来自语音识别，题干明确
  说明名字可能被同音/近音误识（例如 芙兰 → 弗兰/芙蓝），玩家正看着该女仆、或她是准星/
  最近目标时，近音名字也按点名处理，相似名字同时出现时选最接近的那个。
  实测真实 Perplexity Decider：`弗兰你过来一下`/`芙蓝你过来一下` 对候选“芙兰”的目标置信
  0.99+；两个相似候选（芙兰/芙蕾）能按转写区分（0.93）；没说名字但看着女仆（“过来”）也能
  正确选中（0.97）。

玩家与玩家聊天注意：阈值是**概率质量**。实测“Steve 我们叫上芙兰一起去挖矿吧”约 0.87，
“Steve 你觉得芙兰怎么样”约 0.84。盯着 Steve 时后者 0.84 会被拒绝；视线在女仆/别处时
0.84×1.15≈0.97 就能过。默认 `0.85` 仍是保守设定；如果希望女仆更愿意插话，
可把 `freeChatPlayerRelatedConfidence` 调到 `0.8`，或把增益调到 `1.2`。

## 反馈显示位置

「听到：…」和判定原因码默认显示在**屏幕下方居中、原版 action bar 正上方**的 HUD 通知条：
最多 3 行、最新一行在底部、约 4.5 秒后淡出，超宽会按屏幕宽度截断。它由本模组自己绘制，
不占用原版 action bar，因此不会顶掉其他模组的 action bar 消息；经验条、血量/护甲、
切换物品名等原版 HUD 也不受影响。

`[stt].freeChatNotice` 可选 `hud`（默认）/ `chat`（写聊天栏，保留历史）/ `action_bar`
（旧行为，可能覆盖其他消息）/ `off`（关闭反馈）。

## 女仆说话时的采集与打断

畅聊**不会**因为女仆正在播放 TTS 而停止采集：

- 女仆说话时门控临时 ×1.5，减少把她自己的声音当作玩家语音；
- 服务端会用 `SelfEchoMatcher` 丢弃与女仆最近回复高度相似的转写，避免自问自答；
- `freeChatBargeIn=immediate`（默认）：**音量门控一确认起音就打断**，不等 ASR、也不等判定
  服务——本地 TTS 立即停、并请求服务端停止候选女仆语音；这句话是否有效由后续判定决定，
  无效时女仆也不会继续把被打断的那句讲完。起音回调里直接触发（不等下一个 tick）。
  适合耳机；外放时女仆自己的声音可能触发门控导致“自打断”，此时可把阈值调高（400–700）
  或改用 `after_asr`。
- `freeChatBargeIn=after_asr`：等 ASR 返回且自回声检查通过后再停，适合外放/嘈杂环境。
- `freeChatBargeIn=off`：不提前停；判定通过后的新聊天回合会正常接管并打断旧语音。

扬声器外放会让麦克风拾取女仆的声音，产生额外 ASR 请求；建议使用耳机。日志里
`Free chat barge-in: gate onset stopped maid TTS (maids=N)` 可以确认打断是否在起音时触发。

麦克风线路的开关全部在采集线程完成：打开/关闭 STT 设置页、按 ESC、开聊天框时只做
`retune`（复用已打开的线路），只有真正离开世界/长时间关闭畅聊条件时才关闭设备，并且关闭
也是异步的。**不要**在渲染线程调用 JavaSound 的 `TargetDataLine.close()`——Windows 音频
驱动可能阻塞几十秒，表现为游戏窗口“未响应”。如果仍出现整游戏卡顿，先检查系统内存占用
（游戏本体 + Gradle 守护进程 + 其它语音/聊天应用同时跑很容易把 16GB 内存压满）。

## 玩家与玩家聊天

当判定目标为另一位玩家时，女仆只有在内容与女仆有明确关系时才介入，且门槛高于其他场景：

- `direct`：直接叫/点名女仆，或明显邀请她加入；
- `relevant`：聊天明确涉及她的任务、行为、外貌、感受、刚说的话/做的事；
- 泛泛聊女仆/AI 助手（`maid_general`）、无关（`unrelated`）、证据不足（`unclear`）都不介入；
- 门槛比较的是 `P(direct)+P(relevant)` 概率质量（默认 `0.85`，其余场景 `0.7`），
  不是 `confidence` 字段；
- 若主人当时**没有看着**这位玩家（视线在女仆或别处），服务端会给相关概率质量乘以
  `freeChatPlayerOffGazeRelatedGain`（默认 `1.15`）再比较：非玩家视线下“在跟玩家聊天”的
  判断更弱，话题更可能与女仆有关，所以适当放宽。盯着玩家看时不加增益。

判定状态会附带本地玩家最近 3 条环境转写和候选女仆最近一条回复，帮助判断话题是否与女仆有关。
非候选目标的回应者默认是“第一位非 busy 候选女仆”（准星/最近优先），这是初版限制。

## 隐私

- 麦克风音频会发送给选中的 STT 服务商；
- 转写、玩家视角实体摘要、最近环境转写、候选女仆最近一条回复摘要会发送给服务端配置的
  JEV/Perplexity 决策服务；
- 这些内容只用于本次判定和当前聊天回合，不写入日志（诊断日志只有阶段、数量、原因码和耗时）；
- 女仆聊天历史仍按 TLM 的既有规则保存；普通文字/按键语音回合不受影响。

## 排查

日志只记录阶段、数量、原因码和耗时，**不记录转写原文、玩家视角内容或最近环境对话**。
一次正常触发在 `latest.log` 里依次出现：

1. `Free chat listening: device=... threshold=... silenceMs=...`（客户端开始聆听）
2. `Free chat speech captured: speechMs=... peak=... candidates=...`（音量门控切出一段）
3. `Free chat speech recognized: chars=...`（STT 返回非空文本）
4. `Free chat utterance sent: sequence=... candidates=... transcriptChars=...`（C2S 包已发出）
5. `Free chat request received: sequence=... candidateIds=...`（服务端收到）
6. `Free chat asking the decision service: provider=... candidate count=...`
7. `Free chat decision verdict: accepted=... reason=... target=... action=... related=... elapsedMs=...`
8. `Free chat accepted: provider=...` + 客户端 `Free chat verdict from server: status=accepted`
   或者 `Free chat request rejected: reason=...` + 客户端同名原因码。

**日志停在哪一步，问题就在哪一步**：停在 2→3 是 STT；停在 4→5 是网络/版本；停在 5→6
是候选女仆/自回声/决策服务前置；停在 7 是判定结论（`not_addressed`、`not_related`、
`low_confidence` 等）；只有 `Free chat accepted` 之后的静默才是女仆 LLM/TTS 侧的问题。
客户端的 HUD 通知（`freeChatNotice=hud`）会强制显示原因码，不再被 1.5 秒节流吞掉；
它画在 action bar 上方，不占用原版 action bar，因此不会覆盖其他模组的消息。

| 现象 | 检查 |
| --- | --- |
| 开关打开但没反应 | 决策服务是否启用、Key 是否有效；当前 STT 是否支持缓冲转写 |
| 仪表盘没有电平 | 麦克风设备/系统权限；是否被按键录音占用 |
| 一直不触发 | 降低 `freeChatThreshold`，或看仪表盘电平是否长期低于阈值 |
| 环境噪声误触发 | 提高阈值；仪表盘可实时观察 |
| 女仆自问自答 | 确认服务端 `SelfEchoMatcher` 生效；改用耳机或 `after_asr` |
| 玩家对话不介入 | 这是高门槛的预期行为；确认聊天中明确提到/叫到女仆 |
| 判定失败/限流 | 查看屏幕下方居中的畅聊通知原因；检查决策服务余额、429/529、超时 |
| 听到转写但女仆没反应 | 看 `latest.log` 里 `Free chat` 阶段日志停在哪；`rejected: reason=` 即原因码 |
