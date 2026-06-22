> ## Documentation Index
> Fetch the complete documentation index at: https://platform.stepfun.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 概览

> 高速推理 + 原生多模态 + 工具调用 · 阶跃星辰旗舰多模态推理模型

export const frameworkTools = [{
  title: "Claude Code",
  description: "终端内完成编码、调试、重构和工程管理",
  href: "/zh/step-plan/integrations/claude-code",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/claudecode-color.svg"
}, {
  title: "OpenClaw",
  description: "指令驱动的 Agent 和初始化型工作流",
  href: "/zh/step-plan/integrations/openclaw",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/openclaw-color.svg"
}, {
  title: "Hermes Agent",
  description: "终端或消息平台中的开源 AI Agent 框架",
  href: "/zh/step-plan/integrations/hermes-agent",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/hermes-agent.svg"
}, {
  title: "Cline",
  description: "在 VS Code、Cursor 中进行多轮代码协作",
  href: "/zh/step-plan/integrations/cline",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/cline.svg"
}, {
  title: "Roo Code",
  description: "在 IDE 中完成代码生成、调试和文件修改",
  href: "/zh/step-plan/integrations/roo-code",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/roocode.svg"
}, {
  title: "Kilo Code",
  description: "在 IDE 中完成代码生成、代码修改和调试",
  href: "/zh/step-plan/integrations/kilo-code",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/kilocode.svg"
}, {
  title: "Open Code",
  description: "终端内用自然语言驱动代码生成和项目分析",
  href: "/zh/step-plan/integrations/open-code",
  logo: "https://platform.stepfun.com/docs/images/stepplan/tools/opencode.svg"
}];

阶跃星辰的旗舰多模态推理模型。基于 198B 总参数 / 11B 激活参数的稀疏 MoE 架构，原生支持图片和视频理解。

## 关键信息

<Columns cols={3}>
  <Card title="模型类型">
    稀疏 MoE 架构<br />198B 总参数 / 11B 激活参数
  </Card>

  <Card title="上下文长度">
    256K tokens
  </Card>

  <Card title="场景定位">
    高速推理 + 原生多模态<br />智能体与代码任务优化
  </Card>
</Columns>

## 核心能力

<Columns cols={2}>
  <Card title="👁️ 原生多模态">
    原生支持图片和视频理解，在 Agent 框架中无需额外视觉模型，直接拖入对话框即可使用。
  </Card>

  <Card title="🚀 高速推理">
    稀疏 MoE 架构带来高吞吐与低延迟，适合实时智能体工作流与高频调用场景。
  </Card>

  <Card title="🛠️ 工具调用">
    可靠的工具调用能力，支持多步任务分解与计划执行。
  </Card>

  <Card title="🎯 高完成度复杂任务">
    面向代码生成、方案规划、视觉转工作流等复杂任务，单次调用即可产出更完整的计划、代码与文档，减少中途反复调试。
  </Card>
</Columns>

## 推理强度

`step-3.7-flash` 支持三档推理强度，可根据任务复杂度灵活选择：

| 推理强度     | 适用场景              |
| :------- | :---------------- |
| `low`    | 简单问答、摘要、改写、信息抽取   |
| `medium` | 默认推荐，适合一般推理和多步骤任务 |
| `high`   | 复杂推理、数学、规划、代码分析   |

<Info>
  Chat Completions API 使用 `reasoning_effort` 控制推理强度；Messages API 使用 `output_config.effort`。完整调用示例见 [快速上手指南](/zh/guides/models/step-3.7-flash-quickstart)。
</Info>

## 开始使用

<Columns cols={3}>
  <Card title="多模态快速上手" href="/zh/guides/models/step-3.7-flash-quickstart">
    从图片、视频、本地文件和推理强度控制开始接入。
  </Card>

  <Card title="场景示例" href="/zh/guides/models/step-3.7-flash-cookbook">
    查看白板转计划、图表转数据、票据转表格等任务模板。
  </Card>

  <Card title="手机操作 Agent" href="/zh/guides/models/step-3.7-flash-mobile-agent">
    通过 GELab-Zero 连接 Android 真机，让模型基于屏幕截图规划操作。
  </Card>

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
| 输入（缓存命中）  | 0.27 元         |
| 输入（缓存未命中） | 1.35 元         |
| 输出        | 8.1 元          |

## 框架适配

`step-3.7-flash` 可稳定接入主流 Coding 与 Agent 工具，适合在终端、IDE 和 Agent 工作流中完成代码生成、文件编辑与复杂任务协作。

<div className="step-plan-tool-grid">
  {frameworkTools.map((tool) => (
      <a key={tool.href} className="step-plan-tool-card step-plan-tool-card--link" href={tool.href}>
        <div className="step-plan-tool-card__top">
          <span className="step-plan-tool-card__logo-shell" aria-hidden="true">
            <img className="step-plan-tool-card__logo" src={tool.logo} alt="" loading="lazy" />
          </span>
        </div>
        <div className="step-plan-tool-card__body">
          <h3>{tool.title}</h3>
          <p>{tool.description}</p>
        </div>
      </a>
    ))}
</div>

[查看 Step Plan 接入指南 →](/zh/step-plan/quick-start)

## 相关阅读

<Columns cols={2}>
  <Card title="推理模型开发指南" href="/zh/guides/developer/reasoning">
    了解推理模型在复杂任务、工具调用和长上下文中的推荐用法。
  </Card>

  <Card title="图片理解最佳实践" href="/zh/guides/developer/image-chat">
    深入了解图片理解的 API 参数、detail 设置和最佳实践。
  </Card>

  <Card title="视频理解最佳实践" href="/zh/guides/developer/video-chat">
    深入了解视频理解的 API 参数、文件限制和常见问题。
  </Card>

  <Card title="Step 3.5 Flash" href="/zh/guides/models/step-3.5-flash">
    了解不含多模态能力的纯文本推理模型。
  </Card>
</Columns>
