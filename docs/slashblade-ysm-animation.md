# 拔刀剑与 YSM 动画联动

核对目标：官方 YSM **2.6.5 Forge / Minecraft 1.20.1**，TLM 1.5.3，拔刀剑重锋 CR1.13、CR1.15、上游 1.9.65。

## 接入方式

复用 YSM 自带的拔刀剑控制器。服务端 AI 执行刀自身的原生连段及 SA；客户端 YSM 从主手刀的
`CapabilitySlashBlade.BLADESTATE` 读取 `getComboSeq()` 和 `getLastActionTime()`，再从模型动画中
选择对应的 `slashblade:*` 动作。它接受 `LivingEntity`，不限于玩家，所以女仆无需冒充玩家，也不需要
把每个技能转换成轮盘动作。空中次元斩及 `combo_a4_ex` 的动画别名由 YSM 自己处理。

已在官方 2.6.5 发布包确认：女仆的 `swing` 控制器复用该剑技读取器，而且剑技分支在普通
`swinging` 判定之前执行。因此原生连段状态即可驱动剑技动画，不需要为了出动作再伪造一次普通挥手。

`SlashBladeAnimationSync` 使用原版装备包与拔刀剑的 Forge share tag，传递真实刀状态，不新增网络协议、
不依赖 YSM 的混淆类名，也不把 YSM 设为必装依赖。每个战斗会话比较“剑技 ID + 开始时间”，仅在变化时
发送；同名剑技重新起手也会同步。每 tick 结算后只发送最终状态，停战重置为 `none` 后立即同步。
换刀取消旧会话时不得把旧刀发回客户端；新刀的状态由新会话和原版装备同步负责。

YSM 的读取规则还包含原生剑技的超时限制。因此同步开始时间和同步剑技 ID 同样重要，不能只发送名字，
也不能每 tick 把开始时间改成当前时间，否则动画会失效或始终停在起手阶段。

## 模型要求与回退

YSM 文档要求 `BladeLocator`、`SheathLocator`、`LeftWaistLocator`、`RightWaistLocator` 定位组；
完整动作使用独立的 `slashblade` 动画文件。官方 2.6.5 发布包自带
`assets/yes_steve_model/builtin/default/animations/slashblade.animation.json`，包含普通连段、空中连段、
上挑、突进、次元斩、SA 及持刀移动等动作。动作资源的默认回退由 YSM 自己处理，本附属不复制或修改这些资源。
模型缺少必要定位组时，文档明确说明会使用旧版默认动作，不能仅凭默认动作资源存在就保证完整剑技可见。
官方包内酒狐大正女仆模型已核对，四个定位组均存在。

这条联动只为实际注册、实际执行的技能提供状态。刀光、幻影剑与伤害仍由拔刀剑结算；YSM 是否为某个
新增技能提供专门动作，取决于该技能 ID 和模型／默认动画的匹配，不把未知技能强制伪装成普通挥剑。

## 验证

`SlashBladeAnimationGameTests` 对当前运行版本注册表里的全部连段执行真实装备包编解码，检查客户端刀能力
保留剑技 ID 和开始时间，并覆盖同名动作重启、无变化去重、停止以及换刀后拒绝旧刀同步。

运行：`gradlew -PguiQa -PslashbladeQa -PslashbladeVersion=cr113 runGameTestServer`；
`slashbladeVersion` 另可选 `cr115`、`upstream`。这些服务器测试不等于客户端视觉验收；
官方 YSM 2.6.5 + 酒狐的刀身／刀鞘定位、连段衔接和旁观者视角仍需实机观察。

2026-10-24 验证结果：独立构建目录中 629 项 JUnit 测试通过；CR1.13、CR1.15、上游 1.9.65 各
9 项 GameTest 通过（每组包含上述 2 项动画同步测试）。这里验证的是原生状态、网络编解码与战斗回归，
没有把服务器通过当成 YSM 客户端画面的验收结果。

## 查证依据

- [YSM 作者的拔刀剑动画文档](https://ysm.cfpa.team/wiki/animation/slashblade/)
- [官方 2.6.5 Forge 1.20.1 发布页](https://modrinth.com/mod/yes-steve-model/version/Zqooxsd2)
- 发布 jar SHA1：`151ac7b24da8beeca1a20864565743cfd77af286`。
- 实际发布包中剑技读取器引用 `ISlashBladeState.getComboSeq/getLastActionTime`、
  `ComboStateRegistry` 和 `ComboState.getTimeoutMS`；已核对其参数使用 `LivingEntity`。
  临时 jar、局部反编译和资源核对文件在忽略目录 `build/research/ysm-2.6.5/`，不随模组发布。
