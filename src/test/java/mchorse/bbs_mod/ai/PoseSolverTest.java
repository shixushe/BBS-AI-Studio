package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.pose.BoneNameResolver;
import mchorse.bbs_mod.ai.pose.PoseLibrary;
import mchorse.bbs_mod.ai.pose.PoseSolver;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.List;
import java.util.Set;

/**
 * Standalone checks for the M5 pose pipeline
 * ({@code java -cp "main;test;slf4j;logging;joml;dfu;mc-common;mc-client" mchorse.bbs_mod.ai.PoseSolverTest}).
 *
 * <p>The bar: bone resolution never guesses (unresolved = error, candidates
 * supplied), the plan's pose labels all have library coverage, solving is
 * deterministic on the model's real bone names, and pose writes go through
 * the M3 commit machinery - one undo entry, full rollback.</p>
 */
public class PoseSolverTest
{
    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        KeyframeFactories.setup();

        boneResolution();
        solverContract();
        poseWritesThroughCommit();
        libraryCoversContract();
        starAdaptation();
        starBuiltins();

        System.out.println("\n" + (failures == 0 ? "ALL PASS" : failures + " FAILURES") + " (" + checks + " checks)");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    /**
     * Star 3.6 adaptation bar: Chinese rig names auto-resolve, optional eye
     * bones never block, blink carries eye squash, and bone writes fan out to
     * every end (root + body part) that owns the bone.
     */
    private static void starAdaptation()
    {
        BoneNameResolver.Result zh = BoneNameResolver.resolve(List.of(
            "头部", "身体", "左胳膊", "右胳膊", "左腿", "右腿", "左眼瞳", "右眼瞳"));

        check(zh.isComplete(), "Chinese core bones resolve complete");
        check(zh.unresolved.isEmpty(), "Chinese eyes resolve when present (optional)");
        check(zh.resolved.get("head").actual.equals("头部"), "head <- 头部 exact alias");
        check(zh.resolved.get("left_eye").actual.equals("左眼瞳"), "left_eye <- 左眼瞳");

        BoneNameResolver.Result noEyes = BoneNameResolver.resolve(List.of(
            "head", "body", "left_arm", "right_arm", "left_leg", "right_leg"));

        check(noEyes.isComplete(), "core-only inventory stays complete");
        check(noEyes.unresolved.isEmpty(), "absent optional eyes leave nothing unresolved");
        check(noEyes.resolved.get("left_eye") == null, "left_eye simply unbound when absent");

        java.util.Map<String, float[]> blink = PoseLibrary.get("blink");

        check(blink != null && blink.size() == 2, "blink pose covers both eyes");
        check(blink.get("left_eye").length >= 6 && blink.get("left_eye")[4] < 0.5F, "blink squashes eye Y via scale");
        check(mchorse.bbs_mod.ai.plan.AnimationPlan.POSES.contains("blink"), "blink is a valid plan pose");

        /* Optional-unbound solve: blink beats skip eye channels instead of crashing */
        BoneNameResolver.Result bones = BoneNameResolver.resolve(STAR_BONES);
        AnimationPlan blinkPlan;

        try
        {
            blinkPlan = AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 4,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "blink", "spacing": 0, "intents": ["hold"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> blinkPoses = PoseSolver.solve(blinkPlan, bones);

        equal(0, blinkPoses.get(0).channels.size(), "blink with unbound eyes writes no channels");

        BoneNameResolver.Result withEyes = BoneNameResolver.resolve(STAR_BONES);

        withEyes.resolved.put("left_eye", BoneNameResolver.confirmed("left_eye", "左眼瞳"));
        withEyes.resolved.put("right_eye", BoneNameResolver.confirmed("right_eye", "右眼瞳"));

        List<PoseSolver.KeyPose> eyePoses = PoseSolver.solve(blinkPlan, withEyes);

        equal(2, eyePoses.get(0).channels.size(), "blink with bound eyes writes both eye channels");

        /* 幻觉 @技能姿势：技能库没有该名字时不得 NPE——@挥手 退化为空姿势，
         * @wave 退级成基础 wave（有骨骼通道），@compress 退级后还带走 rootY */
        AnimationPlan ghostPlan;

        try
        {
            ghostPlan = AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 30,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "@挥手", "spacing": 0, "intents": ["hold"] },
                    { "index": 1, "tick": 10, "phase": "hold", "pose": "@wave", "spacing": 10, "intents": ["hold"] },
                    { "index": 2, "tick": 20, "phase": "down", "pose": "@compress", "spacing": 10, "intents": ["ease_in_out"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> ghostPoses = PoseSolver.solve(ghostPlan, bones);

        equal(3, ghostPoses.size(), "hallucinated @poses still solve into three beats");
        equal(0, ghostPoses.get(0).channels.size(), "unknown @pose degrades to empty pose (no crash)");
        check(ghostPoses.get(1).channels.size() > 0, "@wave falls back to base wave channels");
        equal(-0.18F, ghostPoses.get(2).rootY, "stripped @compress keeps its rootY offset");

        /* 步态展开：相邻走路拍自动插 walk_pass 过渡帧，走路拍升级平滑插值，
         * 且走路不再带 y 起伏（ROOT_Y 无 walk 条目） */
        AnimationPlan gaitPlan;

        try
        {
            gaitPlan = AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 24,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "contact", "pose": "walk_step", "spacing": 0, "intents": ["hold"] },
                    { "index": 1, "tick": 12, "phase": "contact", "pose": "walk_step_b", "spacing": 12, "intents": ["linear"] },
                    { "index": 2, "tick": 24, "phase": "contact", "pose": "walk_step", "spacing": 12, "intents": ["hold"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> gait = PoseSolver.solve(gaitPlan, bones);

        equal(5, gait.size(), "two walk contacts expand with pass frames between");
        equal("walk_pass", gait.get(1).pose, "pass frame inserted after first contact");
        equal(6, gait.get(1).tick, "pass frame sits at the midpoint");
        equal("ease_in_out", gait.get(1).intent, "pass frame uses smooth curve");
        equal("ease_in_out", gait.get(2).intent, "linear walk beat upgraded to smooth");
        equal(0F, gait.get(0).rootY, "walking carries no vertical bob (user request)");
        check(gait.get(1).channels.size() >= 8, "pass frame animates the full walk rig");

        /* 步幅变化：相邻两步的能量不同（打破机械重复），torso/骨盆已入步态 */
        PoseSolver.BoneChannel leg0 = gait.get(0).channels.stream()
            .filter(c -> c.bone.equals("left_leg")).findFirst().orElse(null);
        PoseSolver.BoneChannel leg1 = gait.get(2).channels.stream()
            .filter(c -> c.bone.equals("left_leg")).findFirst().orElse(null);
        check(gait.get(0).channels.stream().anyMatch(c -> c.bone.equals("torso_lower")),
            "walk animates the pelvis (deep model adaptation)");
        check(leg0 != null && leg1 != null && Math.abs(leg0.x - leg1.x) > 0.0001F,
            "consecutive strides differ in energy (no mechanical repetition)");

        /* 作者姿势平移携带：@技能姿势的 t 分量完整落到通道与轨道值 */
        mchorse.bbs_mod.utils.pose.Pose authored = new mchorse.bbs_mod.utils.pose.Pose();
        mchorse.bbs_mod.utils.pose.PoseTransform at = authored.getOrCreate("body");

        at.rotate.set(0.4F, 0F, 0F);
        at.translate.set(0F, -0.04F, 1.05F);

        mchorse.bbs_mod.utils.pose.PoseTransform armR = authored.getOrCreate("right_arm");

        armR.rotate.set(0F, 0.5F, 0.2F);

        AnimationPlan skillPlan;

        try
        {
            skillPlan = AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 10,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "@蹲下", "spacing": 0, "intents": ["hold"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> skillPoses = PoseSolver.solve(skillPlan, bones,
            1F, java.util.Map.of("@蹲下", authored));

        PoseSolver.BoneChannel skillChannel = skillPoses.get(0).channels.stream()
            .filter(c -> c.bone.equals("body")).findFirst().orElse(null);

        check(skillChannel != null, "authored body channel present");
        equal(9, skillChannel == null ? 0 : skillChannel.values.length, "authored pose channel carries rot+scale+translate");
        equal(1.05F, skillChannel == null ? 0F : skillChannel.values[8], "author translate Z survives into the channel");

        FormProperties skillProps = new FormProperties("skillTrack");
        List<FrameCommitter.ChannelWrite> skillWrites =
            PoseSolver.toPoseTrackWrites(skillPoses, java.util.Map.of(), skillProps, null);
        EditPatch.KeyWrite skillKey = skillWrites.get(0).keys.get(0);
        mchorse.bbs_mod.utils.pose.Pose skillPose = (mchorse.bbs_mod.utils.pose.Pose) skillKey.fullValue;

        equal(1.05F, skillPose.transforms.get("body").translate.z, "pose track writes author translate");
        equal(0.4F, skillPose.transforms.get("body").rotate.x, "pose track keeps authored rotation");

        /* 差异化：同一作者姿势第二次出现整只镜像（左右互换），且不再走
         * linear——进入姿势的到达升级为 S 曲线 */
        AnimationPlan repeatPlan;

        try
        {
            repeatPlan = AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 40,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "@挥手姿势1", "spacing": 0, "intents": ["hold"] },
                    { "index": 1, "tick": 20, "phase": "hold", "pose": "@挥手姿势1", "spacing": 20, "intents": ["hold"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> repeats = PoseSolver.solve(repeatPlan, bones,
            1F, java.util.Map.of("@挥手姿势1", authored));

        equal(2, repeats.size(), "repeated @pose solves twice");
        equal("ease_in_out", repeats.get(0).intent, "arrival into authored pose uses S curve by default");

        PoseSolver.BoneChannel firstWave = repeats.get(0).channels.stream()
            .filter(c -> c.bone.equals("right_arm")).findFirst().orElse(null);
        PoseSolver.BoneChannel secondWave = repeats.get(1).channels.stream()
            .filter(c -> c.bone.equals("left_arm")).findFirst().orElse(null);

        check(firstWave != null, "first occurrence keeps authored sides");
        check(secondWave != null, "second occurrence is mirrored (left/right swapped)");
        check(firstWave != null && secondWave != null
            && Math.abs(firstWave.x - secondWave.x) < 0.0001F
            && Math.abs(firstWave.y + secondWave.y) > 0.0001F,
            "mirror negates Y rotation while keeping X");

        /* v2 直写骨骼值：度→弧度、缺骨沿用上一拍（全关节连续）、move/t/s 解析 */
        AnimationPlan v2plan;

        try
        {
            v2plan = AnimationPlan.parse("""
            {
              "version": 2, "fps": 20, "total_ticks": 16,
              "beats": [
                { "index": 0, "tick": 0, "phase": "hold", "move": [0.4, 0, 0],
                  "pose": { "head": {"r": [10, 0, 0]}, "left_leg": {"r": [16, 0, 0]} } },
                { "index": 1, "tick": 8, "phase": "hold", "move": [0.9, 0, 0],
                  "pose": { "head": {"r": [12, 0, 0]} } }
              ]
            }
            """);
        }
        catch (AiException e)
        {
            throw new RuntimeException(e);
        }

        equal(2, v2plan.version, "v2 plan version kept");
        check(v2plan.beats.get(0).poseObject != null && v2plan.beats.get(0).move != null,
            "v2 beat carries poseObject and move");
        equal(0.9F, v2plan.beats.get(1).move[0], "v2 move parsed");

        List<PoseSolver.KeyPose> v2poses = PoseSolver.solve(v2plan, bones);

        equal(2, v2poses.size(), "v2 beats solve one-to-one");

        PoseSolver.BoneChannel head0 = v2poses.get(0).channels.stream()
            .filter(c -> c.bone.equals("head")).findFirst().orElse(null);

        check(head0 != null && Math.abs(head0.x - (float) Math.toRadians(10)) < 0.0001F,
            "v2 head degrees converted to radians");

        PoseSolver.BoneChannel v2leg0 = v2poses.get(0).channels.stream()
            .filter(c -> c.bone.equals("left_leg")).findFirst().orElse(null);
        PoseSolver.BoneChannel v2leg1 = v2poses.get(1).channels.stream()
            .filter(c -> c.bone.equals("left_leg")).findFirst().orElse(null);

        check(v2leg0 != null && v2leg1 != null && Math.abs(v2leg1.x - v2leg0.x) < 0.0001F,
            "v2 missing bone carries forward the previous beat (joint continuity)");

        PoseSolver.BoneChannel head1 = v2poses.get(1).channels.stream()
            .filter(c -> c.bone.equals("head")).findFirst().orElse(null);

        check(head1 != null && Math.abs(head1.x - (float) Math.toRadians(12)) < 0.0001F,
            "v2 second beat overrides the carried value");

        /* v2 的 t/s 透传：眨眼缩放、body 平移 */
        AnimationPlan v2fx;

        try
        {
            v2fx = AnimationPlan.parse("""
            {
              "version": 2, "fps": 20, "total_ticks": 8,
              "beats": [
                { "index": 0, "tick": 0, "phase": "hold",
                  "pose": { "left_eye": {"r": [0, 0, 0], "s": [1, 0.12, 1]}, "body": {"r": [0, 0, 0], "t": [0, -0.04, 0]} } }
              ]
            }
            """);
        }
        catch (AiException e)
        {
            throw new RuntimeException(e);
        }

        BoneNameResolver.Result withEyes2 = BoneNameResolver.resolve(STAR_BONES);

        withEyes2.resolved.put("left_eye", BoneNameResolver.confirmed("left_eye", "左眼瞳"));

        List<PoseSolver.KeyPose> v2fxPoses = PoseSolver.solve(v2fx, withEyes2);
        PoseSolver.BoneChannel eye = v2fxPoses.get(0).channels.stream()
            .filter(c -> c.bone.equals("左眼瞳")).findFirst().orElse(null);
        PoseSolver.BoneChannel bod = v2fxPoses.get(0).channels.stream()
            .filter(c -> c.bone.equals("body")).findFirst().orElse(null);

        check(eye != null && eye.values.length == 9 && Math.abs(eye.values[4] - 0.12F) < 0.0001F,
            "v2 blink scale passes through");
        check(bod != null && bod.values.length == 9 && Math.abs(bod.values[7] - -0.04F) < 0.0001F,
            "v2 body translate passes through");

        /* 生物力学保底层：移动跨度内弱值/缺值兜底到最低幅度（不再铁板） */
        AnimationPlan v2walk;

        try
        {
            v2walk = AnimationPlan.parse("""
                {
                  "version": 2, "fps": 20, "total_ticks": 16,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "move": [0.5, 0, 0],
                      "pose": { "left_arm": {"r": [3, 0, 0]} } },
                    { "index": 1, "tick": 8, "phase": "hold", "move": [1.0, 0, 0],
                      "pose": {} }
                  ]
                }
                """);
        }
        catch (AiException e)
        {
            throw new RuntimeException(e);
        }

        List<PoseSolver.KeyPose> walkPoses = PoseSolver.solve(v2walk, bones);

        PoseSolver.BoneChannel arm0 = walkPoses.get(0).channels.stream()
            .filter(c -> c.bone.equals("left_arm")).findFirst().orElse(null);

        check(arm0 != null && Math.abs(arm0.x - (float) Math.toRadians(-18)) < 0.0001F,
            "weak authored arm swing is floored to the biomechanics minimum");

        PoseSolver.BoneChannel floorLeg1 = walkPoses.get(1).channels.stream()
            .filter(c -> c.bone.equals("left_leg")).findFirst().orElse(null);

        check(floorLeg1 != null && Math.abs(Math.abs(floorLeg1.x) - (float) Math.toRadians(15)) < 0.0001F,
            "floor-created leg swing carries forward (continuity beats parity)");

        PoseSolver.BoneChannel arm1 = walkPoses.get(1).channels.stream()
            .filter(c -> c.bone.equals("left_arm")).findFirst().orElse(null);

        check(arm1 != null && Math.abs(arm1.x - (float) Math.toRadians(-18)) < 0.0001F,
            "floor respects existing authored amplitude on later beats");

        /* v2 曲线强制：LLM 给 linear 也升为 S 曲线 */
        equal("ease_in_out", walkPoses.get(0).intent, "v2 linear intent upgraded to S curve");

        /* Two-ended writes: same bone on root and part end fans out to both */
        FormProperties properties = new FormProperties("properties");
        PoseSolver.KeyPose pose = new PoseSolver.KeyPose();
        PoseSolver.BoneChannel channel = new PoseSolver.BoneChannel();

        pose.tick = 0;
        channel.bone = "head";
        channel.values = new float[] {12F, 0F, 0F};
        channel.x = 12F;
        pose.channels.add(channel);

        java.util.Map<String, List<String>> ends = java.util.Map.of("head", List.of("", "0"));
        List<FrameCommitter.ChannelWrite> writes = PoseSolver.toChannelWrites(List.of(pose), ends, properties);

        equal(2, writes.size(), "two-ended bone writes both form paths");
        check(properties.get(TrackId.parse("pose.bones.head")) != null, "root end channel created");
        check(properties.get(TrackId.parse("0/pose.bones.head")) != null, "body-part end channel created");

        /* 意图→插值映射：到达意图落在前一个键上 */
        equal("cubic_inout", PoseSolver.interpFor("ease_in_out").getKey(), "ease_in_out -> cubic_inout");
        equal("elastic_out", PoseSolver.interpFor("elastic").getKey(), "elastic -> elastic_out");
        equal("exp_out", PoseSolver.interpFor("snap").getKey(), "snap -> exp_out");
        equal("linear", PoseSolver.interpFor("whatever").getKey(), "unknown intent falls back to linear");
    }

    /**
     * 内置 Star 3.6 模型深度适配回归：直接读取随 mod 打包的 model.bbs.json，
     * 走真实组遍历 + BoneNameResolver，断言——核心六骨骼全解析、眼睛变体的
     * 眼瞳命中、默认绑定齐全。任何一环断了（改名/漏拷/别名回退）都会在这里爆。
     */
    private static void starBuiltins()
    {
        String[][] variants = {
            {"slim_eyes", "59", "true"},
            {"slim_gapless", "48", "false"},
            {"thick_eyes", null, "true"},
            {"thick_gapless", null, "false"},
        };

        for (String[] spec : variants)
        {
            String variant = spec[0];
            String expectedCount = spec[1];
            boolean hasEyes = spec[2].equals("true");

            List<String> bones = readBuiltinBones(variant);

            if (bones == null)
            {
                fail("builtin model missing from resources: " + variant);

                continue;
            }

            if (expectedCount != null)
            {
                equal(Integer.parseInt(expectedCount), bones.size(), variant + " bone count matches the shipped model");
            }

            BoneNameResolver.Result result = BoneNameResolver.resolve(bones);

            check(result.resolved.containsKey("head") && result.resolved.get("head").actual.equals("head"),
                variant + ": head auto-resolves exact");
            check(result.resolved.containsKey("body") && result.resolved.get("body").actual.equals("body"),
                variant + ": body wins over torso/torso_lower decoys");
            check(result.resolved.containsKey("left_arm") && result.resolved.get("left_arm").actual.equals("left_arm"),
                variant + ": left_arm exact");
            check(result.resolved.containsKey("left_leg") && result.resolved.get("left_leg").actual.equals("left_leg"),
                variant + ": left_leg exact");
            check(result.resolved.get("left_elbow") != null
                && result.resolved.get("left_elbow").actual.equals("left_elbow"), variant + ": left_elbow exact");
            check(result.resolved.get("right_knee") != null
                && result.resolved.get("right_knee").actual.equals("right_knee"), variant + ": right_knee exact");
            check(result.resolved.get("headwear") != null
                && result.resolved.get("headwear").actual.equals("headwear"), variant + ": headwear exact");
            check(result.resolved.get("left_elbow") != null
                && result.resolved.get("left_elbow").actual.equals("left_elbow"), variant + ": left_elbow exact");
            check(result.resolved.get("right_knee") != null
                && result.resolved.get("right_knee").actual.equals("right_knee"), variant + ": right_knee exact");
            check(result.resolved.get("headwear") != null
                && result.resolved.get("headwear").actual.equals("headwear"), variant + ": headwear exact");
            for (String poseName : new String[] {"wave", "cheer", "bow", "sit", "run"})
            {
                check(mchorse.bbs_mod.ai.pose.PoseLibrary.get(poseName) != null, variant + ": pose " + poseName + " in library");
            }
            check(result.isComplete(), variant + ": core six complete (optional eyes excluded)");

            if (hasEyes)
            {
                check(bones.contains("左眼瞳") && bones.contains("右眼瞳"), variant + ": eye bones present");
                check(result.resolved.get("left_eye") != null
                    && result.resolved.get("left_eye").actual.equals("左眼瞳"), variant + ": left_eye <- 左眼瞳");
                check(result.resolved.get("right_eye") != null
                    && result.resolved.get("right_eye").actual.equals("右眼瞳"), variant + ": right_eye <- 右眼瞳");
            }

            /* 默认绑定：内置表应有该变体的绑定且含眼睛变体的眼瞳 */
            java.util.Map<String, String> defaults = mchorse.bbs_mod.ai.pose.AiBoneBindings.builtinDefaults("bbs:star36/" + variant);

            check(defaults.containsKey("head") && defaults.get("head").equals("head"),
                variant + ": builtin default binding head");
            check(hasEyes == defaults.containsKey("left_eye"), variant + ": defaults carry eyes iff the variant has them");
            check(hasEyes == defaults.containsKey("left_eyebrow"), variant + ": defaults carry brows iff eyes variant");
            check(defaults.containsKey("left_elbow") && defaults.containsKey("right_knee") && defaults.containsKey("headwear"),
                variant + ": defaults carry elbows/knees/headwear");
        }
    }

    /** Walk a shipped model.bbs.json's group hierarchy (recursive groups maps). */
    private static List<String> readBuiltinBones(String variant)
    {
        try
        {
            java.io.InputStream stream = PoseSolverTest.class.getResourceAsStream(
                "/ai_models/star36/" + variant + "/model.bbs.json");

            if (stream == null)
            {
                return null;
            }

            byte[] raw = stream.readAllBytes();
            stream.close();

            mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(new String(raw, java.nio.charset.StandardCharsets.UTF_8));

            if (map == null || !map.has("model"))
            {
                return null;
            }

            List<String> out = new java.util.ArrayList<>();
            collectGroups(map.get("model").asMap().get("groups"), out);

            return out;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void collectGroups(mchorse.bbs_mod.data.types.BaseType groupsValue, List<String> out)
    {
        if (groupsValue == null || !mchorse.bbs_mod.data.types.BaseType.isMap(groupsValue))
        {
            return;
        }

        for (String name : groupsValue.asMap().keys())
        {
            out.add(name);

            mchorse.bbs_mod.data.types.BaseType nested = groupsValue.asMap().get(name).asMap().get("groups");

            collectGroups(nested, out);
        }
    }

    /** Star-model-shaped inventory: real names, plus decoys that must not win. */
    private static final List<String> STAR_BONES = List.of(
        "head", "headwear", "body", "torso_lower", "left_arm", "left_elbow", "left_arm_end",
        "right_arm", "right_elbow", "right_arm_end", "left_leg", "left_knee", "left_leg_end",
        "right_leg", "right_knee", "right_leg_end", "anchor", "Move_X", "1", "2", "3"
    );

    private static void boneResolution()
    {
        BoneNameResolver.Result result = BoneNameResolver.resolve(STAR_BONES);

        check(result.isComplete(), "star inventory resolves completely");
        equal("head", result.resolved.get("head").actual, "head exact");
        equal("body", result.resolved.get("body").actual, "body exact");
        equal("left_arm", result.resolved.get("left_arm").actual, "left_arm beats left_arm_end (exact first)");
        equal("right_leg", result.resolved.get("right_leg").actual, "right_leg exact");
        check(result.resolved.get("head").score == 1D, "exact match scores 1");

        /* An inventory without legs must report them, not invent them */
        BoneNameResolver.Result partial = BoneNameResolver.resolve(List.of("head", "body", "left_arm", "right_arm"));

        check(!partial.isComplete(), "missing bones are reported");
        check(partial.unresolved.contains("left_leg") && partial.unresolved.contains("right_leg"), "legs unresolved");

        /* Determinism */
        BoneNameResolver.Result again = BoneNameResolver.resolve(STAR_BONES);

        for (String generic : result.resolved.keySet())
        {
            equal(result.resolved.get(generic).actual, again.resolved.get(generic).actual, "resolution deterministic: " + generic);
        }
    }

    private static AnimationPlan plan()
    {
        try
        {
            return AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 40,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "idle", "spacing": 0, "intents": ["hold"] },
                    { "index": 1, "tick": 8, "phase": "anticipation", "pose": "crouch", "spacing": 8, "intents": ["ease_in_out"] },
                    { "index": 2, "tick": 20, "phase": "contact", "pose": "punch", "spacing": 12, "intents": ["snap", "impact"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void solverContract()
    {
        BoneNameResolver.Result complete = BoneNameResolver.resolve(STAR_BONES);
        List<PoseSolver.KeyPose> poses = PoseSolver.solve(plan(), complete);

        equal(3, poses.size(), "one key pose per beat");
        equal(0, poses.get(0).tick, "first beat tick");
        equal(20, poses.get(2).tick, "last beat tick");

        /* punch moves five bones (right_arm, left_arm, left_elbow, body, head) */
        equal(5, poses.get(2).channels.size(), "punch channel count");
        check(poses.get(2).channels.stream().anyMatch(c -> c.bone.equals("left_elbow")), "punch guards with the left elbow when bound");
        check(poses.get(2).channels.stream().anyMatch(c -> c.bone.equals("right_arm")), "punch uses the RESOLVED right_arm name");

        /* idles carry no bones - nothing to write */
        equal(0, poses.get(0).channels.size(), "idle pose is empty by design");

        /* Determinism: same input, same angles */
        List<PoseSolver.KeyPose> again = PoseSolver.solve(plan(), complete);

        for (int i = 0; i < poses.size(); i++)
        {
            equal(poses.get(i).channels.size(), again.get(i).channels.size(), "solve deterministic: channel count " + i);
        }

        /* Contract violations throw, never guess */
        BoneNameResolver.Result partial = BoneNameResolver.resolve(List.of("head"));

        try
        {
            PoseSolver.solve(plan(), partial);
            fail("unresolved bone map must throw");
        }
        catch (IllegalArgumentException e)
        {
            check(e.getMessage().contains("unresolved"), "unresolved error names the problem");
        }

        /* 整只 Pose 轨道的单位契约：solve() 产出的通道值已是弧度，
         * toPoseTrackWrites 必须原样落键——再 toRadians 一次会把 16° 的
         * 抬腿压成 0.28°（腿部"没动作"回归） */
        FormProperties trackProps = new FormProperties("poseTrack");
        List<FrameCommitter.ChannelWrite> trackWrites =
            PoseSolver.toPoseTrackWrites(poses, java.util.Map.of(), trackProps, null);

        check(!trackWrites.isEmpty(), "pose track write produced");
        FrameCommitter.ChannelWrite track = trackWrites.get(0);

        PoseSolver.KeyPose punch = poses.get(2);
        EditPatch.KeyWrite punchKey = track.keys.stream()
            .filter(k -> k.tick == punch.tick).findFirst().orElse(null);

        check(punchKey != null, "punch beat key exists on the pose track");
        mchorse.bbs_mod.utils.pose.Pose punchPose =
            punchKey == null ? null : (mchorse.bbs_mod.utils.pose.Pose) punchKey.fullValue;

        check(punchPose != null, "punch beat key carries a whole Pose");
        int matched = 0;

        for (PoseSolver.BoneChannel channel : punch.channels)
        {
            mchorse.bbs_mod.utils.pose.PoseTransform transform = punchPose == null
                ? null : punchPose.transforms.get(channel.bone);

            if (transform == null)
            {
                continue;
            }

            matched++;
            equal(channel.x, transform.rotate.x, "pose track keeps radian X for " + channel.bone);
            equal(channel.y, transform.rotate.y, "pose track keeps radian Y for " + channel.bone);
            equal(channel.z, transform.rotate.z, "pose track keeps radian Z for " + channel.bone);
        }

        check(matched >= 4, "punch pose carries its solved bones (" + matched + ")");
    }

    private static void poseWritesThroughCommit()
    {
        FormProperties properties = new FormProperties("properties");
        UndoManager<ValueGroup> manager = new UndoManager<>();

        BoneNameResolver.Result bones = BoneNameResolver.resolve(STAR_BONES);
        List<PoseSolver.KeyPose> poses = PoseSolver.solve(plan(), bones);
        List<FrameCommitter.ChannelWrite> writes = PoseSolver.toChannelWrites(poses, properties);

        equal(7, writes.size(), "seven bone channels touched (crouch adds left_elbow via punch; walk knees excluded from this plan)");

        KeyframeChannel arm = properties.get(TrackId.parse("pose.bones.right_arm"));

        check(arm != null, "right_arm channel created");
        check(!CurvePolishGuard.isNumeric(arm), "bone channel is the POSE factory, not numeric");

        FrameDiffProbe.diff = FrameCommitter.commit(properties, manager, writes);

        equal(1, arm.getKeyframes().size(), "right_arm only appears in the punch beat");
        equal(1, manager.getTotalUndos(), "one AI operation = one undo entry");

        manager.undo(properties);
        equal(0, arm.getKeyframes().size(), "undo rolls the pose channel back to empty");
        manager.redo(properties);
        equal(1, arm.getKeyframes().size(), "redo restores the punch key");

        /* FrameDiff saw every written tick */
        equal(9, FrameDiffProbe.diff.changedKeyCount(), "diff covers punch's 5 + crouch's 4 + idle's 0 keys");
    }

    private static void libraryCoversContract()
    {
        List<String> missing = PoseLibrary.missing(AnimationPlan.POSES);

        equal(0, missing.size(), "library covers every pose the contract allows: " + missing);
        check(PoseLibrary.GENERIC_BONES.size() == 6, "six generic bones");
        check(Set.copyOf(PoseLibrary.GENERIC_BONES).size() == 6, "generic bones unique");
    }

    /* helpers */

    private static class CurvePolishGuard
    {
        static boolean isNumeric(KeyframeChannel channel)
        {
            return mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory());
        }
    }

    private static class FrameDiffProbe
    {
        static mchorse.bbs_mod.ai.commit.FrameDiff diff;
    }

    private static void check(boolean condition, String label)
    {
        checks++;

        if (!condition)
        {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    private static void equal(Object expected, Object actual, String label)
    {
        checks++;

        if (!java.util.Objects.equals(expected, actual))
        {
            failures++;
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String label)
    {
        checks++;
        failures++;
        System.out.println("FAIL: " + label);
    }
}
