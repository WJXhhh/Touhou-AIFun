# Touhou AIFun

[English](README.md)

`Touhou AIFun` 是一个面向 [车万女仆 (Touhou Little Maid)](https://modrinth.com/mod/touhou-little-maid)
的 Forge **1.20.1** 附属模组，为女仆的 AI 聊天系统接入更多 AI 服务商 —— 大语言模型对话、
语音合成 (TTS) 与语音识别 (STT)，并在其基础上提供额外的音色自定义界面。

> 需要 **Touhou Little Maid 1.5.3-forge+mc1.20.1**（或同版本范围内的更新版本）。
> 每个服务商都需要各自的 API Key，在女仆的「AI 聊天设置」界面中配置。

## 功能

### 聊天 / 大语言模型 (LLM)
- **StepFun（阶跃星辰）**（含 *Step Plan*）
- **小米 MiMo** —— 自动识别按量付费 (`sk-`) 与 Token Plan (`tp-`) 两种 Key 并选择对应端点
- **MiniMax**
- **SiliconFlow（硅基流动）**
- **OpenAI 兼容** 端点（通用）

### 语音合成 (TTS)
- **StepFun** `stepaudio-3-tts` / `stepaudio-2.5-tts` —— 带合成指令输入框（全局情绪 / 语气 / 人设）
- **小米 MiMo** —— 直接填音色 ID、**Voice Design** 提示词、以及**参考音频克隆**
- **MiniMax**、**SiliconFlow**、**Fish Audio**
- 编辑器内的逐音色模式按钮，以及**逐句流式**播放
- **StepFun 官方 WebSocket 流式合成** —— PCM 边生成边播放；流式回复可在模型还没写完时开口，出错自动回落 HTTP MP3
- **逐句情绪 / 风格标签控制** —— 为 `stepaudio-2.5-tts` / `mimo-v2.5-tts` 在每句开头注入 `(情绪)` 标签（支持 `(委屈，抽泣)` 这类组合标签），并在流式各句之间自动延续

### 语音识别 (STT)
- **StepFun**、**小米 MiMo**、**通义千问（Qwen）**
- 用下拉框选择当前 STT 服务商，并与麦克风 / 距离 / 代理等选项并列
- **畅聊模式**：免按 STT 键，音量门控触发 ASR；STT 设置页内置可拖动阈值的实时门控仪表盘；
  每条转写由服务端 JEV / Perplexity 决策服务判断说话对象、有效性与相关性（玩家对话门槛更高）；
  女仆说话时仍采集并支持打断（建议耳机）。详见 [畅聊模式](docs/free-chat.md)

### 额外界面
- 自定义音色界面（参考音频 + 参考文本）
- TTS 合成指令界面
- 流式开关与改进后的 AI 设置界面

### 看图与截图回看
- 图片模型直接接收女仆六面截图；其他模型使用所选独立识别模型
- 所有站点和模型在 LLM 设置中统一管理，支持逐模型图片能力三态设置
- 内存截图缓存与 `review_observation` 回看工具：[使用与迁移说明](docs/multimodal-observations.md)

### 战斗反射与仇恨指挥
- 女仆/主人被攻击时**服务端即时反射**（通常下一个 tick），不再等基模 20 tick 目标传感器
- 主人被怪打时护主；非攻击工作模式可 opt-in 自卫；夜间/非 WORK 日程也生效
- 玩家也可以还手，但**永远不打主人**；公共女仆或友伤关闭时拒绝一切玩家目标；
  `attack_players=false` 可进一步全禁
- `combat_target` 工具：指挥/清除女仆仇恨目标；`combat_control` 工具：查询/修改战斗规则
  （改规则仅实际主人）。完整规则、配置和测试见 [战斗说明](docs/combat.md)

## 构建

需要 **JDK 17**，首次构建需要联网下载 Forge、基模和 Minecraft 资源。
将 `JAVA_HOME` 指向 JDK 17。Windows 下也可在 `gradle/java-home.local` 中写入一行 JDK 17
的绝对路径（不加引号），`gradlew.bat` 会优先使用它；该文件仅用于本机，不提交到 Git。
IntelliJ 导入项目后，将 **Gradle JVM** 设为 JDK 17。直接使用 Gradle Wrapper：

```powershell
.\gradlew.bat build          # 产物: build/libs/Touhou-AIFun-<版本>.jar
.\gradlew.bat runClient      # 启动开发客户端
.\gradlew.bat genIntellijRuns
```

## 项目结构

女仆自主 GUI 操作、等待策略、只读预览和模组适配接口见 [GUI 操作说明](docs/gui-automation.md)。

- `com.wjx.touhou_aifun.TouhouStepFun` —— 模组入口
- `com.wjx.touhou_aifun.compat` —— `@LittleMaidExtension` 挂载与各服务商集成
  (`compat/ai/{stepfun,mimo,minimax,siliconflow,fishaudio,openai}`)
- `com.wjx.touhou_aifun.client.gui` —— 自定义界面与控件
- `com.wjx.touhou_aifun.mixin` —— 车万女仆 / Minecraft 的 Mixin
- `com.wjx.touhou_aifun.config` —— 客户端配置
- `com.wjx.touhou_aifun.network` —— 设置同步网络包

## 许可证

以 **MIT** 许可证发布。作为车万女仆的附属模组构建；所有车万女仆的素材与 API 归其各自作者所有。
