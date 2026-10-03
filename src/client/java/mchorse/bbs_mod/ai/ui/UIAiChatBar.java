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
    /** 点「执行」(生成模式)：先向用户补充细节（幅度/地面识别），再走生成。 */
    public void askThenGenerate(String script)
    {
        mchorse.bbs_mod.ui.framework.UIContext context = this.getContext();

        mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(context,
            new UIAiGenerateAskPanel(context, (answer) -> this.executeGenerate(script, answer[0], answer[1] == 1)), 240, 0.7F);
    }

    public void executeGenerate(String script)
    {
        this.executeGenerate(script, 1, true);
    }

    public void executeGenerate(String script, int amplitudeIndex, boolean groundDetect)
    {
        this.lastAmplitude = amplitudeIndex;
        this.groundDetect = groundDetect;
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

        if (!caps.particles.isEmpty())
        {
            system += "\n\n可用粒子效果（fx.id 用这些名字）: " + String.join(", ", caps.particles)
                + "。粒子用法：顶层 fx 数组 {\"tick\":<出现 tick>,\"kind\":\"particle\",\"id\":\"效果名\"}。";
        }

        system += "\n\n灯光：fx 数组 {\"tick\":T,\"kind\":\"lighting\",\"value\":<亮度倍率,默认1,打击瞬间可用2~3>,\"duration\":<回落 tick 数>}——会在该 tick 打亮并在 duration 后回到 1。"
            + (caps.ik ? "\nIK: 场景已有 " + caps.ikChains + " 条约束链（手动配置，动画无需请求）。" : "")
            + (caps.numericChannels > 0 ? "\n曲线打磨：生成后可用「打磨」模式对 " + caps.numericChannels + " 条数值通道做缓动/回弹处理。" : "");

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
            poses = mchorse.bbs_mod.ai.pose.PoseSolver.solve(generated, bones,
                mchorse.bbs_mod.ai.ui.UIAiGenerateAskPanel.AMPLITUDES[Math.max(0, Math.min(2, this.lastAmplitude))]);

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

            /* 地面识别：蹲/压缩/落地拍在 y 通道插重心下沉键（幅度来自 ROOT_Y），保证脚贴地 */
            if (this.groundDetect)
            {
                KeyframeChannel<Double> yChannel = replay.keyframes.y;
                double baseY = yChannel.getKeyframes().isEmpty()
                    ? this.replayActorY(0)
                    : yChannel.getKeyframes().get(0).getY();
                int sinkKeys = 0;
                int totalTick = Math.max(1, generated.totalTicks);

                for (mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose pose : poses)
                {
                    float sink = mchorse.bbs_mod.ai.pose.PoseLibrary.ROOT_Y.getOrDefault(pose.pose, 0F);

                    if (sink == 0F)
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

                    EditPatch.KeyWrite down = new EditPatch.KeyWrite();

                    down.tick = pose.tick;
                    down.value = (float) (baseY + sink);
                    down.interpolation = "cubic_out";
                    sinkWrite.keys.add(down);

                    EditPatch.KeyWrite up = new EditPatch.KeyWrite();

                    up.tick = Math.min(totalTick, pose.tick + 6);
                    up.value = (float) baseY;
                    up.interpolation = "cubic_inout";
                    sinkWrite.keys.add(up);

                    sinkKeys += 2;
                }

                if (sinkKeys > 0)
                {
                    for (FrameCommitter.ChannelWrite write : writes)
                    {
                        if (write.trackId.equals("y"))
                        {
                            write.keys.sort(java.util.Comparator.comparingDouble(k -> k.tick));
                        }
                    }

                    thinking.addProcess("地面识别：蹲/落地重心键 +" + sinkKeys
                        + "（y 通道，基线 " + String.format("%.2f", baseY) + "）");
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
        /* 预览键已真实落通道：只刷新时间轴，绝不路由跳面板 */
        AiFilmBridge.notifyTimeline(AiPreviewState.get().getDiff());

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
        this.refreshPreviewRow();
    }

    /** Diff of what the plan would change, computed without touching channels. */

    private void confirm()
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive())
        {
            return;
        }

        /* 预览阶段键已真实写入——入框只负责把预览前快照包成一个撤销条目 */
        java.util.List<AnimationPlan.Fx> pendingFx = new ArrayList<>(state.getFx());
        FrameDiff diff = state.confirm(this.panel.getUndoHandler().getUndoManager());

        AiFilmBridge.broadcast(diff);

        /* 粒子 fx：结构性的，预览不动场景，入框时创建粒子回放 */
        for (AnimationPlan.Fx fx : pendingFx)
        {
            if (!fx.kind.equals("particle") || fx.id.isEmpty())
            {
                continue;
            }

            try
            {
                mchorse.bbs_mod.forms.forms.ParticleForm particle = new mchorse.bbs_mod.forms.forms.ParticleForm();

                particle.effect.set(fx.id);

                Replay particleReplay = this.panel.getData().replays.addReplay();

                particleReplay.form.set(particle);
                particleReplay.category.set("ai");

                /* 出生点沿用演员在该 tick 的位置 */
                float at = Math.max(0F, fx.tick);

                particleReplay.keyframes.x.insert(0, this.replayActorX(at));
                particleReplay.keyframes.y.insert(0, this.replayActorY(at));
                particleReplay.keyframes.z.insert(0, this.replayActorZ(at));

                this.history.log(AiChatMessage.Role.SYSTEM,
                    "粒子回放已创建：" + fx.id + " @tick " + fx.tick + "（可在回放列表调位置/裁剪时长）");
            }
            catch (Exception e)
            {
                this.history.log(AiChatMessage.Role.ERROR, "粒子回放创建失败：" + e.getMessage());
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

        this.refreshPreviewRow();

        /* The transcript keeps the receipt around - what was accepted and how
         * to take it back, per the undo contract */
        this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.bar.committed").format(diff.changedKeyCount()).get());
        this.history.refresh();
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
