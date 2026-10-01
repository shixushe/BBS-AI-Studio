# BBS AI Studio —— 总账(防截断主文档)

> 本文件是 AI 副驾分支的**唯一权威状态账**。对话可能被截断,一切以本文件 + git log 为准。
> 每完成一批工作必须更新本文件并提交推送。
> 仓库:`https://github.com/shixushe/BBS-AI-Studio`(分支 `ai-copilot`)。
> 本地:`E:\BBS FS AI\bbs-fs`。构建:`JAVA_HOME=/c/Program Files/Zulu/zulu-21` → `./gradlew build`。
> 部署:`cp build/libs/BBS-AI-Studio-2.8-1.20.1.jar "/f/mc/.minecraft/versions/BBS FS/mods/"`。
> 启动:`cmd //c "E:\BBS FS AI\启动 BBS FS.bat"`(后台)。日志:`F:\...\BBS FS\logs\latest.log`。
> MCP 调试:`E:\BBS FS AI\mc-mcp\mc_mcp_server.py`(工具:mc_ping/log_tail/screenshot/command/chat/ui;
> 命令语法 `/xxx`=聊天指令,`key:0`=按键,`click:x,y`=窗口相对点击;会话内 ZCode 会按调用自动重启服务)。
> 客户端调试命令:`/aiui dashboard|film|ai|capture|creative` 免点击打开各面板。

## 一、已完成(全部已推送)

| 提交 | 内容 |
| --- | --- |
| 5015dd62 | M0 网络层 + M2 曲线打磨(意图→数学表,确定性) |
| 1506e855 | M3 FrameCommitter + 原生 undo 事务 + FrameDiff(一次操作=恰一条目) |
| b157c2f0 | M1 §5.2 面板 + M4 §5.1 对话条 + 幽灵帧时间轴标记 |
| 4 commits | M5 PoseSolver(12 动作库)+ BoneNameResolver + FrameSequence 双链路 + §5.6 采集面板 |
| 388909c3 | M6 能力桥:Scanner/ToolSchema(两级剪枝)/ActionDispatcher + AiSkill API(VERSION 3) |
| 185241c7 | M7 图像后端+像素管线+SkinWriter(分层 Document) + M8 创意模式(候选/限额/草稿持久化) |
| 31140943 | §5.3 语义调整区(挂 UIFormPanel 基类,状态外置) |
| 5eb22885 | §5.9 AiTargetRouter 界面跟随(一次操作只跳一次) |
| cef4211cb | 模组更名 **BBS AI Studio** + 引导 Tour + L1 解析重试 + 预览区幽灵边框 |
| f94bc2110 | Anthropic/Gemini 适配器 + 11 家供应商预设 + §5.2/§5.1 按 mockup 一比一复刻 |
| f626f129 | 对话条"生成"端到端(剧本→AnimationPlan→PoseSolver→预览→入框) |

测试:四套自写测试全绿(PixelPipeline 1095 / PoseSolver 35 / FrameCommit 29 / AiCopilot 92)。
`gradlew build` 含 apiCheck 全绿。AI 层零异常(游戏内已验证启动)。

## 二、架构(文件地图)

```
src/main/java/mchorse/bbs_mod/ai/           ← 纯逻辑(可无 MC 测试)
  AiClient/AiSettings/AiException/AiChatRequest/AiChatResponse
  AiTextBackend/OpenAiCompatibleBackend/AnthropicBackend/GeminiBackend/AiImageBackend
  plan/AnimationPlan                        ← L1 严格契约
  pose/PoseSolver|PoseLibrary|BoneNameResolver ← L2(骨骼名只认 ModelForm.bones 枚举)
  curve/CurvePolisher|SmoothingKernel|CurveSnapshots|PolishKind|PolishOp|PolishCommandParser ← L3
  commit/FrameCommitter|EditPatch|EditPatchBuilder|FrameDiff|ChannelStateUndo ← L4(唯一写通道)
  cap/AiCapabilityScanner|CapabilityManifest|AiToolSchemaBuilder|AiActionDispatcher|AiSkills
  capture/FrameSequence|CapturedFrame|FrameThinner
  skin/PixelPipeline                        ← M7 像素管线(纯)
src/client/java/mchorse/bbs_mod/ai/
  AiClientInstall(面板注册+幽灵层)/AiDebugCommand(/aiui)
  AiFilmBridge(commit+广播+跟随)/AiPlans(解析重试一次)
  preview/AiPreviewState|GhostFrameLayer|AiGhostBorder
  ui/UIAiPanel|UICapturePanel|UICreativeModePanel|UIAiChatBar|UIAiSemanticSection|AiPolishFlow
  skin/SkinWriter(TextureFiles 三件套)
  route/AiTargetRouter(§5.9)
api/AiSkill + api/events/RegisterAiSkillsEvent   ← VERSION=3,改 api 必跑 gradlew apiDump
docs/AI_ADAPTATION.md                       ← §12.3 实例适配清单(11 addon + Star 模型 41 骨骼)
E:\BBS FS AI\mc-mcp\mc_mcp_server.py        ← MCP 调试服务(config 指向 tools/pyenv 的 python)
```

关键机制:骨骼通道=POSE 类型(非数值,打磨拒绝);数值通道才可打磨;
写通道=FormProperties.getOrCreate(TrackId.parse("pose.bones.<bone>"));撤销=通道序列化快照
(ChannelStateUndo,CompoundUndo.noMerging);l10n 在 `src/client/resources/assets/bbs/assets/strings/{en_us,zh_cn}.json`;
键位:数字 0=BBS dashboard;/aiui=调试导航。

## 三、需求台账(用户追加,按优先级)

| # | 需求 | 状态 |
| --- | --- | --- |
| R1 | UI 按 mockup 一比一(01/02 已重刻;04 幽灵 3D 剪影待 renderer 级) | §5.2/§5.1 ✅ 已提交 |
| R2 | 支持更多 AI 供应商 | ✅ Anthropic/Gemini/11 预设 |
| R3 | 模组名 BBS AI Studio | ✅ 2.8-1.20.1 |
| R4 | **设置界面:AI 设置独立标签,且修"显示键值而非名称"** | ⬜ 本轮(补 `bbs.settings.ai.*` l10n) |
| R5 | **MCP 无感调试不流畅** | ⬜ 本轮(命令序列批处理+动作后恢复原前台+少截图扰动) |
| R6 | **AI 对话框移到属性面板下半截**(影片界面) | ⬜ 本轮(editArea 下半) |
| R7 | **AI 建筑单独界面** | ⬜ 本轮(.nbt 结构理解:列表/读取/校验/描述) |
| R8 | **视频采集:Windows 资源管理器选文件 + 加强**(自动走带/缩略图) | ⬜ 本轮 |
| R9 | **按钮状态感知**(特定情节才可用,需 tooltip 说明)+ **向用户提问的对话框**(骨骼候选确认等) | ⬜ 本轮 |
| R10 | 媲美 harness 的 AI 助手(总纲:R5-R9 都服务于此) | 迭代中 |
| R11 | §5.7 UV 叠层渲染(renderUVRegions 六区域边界线+开关) + MirrorBrush(像素级镜像) + [AI] 图层标识 | ✅ 97c4acabc/0a80c1efe |
| R11b | §5.7 对称笔刷 UI 挂载到笔画回调 + 3D 皮肤映射 | ⬜ 下一轮 |
| R15 | 原生合并 BBS-Cubed(gbeic 475 文件)+posecurve(bbsplus 33 文件)进本体,入口直调,AI 自动覆盖 | ✅ 217061475 |
| R16 | 游戏内 HTTP 调试服务 AiDebugServer(127.0.0.1:17878,/ping /log /screenshot /command /openui)——MCP 无感通道,不抢焦点 | ✅ 7954a2990 |
| R17 | QuickPlay 调试启动器(启动 AI调试.bat,--quickPlaySingleplayer 自动进世界) | ✅ |
| R18 | 设置 AI 标签独立+全部行中文名(修键值显示) | ✅ 1ed3f483e/994e4b4b8 |
| R19 | 状态感知按钮 tooltips + 向用户提问对话框(骨骼候选确认 UIAiAskOverlayPanel) | ✅ 9c4457bc0 |
| R20 | 采集强化:资源管理器选文件 + 自动走带采集(实测自动采 12 帧并持久化) | ✅ bd8121a81 |
| R12 | §10.7 inpainting 局部重绘 | ⬜ 未做(后端已留 reference 参数) |
| R13 | §12.5 三个回归样例完整跑通(需游戏内多步 UI 驱动) | 部分(启动/面板已验) |
| R14 | 实例 mods 里 `_disabled_backup/` 有被禁用的旧 jar(bbs-2.7、bbsfsai-2.6、zh_CN) | 用户可随时恢复 |

## 四、续作指南(截断后从这里继续)

1. 读本文件 + `git log --oneline -12` 对账。
2. 每完成 R4-R9 一项:build → 四套测试 → commit+push → 更新本表状态。
3. 游戏 UI 验证流程:部署 jar → 重启游戏(bat,~30s)→ `mc_ping` → 进世界(若在标题:
   click:958,246 单人游戏 → click 世界 → play)→ `/aiui <面板>` → `mc_screenshot` 对照。
4. 注意:用户可能同时开着 ZCode/其它窗口挡住游戏;截图前务必走 server 的 focus_game
   (已内置 AttachThreadInput 抢前台);点击坐标以**截图像素**为准(1936×1056 或窗口实际尺寸)。
5. 实例 mods:`_disabled_backup/` 内是备份;当前生效 jar 必须只有一个 id=bbs 的(即我们的)。

## 五、已知偏离企划书之处(均"以仓库实际为准")

1. 贝塞尔手柄=tick 单位(非 [0,1]);2. elastic/overshoot 用注册表 easing;3. 骨骼通道=POSE
非数值;4. 此树无 ContentType(AiTargetRouter 自带路由表);5. KeyframeFactories 类初始化
拖 MC 依赖,数值判断用本地镜像(同步注释);6. L1 畸形 JSON 重试一次再报错(AiPlans)。


## 六、当前状态快照（截断恢复点）

- MCP 服务 v2.1.0 持久化于 `tools/mcp/mc_mcp_server.py`（HTTP 优先：/ping /log /screenshot /command /openui
  走游戏内 127.0.0.1:17878，OS 注入仅兜底；`seq:` 批处理；改动 mc_mcp_server.py 后需 kill python 进程让 ZCode 重启它）。
- **截图黑屏 = 游戏窗口被最小化**（MC 最小化时 swapchain 停止渲染）。走查前先还原窗口。
- 游戏内实测：/openui?panel=ai 打开 dashboard 成功；采集面板 auto-walk 采满 12 帧并持久化成功；
  QuickPlay 直进世界成功。面板切换已改为分帧重试（dashboard 懒构建，打开后数帧面板才注册）。

- 最新推送：994e4b4b8（l10n 补齐）。合并版 jar 已部署实例 mods。
- 游戏内已验证：模组列表 BBS AI Studio ✓、BBS++/posecurve 原生加载 ✓、§5.2 AI 面板渲染（①②③通栏标题/生成 blocking/状态灯）✓、
  采集面板自动走带 12 帧并持久化 ✓、QuickPlay 启动器直进世界 ✓。
- **待验证/待修（下一轮入口）**：
  1. §5.2 左栏 script textarea 在游戏内渲染不可见（疑似 h(1F,-52) 塌陷或缺底色）——UI 照 mockup 像素级打磨的入口；
  2. §5.1 对话条已移至属性面板下半截（editArea y(0.5F)），未目视验证；
  3. 设置 AI 标签行名已补齐但未目视复验（bbs.config.ai.* 全量补齐后需重启游戏）；
  4. §5.9 跟随机制已实现未目视验证；
  5. §5.4 幽灵帧：时间轴标记+预览区边框已做，3D 剪影待 renderer 级；
  6. §12.5 三个回归样例：打磨闭环（离线）代码就绪但未在游戏内完整驱动；
  7. 实例 mods `_disabled_backup/` 内有 bbs-2.7/bbsfsai-2.6/Cubed/posecurve 四个被禁 jar（合并后不再需要，用户可随时恢复）。
- 游戏窗口焦点问题：QQ/其它窗口会盖住游戏导致截图黑屏——HTTP /screenshot 是游戏自己渲染，
  窗口最小化时会拍黑；走查前需还原窗口（mc_ping 的 focus 路径会自动 restore）。
