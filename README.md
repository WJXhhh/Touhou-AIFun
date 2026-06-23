# Touhou AIFun

[简体中文](README_zh.md)

`Touhou AIFun` is a Forge **1.20.1** addon for [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid)
that plugs additional AI service providers into the maid's AI chat system — large-language-model chat,
text-to-speech (TTS) and speech-to-text (STT) — and adds extra voice-customization UI on top of them.

> Requires **Touhou Little Maid 1.5.3-forge+mc1.20.1** (or newer in the same range). Each provider needs
> its own API key, configured through the maid's AI Chat settings screens.

## Features

### Chat / LLM providers
- **StepFun** (incl. *Step Plan*)
- **Xiaomi MiMo** — auto endpoint resolution for pay-as-you-go (`sk-`) and Token Plan (`tp-`) keys
- **MiniMax**
- **SiliconFlow**
- **OpenAI-compatible** endpoints (generic)

### Text-to-Speech (TTS)
- **StepFun** `stepaudio-2.5-tts` — with a synthesis instruction field (global emotion / tone / persona)
- **Xiaomi MiMo** — direct voice ID, **Voice Design** prompt, and **reference-audio cloning**
- **MiniMax**, **SiliconFlow**, **Fish Audio**
- Per-voice mode buttons in the editor and **sentence-by-sentence streaming** playback
- **Inline emotion / style control** — per-sentence `(emotion)` markers (incl. combined tags like `(委屈，抽泣)`) for `stepaudio-2.5-tts` / `mimo-v2.5-tts`, carried across streamed sentences

### Speech-to-Text (STT)
- **StepFun**, **Xiaomi MiMo**
- A site dropdown to pick the active STT provider, alongside the microphone / distance / proxy options

### Extra UI
- Custom Voice screen (reference audio + reference text)
- TTS instruction screen
- Streaming toggles and improved AI settings screens

## Building

```powershell
.\gradlew.bat build          # output: build/libs/Touhou-AIFun-<version>.jar
.\gradlew.bat runClient      # launch a dev client
.\gradlew.bat genIntellijRuns
```

## Project layout

- `com.wjx.touhou_aifun.TouhouStepFun` — mod entrypoint
- `com.wjx.touhou_aifun.compat` — `@LittleMaidExtension` hookup and per-provider integrations
  (`compat/ai/{stepfun,mimo,minimax,siliconflow,fishaudio,openai}`)
- `com.wjx.touhou_aifun.client.gui` — custom screens and widgets
- `com.wjx.touhou_aifun.mixin` — Touhou Little Maid / Minecraft mixins
- `com.wjx.touhou_aifun.config` — client config
- `com.wjx.touhou_aifun.network` — settings sync packets

## License

Released under the **MIT** license. Built as an addon for Touhou Little Maid; all Touhou Little Maid
assets and APIs belong to their respective authors.
