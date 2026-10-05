# ChatGPT 订阅接入

模组新增 `chatgpt_subscription`（ChatGPT 订阅）LLM 类别，使用 OpenAI 官方 **Sign in with ChatGPT / ChatGPT plan usage**。由服主授权并消费服主订阅。普通玩家不能登录、切换账号或操作凭据；启用后，使用这个站点的女仆都会消费同一个服主账号的额度。账号和工作区必须具有官方开放的使用资格。

本功能只接 LLM。TTS、STT 仍使用各自原有站点。可用模型先从授权账号的 `/v1/models` 读取，保留官方顺序和名称。因为这个目录可能漏列实际可调用的模型，刷新时会额外验证目录中缺少的 `gpt-6.1-sol`、`gpt-6-sol` 和 `gpt-6-luna`：每项发送一条要求回复 `OK` 的简短请求，只有收到完整成功响应及非空正文才加入列表。失败或中断的模型不会加入。

目录已经包含的模型不额外探测。验证会少量消耗服主订阅额度，最多三条简短请求；只在登录后刷新、主动刷新模型或切换账号时执行，页面的状态轮询不会发起推理。每个账号重新验证，结果不会跨账号复用。补充模型在设置页标为 **已验证**，悬停“可用模型”标题可查看通过或失败原因。点击 **保存** 返回列表后，即可在女仆原有的模型选择器中使用。

## 本机和单人游戏

打开女仆的 LLM 服务商设置，找到 **ChatGPT 订阅**，编辑后点击 **Continue with ChatGPT**。页面直接打开本机浏览器；完成登录并允许应用使用 ChatGPT 订阅后，页面会自动显示账号、授权状态和可用模型。点右上角启用开关，再点 **保存** 返回服务商列表，然后在女仆原有的模型选项里选择模型。

日常操作都可在页面完成：刷新模型、取消授权、退出登录、添加和切换账号、打开额度管理。不需要输入聊天指令。模型名单由订阅账号提供，不能像普通 API key 站点一样任意填写模型名。鼠标悬停在底部状态上可查看完整提示。

页面新增 **推理摘要** 和 **推理强度** 两个按钮，修改后点击 **保存**，从后续请求开始生效。这是整个 ChatGPT 订阅站点的设置，使用它的女仆共用；刷新模型、切换账号和重启会保留设置。

- 推理摘要默认开启，请求 `reasoning.summary: auto`。收到流式摘要时显示在女仆的思考气泡里，最终回答仍单独显示和朗读。模型可能不返回摘要，简单问题尤其如此；关闭模组全局 LLM 流式显示后不会逐步展示摘要。
- 推理强度点击循环切换 **模型默认 → 关闭 → 低 → 中 → 高 → 极高 → 最大**。模型默认不指定 `reasoning.effort`。其余档位分别对应 `none`、`low`、`medium`、`high`、`xhigh`、`max`。更高强度通常会增加等待时间和额度消耗。
- 按所用模型处理不支持的档位：GPT-6 Astra / GPT-6.1 Sol 的关闭映射为最低支持的 `low`，GPT-5.5 的最大映射为 `xhigh`。实际使用 `none` 时不请求摘要。非推理模型不发送这些参数。

两项设置写入站点 JSON 的 `reasoning_summary` 和 `reasoning_effort` 字段，不包含授权令牌。旧配置自动使用“摘要开启、模型默认强度”。这次网络消息格式已更新，客户端和服务端必须一起更新 JAR。

## Fast 加速

账号与模型页新增 **Fast：开/关**，默认关闭，打开后点击 **保存**。使用该订阅站点的女仆共用此设置；刷新模型、切换账号、重启会保留选择。Fast 与推理强度独立，开启不会自动降低推理强度或关闭摘要、搜索。Fast 可能增加额度消耗，实际支持情况取决于账号、模型和服务端。

开启时，每次订阅推理请求发送 `service_tier: "priority"`，这是官方 Fast 的兼容参数；关闭时不发送此字段，恢复接口默认处理。配置字段为 `fast_mode`，旧站点配置自动关闭。本次网络协议版本更新为 14，客户端和服务器需要一起更新。

悬停 Fast 按钮可查看**最近完成请求的实际档位**：`priority` / `fast` 表示服务端确认 Fast，`default` 表示普通处理，缺少字段时显示“尚未确认”。这个状态来自完成响应，不根据开关推断，也不跨刷新模型、切换账号或重启复用。日志记录 `fast_requested` 和 `actual_service_tier`，便于核对请求和实际处理。

2026-10-03 进一步用同一份模组 OAuth 授权对公开订阅接口做了对照：

| 模型 / 请求 | HTTP / 完成状态 | 服务端返回档位 |
| --- | --- | --- |
| `gpt-6.1-sol` + `priority` | 200 / completed | created 为 `auto`，completed 为 `default` |
| `gpt-6.1-sol` + `default`（对照） | 200 / completed | created 为 `auto`，completed 为 `default` |
| `gpt-6-sol` + `priority` | 200 / completed | created 为 `auto`，completed 为 `default` |
| `gpt-6-luna` + `priority` | 200 / completed | created 为 `auto`，completed 为 `default` |
| `gpt-5.6-sol` + `priority` | 200 / completed | created 为 `auto`，completed 为 `default` |
| `gpt-6.1-sol` + `fast` | 400 | `detail: Unsupported service_tier: fast` |

同时，模组授权的 `/v1/models` 中，`gpt-5.6-sol` 等模型的 `service_tiers` 明确包含 `{id: "priority", name: "Fast"}`；本地 Codex 模型目录中 6.1 Sol / 6 Sol / 6 Luna 的 Fast 也都映射为 `priority`。添加本应用的 originator / User-Agent 后，6.1 Sol 的完成响应仍为 `default`。这排除了模组参数别名写反和单个新模型特例，但没有证明原因必然是账号不支持 Fast，也没有证明具体是哪一层重置了请求档位。当前只能确认这条第三方订阅接入没有返回 Fast 成功证据；模组不会把结果显示为已加速，简短 OK 请求的耗时也不作为速度基准。

可用 `scripts/smoke-chatgpt-fast.ps1 [-Model gpt-6.1-sol]` 验证当前账号，脚本只读取模组授权、发送简短请求并输出事件档位、请求 ID 和经过脱敏的错误信息；仅在服务端确认 Fast 且请求完成时返回成功。`-ServiceTier default` 用作普通处理对照，`-ServiceTier fast` 可单独测试新名称是否已被接口支持。模型目录是能力描述，不等同于某次请求的实际处理证明。

参数及返回档位定义见 [OpenAI Docs：Fast mode](https://developers.openai.com/api/docs/guides/fast-mode)。[Codex 的速度说明](https://developers.openai.com/codex/speed)确认这些模型在可用时支持 Fast；[第三方订阅错误说明](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery#structured-responses-errors)则单独列出了不支持 service-tier override 的情况。公开文档没有说明本次“接受 priority 但完成为 default”的具体原因。

## 订阅联网搜索

设置页的 **订阅联网：开/关** 控制 OpenAI 原生 `web_search`，默认开启，点击 **保存** 后用于后续请求。遇到新闻、当前价格、变化较快或不确定的信息，或者明确要求搜索时，模型可自行搜索、打开网页和查找页面内容。普通聊天不会强制搜索。

搜索使用同一个服主 ChatGPT 授权，不需要 DeepSeek API Key，也不会转用 DeepSeek 搜索；关闭此开关后，订阅模型不会获得模组的 DeepSeek `web_search` 函数。女仆其他动作工具照常提供，`web_fetch` 仍可读取指定公开网页。

搜索期间显示“正在联网搜索…”。普通回答只显示正文，不主动列来源、说明引用或追加“需要来源可以问我”。玩家明确要求来源（例如“来源呢”“在哪查的”“给出参考链接”）时，聊天栏才单独显示 **联网来源**，标题可以点击打开；这些链接不参与朗读。最近一次成功搜索的来源按女仆保留在内存里，追问可以直接查看，无需为取回来源重新搜索；重启后不保留此缓存。搜索回答的句子合成等到完成事件和来源注释到齐后开始，以免提前朗读引用链接。搜索过程仍可被新消息打断，失败或中断不会发布来源。

来源同时读取正文引用和搜索工具的来源列表。实时天气、金融、体育数据可能没有普通网页引用，玩家问来源时显示对应的实时数据来源标签，不编造网址；接口未提供来源时，不追加空来源列表，由女仆在追问时如实解释。搜索、思考、答案共用当前气泡句柄，答案开始后替换搜索提示，不会残留“正在联网搜索”。

聊天栏优先展示回答实际引用的来源和实时数据来源，不把浏览过的全部网页都当成引用铺出来。没有正文引用时最多展示三个搜索来源；长标题会缩短，悬停查看完整标题和网址。

现实天气按用户当前提到或对话中确定的城市查询；地点不明时询问城市，不能拿 Minecraft 天气或模组知识库当现实天气。只有问题匹配模组玩法时才调用对应知识库。日志中的 `ChatGPT subscription reply` 会记录是否实际执行原生搜索、来源数量及是否调用女仆工具，不记录授权令牌。

可用性取决于模型及账号/工作区策略；若接口拒绝搜索，会明确报错，可以关闭订阅联网或切换模型后重试。开启权限不保证模型每轮调用；可以明确说“联网搜索……并给出来源”。配置字段为 `web_search`。客户端和服务器需要一起更新。

本地冒烟验证：在游戏中确保模组授权未过期后运行 `scripts/smoke-chatgpt-web-search.ps1 [-Model gpt-6.1-sol]`。脚本只读取模组自己的授权，不读取 Codex 缓存，不输出令牌；一次测试会使用少量订阅额度。

也可以使用命令：

```text
/aifun chatgpt login
/aifun chatgpt status
/aifun chatgpt models
/aifun chatgpt enable
```

专用服务器命令需要权限等级 4；服务器控制台可直接运行。

## 远程专用服务器

OAuth 回调只监听服务器的 `127.0.0.1`，不开放公网回调端口。在服主本机建立 SSH 转发：

```text
ssh -N -L 1455:127.0.0.1:1455 用户@服务器
```

也可以在设置页点击 **远程连接**，填写 `用户@服务器IP`，点击 **复制 SSH 转发命令**，粘贴到服主本机终端并运行。保留 SSH 连接，返回 **账号与模型** 页点击登录；模组会直接打开服主**本机浏览器**。登录后浏览器回调通过 SSH 到达服务器。授权链接有效期为 5 分钟；授权完成后可以关闭 SSH 连接。

若端口被占用，在 **远程连接** 页修改回调端口后重新复制命令，再返回账号页登录。请不要将监听改为 `0.0.0.0`，也不要公开授权链接。服务器控制台仍可使用 `aifun chatgpt login [端口]`。

没有 SSH 转发时，官方也支持用同一个应用在本机完成授权，关闭本机应用后通过 SSH/SFTP 将 `config/touhou_aifun/chatgpt/session.json` 安全复制到服务器同一路径。在 **远程连接** 页点击 **重新读取服务器凭据**，然后返回账号页刷新模型。仅传输 session，**不要覆盖服务器的 host.json**。后续刷新由服务器负责，不要让两个进程并行使用同一份可轮换 refresh token。

## 管理和凭据

| 命令 | 作用 |
| --- | --- |
| `aifun chatgpt status` | 查看当前账号及订阅授权状态 |
| `aifun chatgpt login [端口]` | 首次授权或重登当前注册 |
| `aifun chatgpt new-account` | 为另一个账号/工作区创建注册 |
| `aifun chatgpt accounts` | 列出保存的账号注册 |
| `aifun chatgpt select <client-id>` | 切换到指定注册并刷新模型 |
| `aifun chatgpt models` | 刷新当前账号的模型列表 |
| `aifun chatgpt enable` / `disable` | 启用/禁用站点 |
| `aifun chatgpt cancel` | 取消尚未完成的授权 |
| `aifun chatgpt logout` | 撤销 refresh session 并删除本地令牌 |
| `aifun chatgpt reload` | 重新读取受保护的凭据文件 |

凭据位于运行目录下 `config/touhou_aifun/chatgpt/`。文件在 Unix 上限制为 `0600`，Windows 上设为仅文件所有者可访问。它们不写入 `touhou_little_maid/sites/llm.json`，不通过站点同步包发送给玩家，也不输出到日志。没有操作系统加密；服务器管理员仍应保护服务器目录和备份。

退出登录保留账号注册及服务器 host ID，以便之后重登复用。若未确认远程撤销，命令会明确提示；可以在 [ChatGPT 使用设置](https://chatgpt.com/settings/usage) 中断开应用及管理额度。

接口固定使用公开的 `https://api.openai.com/v1/responses`，请求带 `store:false` 和 `stream:true`，只有收到 `response.completed` 才执行最终回复或女仆工具调用。关闭模组 LLM 流式选项会关闭渐进显示/朗读，网络协议仍按官方要求流式接收。

## 官方参考

- [开源及本地应用订阅接入](https://developers.openai.com/siwc/token-sharing-open-source)
- [模型和推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [远程自托管环境](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
- [当前接口限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [原生联网搜索及来源注释](https://developers.openai.com/api/docs/guides/tools-web-search)

这是新的独立授权，不读取 Codex 或 OpenCode 自己的登录缓存。OpenCode Go 的 API key 站点与此功能独立。
