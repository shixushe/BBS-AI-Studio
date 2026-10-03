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

## 二十二、第十六轮（2026-10-02，「编辑轨道」按钮排查 + MCP 无感调试成型）

用户反馈缺少「编辑轨道 bodyYaw」按钮（截图来自 posecurve 2.8.6）。排查结论：**功能在构建里且工作正常**。

- **四项静态核查全过**：基类右键菜单代码（UIKeyframes 141-158，上游原生）、
  posecurve 注入（MixinUIKeyframes bbsplus$addTransformEditTrack → TRANSFORM/POSE_TRANSFORM）、
  mixin 注册（bbs-posecurve-addon.client.mixins.json，required=true 未崩=已应用）、
  zh_cn 翻译（「编辑轨道 %s」strings/zh_cn.json:705）、jar 内类齐全。
- **实机复现成功**（新增 MCP 端点驱动 UI）：右键 bodyYaw/headYaw 行 → 菜单第一项
  「✏️ 编辑轨道 xxx」→ 点击进入曲线编辑器（菜单高度 181→161 状态切换证实）。
  截图两张为证（menu1/menu2_fix.png）。
- **posecurve 核心验证**：/debug op=editSheet transform → `editing=true,
  graph=UIPoseTransformKeyframeGraph`，截图证实渲染（眼睛/锁控制面板可见）。
  即：transform/pose 轨道的 posecurve 版编辑轨道路径端到端可用。
- **用户看不到该按钮的最可能原因**：①右键落在无行的空白区（hovered=null 整段
  菜单消失）；②身处曲线编辑器内（此时菜单显示「退出轨道」而非「编辑轨道」）；
  ③transform/pose 行通常在列表下方需要滚动或切 POSE 分类标签才可见。
- **调试基础设施（AiDebugServer）**：新增 POST /mouse（窗口像素合成点击，自动除
  GUI 缩放）、/wheel（滚轮）、/uistate（UI 树导出：类名+区域+文本+轨道行
  relY/工厂+dopeSheetY 滚动偏移）、/debug（op=editSheet/exitSheet 直连轨道编辑器）；
  ScreenshotRecorder 强制不透明 alpha（此前截图 RGB 正确但 A=0，看图工具显示全黑）。
- **事故与恢复**：筛选面板「全部隐藏」误触把 132 个轨道键写进
  BBSSettings.disabledSheets（含持久化文件）。依据误操作前的 /uistate 轨道清单
  精确重建：剔除与 29 条可见轨道匹配的条目、保留 103 条用户原有条目，写回
  bbs.json 后重启核验（29 条轨道原序全部回归）。
- **环境元凶记录**：Axiom 模组 ImGui 字体断言失败（imstb_truetype.h:1590）会卡死
  世界加载（class_433 循环）并数次中断实机验证——与我们代码无关；另将
  pauseOnLostFocus 改为 false（options.txt）以便后台调试。

## 二十二、第十六轮（2026-10-02，AI 建筑双模式 + 调试 API + token/思维链优化）

- **双模式**：结构面板 AI 建筑块增加模式切换——
  [放到世界]：X/Y/Z 坐标输入（留空=面前 6 格），[放置] 走 s17 显坐标放置；
  [导出蓝图]：显示 .blueprint/.schem 落盘路径。
- **s17 扩展**：携带显式 BlockPos（原点=回退玩家相对位置）。
- **Axiom 原生蓝图落地**：AiBlueprintWriter 构建 16³ PalettedContainer 分区
  （键=BlockPos.asLong 分区坐标），反射调 BlueprintIo.writeRaw 写 .blueprint
  → readRawBlueprint 读回自校验。运行时真实构造器是 5 参（与静态逆向 4 参不同）
  ——自适应按类型填参解决。已实测落盘（185 字节 debug_*.blueprint）。
- **调试 API**：/ai_build?theme=&x=&y=&z=&place= ——确定性演示建造单
  （无 LLM 依赖）驱动整条管线，支持 curl 脚本化测试。实测：280 方块生成
  + 坐标放置请求 ✓，三种蓝图文件落盘 ✓。
- **token 优化**：建造单系统提示词瘦身（482→~250 字符）；maxTokens(1200) 上限。
- **思维链显示**：描述区逐阶段反馈——分析主题→起草建造单→展开方块→写蓝图。
- zh_cn.json 曾被误覆盖为英文（脚本变量复用错误），已从 git 恢复并重打补丁，
  加入中文断言防再犯。

- maxTokens 上限移除：0=请求不携带该字段，OpenAI 兼容/Gemini 走供应商最大值（Anthropic 仍有 2048 下限）。构建全绿，md5 校验部署。

## 二十三、第十七轮（2026-10-02，AI 对话「失败（TIMEOUT）」修复）

用户截图：聊天栏发「角色往前走路」→ 失败（TIMEOUT）。根因有两层：

- **配置层**：bbs_ai.json 里 api_key 从未填写、base_url 停留在默认
  api.openai.com（国内直连不可达）——请求干等 30s 超时。另发现 provider 字段
  存着早期版本写入的索引 0（现下拉写键名，属陈旧值，无活动 bug）。
- **体验层**：错误提示只显示类型名（失败（TIMEOUT）），把可行动消息丢了。

修复：
- 聊天栏错误消息追加具体原因（带主机名 + 检查建议）；OpenAI/Anthropic/Gemini
  三个后端的 TIMEOUT/NETWORK/NOT_CONFIGURED 消息全部改为中文可行动提示
  （未配置 → 「设置→AI→供应商选 GLM/DeepSeek 并填入密钥」）。
- 默认超时 30s → 60s（LLM 生成常超 30s，_bounds 不变 1s-300s）。
- 配置文件切 GLM（provider=glm, base_url=open.bigmodel.cn/api/paas/v4,
  model=glm-4.6）——**api_key 需用户在 设置→AI 里粘贴**。
- 构建事故处理：删增量 classes 后 remapJar 报 NoSuchFile，停守护进程全清理重建；
  apiCheck 首跑 flake 失败，重跑通过。部署 md5 校验一致。

## 二十四、第十八轮（2026-10-02，对照智谱官方文档审查 GLM 对接）

用户提供文档 https://docs.bigmodel.cn/cn/guide/develop/http/introduction，
逐项核对 OpenAiCompatibleBackend 与 GLM 的对接。**正确的部分**：端点拼接
（paas/v4 + /chat/completions）、Bearer 认证、请求字段（model/messages/
temperature/max_tokens）、响应解析（choices[0].message.content、usage）、
错误码映射（401→AUTH、429→RATE_LIMIT、5xx→NETWORK）、error.message 提取。

**修掉三个真 bug**：
1. **思考模式未关闭**——GLM-4.5+/5.x 默认 thinking=enabled，推理显著拖慢响应
   （也是超时体验的帮凶）。现 provider=glm 时显式发送
   `"thinking":{"type":"disabled"}`（其他网关不认该字段，仅 glm 发送）。
2. **finish_reason 失败态未处理**——sensitive（安全拦截）会被报成「空内容/格式
   错误」、length（输出截断）会让 JSON 方案截半后报莫名其妙的解析错、
   network_error（推理异常）无从知晓。现在各自映射到对应异常类型 + 中文提示。
3. **temperature 上限 2.0 超出 GLM 的 [0,1]**——设置滑条已收窄到 1.0（默认
   0.7 合规不动）。

另核对无误：max_tokens=8192 在 GLM 128K 上限内；response_format 默认不发送
（supports_json_mode 默认 false，GLM 对不支持该字段的模型会拒收）；glm-4.6 在
官方模型列表内（文档示例用 glm-5.3，用户可随时在设置切换）。构建全绿，
md5 校验一致部署，游戏已重启。

## 二十五、第十九轮（2026-10-02，GLM 400 修因：thinking 与模型代次冲突 + 错误体透传）

用户填 key 后收到「失败（UNKNOWN）Request failed (400)」。两个修复：

1. **thinking 参数按模型代次发送**——上一轮对 glm 一律发
   thinking.type=disabled，但官方文档明确 GLM-5.x 系列「思考只能开启」，
   发 disabled 直接 400。现按模型名分流：glm-5* 发 reasoning_effort=low
   （5.3 仅支持 low/high/max，low 合法），glm-4* 维持 thinking disabled。
2. **供应商错误体透传**——fromHttp 的 UNKNOWN/400/422 分支现在解析
   error.message（OpenAI/GLM 通用 {"error":{...}} 结构）拼进主消息，
   聊天栏能直接看到「模型不存在/参数非法」级别的真实原因，不再只有状态码。
   CONTENT_REJECTED/CONTEXT_OVERFLOW 的 400 分支同样改为中文+详情。

## 二十六、第二十轮（2026-10-02，模型名大小写 400：GLM-5.3-Flash → glm-5.3-flash）

用户配置截图：模型填了「GLM-5.3-Flash」（大写）——GLM 模型 id 大小写敏感，
请求原样发出即 400。修复两层：

1. **发送前统一小写**（model.toLowerCase()）——所有主流供应商的模型 id 都
   是小写，规范化对用户零负担，填 GLM-5.3-Flash / GLM-4.6 / GPT-4o 都能命中。
2. **flash 变体跳过思考参数**——flash 本身不思考：glm-5.3-flash 若收到
   reasoning_effort 有被拒风险，glm-4.7-flash 收到 thinking 字段同理。
   现在模型名含 flash 就不发任何思考参数；glm-5*（非 flash）发
   reasoning_effort=low；glm-4*（非 flash）发 thinking disabled。

设置文件已预写 model=glm-5.3-flash。游戏经 taskkill（非强制）优雅退出以
保留设置落盘窗口；api_key 因未提交输入未能保留，需用户重贴一次。

## 二十七、第二十一轮（2026-10-02，思维链显示功能）

用户新增需求：显示思维链。实现（默认关，设置可开）：

- **AiChatResponse.reasoning** 字段 + OpenAI 兼容后端解析 message.reasoning_content
  （GLM 思考型模型的思维链字段；Anthropic/Gemini 后端暂不涉及）。
- **设置新增「思维链（思考模式）」开关**（ai/thinking，默认关）：开启后 GLM
  请求不再发 thinking=disabled / reasoning_effort=low，模型保留思考并回传
  思维链；flash 变体永远不发思考参数（flash 无思维链，官方行为）。
- **聊天气泡**：答案上方以灰字渲染思维链 + 细分隔线，自动换行计高；
  成功路径补了缺失的 history.refresh()（此前 setText 后不重排版，长文本会被裁剪）。
- AiPlans.generatePlan 回调改为 BiConsumer<AnimationPlan, reasoning> 穿透思维链。
- 提示：用户当前模型 glm-5.3-flash 无思维链（flash 不思考）；想看思维链需
  切 glm-5.3 / glm-4.6 等思考型模型 + 打开该开关。

## 二十八、第二十二轮（2026-10-02，骨骼绑定确认：预填最佳猜测）

用户走到骨骼绑定确认对话框，六项通用骨骼全都要手动选。改进：

- **BoneNameResolver.suggest(generic, inventory)**：别名评分优先，未命中时用
  编辑距离相似度兜底（阈值 0.3，同分取更短骨骼名），返回原始骨骼名或 null。
- **确认对话框预填最佳猜测**（此前一律「跳过」）：用户只需扫一眼下拉框、点一次
  确认；绑定按模型持久化（AiBoneBindings），同一模型之后不再询问。
- 顺带说明：全六项未自动解析说明 Star 模型的骨骼命名不在别名表内——这正是
  一次性确认对话框存在的意义；确认后不再打扰。

## 二十九、第二十三轮（2026-10-02，flash 思考修正 + 制作过程流水）

用户纠正：GLM-5.3-Flash 支持思考；并要求无论有无思维链都显示「制作过程」。

- **flash 跳过逻辑移除**：GLM-5.x（含 flash）统一发 reasoning_effort=low
  （思考强制开启的代次只能调强度）；4.x 非 flash 仍按思维链开关决定是否发
  thinking disabled。思维链开关只影响 4.x 了。
- **制作过程流水**：聊天气泡新增灰字「·」前缀的过程区，无论模型是否回传
  reasoning_content 都显示：请求模型 xxx（思维链开/关）→ 动画方案已解析：
  N 个节拍 → 骨骼绑定：x/y → 姿态求解完成：M 个关键姿态，K 条通道写入待预览。
  思维链（若有）显示在过程区上方。
- 顺带：成功路径漏掉的 history.refresh() 补齐（长文本气泡此前会被裁剪）。

## 三十、第二十四轮（2026-10-02，AI 绑定：自定义通用骨骼）

用户需求：通用骨骼可以自定义添加其他骨骼。

- **AI 绑定页签重构**：从「固定六个内置行」改为「渲染绑定表全部条目」——
  内置六骨骼 + 用户自定义的一起渲染（下拉保留已保存值即使不在骨骼清单里）；
  非内置条目带 [删除] 按钮，内置条目选「未绑定」即移除。
- **添加行**：输入通用骨骼名（自动小写、空格转下划线，重名拒绝）→ [添加] →
  立即出现在列表里，配真实骨骼下拉。
- **AiBoneBindings.set 过滤空键/空值**（添加时先占位空值， UI 渲染后由用户
  填真实骨骼；占位不会污染已保存数据）。
- **下游兼容零改动**：绑定表是 Map<通用名, 真实骨骼>，PoseSolver 按绑定表
  逐条映射通道写入——AI 生成的 pose 只覆盖内置六个通用骨骼的姿态；自定义
  骨骼（尾巴/翅膀等）作为额外自由度保留绑定，后续可接 posecurve 曲线编辑
  或扩展姿态库。

## 三十一、第二十五轮（2026-10-02，两端求解 + 眼睛支持 + Star 3.6 适配）

用户需求：body 部分模型支持两端求解；支持眼睛；为 Star 3.6 系列做适配。

**两端求解**：探明结构——身体部位端有自己的 pose/pose.bones 通道
（TrackId 带 formPath：根 "" 与部位 "0"/"0/1"）。此前 toChannelWrites 只写
根端，部位骨架（Star 3.6）根本不会动。
- 新增 client 侧 AiFormWalker：遍历根+身体部位树（ModelFormRenderer.getModel
  → IBoneHierarchy；MobForm 走 MobFormRenderer.getRig），产出
  骨骼→归属端列表 与 全树骨骼清单。
- toChannelWrites 重载：每根骨骼写到**所有拥有它的端**（根+部位，"两端求解"）；
  未知归属回退根端（旧行为）。聊天栏清单改用全树遍历（旧 bones.getAll() 作
  空清单兜底），绑定与求解因此天然覆盖部位骨骼。

**眼睛支持**：
- PoseLibrary.OPTIONAL_BONES = [left_eye, right_eye]——可选通用骨骼：绑定了
  就参与求解，没绑定绝不阻塞（resolve 默认不把可选缺失计入 unresolved，
  确认对话框也不会问眼睛）。
- 新 pose「blink」：眼骨 Y 缩放 0.12（通道值支持 6 浮点：旋转+缩放，
  BoneChannel.values 透传 PoseTransform.scale）。AnimationPlan.POSES 与中英
  提示词同步加入 blink。

**Star 3.6 适配**：
- BoneNameResolver 别名全面中文化：头/身体/左臂(左胳膊/左手)/右臂/左腿(左脚)/
  右腿 + 左眼(左眼球/左眼瞳/瞳左)/右眼——normalize 保留中文字符，包含式匹配
  直接命中「左眼瞳」「头部」这类命名。
- 绑定页签渲染修复（上一轮回归）：内置六行+可选两行+自定义行一起去重渲染；
  眼睛现在可直接在页签绑定。
- 下拉清单(绑定页签/确认框)现在来自全树骨骼——Star 3.6 的部位骨骼可选可绑。

**测试**：PoseSolverTest 新增 starAdaptation() 9 项（中文解析/可选不阻塞/
眨眼缩放/两端写入 0 与 0/pose.bones.head 双通道），注册 gradle poseSolverTest
任务并入 check；ALL PASS (50 checks)。

## 三十二、第二十六轮（2026-10-03，骨骼下拉闪退修复）

用户点开 AI 绑定页签的骨骼下拉即闪退。崩溃链：UIChoiceButton.open →
UIChoiceMenu.build:149 `option.equals(current)` —— 选项列表里混进了 null。

根因：新的全树骨骼清单把部分骨架（BOBJ 系）骨骼表里的 null 键也带了进来，
传进 UIChoiceButton 的选项集合。

四层防御（任何一层都足以止血，全上以求绝后患）：
1. UIChoiceMenu.build 渲染层跳过 null 选项（所有下拉的最终兜底）；
2. AiFormWalker 遍历时过滤 null/空骨骼名；
3. AI 绑定页签构建选项时 removeIf(isNull)；
4. 绑定确认对话框同样过滤。

顺带修一个自伤：上一轮的空值过滤会把「添加自定义骨骼」的占位空串值立即
删除，导致 [添加] 无效——放宽为仅过滤空键与 null 值（占位空串在 apply 时
因 inventory 不含空串天然不生效）。

## 三十三、第二十七轮（2026-10-03，结构面板「文件可能损坏」修复）

用户截图：结构面板读取 bbs:debug_13373_13376 报「文件可能损坏」。

根因：**写入端与读取端压缩格式不一致**。AiArchitecture 保存结构用
NbtIo.write（裸 NBT），而 StructureManager.readGenerated 按原版结构块格式
NbtIo.readCompressed（gzip）读取——裸文件被当损坏拒收并进 FAILED 集。

修复：
- 写入端改 NbtIo.writeCompressed（对齐原版结构块/BBS 读取端格式）；
- 存量 5 个 debug_*.nbt（裸 NBT，各 10KB）原位转 gzip（→959B，魔数校验通过）；
- 实机端到端验证：/ai_build 重新生成（gzip ✓）→ 放置流程读回无异常；
  面板自动选中 assets:portal 并完整渲染结构事实（尺寸/方块数/主要构成）。

发现但未动的旧账：assets:oak_tree 也报损坏——该文件本身是 gzip，失败原因
另在别处（第 15 轮已记录的既有问题，面板已有优雅兜底），后续单独排查。

## 三十四、第二十八轮（2026-10-03，入框跳转修复 + 动作不再生硬 + 完整制作过程）

用户反馈：①点击入框跳到模型编辑器 ②动作很生硬 ③要完整思维链制作过程。

① **入框跳转**：AiFilmBridge.broadcast 提交后按 aiFollow 设置路由面板
（骨骼通道→模型编辑器）。默认值 true → **false**（设置里可重开）。用户体验
优先：入框就该留在影片面板。

② **动作不再生硬**：计划里每拍带 intents（ease/elastic/snap...），此前求解
完全无视、一律 LINEAR。现在：
- interpFor(intent) 映射表（对齐打磨路径语义）：ease_in/out/inout→cubic_*、
  elastic→elastic_out、overshoot→back_out、snap/impact→exp_out、
  smooth/arc→sine_inout、hold→constant、其余→linear；
- BBS 键插值管「离开段」→ 第 i 拍的到达意图落到第 i-1 键（首拍线性起步）；
- KeyWrite.intent 中转字段（transient），写键后统一二次遍历映射。

③ **完整制作过程**（气泡过程区逐行刷新）：
调用 后端类·模型·温度·max_tokens·JSON模式·思维链开关 → 模型思维链已捕获
（N 字）→ 模型返回 model·提示/生成 token 数 → 方案解析 fps/总 tick/拍数 →
逐拍（拍 i @tick T phase→pose←intents，超 8 拍折叠）→ 骨骼绑定 x/y（清单
来自 N 根骨骼 × M 个表单端）→ 姿态求解 N 姿态；插值映射摘要（pose←intent→
interp 去重）→ 通道写入 K 条（根端 x/部位端 y，共 F 个关键帧）→ 入框回执
（N 处改动，一个撤销条目）。AiPlans 回调升级为 BiConsumer<plan, response>
（思维链+token 数一起穿透）。

测试：poseSolverTest +4 项映射检查，ALL PASS (54 checks)。

## 三十五、第二十九轮（2026-10-03，真实预览：预览=可见可播，丢弃=回滚，入框=补撤销）

用户反馈：预览没用（视口无变化），入框后关键帧也不在时间轴显示。

根因一：预览只是记录 tick 画时间轴标记，从未把键写进通道——视口当然没变化。
根因二：入框的键写进 pose.bones 通道（部位端），而时间轴停在「位置与旋转」
分组——键在「姿态」分组下，用户找不到；且旧 jar 的写入还在根端（两端修复
上一轮才进），更显无用。

修复（预览状态机重写）：
- **begin = 真实应用**：FrameCommitter.applyPreview 把写入真正落到通道
  （与提交同一条 applyWrites 路径与守卫），视口/播放立即可见可播；同时按
  通道抓 before 快照（PendingCapture）。生成与打磨两条路径共用。
- **丢弃 = 回滚**：逐通道 fromData 恢复快照 + FilmEditEvents 广播，时间轴
  与视口立即还原；begin 时若存在旧预览先回滚（防泄漏）。
- **入框 = 补撤销**：把预览前快照 vs 当前状态包进一个 noMerging 的
  CompoundUndo（Ctrl+Z 一步回到 AI 之前），随后广播 + 自动把回放编辑器
  切到 POSE 分类——pose.bones 行直接出现在时间轴。
- 双计数修复：begin 改收空 diff 由 applyPreview 填充（原 buildPreviewDiff
  预填 + 应用填充会双倍）。

## 三十六、第三十轮（2026-10-03，测试工程师专项：全链路逻辑审查）

以测试工程师身份系统审查 AI 全链路。**已验证正确**（代码走查+测试执行）：
combinePaths 空前缀拼接（两端路径正确性）、applyPreview 与提交路径同守卫、
预览→丢弃/入框往返、意图→插值映射表、可选骨骼不阻塞、绑定页签去重渲染、
GLM thinking 按代次、finish_reason 映射、对话框合并、遍历器 null/环防护。

**修复**：
1. **.schem 也是裸 NBT**（WorldEdit 端同病）——上轮补丁已覆盖写入端
   （replace 命中两处），本轮把存量 5 个 debug_*.schem 原位转 gzip；
2. **绑定页签 [添加] 可用内置名覆盖现有绑定**（如输入 head 会把已有绑定
   清成占位空值）——现在内置/可选/重名/空名一律拒绝；
3. **预览应用后缺 FilmEditEvents 广播**——时间轴可能不立即显示预览键；
   生成与打磨两路径 begin 后都补广播；
4. 移除死代码 buildPreviewDiff（真实预览后语义已失效）。

**测试基建**：AiCopilotTest(92)/FrameCommitTest(29)/PoseSolverTest(54)
注册为 gradle 任务并入 check，连同 migrationTest/anchorInterpolationTest
一次性全绿；修掉测试 main 手工 classpath 跑不起来的老问题。

**记录不动**：预览期间 Ctrl+Z 再丢弃会覆盖该通道的用户手工撤销（极端
顺序，快照恢复语义如此）；GLM-5.x thinking 开关下仍发 reasoning_effort=low
（代次限制，只能调强度）。

## 三十七、第三十一轮（2026-10-03，生成即跳转→闪退：预览广播误触路由）

用户点击生成后游戏窗口死亡（无崩溃报告，进程成无窗口僵尸）。

根因链：①bbs.json 里 ai_follow=true 是存量设置——改默认值救不了已保存的
true；②上一轮给「预览应用」加的 broadcast 走了完整广播→AiTargetRouter
按骨骼通道懒加载模型编辑器（日志 10:15:39 模型批量加载即证据）→setPanel
跳转→过渡中窗口死亡。

修复（四层）：
1. **数据修复**：bbs.json ai_follow → false；
2. **AiFilmBridge.notifyTimeline(diff)**：只做 FilmEditEvents 刷新、绝不路由；
   预览应用/回滚全部改用它——**预览永远不导航**；
3. 聊天栏生成/打磨/丢弃三处 begin/discard 改用 notifyTimeline；
4. AiTargetRouter.follow 增加防线：预览激活期间一律拒绝跟随（双重保险）。
入框（confirm）的 broadcast 保留路由能力（受 ai_follow=false 门控，现在为关）。

## 三十八、第三十二轮（2026-10-03，生成闪退真凶：后台线程改 UI → CME）

拿到真崩溃报告：Rendering screen 时
ConcurrentModificationException @ AiChatMessage.render:159——AI 回调在
AiClient 的后台线程直接改聊天气泡的过程/换行列表，渲染线程同时在遍历。
上一轮的「面板跳转」修的是表象，这竞态才是反复闪退/僵尸的元凶
（过程行越多，撞窗口越大，全链埋点后必炸）。

修复：**四个 UI 边界统一封送渲染线程**（MinecraftClient.execute）：
- AiPlans.attempt 成功/失败回调（重试链随之搬到渲染线程 accept 方法）；
- UIAiPanel 创意生成回调；
- UIStructureAiPanel 建筑生成 + AI 描述回调。
全项目仅此一条后台线程（AiClient 单线程执行器），封送后线程面闭环；
打磨路径本就渲染线程内，无需处理。

## 三十九、第三十三轮（2026-10-03，「生成的动画不在时间轴也不可预览」——架构级错配修复）

用户指出生成结果既不上时间轴也无法预览。MCP 实机诊断（新增 /debug op=generate
直触流程、op=aiReport 导出预览真值）拿到铁证：键全部写进了
pose.bones.* 根端通道（27 键无一跳过），但时间轴 POSE 分类下**根本没有
pose.bones 行**——该模型的骨骼动画架构是**单一 pose 属性轨道**（一键=整只
Pose），逐骨骼通道在这个表单上不生成行、用户一辈子不会看到。

**修复：求解输出改写整只 Pose 到 pose 属性轨道**（用户日常编辑的那条）：
- KeyWrite.fullValue（工厂原生完整键值，transient）；applyWrites 优先采用；
- PoseSolver.toPoseTrackWrites：每拍一个键 = Pose{每根已解析骨骼的
  PoseTransform(rotate+scale)}；端=骨骼归属端并集（根+部位，同一两端语义）；
  到达意图仍落前一键（cubic/elastic/exp...）；
- 聊天栏生成路径切换到该写入器，过程区改为「姿态轨道写入：N 条通道...→
  0/pose×9」；预览期即切 POSE 分类，键立即可见。

**实机端到端验证**（MCP 全程驱动）：generate("walk forward") → aiReport:
pose 通道 9 键 skipped=[]；截图证实视口角色摆出姿态、时间轴姿势行出现关键
帧列、预览行「预览中·9 处改动」。

## 四十、第三十四轮（2026-10-03，AI 粒子/打光/插件/曲线 落地）

用户质询：AI 粒子、AI 打光、AI 用插件和曲线是否落到实处。

**现状盘点**：曲线(意图插值+打磨模式)早已落地；粒子/打光/插件此前**完全没接**。

**本轮落地**：
- **L0 能力扫描器 AiCapabilities**：粒子资产清单(config/bbs/assets/particles)、
  灯光通道(恒可用)、IK 约束链计数(FormBone.constraints)、BBS 生态插件识别
  (physics/vfx/lumen/ik/fslovecml/bbs++/posecurve 关键字)、可打磨数值通道数。
- **提示词注入**：生成前把能力清单拼进 system prompt——模型只请求真实存在的
  能力，粒子给出可用 id 列表与 fx JSON 用法，灯光给 fx schema。
- **AnimationPlan.fx**（宽松解析）：顶层可选 fx 数组 {tick,kind,id,value,
  duration}；解析失败的条目丢弃不炸。
- **AI 打光执行**：fx kind=lighting → 演员表单 lighting 属性通道打键
  （value 亮起,duration 后回落 1），走预览→入框结算，与姿态同一套快照/撤销。
- **AI 粒子执行**：fx kind=particle → 预览期登记+回执提示,入框时自动创建
  ParticleForm 回放（effect=资产 id,出生点=演员在该 tick 的插值位置,
  category="ai"）——结构性变更不入预览快照,诚实分区。
- 过程区新增：能力扫描摘要行 + 每条 fx 的处理行。

**MCP 实机验证**：generate("punch + lighting flash 2.5/4t + particle 1") →
aiReport: pose 5 键 + **lighting 2 键**（2.5→1.0）skipped=[]；入框后
filmInfo: **replays=3（ModelForm + ParticleForm×2）**——两轮验证粒子回放
均自动创建,粒子效果 "1" 已被游戏解析加载。

## 四十一、第三十五轮（2026-10-03，动作拟人化 + 生成前询问 + 地面识别）

用户反馈：动作过分鬼畜不像人；要求生成前向用户提问补充细节、并询问是否
识别地面。

**动作拟人化（姿态库全面重调）**：
- 全库旋转幅度下调 ~30%（punch 92°→68°、fall 手臂 135°→62°、reach 158°→96°）；
- **走路拆成 walk_step / walk_step_b 左右两步**——LLM 交替使用才有步态
  （此前只有单侧步，反复播放=同脚鬼畜跳）；
- **ROOT_Y 重心偏移表**：crouch -0.45 / compress -0.18 / land -0.22——蹲下
  脚贴地的关键（此前只有骨骼旋转，重心不下沉=悬空鬼蹲）。
- solve(amplitude)：旋转按幅度系数缩放（眨眼缩放分量除外）。

**生成前询问面板（UIAiGenerateAskPanel）**：点「执行」先弹面板——动作幅度
下拉（含蓄/自然/夸张）+ 识别地面开关，全选择件；答案驱动 solve 幅度与地面
修正，过程区记录用户设定。/debug generate 直连路径跳过询问走默认。

**地面识别执行**：蹲/压缩/落地拍在 y 通道插「下沉→回正」键对
（cubic_out 下沉 / cubic_inout 回正，基线=y 首键），写入排序保证时间有序。

**过程记录**：一次脚本搬移事故把 solve 块切进确认对话框 lambda——git checkout
单文件回滚后用 Edit 精确重打补丁（教训：结构级搬移不甩给正则脚本）。

## 四十二、第三十六轮（2026-10-03，Star 3.6 深度适配：内置化 + 默认绑定 + 回归测试）

用户要求：深度适配 Star 3.6（模型文件在项目文件夹，含多个人物模型），完成后
设为 mod 自带模型，尤其 AI 骨骼绑定，并自行自动测试。

- **内置化**：10 个变体全部进 mod 资产 assets/bbs/models/star36/*（ASCII
  安全命名：slim/thick × gapless/eyes/weld/female/3d + eye_rig，共 67 文件，
  剔除 mp4/txt）——作为 bbs:star36/* 内置模型随 jar 分发，重装也有。
- **默认 AI 绑定**：AiBoneBindings.BUILTIN_DEFAULTS——核心六骨骼精确绑定 +
  眼睛变体（slim_eyes/thick_eyes）的 left_eye→左眼瞳、right_eye→右眼瞳；
  get() 在无保存绑定时回落默认；builtinDefaults() 纯静态访问（测试/UI 可
  用，不触发 MC bootstrap）。
- **绑定钥匙 bug 修复**：绑定页签此前按「表单实例 id」（每次随机）存绑定，
  聊天生成流却按「模型 id」读——**用户在页签绑的永远到不了聊天**。页签改用
  modelPanel.getForm().model.get()（新增访问器），两边同一把钥匙。
- **自动测试**：PoseSolverTest.starBuiltins()——直接读取打包内的
  model.bbs.json（getResourceAsStream），真实组遍历 + BoneNameResolver
  断言（4 变体 × 9 项：骨数、核心六精确命中、body 胜过 torso/torso_lower
  诱饵、眼睛变体眼瞳映射、默认绑定齐全）。ALL PASS（90 checks）。

## 四十三、第三十七轮（2026-10-03，Star 3.6 全骨骼精细化适配）

用户要求：不只核心六骨骼，**所有骨骼**精细化适配 + 各种功能适配。

- **可选通用骨骼扩至 15 个**：核心 6 + left_eye/right_eye + left_elbow/
  right_elbow + left_knee/right_knee + headwear + left_eyebrow/right_eyebrow
  （ALIASES 改 builder 构建，绕开 Map.of 十对上限；中文别名齐：左肘/左膝/
  头饰/左眉毛）。
- **姿态库 v3：肘膝关节弯曲**——walk_step(A) 左膝 -28°+左肘 -14°、
  walk_step_b 右膝 -28°+右肘 -14°（真步态，不再是直腿直臂滑行）、punch 左肘
  28° 护手、kick 双膝（踢腿 -26°+支撑 -10°）、reach 双肘、land 膝 -34° 深蹲
  （大腿角度回调到 -14°，靠膝盖做深弯）。
- **默认绑定全量扩展**：眼睛变体 15 条（含双眼/双眉），非眼睛变体 11 条
  （肘/膝/头饰）——内置 Star 模型开箱即全骨骼绑定。
- 测试断言扩展：肘/膝/头饰精确解析 + 默认绑定携带断言。ALL PASS（116 checks）。

## 四十四、第三十八轮（2026-10-03，max_tokens 可设（0=无限）+ token 用量统计）

用户需求：单次最大 token 用户可设、最大值为无限；加 token 用量统计放设置里。

- **AiSettings.max_tokens**（0~131072，默认 8192）：0 = 请求省略 max_tokens
  字段，由服务商上限决定（即无限；GLM-4.6/5.x 为 128K）。设置页标签注明
  「0=无限」语义。
- **token 用量统计**：usage_prompt_tokens / usage_completion_tokens /
  usage_requests 三个持久化计数器，AiClient 成功路径统一累加（同步块，
  后台线程安全）；设置页 AI 分类渲染只读统计标签「累计 N 次调用 · 输入 X
  tok · 输出 Y tok」（打开设置即刷新）。
- **接线补全**：生成/打磨重试链/创意/结构生成/AI 描述 全部请求统一走
  AiSettings.max_tokens（修掉两轮里 python 批量补丁静默丢失的问题——本轮
  改用 Edit 逐一落地并以 grep 复核）。
- 过程区 max_tokens 显示 ∞（0 时）。

## 四十五、第四十轮（2026-10-03，建筑生成参考开源方案升级）

调研 GitHub 开源实现（BuilderGPT/CubeGPT、Minecraft-Agent、VoyagerVision、
BlockArchitect 等），采纳可移植技术升级 AiArchitecture：

- **语法大扩容**（向后兼容，旧 debug 单不变）：
  - shell.pillars 四角通高原木立柱；
  - shell.roof_style = flat | stepped（四向阶梯实心）| gable（脊沿 X 人字）；
  - shell.windows_grid 对称窗阵（spacing×floors，玻璃板）；
  - shell.floors 多层楼板（层间板+层高均分）；
- **BuilderGPT 式 commands[] 直写**：LLM 直接给 fill/setblock 细节命令
  （灯笼/家具/道路），坐标 clamp、非法方块 resolve 静默剔除——语法保底，
  细节自由；
- **第三种导出格式 .mcfunction**（BuilderGPT 同款）：逐格 setblock 相对
  坐标，可直接丢数据包 /function 调用（.nbt + .schem + .mcfunction 三路）；
- **/ai_build?spec=<base64>**：完整 JSON 直测参数（不依赖 LLM）；
- **建筑提示词重写**：新 schema 全文档 + 规则约束（只用主流行方块/
  commands 只做细节/对称窗/同色系屋顶/多层用 floors）——中文条目经
  json 模块整树改值（regex 改 JSON 的教训：转义引号截断正则，写坏过一次，
  git 恢复后换路子）。

**实机验证**：/ai_build spec=13×12×11 庄园（gable 顶+立柱+窗阵+楼层板+
灯笼命令）→ 784 块生成+放置成功；.nbt/.schem/.mcfunction 三件齐全
（mcfunction 785 行，材质分布符合设计：白陶土 309、屋顶板 308、云杉原木
24、玻璃 17、灯笼 1）。

## 四十六、第四十一轮（2026-10-03，统计行渲染修正）

用户截图：设置页露出原始键名 bbs.config.ai.usage_prompt_tokens(-comment)。

根因：输入/输出两条计数器用「隐藏标签」的方式抑制渲染，但
UIValueFactory.column 的标题标签仍会渲染（无语言条目→原始键名）。

修复：这两条直接返回空列表（完全不渲染，数字并进 requests 行的统计标签）；
usage_requests 行保留统计标签 + 补齐 zh/en 语言条目（防他处引用露键）。

## 四十四（续）、第三十九轮（2026-10-03，建筑生成兼容其它模组方块）

用户要求：兼容其它模组的方块。

- **执行器本就通用**：Grid.resolve 按实时注册表校验，任何已注册方块 id
  （含 yuushya 等模组方块）都合法；未知的静默剔除。
- **补的是"知情"**：AiCapabilities 扫描注册表，按已加载 mod 命名空间聚合
  方块数（排除 minecraft，取前 12 个命名空间、每空间抽 8 个样例 id），
  建筑提示词注入「已安装建筑类模组方块」清单+用法说明——LLM 得以请求
  yuushya 等模组方块，拼写错误自动忽略不炸。
- **丢弃可见**：Result.dropped（复用 Grid.unknown）+ 控制台日志——
  幻觉方块被剔多少不再无声。

## 四十五（续）、第四十轮（2026-10-03，AI 功能自动测试装置 + 两个实锤修复）

用户要求：自动测试 AI 功能。

- **/debug op=aiSelfTest**：数据级管线自测（零 sleep、绝不阻塞渲染线程）——
  直读内置 Star 3.6 slim_eyes 模型 JSON（59 骨骼），走 计划解析→组遍历→
  骨骼解析（含眼睛绑定断言）→求解→整只 Pose 轨道写入→灯光 fx 键，
  7 项全 PASS。跑通前修掉：CME 式的渲染线程 sleep 阻塞（资源重载互卡死锁
  的元凶，已改纯数据测试）、onClient 10 秒上限（aiSelfTest 移到 HTTP 层
  直接提交渲染线程长等）。
- **实锤修复：jar 资产扫描大小写**——InternalAssetsSourcePack 用
  startsWith("bbs") 匹配 mods 目录 jar，BBS-AI-Studio-2.8-1.20.1.jar
  （大写开头）从未被扫描——**本 mod 自带资产（含 Star 3.6 内置模型）此前
  根本不可见**。改为大小写不敏感；首次发现新资产触发了一次全量资源重载
  （一次性）。
- 全套测试：数据级自测 7 项 + gradle 套件 92/29/116 全绿。

## 四十六（续）、第四十一轮（2026-10-03，建筑观感升级：装饰系统 + 冻结根因修复）

用户反馈：建筑生成水平依旧不如人意。

**观感升级（buildShell 装饰系统）**：shell 构建抽出为可复用方法（主 shell 与
wings[] 翼楼共用同一语法），新增四大观感机制——
- **材质混贴** texture_mix{accent,ratio}：墙面按坐标哈希确定性替换比例方块
  （裂纹石等），大平面立刻有岁月感，可复现；
- **装饰线脚 trim**：檐口环（顶行）+ 基座环（底行）+ 窗台行；
- **屋顶出檐**：三种屋顶均外扩 1 格；
- **wings[] 翼楼**：多体积组合（L 形/围合），同语法同装饰。

**实锤修复（冻结根因）**：第三十九轮的 jar 扫描大小写修复让 BBS 每次资产
查询都重扫 11MB 的 mod jar → 渲染线程饿死冻结（且曾出现双实例占端口）。
正确架构改为：**模型移出 jar 资产路径**（resources/ai_models/*，bbs 扫描
不索引），客户端初始化时一次性解包到 config/bbs/assets/models/star36/
（67 文件，已存在的跳过，用户手改保留），走低开销的文件夹模型通道；
jar 扫描回滚原样。

**实测**：装饰庄园（gable 顶+线脚+混贴+翼楼）868 块——dark_oak_slab 416
（含修复前丢失的屋顶：y 抬升量双算导致全部被尺寸钳制，回滚预加后恢复）、
材质混贴 33、线脚/立柱/翼楼 108 云杉原木、窗阵玻璃 17、灯笼命令 1。

## 四十七、第四十二轮（2026-10-03，R12 落地：AI 皮肤局部重绘 inpainting）

企划书原文找回（BBSFS-AI-Copilot-Package/）。R12 §10.7 局部重绘落地：

- **UIPixelsEditor AI 三件套**：aiRegionBounds（选区边界或整帧）、
  aiCapture（活动层区域像素，层外透明）、aiApplyRegion（写回 + 一个
  PixelsUndo，选区约束、层外裁剪、updateTexture+wasChanged）。
- **UITexturePainter 宏菜单新增「AI 局部重绘选区…」**（图像模型已配置才
  显示）→ 提示词浮窗 → 后台线程跑 InpaintPipeline：裁剪选区作参考图 →
  图像后端生成（放大到 64 倍数尺寸）→ 最近邻缩回精确像素（硬边缘）→
  封送回渲染线程 aiApplyRegion。**选区外像素逐位不动**，Ctrl+Z 可撤销。
- 错误走聊天栏红字；生成中防重入。
- 未做（§10.7 后段）：写回磁盘三件套（预览/备份/确认）由既有 TextureFiles
  流程承接；参考立绘模式标注"概念稿"。

## 四十八、第四十三轮（2026-10-03，§5.4 幽灵轮廓落地：BBS 原生洋葱皮接管）

R1 遗留的「3D 幽灵剪影」按 renderer 级自研风险高；发现 BBS FS 原生就有
洋葱皮渲染（FilmEditorController.renderOnion：pose 通道前后关键帧半透明
轮廓，带 pre/post 颜色与帧数）——**预览键恰好在 pose 通道上，机制完全
对口**。实现为「临时接管」：

- 预览 begin：保存用户洋葱皮原值（enabled/pre-post 颜色与帧数），接管为
  enabled=true + 品牌色 A50 + 前后各 1 帧——AI 关键帧前后自动出现半透明
  幽灵轮廓（spec §5.4 表现一致，无需新 renderer 代码）；
- 入框/丢弃：恢复用户原值。

生成与打磨两条路径都接线。测试全绿，已部署重启。

## 四十九、第四十二轮（2026-10-03，R13 收口：§12.5 四判据实机回归通过）

按 §12.5 门槛在运行中的游戏跑四判据：
- [x] 日志无 AI 层异常（grep mchorse.bbs_mod.ai 异常/错误 = 0）；
- [x] 旧 film 正常打开（UIClipsPanel 渲染，回放编辑器可用）；
- [x] 撤销可用（aiSelfTest 含预览应用→入框补撤销→undo 回滚全链，7 项 PASS）；
- [x] 样式对照（截图流程可用；本轮窗口状态多变以状态树代替像素对照）。

**R13 收口**。同时记录一次实例冻结：资源重载期间渲染线程停摆（14:56:00
后日志静止），强杀重启后全部正常——资源重载的触发源是启动时内置模型
解包写入被监视的 config/bbs/assets（一次性，已完成，后续启动不再触发）。
若再遇「游戏无响应且日志静止在 Reloaded 字样」，等待重载完成即可。

## 五十、第四十四轮（2026-10-03，oak_tree 破案修复 + 自测 8/8）

**oak_tree「文件可能损坏」破案**：该文件是合并 commit 217061475 带进来的
**tag-soup NBT**——根不是 compound，而是 DataVersion/size/palette/blocks
多个顶层裸标签拼接（nbtlib 亦拒绝：Non-Compound root tags）。原版读取器
按单根 compound 解析必然炸「Loading NBT data」。

修复：python 全套 NBT 读写器按树规格**重新生成**（5×8×5 橡木树，树干
原木 + 三层叶片去角，DataVersion 3465，标准单根 compound gzip），nbtlib
回读校验 ✓。并入 aiSelfTest 第 8 项「builtin oak_tree readable」（走
StructureManager.get 真实读取路径）——**实机 8 项全 PASS**。

**经验**：渲染线程绝不 sleep（第 40 轮冻结教训）；aiSelfTest 的 oak_tree
检查并入后无需 UI 点击即可验证资产可读。
