# §12.3 适配清单(执行者自扫结果)

> 扫描时间:2026-10-01。方法:枚举 `F:\mc\.minecraft\versions\BBS FS\mods\*.jar`(含 `.original`),
> 读各 jar 内 `fabric.mod.json` 的 `entrypoints` 是否含 `bbs-addon` / `bbs-client-addon`,
> 再扫 class 常量池中的 `Register*Event` 字符串。**运行时注册以 `logs\latest.log` 为准,静态结果与之冲突时以运行时为准。**

## A. 实例 mods 目录全量清单

### BBS addon(entrypoints 含 bbs-addon / bbs-client-addon)

| # | jar | mod id | 版本 | 静态扫到的注册事件 | 适配要点 |
| --- | --- | --- | --- | --- | --- |
| 1 | `BBS-And-Ysm-2.1.3-1.20.1.jar` | `bbs-and-ysm` | 2.1.3 | (未扫到 Register*Event 常量) | YSM 模型集成;form 枚举走 `FormArchitect` 自动覆盖 |
| 2 | `BBS-Cubed-3.5.3+1.20.1.jar` | `bbsplusplus` | 3.5.3 | `RegisterDashboardPanelsEvent` | 仪表盘面板;跟随机制(§5.9)需能路由到它 |
| 3 | `BBS-VFX-build-1.3-1.20.1-fs2.6-2.7.jar` | `bbsvfx` | build-1.3 | `RegisterCameraClipsEvent` `RegisterFormSectionsEvent` `RegisterFormsEvent` `RegisterKeyframeFactoriesEvent` | 自定义 form + 关键帧工厂 + 相机 clip;能力枚举(§10.2)与 isNumeric 边界都要覆盖 |
| 4 | `LumenCore-3.5-fix.jar` | `lumencore` | 3.5 | `RegisterFormsEvent` | 自定义 form |
| 5 | `VFX-LIGHTS-0.1.0-1.20.1-bbsfs2.7.jar` | `vfxlights` | 0.1.0 | `RegisterFormsEvent` `RegisterTrackStylesEvent` | 自定义 form + 轨道样式 |
| 6 | `bbs-new-ik-1.3.1.jar` | `bbs-new-ik` | 1.3.1 | (未扫到) | IK 控制器轨道(`IK_TARGET` / `POLE_TARGET`);候选:§10.4 主动声明技能的典型对象 |
| 7 | `bbs-particle-plus-1.0.0.jar.original` | `bbs_particle_addon` | 1.0.0 | (未扫到) | **已禁用**(`.original` 后缀),不进入运行时;仍按潜在 addon 记录 |
| 8 | `bbs-posecurve-addon-2.8.6.jar` | `bbs-posecurve-addon` | 2.8.6 | (未扫到) | 姿态/曲线类 addon;与 L3 曲线打磨能力可能重叠,枚举时注意 |
| 9 | `bbs_fslovecml-1.3.5-1.20.1.jar.original` | `bbs_fslovecml` | 1.0.0 | (未扫到) | **已禁用**,不进入运行时 |
| 10 | `bbs_physics-1.1-1.20.1-1.20.4-zh_CN.jar` | `bbs_physics` | 1.1 | `RegisterActionClipsEvent` `RegisterDashboardPanelsEvent` `RegisterFormPanelsEvent` `RegisterFormSectionsEvent` `RegisterFormsEvent` `RegisterTrackStylesEvent` | 注册面最大;物理轨道多为非数值(`PHYSICS_TARGET` 等)→ **L3 必须跳过打磨**(§10.3 规则 3) |
| 11 | `irlite-1.1.8+mc1.20.1-zh_CN.jar` | `irlite` | 1.1.8 | (未扫到) | IR 灯光;彩色光照相关 form 走注册表枚举 |

### 特殊条目(非 bbs-addon 入口,但与 AI 层直接相关)

| jar | mod id | 版本 | 说明 |
| --- | --- | --- | --- |
| `bbs-2.7-1.20.1-zh_CN.jar` | `bbs` | 2.7-1.20.1 | mods/ 里的一份 BBS 本体(注册了全部事件)。**与版本自带 jar 的 id 相同**,属实例自带环境,不作为适配对象 |
| `bbsfsai-2.6-1.20.1.jar` | `bbs` | 2.6-1.20.1 | **上一轮 AI 尝试的旧产物**,id 同样叫 `bbs`、注册面覆盖全部事件。⚠️ 与新 AI 层并存时会 id 冲突/行为叠加——**实测本分支前应先移出该 jar**(经用户确认后处理) |
| `Axiom-6.1.3-for-MC1.20.1.jar` | `axiom` | 6.1.3 | §10.8 的软依赖对象;结构互通仍走 vanilla `.nbt`,**禁止 import 其类** |

### 普通 mod(非 BBS addon,记录备查)

sodium 0.5.11 / iris 1.7.5 / oculus 1.8.0 / embeddium 0.3.28 / indium 1.0.34 / fabric-api 0.92.12 /
modernfix / modmenu×2 / Ksyxis / LumenCore(见上) / Patchouli / REI / malilib / tweakeroo / continuity /
colored_light / cwb / architectury / cloth-config / ForgeConfigAPIPort / ModernUI / WorldEdit / yuushya×2 /
modern-glass-doors / justzoom / konkrete / smoothboot / Modern Glass Doors;
`no-fmj`(无 fabric.mod.json,按普通 mod 处理):IBEEditor、[信雅互联]Connector、irlite 旧版 `.pre27bak`、`fabric-api-0.92.6+1.11.14`、embeddium、oculus、justzoom、konkrete、smoothboot。

## B. 模型 `Star bbs fs 人物模型3.6`(§12.2)

**位置**:`<游戏目录>\config\bbs\assets\models\Star bbs fs  人物模型3.6\`(注意:**"fs" 与 "人物" 之间是两个空格**)。
该目录是**容器**,真正的模型是它的 8 个变体子文件夹 + 1 个眼睛模型子文件夹,每个变体自带:

```
config.json            ← 仅运行时设置(scale / culling / welds / anchor / 禁用骨骼等),无骨骼信息
model.bbs.json         ← 骨骼层级(groups: name → {parent, origin, cubes})+ 动画(animations)+ 贴图尺寸
poses.json / ik_presets.json / constraints_presets.json / physics_presets.json
<贴图>.png             ← 单张贴图,64×64(model.bbs.json 的 model.texture = [64, 64])
```

变体:`star人物模型{粗胳膊,细胳膊} × {3D 弯曲缝隙优化, 弯曲处焊接, 弯曲处缝隙优化, 自带眼睛, (细胳膊)女性}`。

### 骨骼层级(从 `star人物模型粗胳膊  3D 弯曲缝隙优化\model.bbs.json` 实读,41 组)

- **核心**:head、headwear、body、torso、torso_lower、3d、anchor、Move_X、`1`、`2`、`3`
- **臂**:left_arm / left_elbow / left_arm_end / left_arm_item;right_arm / right_elbow / right_arm_end / right_arm_item
- **腿**:left_leg / left_knee / left_leg_end;right_leg / right_knee / right_leg_end
- **IK rig**:pole_/controller_ × {left_arm, right_arm, left_leg, right_leg}(共 8 个)
- **护甲**:armor_helmet / armor_chest / armor_leggings / armor_left_arm / armor_right_arm / armor_left_leg / armor_right_leg / armor_left_boot / armor_right_boot
- 层级由每个 group 的 `parent` 字段构成(如 headwear → head);`pole_`/`controller_` 供 IK 求解,不是蒙皮骨骼

### 材质槽 / shapeKeys(如实记录)

- **该模型族未声明材质槽**:`model.bbs.json` 无 `materials` 键,单一 64×64 贴图。`TrackKind.MATERIAL_TEXTURE` 对它**无轨可写**——如实报告,不造 `skin`/`main` 之类的槽名
- **无 shapeKeys**
- **有动画库**(`animations`):idle×2、走×4(平静/普通/活力/垂头丧气)、跑步、奔跑、IK走路、呼吸、摆动作×3 等——PoseSolver 产出应避免与内置动画轨道(如 `Move_X`)冲突

### 对 AI 层的三条直接结论

1. **PoseSolver 的骨骼名必须取自 `groups` 键**(41 个真实骨骼名),`BoneNameResolver` 的候选确认 UI 按此生成;
   IK 目标轨道落在 `pole_*` / `controller_*`,不能当成普通骨骼旋转写。
2. 材质枚举(§10.6)对该模型返回"无材质槽"——这是**合法答案**,不许 fallback。
3. 骨骼名含中文与空格(`1`/`2`/`3`、`Move_X`),轨道地址序列化要原样保留,不做任何"规范化"。
