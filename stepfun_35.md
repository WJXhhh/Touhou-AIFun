> ## Documentation Index
> Fetch the complete documentation index at: https://platform.stepfun.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Step 3.5 Flash

> 高速推理 + 工具调用 · 阶跃星辰旗舰语言推理模型

`step-3.5-flash` 是阶跃星辰的旗舰语言推理模型。具备顶尖推理能力与快速可靠的执行能力，能够完成复杂任务的分解与计划，可快速可靠地调用工具执行任务，胜任逻辑推理、数学、软件工程、深度研究等各种复杂任务。基于 196B 总参数 / 11B 激活参数的稀疏 MoE 架构。

## 关键信息

<Columns cols={3}>
  <Card title="模型类型">
    稀疏 MoE 架构<br />196B 总参数 / 11B 激活参数
  </Card>

  <Card title="上下文长度">
    256K tokens
  </Card>

  <Card title="场景定位">
    高速推理 + 工具调用<br />智能体与代码任务优化
  </Card>
</Columns>

## 核心能力

<Columns cols={3}>
  <Card title="🚀 高速推理">
    稀疏 MoE 架构带来高吞吐与低延迟，适合实时智能体工作流与高频调用场景。
  </Card>

  <Card title="🛠️ 工具调用">
    可靠的 `tools` / `tool_choice` 调用能力，支持多步任务分解与计划执行。
  </Card>

  <Card title="🧠 复杂推理">
    胜任逻辑推理、数学、软件工程、深度研究等复杂任务，是 Agent 长链路推理的稳定底座。
  </Card>
</Columns>

## 模型变体

<Columns cols={2}>
  <Card title="step-3.5-flash" icon="bolt">
    **基础版本**：通用推理与工具调用，适合多数 Agent 与复杂任务场景。
  </Card>

  <Card title="step-3.5-flash-2603" icon="rocket">
    **Agent 优化版**：基于 step-3.5-flash 针对高频 Agent 场景优化，Token 效率提升、推理速度更快，可切换至低推理模式大幅降低 Token 消耗，对 Coding 与 Agent 框架兼容性也做了专项优化。支持 `reasoning_effort` 字段（`low` / `high`）。
  </Card>
</Columns>

## API 端点

<Columns cols={2}>
  <Card title="Chat Completion" href="/zh/api-reference/chat/chat-completion-create">
    `POST /v1/chat/completions`<br />OpenAI 协议兼容，支持流式与工具调用。
  </Card>

  <Card title="Messages" href="/zh/api-reference/chat/messages-create">
    `POST /v1/messages`<br />Anthropic 协议兼容，可直接复用 Anthropic SDK。
  </Card>
</Columns>

## 定价

| 计费项       | 单价（每百万 tokens） |
| :-------- | :------------- |
| 输入（缓存命中）  | ¥0.14          |
| 输入（缓存未命中） | ¥0.7           |
| 输出        | ¥2.1           |

[查看完整定价详情 →](/zh/guides/pricing/details)

## 快速上手

<Tabs>
  <Tab title="curl">
    ```bash theme={null}
    curl https://api.stepfun.com/v1/chat/completions \
      -H "Authorization: Bearer $STEP_API_KEY" \
      -H "Content-Type: application/json" \
      -d '{
        "model": "step-3.5-flash",
        "messages": [
          {"role": "user", "content": "你好，请介绍一下你自己"}
        ]
      }'
    ```
  </Tab>

  <Tab title="Python (OpenAI SDK)">
    ```python theme={null}
    from openai import OpenAI

    client = OpenAI(
        api_key="YOUR_STEP_API_KEY",
        base_url="https://api.stepfun.com/v1",
    )

    response = client.chat.completions.create(
        model="step-3.5-flash",
        messages=[
            {"role": "user", "content": "你好，请介绍一下你自己"}
        ],
    )

    print(response.choices[0].message.content)
    ```
  </Tab>
</Tabs>

## 相关阅读

<Columns cols={2}>
  <Card title="推理模型开发指南" href="/zh/guides/developer/reasoning">
    了解推理模型在复杂任务、工具调用和长上下文中的推荐用法。
  </Card>

  <Card title="Step Router V1 智能路由" href="/zh/guides/models/step-router">
    `step-router-v1` 在判定任务为高频或确定性场景时会路由到 `step-3.5-flash` 执行。
  </Card>
</Columns>
