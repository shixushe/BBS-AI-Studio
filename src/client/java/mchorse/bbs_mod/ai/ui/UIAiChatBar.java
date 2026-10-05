package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiFilmBridge;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.EditPatchBuilder;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.ai.curve.PolishCommandParser;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.preview.AiPreviewState;
import mchorse.bbs_mod.ai.ui.components.AiChatHistory;
import mchorse.bbs_mod.ai.ui.components.AiChatMessage;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.colors.Colors;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The film editor's AI chat surface (copilot spec section 5.1) - the lower half
 * of the properties column. One input, no modes: the sentence itself decides
 * what happens. Curve-shaping language (平滑/缓入缓出/6-22...) routes to the
 * offline polish pipeline (iron rule 4 - no backend needed); anything else is
 * a script for the action generation pipeline. Whatever the current scene
 * cannot do, the transcript explains instead of silently failing.
 *
 * <p>Results never land directly: an accepted execution builds channel writes
 * and hands them to {@link AiPreviewState}, which the ghost frame layer draws
 * and the preview row can 入框 (commit through the M3 undo transaction) or
 * 丢弃 (discard). One accepted operation = one undo entry.</p>
 */
public class UIAiChatBar extends UIElement
{
    private static final int ROW = UIConstants.CONTROL_HEIGHT + 4;
    private static final int GAP = UIConstants.MARGIN;

    private final UIFilmPanel panel;

    /** The transcript: everything asked and answered, newest at the bottom. */
    private final AiChatHistory history;

    private final UITextbox input;
    private final UIButton execute;

    private final UIElement inputRow;
    private final UIElement previewRow;
    private final UILabel status;

    private boolean busy;

    /** Change count already reported to the transcript, so the preview entry logs once per result. */
    private int lastLoggedCount = -1;

    /** 生成询问面板带回的答案：动作幅度索引（0 含蓄/1 自然/2 夸张）与地面识别开关 */
    private int lastAmplitude = 1;
    private boolean groundDetect = true;

    /** 贴地移动：行走位移沿地形起伏、遇墙截断（询问面板可关） */
    private boolean lastSnap = true;

    public UIAiChatBar(UIFilmPanel panel)
    {
        this.panel = panel;

        this.history = new AiChatHistory();
        this.add(this.history);

        /* Enter sends, like any chat box - execute() clears the line itself */
        this.input = new UITextbox(256, (t) -> {})
        {
            @Override
            public boolean subKeyPressed(UIContext context)
            {
                if (this.isFocused() && (context.isPressed(GLFW.GLFW_KEY_ENTER) || context.isPressed(GLFW.GLFW_KEY_KP_ENTER)))
                {
                    UIAiChatBar.this.execute();

                    return true;
                }

                return super.subKeyPressed(context);
            }
        };
        this.input.placeholder(L10n.lang("bbs.ui.ai.bar.placeholder"));

        this.execute = new UIButton(L10n.lang("bbs.ui.ai.bar.execute"), (b) -> this.execute());
        this.execute.color(BBSSettings.primaryColor.get() | Colors.A100);
        this.execute.tooltip(L10n.lang("bbs.ui.ai.bar.execute_tooltip"));

        this.inputRow = UI.row(1, this.input, this.execute);
        this.inputRow.row(1).preferred(0).height(ROW);
        this.add(this.inputRow);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.bar.preview"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIButton commit = new UIButton(L10n.lang("bbs.ui.ai.bar.commit"), (b) -> this.confirm());
        UIButton discard = new UIButton(L10n.lang("bbs.ui.ai.bar.discard"), (b) -> this.discard());

        commit.color(BBSSettings.primaryColor.get() | Colors.A100);
        commit.tooltip(L10n.lang("bbs.ui.ai.bar.commit_tooltip"));
        discard.tooltip(L10n.lang("bbs.ui.ai.bar.discard_tooltip"));

        this.previewRow = UI.row(GAP, this.status, commit, discard);
        this.previewRow.row(GAP).preferred(0).height(ROW);
        this.previewRow.setVisible(false);
        this.add(this.previewRow);

        this.relayout();
        this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.chat.welcome").get());
    }

    /** Bottom-anchored rows: input always, preview above it while active, transcript fills the rest. */
    private void relayout()
    {
        boolean preview = this.previewRow.isVisible();

        this.inputRow.relative(this).x(GAP).y(1F, -(GAP + ROW)).w(1F, -GAP * 2).h(ROW);

        if (preview)
        {
            this.previewRow.relative(this).x(GAP).y(1F, -(GAP * 2 + ROW * 2)).w(1F, -GAP * 2).h(ROW);
        }

        this.history.relative(this).x(GAP).y(GAP).w(1F, -GAP * 2).h(1F, -(GAP * (preview ? 3 : 2) + ROW * (preview ? 2 : 1)));
    }

    private void execute()
    {
        if (this.busy)
        {
            return;
        }

        String text = this.input.getText().trim();

        if (text.isEmpty())
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.panel.empty_script").get());

            return;
        }

        /* Small talk (a bare 你好) gets a local conversational reply - never a
         * generation call and never a backend request */
        if (mchorse.bbs_mod.ai.AiSmallTalk.isSmallTalk(text))
        {
            this.history.log(AiChatMessage.Role.USER, text);
            this.input.setText("");
            this.history.log(AiChatMessage.Role.ASSISTANT, mchorse.bbs_mod.ai.AiSmallTalk.reply(text));

            return;
        }

        /* Auto routing: curve-shaping language polishes the open replay's curves
         * (offline); everything else is an action script for the generation
         * pipeline. The scene checks inside each path explain what to change. */
        List<PolishOp> ops = PolishCommandParser.parse(text);

        if (!ops.isEmpty() && this.canPolishScene())
        {
            this.executePolish(text, ops);
        }
        else
        {
            this.askThenGenerate(text);
        }
    }

    /** Whether the open scene offers numeric curves the polisher could act on. */
    private boolean canPolishScene()
    {
        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            return false;
        }

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            if (CurveGuard.polishable(channel))
            {
                return true;
            }
        }

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (CurveGuard.polishable(channel))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * 生成: script -> (backend) AnimationPlan -> PoseSolver on the open
     * replay's model bones -> the same preview/commit pipeline polish uses.
     * The end-to-end loop of the copilot spec's delivery goal.
     */
    /** 点「执行」(生成模式)：先向用户补充细节（幅度/地面识别/贴地），再走生成。 */
    public void askThenGenerate(String script)
    {
        mchorse.bbs_mod.ui.framework.UIContext context = this.getContext();

        mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(context,
            new UIAiGenerateAskPanel(context, (answer) -> this.executeGenerate(script, answer[0],
                answer[1] == 1, answer.length > 2 && answer[2] == 1)), 240, 0.7F);
    }

    public void executeGenerate(String script)
    {
        this.executeGenerate(script, 1, true, true);
    }

    public void executeGenerate(String script, int amplitudeIndex, boolean groundDetect)
    {
        this.executeGenerate(script, amplitudeIndex, groundDetect, true);
    }

    public void executeGenerate(String script, int amplitudeIndex, boolean groundDetect, boolean groundSnap)
    {
        this.lastAmplitude = amplitudeIndex;
        this.groundDetect = groundDetect;
        this.lastSnap = groundSnap;
        this.history.log(AiChatMessage.Role.USER, script);
        this.input.setText("");

        if (!AiSettings.isConfigured())
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.chat.unconfigured").get());

            return;
        }

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_replay").get());

            return;
        }

        if (!(replay.form.get() instanceof mchorse.bbs_mod.forms.forms.ModelForm modelForm))
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.not_model").get());

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");

        AiChatMessage thinking = this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.chat.thinking").get());

        String system = L10n.lang("bbs.ui.ai.panel.prompt").get();

        /* L0 能力扫描：让模型只请求本安装真实存在的能力 */
        mchorse.bbs_mod.ai.AiCapabilities caps = mchorse.bbs_mod.ai.AiCapabilities.scan(this.panel.getData());

        var skillPoses = mchorse.bbs_mod.ai.AiSkillLibrary.posesForModel(modelForm.model.get());

        if (!skillPoses.isEmpty())
        {
            String intentTable = mchorse.bbs_mod.ai.AiSkillLibrary.intentPrompt(modelForm.model.get());

            system += "\n\n该模型自带 " + skillPoses.size() + " 个预设姿势（beat.pose 用 \"@名字\" 直接引用，作者调好的成品姿势）:"
                + intentTable
                + "【硬性要求】挥手、鞠躬、坐下、思考、哭、害羞、睡觉、疑惑、抱胸、摊手、登场、赞美、搬东西、牵手、卖萌等表情/姿态类动作，"
                + "必须直接用上面的 @作者姿势（一个 beat 定住 10-20 tick 即可）；@姿势拍里的 pose 对象可省略或只给少数骨骼，之后的拍子从该造型自然延续。"
                + "走/跑/蹲起等位移动作用骨骼值创作。";
        }

        /* v2 骨骼清单：把实际绑定的泛骨骼连同运动学提示交给模型——
         * 它必须用这些键名逐关节创作 */
        try
        {
            var inv = mchorse.bbs_mod.ai.AiFormWalker.collectBones(modelForm);
            var resolvedNow = mchorse.bbs_mod.ai.pose.BoneNameResolver.resolve(inv);

            java.util.LinkedHashMap<String, String> boneHints = new java.util.LinkedHashMap<>();

            boneHints.put("head", "头：注视先动，身体跟随");
            boneHints.put("body", "上身根：整体前倾/侧倾，配 t 表达重心");
            boneHints.put("torso", "胸廓：呼吸 ±1°、与骨盆拮抗");
            boneHints.put("torso_lower", "骨盆：迈步反旋、蹲/坐下沉配 t");
            boneHints.put("left_arm", "左大臂：与右腿反相摆");
            boneHints.put("right_arm", "右大臂：与左腿反相摆");
            boneHints.put("left_elbow", "左小臂：铰链 X -150~0");
            boneHints.put("right_elbow", "右小臂：铰链 X -150~0");
            boneHints.put("left_leg", "左大腿：步幅/抬腿");
            boneHints.put("right_leg", "右大腿：步幅/抬腿");
            boneHints.put("left_knee", "左小腿：铰链 X 0~150");
            boneHints.put("right_knee", "右小腿：铰链 X 0~150");
            boneHints.put("left_eye", "左眼：眨眼用 s=[1,0.12,1]");
            boneHints.put("right_eye", "右眼：眨眼用 s=[1,0.12,1]");
            boneHints.put("left_eyebrow", "左眉：表情");
            boneHints.put("right_eyebrow", "右眉：表情");
            boneHints.put("headwear", "头饰：随头微晃");

            StringBuilder boneList = new StringBuilder();
            int bound = 0;

            for (var entry : boneHints.entrySet())
            {
                if (resolvedNow.resolved.containsKey(entry.getKey()))
                {
                    if (bound > 0)
                    {
                        boneList.append("；");
                    }

                    boneList.append(entry.getKey()).append("(").append(entry.getValue()).append(")");
                    bound++;
                }
            }

            if (bound > 0)
            {
                system += "\n\n本模型主要骨骼（pose 的键名，每拍全部给出，共 " + bound + " 根）：" + boneList;
            }

            /* 深度适配：其余可直接驱动的实际骨骼（跳过泛骨骼层，直呼其名）；
             * 物理骨由物理系统驱动（二级运动），不列入可驱动清单 */
            java.util.Set<String> physicsBones = mchorse.bbs_mod.ai.AiSkillLibrary.physicsBones(modelForm.model.get());
            java.util.List<String> extras = new java.util.ArrayList<>();

            for (String actual : resolvedNow.inventory)
            {
                boolean mapped = false;

                for (String g : boneHints.keySet())
                {
                    var r = resolvedNow.resolved.get(g);

                    if (r != null && r.actual.equals(actual))
                    {
                        mapped = true;

                        break;
                    }
                }

                if (!mapped
                    && !actual.contains("controller")
                    && !actual.contains("pole")
                    && !actual.contains("_IK")
                    && !actual.contains("_FK")
                    && !actual.contains("Move_IK")
                    && !actual.equals("anchor")
                    && !physicsBones.contains(actual)
                    && !actual.matches("\\d+"))
                {
                    extras.add(actual);
                }
            }

            if (!extras.isEmpty())
            {
                system += "\n其余可直接驱动的骨骼（作者姿势也用这些名字，如 low_body、上眼皮）: "
                    + String.join("、", extras)
                    + "。IK 控制器/极向量骨骼（controller_*、pole_*）留给模型内部程序，不要直接驱动。";
            }

            /* IK 接地：模型自带腿部 IK 链时告知接地策略 */
            String ikNote = mchorse.bbs_mod.ai.AiSkillLibrary.ikSummary(modelForm.model.get());

            if (!ikNote.isEmpty())
            {
                system += "\n\n" + ikNote;
            }
        }
        catch (Exception ignored)
        {}

        if (!caps.modBlocks.isEmpty())
        {
            StringBuilder palette = new StringBuilder();

            for (java.util.Map.Entry<String, Integer> entry : caps.modBlocks.entrySet())
            {
                palette.append(entry.getKey()).append("(").append(entry.getValue()).append("个) ");
            }

            system += "\n\n已安装建筑类模组方块（可直接用其 id，如 " + String.join("、", caps.blockSamples) + " …）: "
                + palette.toString().trim()
                + "。也可使用其它这些命名空间下的真实方块 id；拼写错误的方块会被自动忽略。";
        }

        /* 运动质量参数：从作者动画蒸馏的量化规律 */
        try
        {
            var motionStream = UIAiChatBar.class.getResourceAsStream("/ai_skills/motion_patterns.json");

            if (motionStream != null)
            {
                String motionJson = new String(motionStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                motionStream.close();

                system += "\n\n运动质量规则（作者动画蒸馏）:" + motionJson;
            }
        }
        catch (Exception ignored)
        {}

        if (!caps.particles.isEmpty())
        {
            system += "\n\n可用粒子效果（fx.id 用这些名字）: " + String.join(", ", caps.particles)
                + "。粒子用法：顶层 fx 数组 {\"tick\":<出现 tick>,\"kind\":\"particle\",\"id\":\"效果名\"}。";
        }

        system += "\n\n灯光：fx 数组 {\"tick\":T,\"kind\":\"lighting\",\"value\":<亮度倍率,默认1,打击瞬间可用2~3>,\"duration\":<回落 tick 数>}——会在该 tick 打亮并在 duration 后回到 1。"
            + (caps.ik ? "\nIK: 场景已有 " + caps.ikChains + " 条约束链（手动配置，动画无需请求）。" : "")
            + (caps.numericChannels > 0 ? "\n曲线打磨：生成后可用「打磨」模式对 " + caps.numericChannels + " 条数值通道做缓动/回弹处理。" : "");

        /* 运镜 fx：入框时创建相机关键帧夹具 */
        system += "\n\n运镜 fx（每个动作建议至少一个机位 + 一处打光）：fx 数组 "
            + "{\"kind\":\"camera\",\"id\":\"orbit|static|dolly\",\"tick\":<起始>,\"duration\":<持续>,\"value\":<距离格数,默认5>}——"
            + "orbit=环绕演员一周，static=3/4 角固定机位，dolly=缓慢推近。duration 缺省 40 tick。";

        /* 修改关键帧：已有动作时附上当前键摘要，让模型学会改而不是重造 */
        try
        {
            Replay currentReplay = this.panel.replayEditor == null ? null : this.panel.replayEditor.getReplay();

            if (currentReplay != null)
            {
                mchorse.bbs_mod.utils.keyframes.KeyframeChannel<mchorse.bbs_mod.utils.pose.Pose> poseTrack =
                    currentReplay.properties.get(
                        mchorse.bbs_mod.film.replays.tracks.TrackId.property("",
                            mchorse.bbs_mod.film.replays.FormProperties.POSE_PROPERTY));

                if (poseTrack != null && !poseTrack.getKeyframes().isEmpty())
                {
                    StringBuilder keys = new StringBuilder();
                    int shown = 0;

                    for (mchorse.bbs_mod.utils.keyframes.Keyframe<mchorse.bbs_mod.utils.pose.Pose> kf : poseTrack.getKeyframes())
                    {
                        if (shown++ >= 16)
                        {
                            break;
                        }

                        mchorse.bbs_mod.utils.pose.Pose p = kf.getValue();

                        {
                            keys.append("tick ").append((int) kf.getTick()).append(": ");

                            int c = 0;

                            for (var t : p.transforms.entrySet())
                            {
                                if (c++ > 0)
                                {
                                    keys.append(", ");
                                }

                                keys.append(t.getKey()).append("=")
                                    .append(String.format("%.0f,%.0f,%.0f",
                                        Math.toDegrees(t.getValue().rotate.x),
                                        Math.toDegrees(t.getValue().rotate.y),
                                        Math.toDegrees(t.getValue().rotate.z)));
                            }

                            keys.append("\n");
                        }
                    }

                    if (keys.length() > 0)
                    {
                        system += "\n\n【当前关键帧】（用户要求修改/调整动作时参考）：\n" + keys
                            + "修改要求：输出完整修改后的 v2 计划——未提及的拍子保持 tick 与数值原样，只改用户指出的骨骼/区间；改完仍是整条计划（系统会自动替换旧关键帧）。"
                            + "分层编辑：骨骼分三组——上半身(body/torso/torso_lower/左右臂/左右肘)、下半身(左右腿/左右膝)、头部组(head/headwear/眼睛/眉毛)；只动用户指定的组，其余组数值逐拍原样保留。";
                    }
                }
            }
        }
        catch (Exception ignored)
        {}

        AiChatRequest request = new AiChatRequest(system, script);

        request.temperature(AiSettings.temperature.get());
        request.maxTokens(AiSettings.maxTokens.get());
        request.json(AiSettings.jsonMode.get() && AiSettings.supportsJsonMode.get());

        mchorse.bbs_mod.ui.framework.UIContext context = this.getContext();

        thinking.addProcess("调用 " + AiSettings.createBackend().getClass().getSimpleName()
            + " · 模型 " + AiSettings.model.get()
            + " · 温度 " + AiSettings.temperature.get()
            + " · max_tokens " + (request.maxTokens > 0 ? String.valueOf(request.maxTokens) : "∞(服务商上限)")
            + " · JSON模式 " + (request.jsonMode ? "开" : "关")
            + (AiSettings.thinking.get() ? " · 思维链开" : ""));
        this.history.refresh();

        mchorse.bbs_mod.ai.AiPlans.generatePlan(request, (generated, response) ->
        {
            this.busy = false;

            if (response.reasoning != null && !response.reasoning.isEmpty())
            {
                thinking.setReasoning(response.reasoning);
                thinking.addProcess("模型思维链已捕获（" + response.reasoning.length() + " 字）");
            }

            thinking.addProcess("模型返回：" + response.model
                + " · 提示 " + response.promptTokens + " tok + 生成 " + response.completionTokens + " tok");
            thinking.addProcess("方案解析成功：fps=" + generated.fps + "，总 " + generated.totalTicks
                + " tick，" + generated.beats.size() + " 拍");

            int shown = Math.min(generated.beats.size(), 8);

            for (int i = 0; i < shown; i++)
            {
                AnimationPlan.Beat beat = generated.beats.get(i);

                thinking.addProcess("拍 " + beat.index + " @tick " + beat.tick
                    + " " + beat.phase + " → " + beat.pose
                    + "（" + beat.intents.stream().map(intent -> intent.name().toLowerCase()).collect(java.util.stream.Collectors.joining(",")) + "）");
            }

            if (generated.beats.size() > shown)
            {
                thinking.addProcess("……其余 " + (generated.beats.size() - shown) + " 拍略");
            }

            this.history.refresh();

            /* 全树骨骼清单：根端 + 身体部位端（Star 3.6 的骨架在部位下） */
            java.util.List<String> inventory = mchorse.bbs_mod.ai.AiFormWalker.collectBones(modelForm);

            if (inventory.isEmpty())
            {
                for (mchorse.bbs_mod.settings.values.base.BaseValue child : modelForm.bones.getAll())
                {
                    inventory.add(child.getId());
                }
            }

            /* 骨骼归属端：谁拥有这根骨骼（根 ""、部位 "0"、或两端都有） */
            Map<String, List<String>> boneEnds = mchorse.bbs_mod.ai.AiFormWalker.collectBoneEnds(modelForm);

            mchorse.bbs_mod.ai.pose.BoneNameResolver.Result bones = mchorse.bbs_mod.ai.pose.BoneNameResolver.resolve(inventory);

            /* Saved model bindings (model editor's AI tab / past confirmations) answer first */
            mchorse.bbs_mod.ai.pose.AiBoneBindings.apply(modelForm.model.get(), inventory, bones);

            thinking.addProcess("能力扫描：" + caps.summary());
            thinking.addProcess("用户设定：幅度 "
                + (this.lastAmplitude == 0 ? "含蓄" : this.lastAmplitude == 1 ? "自然" : "夸张")
                + " · 地面识别" + (this.groundDetect ? "开（蹲/落地自动贴地）" : "关"));

            java.util.List<AnimationPlan.Fx> fxList = generated.fx;

            for (AnimationPlan.Fx fx : fxList)
            {
                if (fx.kind.equals("particle"))
                {
                    thinking.addProcess("粒子请求：" + fx.id + " @tick " + fx.tick + "（入框时创建粒子回放）");
                }
                else if (fx.kind.equals("lighting"))
                {
                    thinking.addProcess("打光请求：亮度 " + fx.value + " @tick " + fx.tick
                        + (fx.duration > 0 ? "，" + fx.duration + " tick 后回落" : ""));
                }
            }

            thinking.addProcess("骨骼绑定：" + (inventory.size() - bones.unresolved.size()) + "/" + inventory.size()
                + "（清单来自 " + boneEnds.size() + " 根骨骼 × " + new java.util.LinkedHashSet<String>() {{
                    for (java.util.List<String> v : boneEnds.values()) addAll(v);
                }}.size() + " 个表单端）");
            this.history.refresh();

            if (!bones.isComplete())
            {
                /* The assistant never guesses bone names - it asks */
                thinking.setText(L10n.lang("bbs.ui.ai.ask.open").get());
                this.history.refresh();

                UIAiAskOverlayPanel ask = new UIAiAskOverlayPanel(context, bones.unresolved, inventory, modelForm.model.get(), (confirmed) ->
                {
                    /* The user may skip bones - then the map is still incomplete and
                     * PoseSolver would throw. Explain instead of ever crashing. */
                    if (!confirmed.isComplete())
                    {
                        thinking.setRole(AiChatMessage.Role.SYSTEM);
                        thinking.setText(L10n.lang("bbs.ui.ai.chat.bindings_missing").format(String.join(", ", confirmed.unresolved)).get());
                        this.history.refresh();

                        return;
                    }

                    this.previewGenerated(generated, confirmed, replay, boneEnds, thinking);
                });

                mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(context, ask, 280, 0.7F);

                return;
            }

            /* 物理骨保护：物理系统驱动的骨骼（发辫/垂饰等）由物理叠二级运动，
             * AI 关键帧不得与之打架——从计划里剥掉这些键 */
            java.util.Set<String> physicsBones = mchorse.bbs_mod.ai.AiSkillLibrary.physicsBones(modelForm.model.get());

            if (!physicsBones.isEmpty())
            {
                int stripped = 0;

                for (AnimationPlan.Beat beat : generated.beats)
                {
                    if (beat.poseObject == null)
                    {
                        continue;
                    }

                    for (String bone : new java.util.ArrayList<>(beat.poseObject.keys()))
                    {
                        if (physicsBones.contains(bone))
                        {
                            beat.poseObject.remove(bone);
                            stripped++;
                        }
                    }
                }

                if (stripped > 0)
                {
                    thinking.addProcess("物理保护：剥除物理骨键 " + stripped + " 个（" + String.join("、", physicsBones) + " 由物理系统驱动）");
                }
            }

            this.previewGenerated(generated, bones, replay, boneEnds, thinking);
        }, (error) ->
        {
            this.busy = false;
            thinking.setRole(AiChatMessage.Role.ERROR);
            String detail = error.getMessage() == null || error.getMessage().isEmpty() ? error.type.name() : error.getMessage();

            thinking.setText(L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get() + " " + detail);
            this.history.refresh();
        });
    }

    private void previewGenerated(AnimationPlan generated, mchorse.bbs_mod.ai.pose.BoneNameResolver.Result bones, Replay replay, Map<String, List<String>> boneEnds, AiChatMessage thinking)
    {
        List<mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose> poses;
        List<FrameCommitter.ChannelWrite> writes;

        try
        {
            java.util.Map<String, mchorse.bbs_mod.utils.pose.Pose> skillPoses = java.util.Collections.emptyMap();
            var modelFormHere = replay.form.get();

            if (modelFormHere instanceof mchorse.bbs_mod.forms.forms.ModelForm mf)
            {
                skillPoses = mchorse.bbs_mod.ai.AiSkillLibrary.posesForModel(mf.model.get());
            }

            if (!skillPoses.isEmpty())
            {
                thinking.addProcess("技能姿势：" + skillPoses.size() + " 个（@名字 可在计划中直接引用）");
            }

            poses = mchorse.bbs_mod.ai.pose.PoseSolver.solve(generated, bones,
                mchorse.bbs_mod.ai.ui.UIAiGenerateAskPanel.AMPLITUDES[Math.max(0, Math.min(2, this.lastAmplitude))],
                skillPoses);

            /* 插值映射摘要：拍.pose ← 意图 → BBS 插值（去重） */
            java.util.LinkedHashSet<String> mappings = new java.util.LinkedHashSet<>();

            for (mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose pose : poses)
            {
                mappings.add(pose.pose + "←" + pose.intent + "→"
                    + mchorse.bbs_mod.ai.pose.PoseSolver.interpFor(pose.intent).getKey());
            }

            thinking.addProcess("姿态求解：" + poses.size() + " 个关键姿态；插值映射 " + String.join("，", mappings));

            /* 整只 Pose 写进 pose 属性轨道——用户看得见、可编辑的那条 */
            writes = mchorse.bbs_mod.ai.pose.PoseSolver.toPoseTrackWrites(poses, boneEnds, replay.properties, replay.form.get());

            /* 地面识别 + 行走位移：
             * 蹲/压缩/落地拍在 y 通道插重心下沉键（幅度来自 ROOT_Y），保证脚贴地；
             * 走路家族（walk_step/walk_step_b）改成步态节奏——触地拍低位、两拍中点
             * 回升，形成自然的重心起伏，而不是每拍一蹲一弹的原地蹦跳；
             * 同时沿角色初始朝向写 x/z 线性键（原版步速 0.215 格/tick），人物真正前进 */
            if (this.groundDetect)
            {
                KeyframeChannel<Double> yChannel = replay.keyframes.y;
                double baseY = yChannel.getKeyframes().isEmpty()
                    ? this.replayActorY(0)
                    : yChannel.getKeyframes().get(0).getY();
                int sinkKeys = 0;
                int totalTick = Math.max(1, generated.totalTicks);

                /* 行走位移先算：贴地采样的 y 键要沿着这条轨迹取地表高度 */
                int firstWalk = -1;
                int lastWalk = -1;

                for (mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose pose : poses)
                {
                    if (mchorse.bbs_mod.ai.pose.PoseLibrary.isWalk(pose.pose))
                    {
                        if (firstWalk < 0)
                        {
                            firstWalk = pose.tick;
                        }

                        lastWalk = pose.tick;
                    }
                }

                double x0 = this.replayActorX(0);
                double z0 = this.replayActorZ(0);
                double yawRad = Math.toRadians(this.currentReplayDouble(replay.keyframes.yaw, 0));
                double dirX = -Math.sin(yawRad);
                double dirZ = Math.cos(yawRad);
                double travel = 0D;
                boolean wallHit = false;

                /* 世界锚点：键坐标是电影空间，与世界隔着锚点/相对偏移（直接拿
                 * 键坐标采样曾把虚空基岩当成墙）。电影实体在真实世界里的位置
                 * 才是碰撞与贴地的采样基准；换算回键空间只差 anchorDY */
                mchorse.bbs_mod.forms.entities.IEntity worldAnchor =
                    this.panel.getController() == null ? null
                        : this.panel.getController().getEntities().get(replay.getId());

                double wx = worldAnchor == null ? x0 : worldAnchor.getX();
                double wy = worldAnchor == null ? baseY : worldAnchor.getY();
                double wz = worldAnchor == null ? z0 : worldAnchor.getZ();
                double anchorDY = baseY - wy;

                /* 碰壁截断自检：演员出生位置本身被方块包裹（电影世界对齐
                 * 异常，比如嵌在虚空基岩里）时，碰撞采样不可信——本次放弃
                 * 截断，按电影空间直行，绝不误报"前方碰壁"。贴地不受此限：
                 * 渐进式跟随（见下）恰好能把嵌在虚空里的演员逐键抬回地表 */
                net.minecraft.world.World snapWorld = this.worldOrNull();
                boolean startFree = snapWorld != null && !this.bodyBlocked(snapWorld, wx, wy, wz);

                /* 贴地链：从起脚位置的地表高度起步，每个 y 键最多升降 ±2 格
                 * 渐进跟随——嵌在虚空/地下的演员逐键抬回地表，树冠、屋顶的
                 * 高度图突变（一跳十几格）被挡在门外 */
                double prevGround = this.lastSnap
                    ? Math.max(wy - 2D, Math.min(wy + 2D, this.groundYAt(wx, wz, wy)))
                    : wy;

                /* ══ v2 直写模式：move 是角色局部轴 [前进, 垂直, 左移]（相对
                 * 初始朝向的累计格数）——世界坐标换算后逐段碰壁截断；移动
                 * 跨度内每个拍都写键（恒速连续，不走走停停）；贴地链跟随 */
                if (generated.version >= mchorse.bbs_mod.ai.plan.AnimationPlan.VERSION_DIRECT)
                {
                    /* 路径点：tick → 键空间位置（局部轴换算 + 碰壁截断） */
                    java.util.List<float[]> path = new java.util.ArrayList<>();
                    double prevKx = x0;
                    double prevKz = z0;
                    double prevWx = wx;
                    double prevWz = wz;
                    int prevTick = 0;
                    int segStartTick = 0;
                    int v2Moves = 0;
                    double v2Travel = 0D;
                    int v2WallHits = 0;

                    for (AnimationPlan.Beat beat : generated.beats)
                    {
                        if (beat.move == null)
                        {
                            continue;
                        }

                        double fwd = beat.move[0];
                        double lat = beat.move.length >= 3 ? beat.move[2] : 0F;

                        /* 局部轴 → 键空间：前进沿面朝方向，左移为角色的左侧 */
                        double tgtKx = x0 + fwd * dirX + lat * dirZ;
                        double tgtKz = z0 + fwd * dirZ + lat * dirX;
                        double segKx = tgtKx - prevKx;
                        double segKz = tgtKz - prevKz;
                        double segLen = Math.sqrt(segKx * segKx + segKz * segKz);

                        if (segLen < 0.001D)
                        {
                            prevTick = beat.tick;

                            continue;
                        }

                        double allowed = segLen;

                        if (startFree)
                        {
                            allowed = this.clampTravel(prevWx, wy, prevWz,
                                segKx / segLen, segKz / segLen, segLen);

                            if (allowed + 0.01D < segLen)
                            {
                                v2WallHits++;
                            }
                        }

                        double f = allowed / segLen;

                        prevKx += segKx * f;
                        prevKz += segKz * f;
                        prevWx += segKx * f;
                        prevWz += segKz * f;
                        v2Travel += allowed;
                        v2Moves++;

                        /* 段起点键（恒速段的开始） */
                        path.add(new float[] {segStartTick, (float) (prevKx - segKx * f), (float) (prevKz - segKz * f), 0F});
                        path.add(new float[] {beat.tick, (float) prevKx, (float) prevKz, 1F});

                        segStartTick = beat.tick;
                        prevTick = beat.tick;
                    }

                    if (v2Moves > 0)
                    {
                        for (int i = 0; i + 1 < path.size(); i += 2)
                        {
                            float[] a = path.get(i);
                            float[] b = path.get(i + 1);

                            this.writeLinearMove(writes, replay.keyframes.x, "x", (int) a[0], (int) b[0], a[1], b[1]);
                            this.writeLinearMove(writes, replay.keyframes.z, "z", (int) a[0], (int) b[0], a[2], b[2]);
                        }

                        /* 贴地：移动跨度内每个路径点一个地表跟随键（无起伏） */
                        if (this.lastSnap)
                        {
                            FrameCommitter.ChannelWrite v2Y = new FrameCommitter.ChannelWrite("y", yChannel, 0F);

                            writes.add(v2Y);

                            for (float[] pt : path)
                            {
                                double wkx = wx + (pt[1] - x0);
                                double wkz = wz + (pt[2] - z0);

                                prevGround = this.stepGround(this.groundYAt(wkx, wkz, wy), prevGround);

                                EditPatch.KeyWrite yKey = new EditPatch.KeyWrite();

                                yKey.tick = pt[0];
                                yKey.value = (float) (prevGround + anchorDY);
                                yKey.interpolation = "cubic_inout";
                                v2Y.keys.add(yKey);
                            }

                            v2Y.keys.sort(java.util.Comparator.comparingDouble(k -> k.tick));
                        }

                        thinking.addProcess("行走位移：" + v2Moves + " 段共 "
                            + String.format("%.1f", v2Travel) + " 格（沿角色朝向，恒速连续"
                            + (v2WallHits > 0 ? "，" + v2WallHits + " 段碰壁截断" : "")
                            + (this.lastSnap ? "，贴地跟随" : "") + "）");
                    }
                }


                if (firstWalk >= 0 && lastWalk > firstWalk)
                {
                    double raw = mchorse.bbs_mod.ai.pose.PoseLibrary.WALK_SPEED * (lastWalk - firstWalk);

                    if (startFree)
                    {
                        /* 碰壁截断：沿路径逐 0.25 格采样碰撞箱，撞墙就停在墙前 */
                        travel = this.clampTravel(wx, wy, wz, dirX, dirZ, raw);
                        wallHit = travel + 0.01D < raw;
                    }
                    else
                    {
                        travel = raw;
                    }

                    if (travel > 0.05D)
                    {
                        this.writeLinearMove(writes, replay.keyframes.x, "x", firstWalk, lastWalk, x0, x0 + dirX * travel);
                        this.writeLinearMove(writes, replay.keyframes.z, "z", firstWalk, lastWalk, z0, z0 + dirZ * travel);
                    }
                    else
                    {
                        travel = 0D;
                    }
                }

                for (int i = 0; i < poses.size(); i++)
                {
                    mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose pose = poses.get(i);
                    float sink = mchorse.bbs_mod.ai.pose.PoseLibrary.ROOT_Y.getOrDefault(pose.pose, 0F);
                    boolean inWalkSpan = firstWalk >= 0 && pose.tick >= firstWalk && pose.tick <= lastWalk;
                    boolean walkSnapKey = this.lastSnap && inWalkSpan
                        && (mchorse.bbs_mod.ai.pose.PoseLibrary.isWalk(pose.pose) || "walk_pass".equals(pose.pose));

                    /* 走路段内不写任何下沉键：LLM 偶尔把 compress/land 当步态
                     * 的 down/up 相位塞进走路计划，那就是"走路上下跳" */
                    if (sink != 0F && inWalkSpan)
                    {
                        continue;
                    }

                    /* 贴地开启时走路拍（含过渡帧）写地表跟随键——沿地形渐进
                     * 走，没有起伏节奏；贴地关闭或非走路拍且无下沉则不碰 y */
                    if (sink == 0F && !walkSnapKey)
                    {
                        continue;
                    }

                    FrameCommitter.ChannelWrite sinkWrite = null;

                    for (FrameCommitter.ChannelWrite write : writes)
                    {
                        if (write.trackId.equals("y"))
                        {
                            sinkWrite = write;

                            break;
                        }
                    }

                    if (sinkWrite == null)
                    {
                        sinkWrite = new FrameCommitter.ChannelWrite("y", yChannel, 0F);
                        writes.add(sinkWrite);
                    }

                    /* 贴地链：每个 y 键沿所在位置的地表高度（世界坐标，逐键
                     * ±2 渐进）走，加回键空间与世界空间的竖直偏移 */
                    double lowBase = baseY;

                    if (this.lastSnap)
                    {
                        double[] lowPos = this.travelPos(wx, wz, dirX, dirZ, travel, firstWalk, lastWalk, pose.tick);

                        prevGround = this.stepGround(this.groundYAt(lowPos[0], lowPos[1], wy), prevGround);
                        lowBase = prevGround + anchorDY;
                    }

                    EditPatch.KeyWrite down = new EditPatch.KeyWrite();

                    down.tick = pose.tick;
                    down.value = (float) (lowBase + sink);
                    down.interpolation = walkSnapKey ? "cubic_inout" : "cubic_out";
                    sinkWrite.keys.add(down);

                    sinkKeys++;

                    if (walkSnapKey)
                    {
                        /* 走路贴地键：一拍一键，地表在哪 y 就在哪——无起伏 */
                        continue;
                    }

                    EditPatch.KeyWrite up = new EditPatch.KeyWrite();

                    up.tick = Math.min(totalTick, pose.tick + 6);

                    double upBase = baseY;

                    if (this.lastSnap)
                    {
                        double[] upPos = this.travelPos(wx, wz, dirX, dirZ, travel, firstWalk, lastWalk, up.tick);

                        prevGround = this.stepGround(this.groundYAt(upPos[0], upPos[1], wy), prevGround);
                        upBase = prevGround + anchorDY;
                    }

                    up.value = (float) upBase;
                    up.interpolation = "cubic_inout";
                    sinkWrite.keys.add(up);

                    sinkKeys++;
                }

                if (sinkKeys > 0 || travel > 0D)
                {
                    for (FrameCommitter.ChannelWrite write : writes)
                    {
                        if (write.trackId.equals("y") && sinkKeys > 0)
                        {
                            write.keys.sort(java.util.Comparator.comparingDouble(k -> k.tick));
                        }
                    }

                    thinking.addProcess("地面识别：贴地/重心键 +" + sinkKeys
                        + "（y 通道，基线 " + String.format("%.2f", baseY)
                        + (this.lastSnap ? "，贴地跟随（逐键 ±2 渐进）" : "") + "）");

                    if (travel > 0D)
                    {
                        thinking.addProcess("行走位移：沿朝向前进 " + String.format("%.1f", travel)
                            + " 格（x/z 线性键，原版步速" + (wallHit ? "，前方碰壁已截断" : "")
                            + (!startFree ? "，未做碰壁检测" : "") + "）");
                    }
                    else if (wallHit)
                    {
                        thinking.addProcess("行走位移：前方被挡，原地踏步（未穿墙）");
                    }
                }
            }

            /* 打光 fx：lighting 数值通道打键（预览可见，随入框/丢弃一起结算） */
            for (AnimationPlan.Fx fx : generated.fx)
            {
                if (!fx.kind.equals("lighting"))
                {
                    continue;
                }

                mchorse.bbs_mod.film.replays.tracks.TrackId lightingId =
                    mchorse.bbs_mod.film.replays.tracks.TrackId.property("", "lighting");
                KeyframeChannel<?> lighting = replay.properties.getOrCreate(replay.form.get(), lightingId);
                FrameCommitter.ChannelWrite lw = new FrameCommitter.ChannelWrite(lightingId.toKey(), lighting, 0F);

                EditPatch.KeyWrite hit = new EditPatch.KeyWrite();

                hit.tick = fx.tick;
                hit.value = fx.value;
                hit.interpolation = "exp_out";
                lw.keys.add(hit);

                if (fx.duration > 0)
                {
                    EditPatch.KeyWrite back = new EditPatch.KeyWrite();

                    back.tick = fx.tick + fx.duration;
                    back.value = 1F;
                    lw.keys.add(back);
                }

                writes.add(lw);
            }

            /* 粒子 fx 是结构性的：预览期只登记，入框时创建 */

            StringBuilder ends = new StringBuilder();

            for (FrameCommitter.ChannelWrite write : writes)
            {
                ends.append(write.trackId).append("×").append(write.keys.size()).append(" ");
            }

            thinking.addProcess("姿态轨道写入：" + writes.size() + " 条通道，共 "
                + writes.stream().mapToInt(w -> w.keys.size()).sum() + " 个关键帧 → " + ends.toString().trim());
        }
        catch (Exception e)
        {
            /* A chat action must never take the game down (crash report 2026-10-02_11.56) */
            thinking.setRole(AiChatMessage.Role.ERROR);
            thinking.setText(L10n.lang("bbs.ui.ai.panel.failed").format(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).get());
            this.history.refresh();

            return;
        }

        /* 空 diff 由 applyPreview 在真实应用时填充——预填会双倍计数 */
        AiPreviewState.get().begin(replay, writes, new FrameDiff());
        AiPreviewState.get().setFx(generated.fx);

        if (AiPreviewState.get().getTrimmedCount() > 0)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, "已清理上次 AI 的残留键 "
                + AiPreviewState.get().getTrimmedCount() + " 个（重新生成即替换旧内容）");
            this.history.refresh();
        }

        /* 预览键已真实落通道：只刷新时间轴，绝不路由跳面板 */
        AiFilmBridge.notifyTimeline(AiPreviewState.get().getDiff());
        this.ghostOnionOn();

        /* 键在姿态分组下——预览期就切过去，立即可见可改 */
        try
        {
            if (this.panel.replayEditor != null)
            {
                this.panel.replayEditor.setCategory(mchorse.bbs_mod.api.client.editor.TrackCategory.POSE);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        int lastTick = generated.beats.isEmpty() ? 0 : generated.beats.get(generated.beats.size() - 1).tick;

        thinking.setText(L10n.lang("bbs.ui.ai.chat.generated").format(generated.beats.size(), lastTick).get());
        this.history.refresh();
        this.refreshPreviewRow();
    }

    /** Polish: local intent parsing -> L3 on every numeric channel of the open replay -> preview. */
    private void executePolish(String text, List<PolishOp> ops)
    {
        this.history.log(AiChatMessage.Role.USER, text);
        this.input.setText("");

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_replay").get());

            return;
        }

        List<FrameCommitter.ChannelWrite> plan = new ArrayList<>();
        int considered = 0;

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            if (!CurveGuard.polishable(channel))
            {
                continue;
            }

            considered++;
            plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
        }

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (!CurveGuard.polishable(channel))
            {
                continue;
            }

            considered++;
            plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
        }

        if (considered == 0)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_numeric").get());

            return;
        }

        /* Compute the diff by dry-running the polisher result against the
         * current keys - the preview state only holds what WOULD change */
        /* 空 diff 由 applyPreview 真实应用时填充（打磨路径同样真实预览） */
        AiPreviewState.get().begin(replay, plan, new FrameDiff());
        AiFilmBridge.notifyTimeline(AiPreviewState.get().getDiff());
        this.ghostOnionOn();
        this.refreshPreviewRow();
    }

    /** Diff of what the plan would change, computed without touching channels. */

    /** 洋葱皮被幽灵预览接管前的用户原值（confirm/discard 恢复用）。 */
    private int[] savedOnion;
    private boolean savedOnionEnabled;

    /**
     * §5.4 幽灵轮廓：预览期间把 BBS 原生洋葱皮临时接管为品牌色、前后各 1 帧
     * ——预览键落在 pose 通道上，洋葱皮正好渲染前后关键帧的半透明轮廓。
     * 结束（入框/丢弃）恢复用户原值。
     */
    private void ghostOnionOn()
    {
        try
        {
            var onion = this.panel.getController().getOnionSkin();

            /* 只在第一次接管时保存用户原值——连续生成（上一次预览还没
             * 决定就再生成）时不能把 AI 样式当成"用户原值"存进去，
             * 否则入框后洋葱皮会永远停留在幽灵样式（幽灵帧不消失） */
            if (this.savedOnion == null)
            {
                this.savedOnion = new int[] {onion.preColor.get(), onion.postColor.get(),
                    onion.preFrames.get(), onion.postFrames.get()};
                this.savedOnionEnabled = onion.enabled.get();
            }

            int accent = mchorse.bbs_mod.utils.colors.Colors.setA(
                mchorse.bbs_mod.utils.colors.Colors.opaque(BBSSettings.primaryColor.get()), 0.5F);

            onion.enabled.set(true);
            onion.preColor.set(accent);
            onion.postColor.set(accent);
            onion.preFrames.set(1);
            onion.postFrames.set(1);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void ghostOnionRestore()
    {
        if (this.savedOnion == null)
        {
            return;
        }

        try
        {
            var onion = this.panel.getController().getOnionSkin();

            onion.preColor.set(this.savedOnion[0]);
            onion.postColor.set(this.savedOnion[1]);
            onion.preFrames.set(this.savedOnion[2]);
            onion.postFrames.set(this.savedOnion[3]);
            onion.enabled.set(this.savedOnionEnabled);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.savedOnion = null;
    }

    private void confirm()
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive())
        {
            return;
        }

        /* 预览阶段键已真实写入（旧残留键已在 begin 时清掉）——入框只负责
         * 把预览前快照包成一个撤销条目；特效回放是入框时才创建的，
         * 上一次的 ai 分类回放在这里移除（新计划若带特效会重建） */
        java.util.List<AnimationPlan.Fx> pendingFx = new ArrayList<>(state.getFx());

        int removedFx = this.removeOldAiReplays();

        FrameDiff diff = state.confirm(this.panel.getUndoHandler().getUndoManager());

        AiFilmBridge.broadcast(diff);

        /* 结构性 fx：预览不动场景，入框时创建特效回放（粒子/原版粒子/拖尾/相机） */
        for (AnimationPlan.Fx fx : pendingFx)
        {
            /* 相机 fx：不建回放，直接在 film.camera 里建关键帧夹具 */
            if (fx.kind.equals("camera"))
            {
                try
                {
                    this.createCameraClip(fx);
                }
                catch (Exception e)
                {
                    this.history.log(AiChatMessage.Role.ERROR, "运镜创建失败：" + e.getMessage());
                }

                continue;
            }

            if (fx.id.isEmpty())
            {
                continue;
            }

            try
            {
                mchorse.bbs_mod.forms.forms.Form form;

                switch (fx.kind)
                {
                    case "trail" ->
                    {
                        var trail = new mchorse.bbs_mod.forms.forms.TrailForm();

                        trail.length.set(Math.max(2F, fx.value * 10F));
                        form = trail;
                    }
                    case "vanilla_particle" ->
                    {
                        var vanilla = new mchorse.bbs_mod.forms.forms.VanillaParticleForm();
                        var settings = new mchorse.bbs_mod.forms.forms.utils.ParticleSettings();

                        settings.particle = mchorse.bbs_mod.resources.Link.assets(fx.id.contains(":") ? fx.id : "minecraft:" + fx.id).path == null
                            ? settings.particle
                            : new net.minecraft.util.Identifier(fx.id.contains(":") ? fx.id : "minecraft:" + fx.id);
                        vanilla.count.set(Math.max(1, (int) fx.value));
                        vanilla.frequency.set(3);
                        vanilla.settings.set(settings);
                        form = vanilla;
                    }
                    case "particle" ->
                    {
                        var particle = new mchorse.bbs_mod.forms.forms.ParticleForm();

                        particle.effect.set(fx.id);
                        form = particle;
                    }
                    default ->
                    {
                        continue;
                    }
                }

                Replay fxReplay = this.panel.getData().replays.addReplay();

                fxReplay.form.set(form);
                fxReplay.category.set("ai");

                /* 出生点沿用演员在该 tick 的位置（抬高 1 格防入地） */
                float at = Math.max(0F, fx.tick);

                fxReplay.keyframes.x.insert(0, this.replayActorX(at));
                fxReplay.keyframes.y.insert(0, this.replayActorY(at) + 1.0);
                fxReplay.keyframes.z.insert(0, this.replayActorZ(at));

                this.history.log(AiChatMessage.Role.SYSTEM,
                    "特效回放已创建：" + fx.kind + " / " + fx.id + " @tick " + fx.tick + "（可在回放列表调位置/裁剪时长）");
            }
            catch (Exception e)
            {
                this.history.log(AiChatMessage.Role.ERROR, "特效回放创建失败：" + e.getMessage());
            }
        }

        this.history.refresh();

        /* 入框后把时间轴带到姿态分组，pose.bones 行直接可见 */
        try
        {
            if (this.panel.replayEditor != null)
            {
                this.panel.replayEditor.setCategory(mchorse.bbs_mod.api.client.editor.TrackCategory.POSE);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.ghostOnionRestore();
        this.refreshPreviewRow();

        /* The transcript keeps the receipt around - what was accepted and how
         * to take it back, per the undo contract */
        this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.bar.committed").format(diff.changedKeyCount()).get());

        if (removedFx > 0)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, "已移除上次 AI 的特效回放 " + removedFx + " 个（新生成会重建）");
        }

        this.history.refresh();
    }

    /**
     * 运镜 fx：在 film.camera 里创建关键帧夹具（导演机位）。
     * orbit=环绕演员一周，static=3/4 角固定机位，dolly=缓慢推近；
     * 机位基点取演员在该 tick 的世界位置与朝向。
     */
    private void createCameraClip(AnimationPlan.Fx fx)
    {
        Replay actor = this.panel.replayEditor == null ? null : this.panel.replayEditor.getReplay();

        if (actor == null || this.panel.getData() == null)
        {
            return;
        }

        float at = Math.max(0F, fx.tick);
        double ax = this.replayActorX(at);
        double ay = this.replayActorY(at) + 1.2D;
        double az = this.replayActorZ(at);
        double yaw = this.currentReplayDouble(actor.keyframes.yaw, at);

        double dist = fx.value > 0.1F ? fx.value : 5D;
        int duration = fx.duration > 0 ? fx.duration : 40;

        var clip = new mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip();

        clip.title.set("AI " + fx.id);
        clip.tick.set(Math.max(0, (int) at));
        clip.duration.set(duration);

        double dirX = -Math.sin(Math.toRadians(yaw));
        double dirZ = Math.cos(Math.toRadians(yaw));

        /* 站到相机位后看向演员的 yaw */
        java.util.function.BiConsumer<Double, double[]> place = (t, cam) ->
        {
            clip.x.insert(t.floatValue(), cam[0]);
            clip.y.insert(t.floatValue(), cam[1]);
            clip.z.insert(t.floatValue(), cam[2]);

            double dx = ax - cam[0];
            double dz = az - cam[2];

            clip.yaw.insert(t.floatValue(), Math.toDegrees(Math.atan2(-dx, dz)));
            clip.pitch.insert(t.floatValue(), -8D);
            clip.fov.insert(t.floatValue(), 65D);
        };

        switch (fx.id)
        {
            case "orbit" ->
            {
                int steps = 8;

                for (int i = 0; i <= steps; i++)
                {
                    double a = 2 * Math.PI * i / steps;

                    place.accept((double) duration * i / steps, new double[] {
                        ax + Math.sin(a) * dist,
                        ay,
                        az + Math.cos(a) * dist
                    });
                }
            }
            case "dolly" ->
            {
                /* 从正前方 dist 推近到 45% */
                place.accept(0D, new double[] {ax + dirX * dist, ay, az + dirZ * dist});
                place.accept((double) duration, new double[] {ax + dirX * dist * 0.45D, ay + 0.2D, az + dirZ * dist * 0.45D});
            }
            default ->
            {
                /* static：3/4 角固定机位 */
                double side = dist * 0.7D;

                place.accept(0D, new double[] {
                    ax + dirX * dist + dirZ * side,
                    ay + 0.4D,
                    az + dirZ * dist - dirX * side
                });
            }
        }

        /* 键间平滑 */
        for (var channel : clip.channels)
        {
            for (var keyframe : channel.getKeyframes())
            {
                keyframe.getInterpolation().setInterp(mchorse.bbs_mod.utils.interps.Interpolations.CUBIC_INOUT);
            }
        }

        this.panel.getData().camera.addClip(clip);

        this.history.log(AiChatMessage.Role.SYSTEM,
            "运镜已创建：" + fx.id + " @" + fx.tick + "（时长 " + duration + " tick，距离 " + String.format("%.1f", dist) + " 格，可在相机轨调整）");
    }

    /** 删掉上次 AI 入框创建的特效回放（category=ai），返回删除数 */
    private int removeOldAiReplays()
    {
        if (this.panel.getData() == null)
        {
            return 0;
        }

        java.util.List<Replay> stale = new ArrayList<>();

        for (Replay replay : this.panel.getData().replays.getList())
        {
            if ("ai".equals(replay.category.get()))
            {
                stale.add(replay);
            }
        }

        for (Replay replay : stale)
        {
            this.panel.getData().replays.remove(replay);
        }

        return stale.size();
    }

    private double replayActorX(float tick)
    {
        return this.currentReplayDouble(this.panel.replayEditor.getReplay() == null ? null : this.panel.replayEditor.getReplay().keyframes.x, tick);
    }

    private double replayActorY(float tick)
    {
        Replay r = this.panel.replayEditor.getReplay();

        return this.currentReplayDouble(r == null ? null : r.keyframes.y, tick);
    }

    private double replayActorZ(float tick)
    {
        Replay r = this.panel.replayEditor.getReplay();

        return this.currentReplayDouble(r == null ? null : r.keyframes.z, tick);
    }

    /** 当前客户端世界（可为 null——不在世界内时世界采样全部退化为平面） */
    private net.minecraft.world.World worldOrNull()
    {
        return net.minecraft.client.MinecraftClient.getInstance().world;
    }

    /** 行走轨迹上 tick 时刻的 (x,z)：位移区间内线性插值，之外停在端点 */
    private double[] travelPos(double x0, double z0, double dirX, double dirZ, double travel,
        int firstWalk, int lastWalk, float tick)
    {
        if (travel <= 0D || lastWalk <= firstWalk)
        {
            return new double[] {x0, z0};
        }

        double frac = (tick - firstWalk) / (double) (lastWalk - firstWalk);

        frac = Math.max(0D, Math.min(1D, frac));

        return new double[] {x0 + dirX * travel * frac, z0 + dirZ * travel * frac};
    }

    /** 贴地链的单步：地表采样相对上一个键最多升降 ±2 格——既能把嵌在
     * 虚空/地下的演员逐键抬回地表，又挡住树冠/屋顶的高度图突变 */
    private double stepGround(double groundY, double prevGround)
    {
        return Math.max(prevGround - 2D, Math.min(prevGround + 2D, groundY));
    }

    /** 贴地：采样 (x,z) 的站立面。不用 getTopY 高度图——实测在某些世界
     * 里比真实地表低好几格；改为从脚面向下局部扫描，找最上面的带碰撞
     * 方块，其顶面即站立面；无世界时回退 fallback */
    private double groundYAt(double x, double z, double fallback)
    {
        try
        {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();

            if (client.world == null)
            {
                return fallback;
            }

            net.minecraft.util.math.BlockPos feet =
                net.minecraft.util.math.BlockPos.ofFloored(x, fallback, z);

            for (int dy = 1; dy >= -5; dy--)
            {
                net.minecraft.util.math.BlockPos pos = feet.add(0, dy, 0);

                if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty())
                {
                    return pos.getY() + 1;
                }
            }

            return fallback;
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    /** 碰壁截断：沿 (dirX,dirZ) 逐 0.25 格采样，返回不穿墙的最大位移 */
    private double clampTravel(double x0, double feetY, double z0, double dirX, double dirZ, double maxDist)
    {
        try
        {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();

            if (client.world == null)
            {
                return maxDist;
            }

            double dist = 0D;

            while (dist < maxDist)
            {
                double step = Math.min(0.25D, maxDist - dist);

                if (this.bodyBlocked(client.world, x0 + dirX * (dist + step), feetY, z0 + dirZ * (dist + step)))
                {
                    break;
                }

                dist += step;
            }

            return dist;
        }
        catch (Exception e)
        {
            return maxDist;
        }
    }

    /** 头部高度（feetY+1）有碰撞才算墙——1 格台阶不算，贴地模式下由
     * y 采样抬过去，斜坡和台阶不会把行走堵死 */
    private boolean bodyBlocked(net.minecraft.world.World world, double x, double feetY, double z)
    {
        net.minecraft.util.math.BlockPos head =
            net.minecraft.util.math.BlockPos.ofFloored(x, feetY + 1, z);

        return !world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    /** 行走位移：在 x/z 通道插两端线性键（起点 a、终点 b），中间匀速 */
    private void writeLinearMove(List<FrameCommitter.ChannelWrite> writes, KeyframeChannel<Double> channel,
        String trackId, int fromTick, int toTick, double a, double b)
    {        FrameCommitter.ChannelWrite write = null;

        for (FrameCommitter.ChannelWrite existing : writes)
        {
            if (existing.trackId.equals(trackId))
            {
                write = existing;

                break;
            }
        }

        if (write == null)
        {
            write = new FrameCommitter.ChannelWrite(trackId, channel, 0F);
            writes.add(write);
        }

        EditPatch.KeyWrite start = new EditPatch.KeyWrite();

        start.tick = fromTick;
        start.value = (float) a;
        start.interpolation = "linear";
        write.keys.add(start);

        EditPatch.KeyWrite end = new EditPatch.KeyWrite();

        end.tick = toTick;
        end.value = (float) b;
        end.interpolation = "linear";
        write.keys.add(end);
    }

    private double currentReplayDouble(KeyframeChannel<Double> channel, float tick)
    {
        if (channel == null || channel.getKeyframes().isEmpty())
        {
            return 0D;
        }

        try
        {
            return channel.interpolate(tick);
        }
        catch (Exception e)
        {
            return channel.getKeyframes().get(0).getY();
        }
    }

    private void discard()
    {
        AiPreviewState state = AiPreviewState.get();
        FrameDiff diff = state.getDiff();

        state.discard();

        this.ghostOnionRestore();

        /* 回滚后刷新时间轴与视口（不路由） */
        AiFilmBridge.notifyTimeline(diff);

        this.refreshPreviewRow();
    }

    /** Sync the preview row with the shared preview state (also called on open). */
    public void refreshPreviewRow()
    {
        AiPreviewState state = AiPreviewState.get();
        boolean active = state.isActive();

        if (active)
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.preview").format(state.getChangeCount());

            if (state.getChangeCount() != this.lastLoggedCount)
            {
                this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.bar.preview").format(state.getChangeCount()).get());
                this.lastLoggedCount = state.getChangeCount();
            }
        }
        else
        {
            this.lastLoggedCount = -1;
        }

        if (this.previewRow.isVisible() != active)
        {
            this.previewRow.setVisible(active);
            this.relayout();
        }

        this.resize();
    }

    @Override
    public void render(UIContext context)
    {
        /* Chrome surface: the bar is editor chrome, not workspace (spec 5.0.1 I) */
        this.area.render(context.batcher, BBSSettings.chromeSurface());
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 2, Colors.opaque(BBSSettings.primaryColor.get()));

        super.render(context);
    }

    /** Static guard indirection so the bar reads like the rest of the AI surface. */
    private static final class CurveGuard
    {
        private static boolean polishable(KeyframeChannel<?> channel)
        {
            return mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory());
        }
    }
}
