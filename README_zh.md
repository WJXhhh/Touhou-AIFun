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
- **StepFun** `stepaudio-2.5-tts` —— 带合成指令输入框（全局情绪 / 语气 / 人设）
- **小米 MiMo** —— 直接填音色 ID、**Voice Design** 提示词、以及**参考音频克隆**
- **MiniMax**、**SiliconFlow**、**Fish Audio**
- 编辑器内的逐音色模式按钮，以及**逐句流式**播放
- **逐句情绪 / 风格标签控制** —— 为 `stepaudio-2.5-tts` / `mimo-v2.5-tts` 在每句开头注入 `(情绪)` 标签（支持 `(委屈，抽泣)` 这类组合标签），并在流式各句之间自动延续

### 语音识别 (STT)
- **StepFun**、**小米 MiMo**
- 用下拉框选择当前 STT 服务商，并与麦克风 / 距离 / 代理等选项并列

### 额外界面
- 自定义音色界面（参考音频 + 参考文本）
- TTS 合成指令界面
- 流式开关与改进后的 AI 设置界面

## 构建

```powershell
.\gradlew.bat build          # 产物: build/libs/Touhou-AIFun-<版本>.jar
.\gradlew.bat runClient      # 启动开发客户端
.\gradlew.bat genIntellijRuns
```

## 项目结构

- `com.wjx.touhou_aifun.TouhouStepFun` —— 模组入口
- `com.wjx.touhou_aifun.compat` —— `@LittleMaidExtension` 挂载与各服务商集成
  (`compat/ai/{stepfun,mimo,minimax,siliconflow,fishaudio,openai}`)
- `com.wjx.touhou_aifun.client.gui` —— 自定义界面与控件
- `com.wjx.touhou_aifun.mixin` —— 车万女仆 / Minecraft 的 Mixin
- `com.wjx.touhou_aifun.config` —— 客户端配置
- `com.wjx.touhou_aifun.network` —— 设置同步网络包

## 许可证

以 **MIT** 许可证发布。作为车万女仆的附属模组构建；所有车万女仆的素材与 API 归其各自作者所有。
