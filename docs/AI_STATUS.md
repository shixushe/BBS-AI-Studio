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
| R6 | **AI 对话框移到属性面板下半截**(影片界面) | ✅ editArea 对半分：上半属性(host)+下半 AI 对话（本轮升级为滚动对话流） |
| R7 | **AI 建筑单独界面** | ⬜ 本轮(.nbt 结构理解:列表/读取/校验/描述) |
| R8 | **视频采集:Windows 资源管理器选文件 + 加强**(自动走带/缩略图) | ⬜ 本轮 |
| R9 | **按钮状态感知**(特定情节才可用,需 tooltip 说明)+ **向用户提问的对话框**(骨骼候选确认等) | ⬜ 本轮 |
| R10 | 媲美 harness 的 AI 助手(总纲:R5-R9 都服务于此) | 迭代中 |
| R11 | §5.7 UV 叠层渲染(renderUVRegions 六区域边界线+开关) + MirrorBrush(像素级镜像) + [AI] 图层标识 | ✅ 97c4acabc/0a80c1efe |
| R11b | §5.7 对称笔刷接线到 paintPixel 笔画路径(aiMirrorPaint 静态开关+paintMirrorPixel 中心线镜像) | ✅ 17204f57 |
| R11c | §5.7 UV 叠层渲染接线到 UIPixelsEditor 画布(renderUVRegions 六区域边界线+aiUVOverlay 开关) | ✅ 29712483b |
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
  2. §5.1 对话条已升级为对话界面并落位 editArea 下半（见"本轮改动"），游戏内目视已验证 ✓；
  3. 设置 AI 标签行名已补齐但未目视复验（bbs.config.ai.* 全量补齐后需重启游戏）；
  4. §5.9 跟随机制已实现未目视验证；
  5. §5.4 幽灵帧：时间轴标记+预览区边框已做，3D 剪影待 renderer 级；
  6. §12.5 三个回归样例：打磨闭环（离线）代码就绪但未在游戏内完整驱动；
  7. 实例 mods `_disabled_backup/` 内有 bbs-2.7/bbsfsai-2.6/Cubed/posecurve 四个被禁 jar（合并后不再需要，用户可随时恢复）。
- 游戏窗口焦点问题：QQ/其它窗口会盖住游戏导致截图黑屏——HTTP /screenshot 是游戏自己渲染，
  窗口最小化时会拍黑；走查前需还原窗口（mc_ping 的 focus 路径会自动 restore）。

## 七、本轮改动（2026-10-02，R6 对话界面 + 影片属性分屏）

- **影片界面 50/50 分屏**：`UIFilmPanel` 新增 `propertiesHost`（editArea 上半，y(0)~h(0.5F)），
  相机剪辑面板（UIClipsPanel.target）与录制关键帧编辑器（UIReplaysEditor 里 keyframeEditor.target）
  都改挂 propertiesHost；`aiChatBar` 落位 editArea 下半（y(0.5F) h(0.5F)）。时间线 main 恢复满高。
- **UIAiChatBar 升级为对话界面**（类名不变，R6+R10）：
  - 新组件 `AiChatHistory`（UIScrollView + column().scroll() 滚动条）+ `AiChatMessage`（角色气泡：
    你=accent 淡底 / AI=深底 / 系统=灰字无底 / 错误=红底，自动换行、高度随内容、上限 100 条、自动跟底）；
  - 底部两行：输入行（AI 徽标+生成/打磨模式切换+输入框+执行，回车即发送发送后清空）+ 预览行
    （仅在有待入框预览时出现，不再用状态行常驻）；
  - 一切反馈进对话流：未配置后端/无回放/非模型/无意图等不再挤在一行状态里，
    生成中显示「正在生成…」气泡，完成/失败原地更新（失败转红），入框后留回执（含 Ctrl+Z 提示）；
  - 欢迎语教流程（生成/打磨/幽灵帧预览/入框/撤销）。
- **上半属性滚动条**：UIClip/UIKeyframeFactory 本就内建 UIScrollView（UI.scrollView → column().scroll()），
  压到半高后内容溢出即出滚动条，无需改动。
- 构建：compileClientJava + gradlew build（四套测试 + apiCheck）全绿；jar 已部署实例 mods。
- **游戏内目视已验证（09:38 截图）**：/aiui film 后右栏 50/50 分屏正确——上半属性（空态提示/选中剪辑后的
  位置+角度字段，带自身滚动条，不再溢出），中缝 accent 分界线，下半 AI 对话（欢迎语完整换行、
  底部 [AI][生成][打磨][输入][执行] 行）。log 无任何 bbs_mod UI 异常。
- **缝合点修复**：gbeic `UIFilmPanelMiniWindowSupport.editAreaPanel()` 原样返回整个 editArea，
  其 reassert/embed 会把检查器满高拽回 editArea 盖住对话栏（首轮截图实测溢出）——已改为优先返回
  `panel.propertiesHost`（上半），BBS++ 全部重挂路径（UIClipInspectorEmbedMixin 等）随之归位。
- AnimationStateLayoutSupport 的 editArea 是 statesKeyframes 自己的元素（非影片面板的），无需改动。
- 新 l10n（en+zh）：bbs.ui.ai.chat.you/assistant/welcome/unconfigured/thinking/generated。

## 八、第二轮修复（2026-10-02 上午，用户走查反馈）

用户反馈四点：关键帧轴改回默认、属性面板超出分界线+滚动失效、属性滚动改上下、删掉 [AI] 徽标。

- **属性面板回归竖排+上下滚动**：`UIClip` 不再读 `timeline.horizontal_clip_editor`
  （用户配置为 true，横向排列在半高属性区里字段卷成横向列、滚轮方向对不上——即"滚动失效/显示问题"）。
  固定 `UIScrollView(VERTICAL)` + `column().scroll().vertical().stretch()`，即 BBS 默认形态。
  设置项仍注册（与 BBSSettings 540 行列表耦合）但已无消费者，配置里残留的 true 无效果。
- **删除输入行 [AI] 徽标**：UIAiChatBar 的 chip 字段/创建/行成员/refreshPreviewRow 里的计数更新全部移除；
  预览计数仍在预览行状态与对话流里。
- **超出分界线**：用户截图实为上一轮旧 jar 的现象（editAreaPanel 缝合点修复前）；
  本轮复核 BBS++ 全部重挂路径均指 propertiesHost，refreshOffsets 仅 resize，安全。
- 构建+四套测试+apiCheck 全绿，jar 已部署，/aiui film 打开无异常（log 验证）。
  **目视复验未完成**：用户正在钉钉沟通（窗口最小化了游戏），为不打扰只做零干扰验证；
  待用户方便时自行核看：右栏上半=竖排属性+竖向滚动条、下半对话无 [AI] 徽标。

## 九、第三轮（2026-10-02，R4'：设置界面全部键名→名称）

用户截图：BBS++ 设置分类（bbspp.config.*）整组显示原始键名。全量对账后发现主模组
15 个分类（transformation/camera/viewport/performance/timeline/workspace/misc 等
约 180 键）在 zh_cn 同样缺名——之前 R4/R18 只补了 AI 标签。

- 对账脚本（tools/fill_settings_l10n.py 内含审计逻辑）：解析 BBSSettings.java +
  BBSPlusPlusSettings.java 的 category()/get*() 注册 → 与 zh_cn/en_us 比对。
- 补齐 zh_cn 280 键（名称+注释，含 8 个缺失分类的 title/tooltip）、en_us 94 键；
  删除 4 个与代码键名脱节的过时键（prevent_negative_keyframe / enable_ui_keyframes_layer /
  locked_layout_prevents_rotation / item_spray），按现行键名重写。
- 补 film_alt_wheel_timeline_mode 的三个模式标签（默认缩放/禁用/水平滚动）。
- 剩余未补的只有 `.invisible()` 隐藏键（不在界面渲染，无需名称）。
- 构建+测试全绿，jar 已部署重启。验证方式：重启后打开设置（0 → 设置）逐页核对。

## 十、第四轮（2026-10-02，R4''：键名清理收官 + bbspp 语言文件从未加载的合并 bug）

用户三张截图：影片可见性菜单（bbspp.ui.film.visibility.*）、`bbspp.ui.film.visibility.title`、
`bbs.config.ai.ai_uv_overlay` 仍是键名；并要求"设置里的总名称也要改"。

- **根因（合并 bug）**：BBS-Cubed（FSloveCML）合并时没带 Fabric 入口——fabric.mod.json 只有
  BBSMod/BBSModClient，`BBSFSloveCMLClient.onInitializeClient` 与其 Addon 事件订阅从未执行，
  `assets/bbspp/{strings,lang}/*.json`（约 1351 键）从未被 L10n 加载——bbspp 的所有 UI 标签
  一直在显示键名（可见性菜单、动画状态面板等），F5 可见性菜单键位也一直没注册。
- **修复**：`BBSModClient` 初始化里把 `BBSFSloveCMLClientAddon` 挂上事件总线
  （恢复 RegisterDashboardPanelsEvent→F5 键位）；bbspp 两个语言文件并入主 strings 文件
  （merge 脚本 tools/merge_bbspp_l10n.py，主文件优先只补缺）；addon 内部失效的
  bbspp 路径注册删除（主 provider 读不到 assets/bbspp/...，注册只会报加载失败）。
- **反向对账**（tools/audit_lang_keys4.py）：运行时命名空间 = 主 jar 全部语言文件 + 活跃插件 jar，
  以"en_us 与 zh_cn 都缺才显示键名"为准——修复后 2229 个 lang() 字面量调用零键名残留。
- **补齐**：bbs.config.ai.ai_uv_overlay（+AI 分类 tooltip）、bbspp.config.title（设置模块总名称
  「BBS++ 设置」）、动画状态三个停靠面板标题、回放关键帧分区×6、循环菜单×7、轨道分类×5、
  模型调试元素×6、程序化骨骼×7、内置曲线通道×3（zh+en 全配）。
- 构建+测试全绿；启动日志无语言加载失败；重启后可见性菜单/设置 AI 页/设置模块标题即正常。

## 十一、第五轮（2026-10-02，R4'''：964 个"英文回退"键全量翻译）

用户："排查其他类似错误，有很多"。把排查口径升级为三级：
A 键名残留（zh+en 都缺）、B **英文回退**（zh 缺 en 有——中文用户看到英文）、C 硬编码字面量。
对账脚本：tools/audit_lang_keys5.py（A/B/C 三桶）+ dump_english_only.py（带英文值导出）。

- **B 桶 964 键全部完成中文翻译**（zh_batch1/2/3.py，按命名空间分三批）：
  形态编辑器 139、模型编辑器 ~200、纹理浏览器/画笔/图层/帧动画 ~120、场景回放处理操作 56、
  新手引导 52（含 tour 全部步骤）、相机面板 30、影片回放 29（烘焙 IK/运动路径/玩家配置）、
  影片控制器/标记/备份/渲染队列、模型方块、雪球粒子、结构选框与切取、变换操控（欧拉/四元数、
  镜像编辑、空间列表）、按键绑定等。
- zh_cn.json 现 3625 键（本轮 +966，含按键分类 category.bbspp.keys 与 key.bbspp.open_quick_replays）。
- A 桶仅剩 8 个动态拼接前缀（具体子键已全部补齐，均有优雅回退）；C 桶 2 处是 IR 汉化器内部标记，非 UI。
- 构建+测试全绿，部署重启零语言加载失败。至此代码引用的全部语言键在中文下零键名、零英文回退。

## 十二、第六轮（2026-10-02，滚动条被分隔条吃掉）

用户：影片界面时间轴右缘的轨道滚动条"点不了"（截图圈选 x≈1550 竖条）。

- **根因**：UIDockLayout 的分隔条手柄宽 14px 且骑缝居中（applySplitterHandleBounds：
  seam ± half），而 dock 面板相邻无缝——手柄带向两侧各压 7px，正好完整盖住被停靠时间轴
  在自身右缘绘制的轨道滚动条命中区（约 6px 宽）。手柄在事件派发顺序里先于面板，
  点击滚动条被当成"开始调整面板大小"，滚动条永远拖不动。左栏（回放属性/列表）等
  右缘带滚动条又右邻接缝的面板同样受害。
- **修复（让位机制）**：
  - `UIElement.isOverScrollbar(x, y)` 多态探针（默认 false）；
  - `UIScrollView` 用自身 `scroll.hasScrollbar() && getScrollArea().isInside` 覆写；
  - `UIClips` 用 `vertical` 滚动条同样覆写（时间轴轨道滚动条）；
  - `UIDockLayout` 分隔条手柄 `subMouseClicked` 开头：点击落在任何可见面板的滚动条上
    （递归遍历 dock 子树）→ 直接放行不消费，面板自己的滚动逻辑接管。
- 构建全绿，已部署重启。待用户验证：时间轴右缘滚动条可拖动，且拖动分隔条（缝上非
  滚动条区域）仍正常调整面板大小。

## 十三、第七轮（2026-10-02，确认弹窗崩溃 + 自动路由细化 + AI 骨骼绑定）

用户实测反馈四点：①确认对话框里点输入/确认直接崩溃（crash 2026-10-02_11.56）；②
"你好"这类词不该触发生成；③模型编辑界面应有骨骼绑定功能；④形态选择器里微型模型
（眼睛/嘴/手持物）显示成小白点。

- **崩溃修复（根因=PoseSolver 对不完整骨骼表抛 IllegalArgumentException）**：
  - 确认回调先查 isComplete，不完整→对话流提示缺哪些骨骼+去「AI 绑定」页签，不再进解算器；
  - previewGenerated 整体 try/catch，聊天动作永远不把游戏带崩；
  - 顺带修复弹窗空白（上轮 height(-1) 塌缩）——内容改挂 UIOverlayPanel.content
    （标题栏下方本体），提示+滚动骨骼行（expand 撑满）+固定按钮行。
- **自动路由细化**：新 `AiSmallTalk`（src/main，纯确定性）——纯问候/感谢/身份询问/道别
  → 本地对话回应，不调后端不进生成；剥离关键词后残留含数字/曲线词/过长文本照常路由。
  UIAiPanel（§5.2）同样接入守卫。
- **AI 骨骼绑定（新功能）**：
  - `AiBoneBindings`（main）：每模型 通用→真实 骨骼映射，持久化到
    config/bbs/ai_bone_bindings.json；键=模型路径末段（编辑器 config id 与回放表单全路径归一）；
  - 模型编辑器新增「AI 绑定」页签（KEY_CAP 图标）：六个通用骨骼各一个下拉（全部真实骨骼+
    未绑定），即改即存；
  - 消费方：UIAiChatBar/UIAiCreativePanel 在 resolve() 后先 apply 绑定；
    确认对话框的确认结果也自动存为绑定（同一模型不再重复询问）。
- **微型模型预览自动放大**：ModelFormRenderer.renderInUI 乘 previewFitScale(model)
  （立方体包围球估算，clamp 1~40 倍，只放大不缩小）——眼睛/嘴这类小模型在形态选择器
  里从白点放大到可见；正常尺寸模型返回 1F 渲染不变。
- 构建+测试全绿，部署重启零加载失败。§5.2/采集/创意/结构四板块的深度界面逻辑
  （逐面板走查改造）待下轮游戏内目视驱动继续。

## 十四、第八轮（2026-10-02，四个新增面板以人为中心重构）

- **结构 AI（UIStructureAiPanel，重构最大）**：结构列表原来是一列**不可点**的纯 label——
  现在每行可点选（选中高亮），选中立即在右侧显示"看得懂的事实"：id、尺寸（长×高×宽）、
  方块数、方块实体数；新增「AI 描述」按钮——把这些统计交给 LLM 翻成两三句可读说明
  （未配置后端时按钮直接说明去哪配置）；描述区改用滚动+预换行文本（UILabel 不换行问题）；
  空状态教用户把 .nbt 放进 structures/ 后重开面板刷新；首个结构自动选中。
- **创意模式（UICreativeModePanel）**：候选板原来是无滚动的 column——多批候选直接溢出
  不可见（真 bug）；现在挂 UIScrollView。候选行可**点选**（选中 accent 高亮），「采纳所选」
  采纳用户点中的那条（原来永远只采纳最新一条）；空板显示教学提示（主题示例+流程）。
- **采集面板（UICapturePanel）**：采集窗口原来写死播放头后 48 tick——新增「采集时长（tick）」
  输入框（默认 48，容错回退）；采集完成文案保留帧数回执。
- **AI 面板（UIAiPanel §5.2）**：初始状态从"● 未配置"改为流程教学
  （写描述→生成看节拍表→入框去影片编辑器对话栏）；问候语守卫上轮已接。
- 新 l10n zh+en 各 21 条（structure.facts_*/ai_prompt/none_hint、creative.pick_*、
  capture.duration、panel.ready_hint）。构建+测试全绿，部署重启日志零本模组异常。

## 十五、第九轮（2026-10-02，四个面板推倒重写 + 共享设计语言）

用户："直接把整个界面重新搞，不要在史山上堆史"。执行：不再修补，四个面板的布局代码
全部重写，公共视觉抽到一个组件类。

- **新增 `AiUi`（ai/ui/components）**：四个面板唯一的视觉语言来源——accent 通栏标题条
  （header）、教学提示（hint）、滚动内容列（scrollColumn）、**帧缩略图条（FrameStrip）**
  （Pixels→Texture.textureFromPixels 懒上传，采集中零开销，空态居中提示，release 可释放）。
- **删除死组件**：AiSectionHeader、AiDropZone（无拖入行为）、IntentChip（纯静态）、
  StatusLamp（只剩文案功能）——`git rm`，UIAiPanel 不再引用。
- **UIAiPanel 从零重写**：三栏装饰布局 → 两列聚焦流（左 45%：大剧本输入区+角色/时长
  一行参数；右：节拍表滚动）。砍掉：意图 chips、拖入框、fps、供应商下拉、视觉开关、
  输出统计框。问候语守卫保留。
- **UICapturePanel 从零重写**：两栏半空布局 → 单列流：参数行（间隔/时长/开始采集同行）→
  **帧序列缩略图条**（替代"N 帧"文字，最多 24 帧真实画面）→ 输出路径+状态 → 底部[送去理解]。
- **UICreativeModePanel 从零重写**：左右两栏（左栏几乎空）→ 竖向流：主题（3 行输入）
  +换一批同行 → 候选板滚动+点选高亮 → 底部[采纳所选]+状态。骨骼绑定/不完整解算保护保留。
- **UIStructureAiPanel**：套 AiUi header（可点选/事实/AI 描述逻辑上轮已建，不动）。
- 新 l10n：capture.strip_empty（zh+en）。构建+测试全绿，部署重启无本模组异常。
- 面板类行数变化：UIAiPanel 397→249，UICapturePanel 312→262，UICreative 408→359。

## 十六、第十轮（2026-10-02，功能架构收敛：五入口 → 四入口）

用户："界面还是很混乱，你要理清功能"。诊断：乱的根源不是样式，是**职责重叠**——
五个入口都在"打字→AI 干活"（影片对话栏生成 vs AI 面板生成 vs 创意模式=生成的换皮）。

- **新信息架构**（功能各管一件事）：
  | 入口 | 职责 |
  |---|---|
  | **AI 对话中心**（AI 面板+创意模式合并，删 UICreativeModePanel） | 规划：对话→一批方案→点选看节拍→写入影片 |
  | 影片对话栏（影片界面属性下半） | 执行：生成/打磨/入框，就在曲线上下文里 |
  | 采集面板 | 视觉输入：抓帧→缩略图→（视觉后端）理解 |
  | 结构面板 | 理解 .nbt：事实+AI 描述 |
- **AI 对话中心（UIAiPanel 第三次重写）**：上=对话流（AiChatHistory+输入+发送，回车即发）；
  下左=方案板（滚动+点选高亮）；下右=选中方案的节拍表；底部=[写入影片]+状态。
  路由：问候本地回应 / 打磨词指回影片对话栏 / 其余 CreativeSession 出批
  （预算、草稿持久化保留）。写入走绑定→M3 提交→try/catch。
- **注册收敛**：AiClientInstall 删创意注册；/aiui creative 与 /openui creative 变成
  hub 的别名；Onboarding 删 CREATIVE 独立导览；TourAnchors creative.* 改挂 hub。
- 新 l10n zh+en 各 14 条（hub.*）。构建+测试全绿，部署重启无本模组异常。

## 十七、第十一轮（2026-10-02，布局散架根因修复 + 目视验证闭环）

用户三张截图：三个面板布局全散架。**根因（DSL 误用）**：`UI.column(...)` 创建的元素再调
`.row(...)` 会把列布局**替换成行布局**——我在 hub/采集/结构三个面板都犯了；采集面板还漏了
render() 背景绘制（世界透出）。

- **修复方式：三个面板全部改为绝对定位**（relative().x/y/w/h 逐块钉死），
  不再依赖嵌套 resizer 的隐式行为：
  - hub：对话流（0.32F 高）+输入行 → 方案板|节拍双列（标题各占各的半宽——首轮全宽标题
    互相遮盖已修）→ 底部写入影片+状态；
  - 采集：来源标题→参数行→帧序列标题→缩略图条→输出标题→路径→状态的固定竖排栈 + 补
    render() baseSurface 背景；
  - 结构：左 200px 列表、右侧描述+AI 描述按钮各自钉位。
- **目视验证闭环建立**：部署后 /openui 逐面板截图自查——hub 已截图确认（垂直流正确、
  欢迎语/输入行/底部按钮全部就位），采集/结构因用户在使用电脑（看视频）停止抢屏，
  同模式推定正确、待用户下轮确认。
- 教训入账：**任何 UI 提交前必须先游戏内截图自查**，不允许未目视的布局交付。

## 十八、第十二轮（2026-10-02，"太空了"：用可用的内容填充）

- **hub**：空方案板开局放三个**可点击示例主题**（点击填入输入框，教即所用）；
  节拍列在未选中时显示说明文字。
- **采集**：帧缩略图条从 72px 长条改为**撑满剩余高度**；状态下方加一行使用说明
  （自动走带/送去理解/继续剪辑）。
- **结构**：事实区新增**主要构成**——按数量排序的前五种方块及计数，
  AI 描述到来之前描述区就像一张材料单。
- 新 l10n zh+en 各 8 条。构建全绿，jar 已部署（静默，未动用户前台）。

## 十九、第十三轮（2026-10-02，AI 建筑：直接生成+放置+蓝图导出）

用户："AI 建筑要可以直接做出建筑，和公里(Axiom)mod联动直接出蓝图"。

- **AiArchitecture（client）**：LLM 输出严格 JSON 建造单（shell 外壳[墙/地板/顶/窗/门]
  + boxes 实心填充 + towers 空心塔带雉堞），确定性展开（坐标 clamp、方块 id 注册表校验、
  未知剔除）→ 双蓝图导出：原版 .nbt → 存档 generated/bbs/structures（结构面板可列、
  可放置）；Sponge v2 .schem → 创世神 schematics 文件夹（//schem load，Axiom 兼容格式）。
- **放置通道**：ServerNetwork s17（服务端从 bbs 命名空间加载模板，玩家面前 6 格摆放，
  0x12 flags）+ c21 回包 → ClientNetwork 转发 → 面板描述区显示结果。
- **结构面板新增「AI 生成建筑」块**：主题输入 + [生成建筑] + [放置]（绝对定位；
  首轮截图验证发现放置按钮越界，已修正并重新部署）。
- **限制如实**：Axiom 私有 .blueprint 格式在本机无样本，蓝图导出采用开放 .schem
  （创世神/Axiom 都能读）；生成需配置后端；LLM 只出建造单，形状由确定性展开保证。
- 构建+测试全绿；已部署。用户正在实测（影片界面右键菜单截图确认对话栏精细化生效）。

## 二十、第十四轮（2026-10-02，"界面显示不全"：任务栏遮挡）

根因：**仪表盘任务栏覆盖面板底部约 20 GUI 像素**，所有贴底行（结构面板的[放置]、
hub 输入行+状态、采集的状态/输出栈）都落在被遮挡带内。另查明上一轮部署顺序错误
（先 cp 后关游戏，文件锁致 cp 静默失败），用户拿到的是旧 jar——已改为先关后部署。

- `AiUi.TASKBAR = 20` 遮挡余量常量；hub（输入行/方案板/节拍底缘）、采集（缩略图条
  高度/输出/路径/状态/提示/底栏）、结构（描述/AI 描述/主题行/生成/放置）全部抬高。
- md5 校验机制入流程：部署后必须比对 build 与 mods 的 jar 指纹。
- **目视验证通过**（870×519 小窗）：结构面板全部区块可见——[放置] 全宽按钮、
  [生成建筑]、计数 caption「2 个结构」。构建全绿，已部署。

## 二十一、第十五轮（2026-10-02，结构面板细节：裁切/材料名/安全自动选中）

用户截图：描述区首行被滚动区裁掉一半。

- **showLines 每行补显式行高**（列布局默认子高度为 0，首行文字被裁半）；
- **自动选中改为挑第一个可读结构**——资源包条目（assets:oak_tree）读取失败时不再
  作为面板第一印象甩错误，列表里仍可见、点击才显示失败原因；
- 主要构成材料名改用注册表 id（去掉 Block{...} 包装：obsidian × 14 这样的干净清单）。
- 构建+部署（先关后拷+md5 校验）+ 目视验证：标题条/描述/材料清单/生成建筑/放置全部
  正常渲染。
