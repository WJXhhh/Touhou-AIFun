# 图片模态与浅层环境扫描审计记录

日期：2026-08-07  
审计对象：提交 `bcfd467 feat: add grounded vision and environment scanning`，以及其后的未提交视觉设置 UI 调整  
审计性质：仅记录问题和建议；本轮未修改功能代码

## 修复进度（2026-08-07）

已完成首批修复：

- [x] 重要方块可见性射线到达目标体素时停止，不再让不透明目标遮挡自身。
- [x] 扫描 front/right/back/left 与 Minecraft 相机坐标系统一，并增加四个水平朝向回归测试。
- [x] 冻结扫描原点、yaw 和起始 tick，避免多 tick 主射线跟随女仆实时转向。
- [x] 修正 DDA 命中距离和并列轴推进，删除每条主射线的装箱 `HashSet`。
- [x] 遇到未加载区块时保守终止射线，不再把未知区块当空气。
- [x] 缓存键加入规范化 focus；同一女仆只保留一个活动扫描，不同请求会取消旧任务。
- [x] 新增 SenseNova 独立适配器，使用 `image_base64`、`max_new_tokens`、`thinking.enabled` 和 `data.choices[].message`。
- [x] OpenAI 兼容和 SenseNova 的空响应现在返回失败，不再伪装成功。
- [x] 截图请求不再向客户端发送 focus 和浅扫描 JSON，并加入女仆 UUID 校验。
- [x] 客户端无法截图、找不到女仆、编码失败或图片超限时立即回报服务器，不再统一等待 12 秒。
- [x] 截图期间强制第一人称并恢复原相机模式；JPEG 改为后台单线程编码，质量显式设为 0.82。
- [x] 扫描调度改为服务器全局 2000 主射线 / 40000 DDA / 4 section 预算，并按女仆轮询分配；实体遮挡展开改为多 tick。
- [x] 表面结果改为真正的组级 DTO：计数只计算一次，代表坐标独立保存，覆盖率按命中射线计算，并保留方向分布和相对包围范围。
- [x] 扫描、视觉响应和最终组合包统一按 UTF-8 字节执行 16 KiB 上限；超限时返回合法且明确标记截断的紧凑 JSON。
- [x] 图片失败但扫描成功时返回 `partial`，不再把仍可使用的代码扫描整体标记为失败。
- [x] 六面渲染拆为每 tick 一面，冻结首帧 yaw，回传真实捕获起止 tick；提示模型识别跨 tick 动态场景冲突。
- [x] 视觉提供商原始 HTTP Future 可按女仆取消；相同观察请求合并，不同请求会取代旧请求。
- [x] `grounded_matches` 由服务器按注册名和相对坐标校验；无法在扫描结果中定位的模型声明会被删除并写入不确定性。
- [x] 重要方块和实体可见性使用真实方块 outline shape，避免把楼梯、栅栏和开门的整个体素当成实心墙。
- [x] section 重要方块兜底不再为每个 palette 位置创建 `BlockPos`，BlockEntity section 查询也不再重复遍历候选集合。
- [x] 客户端站点同步改为断线清空的脱敏快照，不再污染本地 `vision.json` 或混入本地密钥；支持密钥状态显示和显式清除。
- [x] 站点 UPSERT 校验外层/内层 ID，一般开关统一为即时保存，服务商列表显示服务端解析后的实际选中项。
- [x] `nearby_entities` 增强注入首次生效/失败都会写日志，运行时不再静默降级。
- [x] 重要方块和实体在详情截断后仍保留全量方向分布与相对包围范围；实体候选查询、球形过滤和排序改用冻结原点。
- [x] 相同在途观察合并、成功视觉结果短时复用；全服最多 4 个截图＋付费视觉 HTTP 链路并发，超限明确退回 scan-only partial。
- [x] 第三方视觉失败但扫描成功、或扫描失败但图片成功时，统一返回 `partial` 并分别标记 `image_status`/`scan_status`。
- [x] 服务商 UI 区分缺少密钥、缺少模型、无效 endpoint 与可用状态；保存/清除/权限失败均显示服务端确认结果。
- [x] 脱敏站点同步同时移除自定义 headers，避免 Authorization/X-API-Key 等旁路密钥泄漏，并在编辑时由服务端保留原 headers。
- [x] 新对话、超时或无效分片会向客户端发送截图取消包，停止仍在排队、逐面渲染或等待发送的旧捕获。
- [x] 中文 focus 增加箱子、苦力怕、危险、矿石、传送门等常用别名排序；只影响摘要顺序，不改变可见性。
- [x] 使用当前 TLM 1.5.3 发布 JAR 的 `javap` 核对 `NearbyEntitiesContext.getValue(EntityMaid): String`，与 required Mixin 注入描述符一致。
- [x] `observe_surroundings` 气泡按“浅扫描环境 → 获取六面画面 → 等待视觉模型”三个阶段本地化更新。

仍待运行时验证：真实世界六方向/透明度/模组方块布置测试，光影下分帧截图方向与色彩检查，各服务商真实 Key 冒烟，以及密集实体和多人并发性能基准。六面 atlas 可作为后续可选优化。

## 结论

当前代码可以在 Java 17 下完整构建，视觉设置入口、渲染投影和 `nearby_entities` 增强 Mixin 也确实在当前 TLM 版本上成功应用，并非整体注入失效。

但是图片与扫描的 grounding 链路仍有四类必须优先处理的问题：

1. 重要方块会被可见性射线当成自身遮挡物，导致不透明箱子、矿石、机器等从重要方块结果中消失。
2. 扫描的左右方向与实际六面截图左右方向相反，图片和代码扫描会错误对齐。
3. 商汤 SenseNova 并不兼容当前通用 OpenAI 请求和响应格式，需要独立适配器。
4. 浅扫描数据的实际传输边界与“服务器内部处理”的预期存在冲突，需要删除无用的服务端到客户端传输，并明确是否允许发送给视觉服务商和主 LLM。

在以上问题修复前，不应把视觉 grounding 当成可靠的精确判断来源。

## 已验证事项

- `gradlew build` 在 Java 17 下成功。
- `compileJava`、测试、Mixin 处理、refmap 打包和 `reobfJar` 均成功。
- `run/logs/debug.log` 显示以下 Mixin 已应用：
  - `client.GameRendererVisionMixin`
  - `client.AIChatSettingsHubVisionMixin`
  - `NearbyEntitiesContextMixin`
- 构建出的 `Touhou-AIFun-0.3.jar` 包含：
  - `touhou_aifun.mixins.json`
  - `touhou_aifun.refmap.json`
- 当前测试目录只有上下文管理测试，没有视觉和扫描专项测试。

开发环境日志中的 `Reference map ... could not be read` 是 userdev 环境的提示；发布 JAR 内实际带有 refmap。当前视觉问题的主要原因不是 Mixin 没加载。

## P0：必须优先修复

### 1. 重要方块被自身判定为遮挡

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:291`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:588`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:656`

`blockVisibility` 对目标中心和六个面中心发射可见性射线。DDA 进入目标方块所在体素后，会先分类目标方块本身；如果目标是不透明方块，预算立即耗尽并返回不可见。

影响：

- 箱子、矿石、机器、信标、附魔台等不透明目标可能被系统性排除。
- “重要方块兜底避免单格目标从射线缝隙遗漏”的核心设计被破坏。

建议：

- 可见性射线应接收目标 `BlockPos`。
- 到达目标体素时立即认为射线成功，不允许目标自身消耗遮挡预算。
- 为目标中心、近侧面中心、远侧面中心分别增加回归测试。

### 2. 扫描和截图左右方向相反

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:438`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:453`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:707`
- `src/main/java/com/wjx/touhou_aifun/client/vision/CubemapCapture.java:81`
- `src/main/java/com/wjx/touhou_aifun/vision/OpenAICompatibleVisionClient.java:121`

当前扫描把本地 `+X` 当作 `right`；但截图使用 `maidYaw + 90°` 获取右侧，这是 Minecraft 坐标系中的实际右转方向。以朝南（yaw=0）为例：

- 截图右侧为世界 `-X`。
- 扫描右侧为世界 `+X`。

影响：

- 图片 `right` 会与扫描中的 `left` 内容对齐。
- 实体方向、重要方块方向和 `grounded_matches` 都可能左右颠倒。
- 正面和背面基本正常，因此该问题不一定在普通冒烟测试中立即暴露。

建议：

- 以 Minecraft `Direction.fromYRot(yaw)` 和 `getClockWise()` 为唯一方向基准。
- 扫描、截图、实体方向、提示词罗盘映射共用同一个方向转换工具。
- 添加四个水平朝向下的 front/right/back/left 单元测试或游戏测试。

### 3. SenseNova 请求和响应格式不兼容

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/OpenAICompatibleVisionClient.java:39`
- `src/main/java/com/wjx/touhou_aifun/vision/OpenAICompatibleVisionClient.java:69`
- `src/main/java/com/wjx/touhou_aifun/vision/OpenAICompatibleVisionClient.java:134`

当前通用适配器发送：

- `max_tokens`
- `image_url: {"url":"data:image/jpeg;base64,..."}`
- OpenAI 形状的消息与响应

SenseNova 当前图文接口要求：

- `max_new_tokens`
- URL 图片为字符串 `image_url`
- Base64 图片为 `type=image_base64` 和纯 Base64 字符串
- 非流式响应正文位于 `data.choices[].message`
- 当前部分模型还要求 `thinking.enabled`

当前解析器遇到 SenseNova 的 `data` 对象时，会把整个对象序列化成字符串，而不是提取真实回复；甚至可能错误返回 `status=ok`。

建议：

- 新增独立 `SenseNovaVisionClient`。
- 使用 `image_base64`，去掉 Data URL 前缀。
- 使用 `max_new_tokens`。
- 正确读取 `data.choices[0].message`。
- 按模型能力发送 `thinking.enabled`。
- 只保留官方要求的 `Authorization: Bearer`；额外 `X-API-Key` 没有必要。

官方依据：

- https://console.sensecore.cn/micro/help/docs/model-as-a-service/nova/vision/ChatCompletions/
- https://console.sensecore.cn/micro/help/docs/model-as-a-service/nova/overview/Authorization/

### 4. 浅扫描数据边界不清晰且存在无用传输

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/VisionObservationManager.java:78`
- `src/main/java/com/wjx/touhou_aifun/vision/VisionCaptureTransport.java:26`
- `src/main/java/com/wjx/touhou_aifun/network/message/AIFunVisionCaptureRequestMessage.java:13`
- `src/main/java/com/wjx/touhou_aifun/vision/OpenAICompatibleVisionClient.java:113`

当前扫描 JSON 会：

1. 随截图请求从服务器发送给女仆主人客户端；客户端捕获代码并不使用这些数据。
2. 被完整加入视觉服务商提示词。
3. 作为工具结果进入主 LLM 的后续上下文。

问题：

- 服务端到客户端的 `focus/scanJson` 传输没有用途，增加网络负担并扩大数据暴露面。
- “扫描只在服务器内部处理”与“视觉模型同时收到扫描 JSON”在产品语义上互相冲突。
- 设置页目前只说明浅扫描不会上传图片，没有说明结构化方块和实体数据可能发送给模型服务商。

建议：

- 立即从截图请求消息中删除 `focus` 和 `scanJson`，只传请求 ID、女仆 UUID/实体 ID和必要的拍摄姿态信息。
- 明确选择一种产品策略：
  - 隐私优先：视觉服务商只收图片和 focus，扫描与视觉结果由主 LLM 或服务器融合。
  - grounding 优先：允许视觉服务商接收扫描摘要，但设置页必须明确披露，并尽量只发送经过压缩和脱敏的必要字段。
- 无论采用哪种策略，都不应把完整扫描 JSON 无意义地发送给客户端。

## P1：高优先级正确性与性能问题

### 5. 多 tick 扫描没有冻结完整姿态

扫描开始时冻结了眼睛位置和方块位置，但以下逻辑仍读取实时女仆状态：

- 主射线旋转使用实时 `maid.getYRot()`。
- 实体相对位置使用实时 `maid.getX/Z()`。
- 实体与方块方向使用实时 yaw。
- section 距离排序读取实时坐标。

如果女仆在 5–10 tick 扫描期间移动或转身，结果会混合多个坐标系，缓存键却仍表示开始时的姿态。

建议冻结：

- 精确眼睛坐标。
- 方块原点。
- yaw/pitch。
- 世界维度。
- 起始 game tick。

若移动超过阈值，应取消并重启，而不是继续拼接结果。

### 6. 图片时间戳和罗盘映射不是实际拍摄时刻

`captureTick` 在服务器发出截图请求前记录，不是客户端完成截图的 tick；视觉提示词中的世界方向映射又在 API 请求阶段读取女仆实时 yaw。

网络延迟或女仆转身后，图片、扫描和罗盘映射可能分别对应三个时刻。

建议让客户端返回捕获时的冻结 yaw/pitch，并让服务器携带实际捕获完成 tick 或至少明确标记为近似时间。

### 7. 扫描调度预算是每任务预算，不是服务器全局预算

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/scan/VisionScanScheduler.java:21`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/VisionScanScheduler.java:54`

每个活动任务每 tick 都可以使用 2000 条射线、40000 次 DDA 和 4 个 section。多名女仆同时扫描时，总负载线性叠加。

同时，活动任务按完整缓存键保存，因此同一女仆可以运行多个不同参数扫描，不符合“同一女仆只允许一个活动扫描”的设计。

建议：

- 增加服务器级全局预算并进行轮询调度。
- 另建 `maid UUID -> active scan` 索引。
- 相同参数合并；不同参数按策略取消旧请求、升级旧请求或排队。

### 8. 缓存键缺少 focus

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/scan/VisionScanCache.java:36`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:876`

缓存键不包含 `focus`，但结果包含 focus，并按 focus 调整排序。短时间内以不同 focus 请求相同扫描参数时，会复用上一请求的排序和 focus 文本。

建议：

- 最佳方案是缓存不带 focus 的中立原始结果，每次调用时重新排序和生成摘要。
- 简单方案是将规范化后的 focus 加入缓存键，但复用率会降低。

### 9. DDA 热路径仍有大量分配

问题包括：

- 每条主射线创建一个 `HashSet<Long>`；标准单轴 DDA 通常不会重复进入体素。
- section 枚举每个位置创建一个新 `BlockPos`。
- BlockEntity section 判断对集合反复 stream，并反复 `BlockPos.of`。

建议：

- 删除每射线 `HashSet`，或仅在实现真正 supercover DDA 后使用无装箱小缓存。
- 全程复用 `MutableBlockPos` 或 packed long。
- BlockEntity 直接按 section 分组并直接检查候选位置，不必为了 BlockEntity 展开整个 section。

### 10. DDA 距离和边界并列处理不准确

当前代码先推进 `tMax`，再把三个更新后 `tMax` 的最小值作为命中距离，因此记录的是下一次边界距离，而不是进入当前体素的距离。

当两轴或三轴 `tMax` 相等时，代码一次只推进一个轴，可能在棱角边界额外经过本不应命中的邻接体素。

建议记录本次选择轴推进前的 entry distance，并明确采用普通 DDA 或 supercover DDA 的并列轴规则。

### 11. 方块形状没有真正参与射线相交

当前只要 DDA 进入非空气方块的整个体素，就会消耗透明预算；碰撞形状只用于决定透明类别，没有验证射线是否真的碰到形状。

影响对象包括：

- 打开的门和活板门。
- 栅栏、墙、铁栏杆。
- 台阶、楼梯。
- 火把、花草和细小模组装饰。

建议在重要可见性和实体可见性上至少进行 shape-aware 交点验证；普通概况射线可以保留更便宜的近似路径。

### 12. 不可用区块被当作空气继续穿透

射线遇到未加载区块时当前执行 `continue`。这不会加载新区块，但会把未知区域当成透明空间。

更保守的做法是终止该射线并标记 `unknown/unloaded_boundary`，避免声称未知区域后方目标可见。

### 13. 六面截图会阻塞渲染线程

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/client/vision/CubemapCapture.java:43`
- `src/main/java/com/wjx/touhou_aifun/client/vision/CubemapCapture.java:89`
- `src/main/java/com/wjx/touhou_aifun/client/vision/CubemapCapture.java:118`

一次渲染线程任务中依次执行：

- 六次完整 `renderLevel`。
- 六次 GPU 截图读取。
- 逐像素复制到六个 `BufferedImage`。
- 六次同步 JPEG 编码。

这很容易产生明显卡顿，光影环境下风险更高。

建议：

- 每个渲染帧只拍一面。
- GPU readback 完成后，把像素转换和 JPEG 编码放到受控后台线程。
- 限制同一女仆和客户端同时存在的截图任务数。
- 考虑生成带方向标签的 3×2 atlas，一次上传，减少多图协议开销和模型方向混淆。

### 14. 截图没有强制第一人称

代码只替换了 camera entity，没有保存并强制 `CameraType.FIRST_PERSON`。当玩家当前是第三人称时，渲染器可能从女仆身后或正面偏移位置拍摄，而不是从眼部拍摄。

建议保存相机模式，截图期间强制第一人称，在 finally 中恢复。

### 15. 捕获失败路径等待过久且缺少失败回包

问题包括：

- 客户端没加载到女仆实体时直接返回，服务器等待完整 12 秒。
- 捕获异常只写客户端日志，没有向服务器发送失败原因。
- 分片重复、数量不一致或超过总大小时，服务器删除请求但没有完成 Future，同样等到超时。
- 图像 HTTP 请求最长 45 秒；叠加截图等待后，一次工具调用可能接近一分钟。

建议：

- 增加显式 capture failure 消息和枚举原因。
- 服务器发送前检查主人是否同维度、是否正在 tracking 女仆。
- 损坏分片应立即 `completeExceptionally` 或返回失败结果。
- 为截图、上传、视觉 API 设置分阶段超时和状态提示。

### 16. 新对话不能取消已经发出的视觉 HTTP 请求

新对话会取消扫描和等待中的截图，但视觉提供商的 `HttpClient.sendAsync` Future 没有纳入 `ChatFlowManager`。旧请求仍会消耗时间和费用，只是迟到结果最终不进历史。

建议为视觉 HTTP 单独登记可取消 Future，并在 superseded 时主动取消。

### 17. 没有视觉调用频率和费用保护

模型可以在同一轮多次调用 `observe_surroundings`，反复生成六张图片并请求第三方模型。

建议：

- 同一轮默认最多一次图片观察；确需重拍时要求显式原因。
- 相同姿态下短期复用 cubemap。
- 对相同 focus/scan 参数合并在途请求。
- 在设置页提示六图调用可能产生的费用和等待时间。

## P1：结果可信度和输出约束

### 18. grounded_matches 没有程序化校验

视觉提示词声明扫描是权威来源，但模型返回的 `grounded_matches` 会被原样透传。模型可以编造扫描中不存在的注册名和位置。

建议服务器根据扫描结果验证：

- `scan_kind`
- `registry_id`
- `relative_position`

输出明确的 `verified`、`unverified` 或 `conflict`，并只把验证通过的项目放入权威匹配列表。

### 19. 空视觉响应会被错误标记为成功

提供商返回 2xx 但没有可提取文本时，当前解析器仍构造 `status=ok` 和空场景摘要。

建议空内容返回 `failed` 或 `partial`，并保留明确错误码。

### 20. 图片失败但扫描成功时整体状态仍为 failed

当前结果可能包含有效扫描，却返回顶层 `status=failed`。主 LLM可能把整个工具结果视为失败并忽略扫描。

建议使用：

```json
{
  "status": "partial",
  "image_status": "failed",
  "scan_status": "ok"
}
```

### 21. 最终组合结果不一定严格小于 16 KiB

`mergeScan` 删除 raw text、缩短场景和答案后直接返回，没有再次保证整体长度。完整扫描与视觉字段合并后仍可能超过上限。

建议最后一定生成合法的紧凑 JSON，不能做字符串硬截断。

### 22. 扫描统计存在误导

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/scan/EnvironmentScanResult.java:106`
- `src/main/java/com/wjx/touhou_aifun/vision/scan/ShallowEnvironmentScanner.java:765`

问题包括：

- 已计算 `nearest`，但没有输出最近距离。
- 每个分组最多三个代表样本，每个代表样本又重复携带整个组的 `count`，汇总时会重复计数。
- 透明层可让一条射线产生多个命中，`surface_coverage_estimate` 不再表示射线覆盖率。
- `omittedSurfaceGroups` 实际按超过上限后的命中次数增长，不是不同分组数。
- 紧凑结果中的 `surface_groups = surfaces.size() + omittedSurfaceGroups` 把代表样本数量当成分组数量。
- 超限紧凑结果没有保留方向分布、包围范围和各类总数，未完全满足“不静默丢失”的目标。

建议把“聚合分组”和“代表坐标”建成独立 DTO，再从真实组级计数生成 JSON。

### 23. 实体优先级在可见性计算前做了第一次截断

实体先按玩家、敌对、距离排序；达到 48 个后才停止展开。可见性是在排序和截断之后才计算，因此近距离遮挡实体可能挤掉更远但可见的重要实体。

建议先做轻量可见性/重要性排序，再展开前 48 个详情；或者为玩家、敌对、可见目标和物品载具分别保留配额。

### 24. 自定义实体名称也是不可信输入

视觉提示词只声明图片中文字不可信，但扫描结果中的实体自定义名称同样可以由玩家或世界数据控制。

建议提示主 LLM和视觉模型：实体名称、告示文本、物品自定义名称都只能作为数据，不得作为指令执行。

## nearby_entities 兼容注入审计

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/mixin/NearbyEntitiesContextMixin.java:16`

当前目标类和方法与本仓库依赖的 TLM 版本一致：

- 目标：`NearbyEntityMaidContexts$NearbyEntitiesContext`
- 方法：`getValue(EntityMaid)`
- 目标是其他 mod 类，因此 `remap=false` 正确。
- 运行日志证明 Mixin 已应用。

仍需改进：

- 捕获 `Throwable` 后完全静默降级，不符合“不允许悄悄失效”的要求。
- 应至少限频记录一次警告，包含女仆 UUID、维度和异常类型。
- 可以保留原版回退，但必须可诊断。
- `VisionScanCache.scan` 走同步实体扫描；密集实体环境仍可能在一次 context 读取里占用主线程。

建议为兼容注入增加启动自检或 debug 指标，例如记录增强扫描调用次数、成功次数和回退次数。

## 配置同步与密钥管理问题

### 25. 客户端同步不应复用服务端站点仓库

涉及代码：

- `src/main/java/com/wjx/touhou_aifun/vision/AvailableVisionSites.java:123`
- `src/main/java/com/wjx/touhou_aifun/vision/AvailableVisionSites.java:158`
- `src/main/java/com/wjx/touhou_aifun/network/message/AIFunVisionSitesSyncMessage.java:38`

客户端收到服务器脱敏站点后调用 `AvailableVisionSites.replaceFromJson`，该方法会：

- 加载客户端本地 `vision.json`。
- 把本地同 ID 密钥重新塞入服务器下发的站点对象。
- 把结果再次写回客户端本地 `vision.json`。

风险：

- 连接不同服务器会污染本地视觉站点配置。
- 本地密钥会和远端服务器的 endpoint/model 元数据意外结合。
- 有权限的玩家编辑远端站点时，可能在不知情的情况下把本地同 ID 密钥提交给服务器。
- 断开专用服务器后再启动单人世界，静态客户端快照可能影响集成服务器配置。

建议：

- 服务端继续使用 `AvailableVisionSites` 持久化。
- 客户端新增只读 `ClientVisionSitesSnapshot`，绝不写服务器快照到本地服务端配置文件。
- 客户端 DTO 显式保留 `apiKeyPresent`，但绝不保存真实密钥。

### 26. API Key 状态和清除操作缺失

服务器已经发送 `api_key_present`，但 `VisionSite.fromJson` 和 UI 都不保存或展示该状态。

当前“留空保留原密钥”意味着用户无法显式清除密钥。

建议：

- UI 显示“已配置密钥”或“缺少密钥”。
- 增加明确的“清除密钥”操作或 `clear_api_key` 布尔字段。
- 保存响应应显示成功或失败状态。

### 27. UPSERT 没有校验消息 siteId 与 JSON id 一致

`AIFunVisionSiteSaveMessage` 的 UPSERT 分支忽略消息外层 `siteId`，直接信任 JSON 内的 ID。虽然操作要求权限等级 2，但仍应拒绝不一致载荷，减少配置错误和未来安全风险。

### 28. thinking 字段目前基本是死配置

`VisionSite` 保存 `thinking`，但 UI 未提供编辑，通用客户端也没有按照它统一发送参数。Qwen 和智谱目前写死关闭，SenseNova 又可能要求自己的 `thinking.enabled` 形状。

建议将思考开关留在 provider adapter 内按协议处理；如果不打算给用户配置，就删除无效公共字段。

## UI 与游戏体验优化

### 29. 选中服务商的显示可能与实际调用不一致

当服务器 `selectedSite` 为空时，UI 会把列表第一项显示为已选；运行时却会选择第一个配置完整的站点。如果腾讯没有密钥而阶跃有密钥，UI 可能显示腾讯，实际调用阶跃。

建议服务器同步“解析后的有效 selected site”，UI 不自行猜测。

### 30. 保存语义不一致

- 选择服务商会立即同步。
- 图像开关和扫描开关只修改本地屏幕状态，需要额外点“保存”。
- 直接按返回会丢失开关修改。

建议统一为即时保存，或所有设置都在关闭前统一保存并提示未保存修改。

### 31. 工具执行状态缺少阶段反馈

当前玩家通常只看到英文：

- `scan surroundings`
- `observe surroundings`

建议本地化，并展示：

1. 正在浅扫描环境。
2. 正在获取六面画面。
3. 正在等待视觉模型分析。

失败时应明确区分：主人不在线、女仆未加载、截图失败、图片过大、视觉站点未配置、API 超时、扫描超时。

### 32. provider 可用状态不透明

选中服务商缺少密钥或模型时，`observe_surroundings` 会直接从工具目录消失，玩家和主 LLM都不知道原因。

建议设置页显示：

- 当前选中。
- 已配置/缺少密钥。
- 模型为空。
- endpoint 无效。
- 图像功能开关关闭。

工具不可用时可在目录中保留一个简短不可用原因，而不是完全静默消失。

### 33. focus 对自然语言的排序能力较弱

focus 只与注册 ID 做字符串包含匹配。中文“箱子”“苦力怕”等不会匹配 `minecraft:chest` 或 `minecraft:creeper`。

建议 focus 主要用于视觉提示，不要宣称它能可靠影响代码扫描排序；若需要排序，可增加注册名、翻译键和基础别名映射。

### 34. 可考虑六面 atlas

目前向模型发送六张独立图片并穿插英文方向标签。可以考虑在客户端生成 3×2 合成图，并直接在图像边缘绘制稳定方向标签：

- 减少多图协议差异。
- 降低 SenseNova“最多六张”的边界风险。
- 让模型更容易理解六个面的对应关系。
- JPEG 通常比六个独立文件具有更好的总体压缩率。

若 OCR 质量受影响，可以同时保留 focus 方向的单独高分辨率图。

## 服务商核对结果

### 腾讯 TokenHub

- 默认 endpoint 正确：`https://tokenhub.tencentmaas.com/v1/chat/completions`
- 默认模型正确：`youtu-vita`
- OpenAI 兼容的嵌套 `image_url.url` 结构正确。
- YT-VITA 支持多图；六面图片数量在官方限制内。

官方文档：https://cloud.tencent.com/document/product/1823/130988

### 阶跃普通 API / Step Plan

- 普通 endpoint 形状正确。
- Step Plan 的 `/step_plan/v1/chat/completions` 路径正确。
- `step-3.7-flash` 支持原生多模态输入，默认模型选择合理。

官方资料：

- https://static.stepfun.com/blog/step-3.7-flash/
- https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create
- https://platform.stepfun.com/docs/zh/step-plan/quick-start

### 智谱

- endpoint 与 `glm-4.6v-flash` 正确。
- `image_url.url` 结构符合官方示例。
- 官方支持开启或关闭思考模式；默认关闭符合需求。

官方文档：https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash

### 通义千问

- DashScope OpenAI 兼容 endpoint 正确。
- `qwen3-vl-flash` 默认模型正确。
- 根级 `enable_thinking=false` 符合当前兼容接口用法。

官方文档：https://help.aliyun.com/zh/model-studio/vision/

### 商汤 SenseNova

- endpoint 正确。
- 鉴权使用 `Authorization: Bearer API_KEY` 可行。
- 图片、输出上限、thinking 和响应解析都需要 provider 专用逻辑。
- 当前接口最多六张图片，现有六面设计刚好顶到上限。

## 建议测试补充

### 方块和方向

- 女仆分别朝南、西、北、东，在六个方向放置不同注册 ID 方块。
- 验证图片 face、扫描 direction、世界罗盘映射完全一致。
- 在相邻主射线之间放置单格箱子、矿石和机器。
- 验证目标自身不会遮挡自己，墙后的目标不会返回。

### 透明度和形状

- 石墙、两层树叶、四层染色玻璃、八层透明玻璃、水、植物。
- 打开/关闭门、活板门。
- 栅栏、玻璃板、楼梯、台阶和模组动态碰撞形状方块。

### 移动和缓存

- 扫描过程中让女仆旋转、移动、传送或切维度。
- 同一姿态连续用不同 focus 调用。
- 同一女仆并发不同方向和模式请求。
- 多名女仆同时扫描，记录全局每 tick 工作量。

### 实体

- 玩家、敌对生物、被动生物、掉落物、载具、投射物。
- 近处遮挡目标与远处可见目标同时超过 48 个。
- 自定义实体名包含伪指令文本。
- 验证 `nearby_entities` 增强成功和故障回退日志。

### 截图和网络

- 第一人称、第三人称背后和第三人称正面。
- 主人不同维度、女仆不在客户端 tracking 范围、主人离线。
- 光影/Oculus、窗口缩放、资源重载。
- 分片缺失、重复、乱序、超大小和主动取消。
- 统计六面截图对单帧耗时和主线程卡顿。

### 服务商

- 每家使用真实 Key 的可选冒烟测试。
- 验证请求体快照，不把真实 Key 写入测试输出。
- SenseNova 必须独立覆盖 `image_base64`、`max_new_tokens` 和 `data.choices[].message`。
- 2xx 空回复、非 JSON 回复、429、401、超时、主动取消。

### 输出约束

- 密集方块和实体确保最终 JSON 始终合法且不超过 16 KiB。
- 验证省略计数、方向分布、最近/最远距离和包围范围。
- 验证 `grounded_matches` 中伪造注册名被标记为未验证或冲突。

## 推荐实施顺序

1. 修复重要方块自身遮挡。
2. 统一扫描、截图、实体和罗盘左右方向。
3. 增加上述两项的自动化回归测试。
4. 新增 SenseNova 独立适配器和请求体测试。
5. 删除截图请求中的 focus/scanJson，并确定扫描数据对第三方的隐私策略。
6. 冻结扫描与拍摄姿态，修正 tick 和方向映射。
7. 改造扫描调度为同女仆单任务、服务器全局预算。
8. 优化 DDA 分配、section/BlockEntity 访问和 shape-aware 可见性。
9. 拆分六面截图到多帧并异步 JPEG 编码，增加失败回包和取消。
10. 修正输出统计、最终 16 KiB 边界和 grounded match 校验。
11. 拆分客户端站点快照与服务端持久化仓库。
12. 完成 provider 状态、密钥清除、本地化阶段提示和保存手感。

## 当前实施与验证状态

上述推荐项已经完成代码修复，包括方向与冻结姿态、重要方块可见性、全局分 tick
预算、实体增量扫描、shape-aware 遮挡、六面分帧截图、异步 JPEG 编码、截图取消、
SenseNova 专用协议、服务商配置脱敏、输出截断统计、grounding 校验、请求限流与阶段提示。

后续逐项复核又修正了默认数据覆盖：`vision_scan_important` 现已明确包含矿石标签、
远古残骸、梯子/脚手架、三类传送门、熔岩、普通火和灵魂火；`thin` 透明度标签补充了
草、蕨、藤蔓、洞穴藤蔓及下界藤蔓等典型薄层植物。这些项目此前虽然有扫描算法支持，
但会因默认标签漏项而无法稳定获得计划中的重要方块兜底或透明预算。

第二轮实现对账继续修正了四项隐藏偏差：

- 组合观察改为服务端浅扫描与客户端六面截图并行启动，避免原先串行流程带来的约
  11–16 tick 时间错位；视觉 API 仍在两边汇合后才调用。
- cubemap 单面渲染同时冻结当前/上一帧旋转，消除 `partialTick` 将 90° 面旋转插值成
  任意角度的问题；六面拍摄期间观察点也固定到首面位置，渲染结束完整恢复客户端实体。
- BlockEntity 候选改为直接使用区块索引逐个验证；仅 palette 确实包含其他重要状态时
  才枚举 section，不再因为一个模组机器把整个 4096 状态 section 扫一遍。
- 扫描缓存拒绝负 tick 年龄，停服时清空，并在清理过期项后仍执行 256 条硬容量限制。

第三轮配置与工具调度对账修正：

- 首选视觉站点存在但缺少 Key、模型或有效 endpoint 时，现与首选 ID 丢失时一致回退到
  第一个配置完整的站点；客户端同步的是实际生效站点。没有可用站点时只停用图片工具。
- 配置路径由类加载时解析改为使用时惰性解析，避免在 Forge 生命周期尚未建立时触发
  `FMLPaths.CONFIGDIR` 初始化异常。
- 当前 TLM 1.5.3 的 `LLMCallback.onSingleCall` 字节码确认统一调用 `ITool.onCallAsync`。
  两个视觉工具的旧同步钩子不再执行完整方块扫描，防止绕过全局 per-tick 预算。
- 新增首选回退、六个默认站点 endpoint/model，以及 Qwen/智谱关闭思考 wire shape 的
  回归测试；阶跃普通和 Plan 默认均锁定为 `step-3.7-flash`。

第四轮输出与取消对账修正：

- 扫描硬超时现在返回 `truncation_reasons=["hard_timeout"]`，并提供
  `expected_primary_rays` 与 `unprocessed_primary_rays`，不再只给一个没有原因的
  `truncated=true`。
- 扫描完整结果、compact 摘要、视觉模型响应和最终组合包每一层都增加 UTF-8 16 KiB
  复检与无外部可变文本的 emergency envelope；极端自定义站点 ID、错误文本、维度名或
  聚合键也不能突破边界。
- compact scan 的说明改为中性的“详细项在紧凑摘要中省略”，避免组合包缩减扫描时谎称
  原始扫描自身超过 16 KiB。
- 截图取消消息由 maid 级升级为 `requestId + maidUuid` 精确取消，避免旧超时包误停同一
  女仆的新截图；网络协议升为 v4。
- 截图请求发送同步失败时立即移除 pending；返回给活动观察的 Future 被取消时立即释放
  全局付费请求许可，不再等待 12/45 秒上游超时。

自动化验证：

- Java 17 下 `gradlew build --no-daemon` 已通过，包括单元测试、Mixin refmap 生成和
  `reobfJar`。
- `VisionScanTagCoverageTest` 会锁定重要资源/通行/危险方块以及五档透明度代表项；新增
  数据覆盖后已单独执行通过。
- 成品 `build/libs/Touhou-AIFun-0.3.jar` 已确认包含
  `touhou_aifun.refmap.json`、`touhou_aifun.mixins.json`、视觉取消消息和
  `NearbyEntitiesContextMixin`。
- 2026-08-07 13:34 使用 Forge 47.2.0 + TLM 1.5.3 启动开发客户端，完成模组初始化、
  资源重载、OpenAL 初始化和纹理图集创建并稳定到主菜单；没有出现 MixinApplyError、
  InvalidInjection、InvalidAccessor 或视觉网络注册异常。
- 开发运行配置会打印 `touhou_aifun.refmap.json could not be read` 警告；这是 userdev
  类路径不直接加载构建产物 refmap 所致，成品 JAR 内已验证存在。日志唯一异常是离线开发
  账号无法鉴权 Realms，与本模组无关。
- 上述第二轮改动后再次执行完整 `gradlew build --no-daemon`，单测、refmap 和 reobfJar
  全部通过；13:49 再次启动到主菜单，未出现 Mixin、字段映射、资源或网络加载错误。
- 第三轮配置和工具调度修复后再次完整构建通过。
- 第四轮修复后完整构建通过，并再次启动到主菜单；网络 v4 消息注册和客户端类加载正常，
  日志仍只有离线开发账号的 Realms 鉴权信息。
- 2026-08-07 14:18 首次世界内浅扫描暴露六面主射线的收尾越界：第六面最后一条射线
  推进后 `faceIndex` 变为 6，但旧逻辑要等退出循环才标记完成；同 tick 尚有预算时会读取
  `faces.get(6)`。现已在循环入口校验 `faceIndex < faces.size()`，并在最后一面推进完成时
  立即设置 `surfaceDone`，形成双重边界保护。修复后 Java 17 `gradlew test --no-daemon`
  通过；调度器异常路径也确认会异常完成 Future 并移除活动任务，不会永久占用女仆扫描槽。
- 2026-08-07 15:01 世界内组合观察确认商汤视觉只实际请求一次并返回 HTTP 403。随后按
  `SenseNova 6.7 Flash-Lite` Token 平台示例复核，发现旧实现混用了两套协议：模型属于
  `https://token.sensenova.cn/v1/chat/completions` 的 OpenAI 兼容接口，代码却请求旧的
  `api.sensenova.cn/v1/llm/chat-completions`，并发送旧式 `image_base64` 内容块。现已改为
  `image_url: {url: data:image/jpeg;base64,...}`、`max_tokens`、`n=1`、
  `reasoning_effort=none`，响应按顶层 `choices[].message.content` 解析。旧默认端点会安全迁移，
  自定义 URL、Key 和模型名不变；非 2xx 仍保留最多 320 字安全错误摘要。
- 同一轮阶跃 TTS 因回复中的逗号、句号和情绪切换被拆成至少 7 个并行请求，叠加一分钟内
  既有调用后超过账号 10 RPM（日志显示 current=11）。阶跃及 Step Plan 现改为每条完整回复
  整批合成一次；其他服务商继续保留逐句流式策略。这样保留回复级打断和队列顺序，同时
  避免低 RPM 阶跃账号被标点数量直接打爆。
- 15:14 的实测又暴露阶跃客户端内部二次切句：即使外层已按整条回复批处理，
  `StepFunTTSClient` 仍使用通用标点切分器，短回复也会进入多段 WAV 路径；WAV 随后被通用
  `TTSAudioToClientMessage` 当 MP3/Ogg 解码，造成 `Next ogg packet header not found`，并让
  基模 `SoundEngine` 对空流触发后续 NPE。现改为仅在超过服务商 1000 code point 硬限制时
  才内部切块，普通回复固定为一次 MP3 请求/一次 callback，并增加含多标点与情绪标记的测试。
- 按 2026-08-07 StepAudio 2.5 TTS 官方新文档再次校正 HTTP 请求：
  `stepaudio-2.5-tts` 的 `/v1/audio/speech` 不再发送旧 `response_format`/`sample_rate`，直接
  使用接口默认 MP3；保留 `model`、`voice`、`input` 与可选全局 `instruction`，正文中的
  ASCII 圆括号指令原样传给 Inline Context。旧 `step-tts-mini/2` 仍显式请求 MP3，防止
  新协议改动破坏旧模型。实时接口文档要求 WAV；仓库内旧 WebSocket listener 尚未接入当前
  回复中枢，不能在未实现 WAV 容器增量拆包及代际取消前把它冒充裸 PCM 开启。
- 同轮商汤模型报告六面全白。原因是 `GameRenderer.renderLevel` 主动写 Minecraft 主帧缓冲，
  旧代码绑定的独立 `TextureTarget` 只经历了白色清屏却从未收到场景。捕获因此改为从真实主
  RenderTarget 读取；编码前会拦截六面全部近纯白的结果，避免把明显坏图发送并计费。后续
  光影兼容修复已进一步保证读取过程中不改变该 RenderTarget 的尺寸。
- 15:34 阶跃视觉实测仍返回 HTTP 403。官方模型介绍确认 `step-3.7-flash` 原生支持视觉，
  Chat Completion 文档也确认当前使用的 `image_url.url = data:image/jpeg;base64,...` 合法，
  因此没有降级改用旧 `step-1v`。检查时还发现当前日志中的主 LLM 实际为
  `deepseek-v4-flash`，不能据此证明独立 `vision.json` 内的阶跃 Key 具备相同权限。通用
  视觉适配器现会对每次非 2xx 单次记录安全错误摘要、site/provider/model/endpoint 以及
  UTF-8 请求体大小；短 JSON 错误和 WAF/网关纯文本均保留最多 320 字，不再只剩裸 403。
- 随后使用 `vision.json` 中同一阶跃 Key 做四次最小直连冒烟：公网 URL 单图、Base64
  Data URL 单图、六张公网图、六张 Base64（约 1.47 MB 请求体）均由
  `step-3.7-flash` 返回 HTTP 200，排除 Key、模型视觉权限、Data URL、六图数量及该体积
  请求限制。最终确认运行配置的 LLM 首选虽为 `stepfun`，但 `[vision].selectedSite` 仍是
  `sensenova`，403 实际来自商汤。已将本地视觉首选纠正为 `stepfun`，并让每次观察开始时
  记录实际 site/provider/model/endpoint，避免以后将独立的 LLM 与视觉选择混为一谈。
- 15:44 用户再次要求检查视觉时，日志中没有新的 `observe_surroundings` 工具调用、捕获或
  HTTP 请求；LLM 直接读取历史里的商汤 `site_id=sensenova`/空响应并声称当前视觉仍罢工。
  这不是阶跃运行失败，而是时效性工具选择错误。系统视觉规则和工具摘要现明确要求：用户
  说“再看、重试、测试视觉、现在看到什么”时，本轮必须重新调用观察工具；历史结果仅代表
  过去，禁止用旧失败判断当前服务状态。
- 继续检查真实阶跃响应后确认还有第二个独立故障：请求虽然 HTTP 200，但
  `step-3.7-flash` 在较小的 `max_tokens=800` 额度下可能把全部输出耗在
  `message.reasoning`/`reasoning_content`，最终 `message.content` 为空并以
  `finish_reason=length` 结束；旧解析器只读取 `content`，于是误报为“provider returned an
  empty response”。使用同一视觉 Key 实测 `reasoning_effort=low`、
  `reasoning_format=deepseek-style`、`response_format=json_object` 和更充足输出额度后，接口
  HTTP 200、`finish_reason=stop` 且最终 JSON 正常进入 `content`。所有 OpenAI 兼容视觉站点
  现均不再发送 `max_tokens`，交给服务商自行决定输出上限；提示词只要求字段完整、内容精简，
  解析器会区分最终答案和未完成推理，若仍只有推理，
  明确报告 `provider returned reasoning but no final answer (finish_reason=...)`，不会泄露推理
  草稿或再伪装成图片未传到。提示词同时强制最终 JSON 必须写入 final message content。
- 20:07 在另一套光影下触发连续 OpenGL `GL_INVALID_VALUE`，具体信息为复制操作的 y 区域
  超出目标图像边界，随后主画面黑屏。根因是六面捕获为取得方形画面，曾把 Minecraft 主
  RenderTarget 临时改成 1024×1024；光影管线的辅助 framebuffer 仍按窗口尺寸分配并复制，
  两边尺寸不一致必然越界。捕获现不再 resize、替换或重建主 RenderTarget，而是保持光影的
  原始 framebuffer 尺寸完成渲染和读回。投影矩阵使用 framebuffer 的真实宽高比，并让较长
  的轴额外渲染视野：横屏多渲染左右，竖屏多渲染上下。随后从内存 RGBA 中心取得天然为
  1:1、精确覆盖 90°×90° 的区域，再缩放到 1024×1024；裁掉的只有为适配非正方形屏幕而
  主动增加的外圈视野，cubemap 所需内容不丢失，也没有任何阶段发生比例拉伸。新增单测锁定
  横屏/竖屏中心取样和非法尺寸拒绝行为。
- 22:15 整合包 Forge 47.4.20 / EventBus 6.2.33 在流式 Anthropic 响应结束时记录
  `Could not find parent ...openai.response.Message for ...ReasoningOpenAIMessage`。该类仅为复用
  三个响应字段而继承基模 `Message`，却在 `ForkJoinPool.commonPool` 首次被 Gson 反射加载；
  EventBus 转换器使用系统 AppClassLoader 追踪跨模组父类时无法看到 TLM 模块。现改为不继承
  任何基模类型的自有 DTO，只在确有 agent 工具调用时转换成基模 `Message`。转换会把推理
  编码、可见内容及完整 `tool_calls` 一并保留；Anthropic 非流式工具调用也直接构造基模
  Message，不再借道该 DTO。回归测试锁定 DTO 父类为 `Object` 以及工具历史无损转换。
- 对用户提供的 Oculus 实机 `latest.log` 按聊天时机重新对齐：22:26:49.188 玩家发送
  “用图像模型观察一下周围环境”，22:26:51.465 `VisionObservationManager` 开始商汤观察，
  22:27:00.539 女仆基于图像正常回答；黑屏发生在该工具调用的六面客户端捕获窗口。日志无
  GL 报错是因为 Oculus 22:25:21.321 明确记录 `Debug functionality is disabled`，不能据此
  排除渲染状态损坏。图片和 HTTP 结果均成功，进一步把根因限定到捕获代码从 ClientTick
  额外手动调用六次 `GameRenderer.renderLevel`：Oculus 的世界渲染管线只支持由正常游戏帧
  成对驱动，额外入口会绕开/打乱 composite 生命周期。现改为在随后六个正常
  `renderLevel` 帧的 HEAD 临时切换女仆相机，并在 RETURN 读取一面、立即恢复，不再主动调用
  `renderLevel`、clear、resize 或重绑 framebuffer。实机安装的 Oculus 1.8.0 经字节码确认在
  `renderLevel` TAIL 执行 `finalizeGameRendering()`；我方 Mixin 优先级设为 900，确保其默认
  优先级收尾先运行、我方再从完成合成的主 RenderTarget 截图。客户端新增捕获开始/结束日志，
  后续可直接看到 request、maid、framebuffer 尺寸、六面完成和 game tick 区间。
- 用户继续确认正常帧借用相机会让自己的视角在六帧内明显转动，因此最终实现已替换为真正
  的第二相机离屏渲染。构建脚本以 `compileOnly` 接入 Oculus 1.20.1-1.8.0，并在
  `mods.toml` 声明可选、仅客户端、1.8.0 以上依赖；成品 jar 不捆绑也不强制安装 Oculus。
  捕获使用独立 1024×1024 深度 framebuffer 和不加入世界的隐形 ArmorStand 相机，女仆与
  玩家实体的坐标、朝向均不再被修改。每个玩家正常画面帧之前只额外离屏渲染一个 cubemap
  面，约六帧完成，随后立刻恢复主 framebuffer、玩家相机和视角模式，以免单帧连续六次世界
  渲染造成长卡顿。Oculus 开启光影时，专用桥接层为当前光影包/维度建立并缓存一套独立
  `IrisRenderingPipeline`；捕获期间同时替换 `PipelineManager` 的当前项和维度项，使 begin、
  shadow、composite、final pass 全部落入离屏目标，再在 `finally` 中原样恢复主 pipeline。
  换光影包、主 pipeline、维度或退出世界时销毁离屏资源。无 Oculus 或未启用光影包时走同一
  离屏相机/framebuffer，但不加载任何 Oculus 类。Accessor refmap 已确认将 Minecraft
  `mainRenderTarget` 正确映射为 1.20.1 SRG `f_91042_`。
- 同一份日志还发现 `vision_scan_important` 引用了 1.20.1 不存在的 `#minecraft:ores`，导致
  整个重要方块标签加载失败；已改为 Forge 1.20.1 的 `#forge:ores` 并更新覆盖测试。该问题
  与黑屏无关，但此前会使矿石重要目标兜底失效。

仍需在游戏世界内人工/集成验证：

- 六面真实截图、方向映射、透明度预算、重要方块兜底及 `nearby_entities` Mixin 首次
  实际调用。
- Oculus/光影、模组动态方块形状、多女仆并发扫描及单 tick 性能采样。
- 使用各服务商真实 API Key 的图片请求、限流、超时和取消冒烟测试。

## 保留范围

- 未重写正在优化的上下文压缩、长期记忆或工具历史策略；视觉/扫描只提交最终工具结果。
- 当前工作区同时含上下文管理改动，视觉修复没有回退或覆盖这些用户改动。
- 当前改动尚未暂存或提交。
