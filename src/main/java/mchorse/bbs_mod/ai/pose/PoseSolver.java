package mchorse.bbs_mod.ai.pose;

import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.NumericType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.pose.PoseTransform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L2 motion layer: turns an {@link AnimationPlan}'s beats into blocking key
 * pose frames on the model's REAL bone channels - one extreme pose per beat,
 * never per-frame generation (copilot spec section 3 L2).
 *
 * <p>The plan names poses ({@link PoseLibrary}); the bone names come from the
 * model via {@link BoneNameResolver}, which the caller must have let the user
 * confirm - a resolver result with unresolved bones is rejected here rather
 * than guessed at (section 12.2's hard rule). Writes go out as
 * {@link FrameCommitter.ChannelWrite}s on the {@code pose.bones.<bone>}
 * POSE channels, so commit, undo and diff stay the M3 machinery.</p>
 */
public class PoseSolver
{
    /** One solved extreme pose: bone channel -> rotation offsets, in degrees. */
    public static class KeyPose
    {
        /** v2 直写拍：与相邻直写拍之间不做错帧中点（纯 S 曲线段更顺滑） */
        public boolean direct;

        /** v2 步行/跑步拍（位移跨度内）：呼吸层与错帧层对其让路 */
        public boolean gait;

        public int tick;
        public String phase;
        public String pose;
        /** 到达这一拍的缓动意图（计划 intents 首选），决定进入段的插值 */
        public String intent = "linear";

        /** 该拍的根重心偏移（方块，负=下沉），来自姿态库 ROOT_Y */
        public float rootY;
        public final List<BoneChannel> channels = new ArrayList<>();
    }

    /**
     * Plan intent vocabulary -> BBS interpolation, mirroring the polish path's
     * semantics (snap/impact front-load then hold; elastic/back overshoot out).
     * Named easings keep keys legible in the curve editor's dropdown.
     */
    public static mchorse.bbs_mod.utils.interps.IInterp interpFor(String intent)
    {
        if (intent == null)
        {
            return Interpolations.LINEAR;
        }

        switch (intent)
        {
            case "hold": return Interpolations.CONST;
            case "ease_in": return Interpolations.CUBIC_IN;
            case "ease_out": return Interpolations.CUBIC_OUT;
            case "ease_in_out": return Interpolations.CUBIC_INOUT;
            case "elastic": return Interpolations.ELASTIC_OUT;
            case "overshoot": return Interpolations.BACK_OUT;
            case "snap":
            case "impact": return Interpolations.EXP_OUT;
            case "smooth":
            case "arc": return Interpolations.SINE_INOUT;
            default: return Interpolations.LINEAR;
        }
    }

    /** A single bone's contribution to a pose, in degrees. */
    public static class BoneChannel
    {
        public String bone;
        public float x;
        public float y;
        public float z;

        /** Library entry: rotation [0..2], optional scale [3..5], optional
         * translate [6..8] (作者姿势的 t 分量——蹲下/站姿等靠平移造型). */
        public float[] values = new float[] {0F, 0F, 0F};
    }

    /** 泛骨骼名 → 模型实际骨骼名（未解析返回 null） */
    private static String actualOf(BoneNameResolver.Result bones, String generic)
    {
        BoneNameResolver.Resolution r = bones.resolved.get(generic);

        return r == null ? null : r.actual;
    }

    /** 步行保底：骨骼值幅度不足 threshold 时设为 target（度）——只兜底不覆盖 */
    private static void ensure(java.util.Map<String, float[]> vals, String generic, int axis, float threshold, float target)
    {
        float[] v = vals.get(generic);

        if (v == null)
        {
            v = new float[] {0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 0F};
            vals.put(generic, v);
        }

        if (Math.abs(v[axis]) < threshold)
        {
            v[axis] = target;
        }
    }

    /** 读骨骼值向量（r/t/s）：缺失或类型不对时用 fallback 填满 3 位 */
    private static float[] readVec3(MapType map, String key, float fallback)
    {
        float[] out = new float[] {fallback, fallback, fallback};
        BaseType value = map.get(key);

        if (!BaseType.isList(value))
        {
            return out;
        }

        ListType list = value.asList();

        for (int i = 0; i < 3 && i < list.size(); i++)
        {
            if (BaseType.isNumeric(list.get(i)))
            {
                out[i] = ((NumericType) list.get(i)).floatValue();
            }
        }

        return out;
    }

    /** X 轴镜像的骨骼名：left_/right_（含中文 左/右）前缀互换，其余原样 */
    private static String mirroredBone(String bone)
    {
        if (bone.startsWith("left_"))
        {
            return "right_" + bone.substring(5);
        }

        if (bone.startsWith("right_"))
        {
            return "left_" + bone.substring(6);
        }

        if (bone.startsWith("左"))
        {
            return "右" + bone.substring(1);
        }

        if (bone.startsWith("右"))
        {
            return "左" + bone.substring(1);
        }

        return bone;
    }

    /**
     * Solve blocking poses for a plan against a user-confirmed bone map.
     *
     * @throws IllegalArgumentException when the bone map still has unresolved
     *                                 generics or the plan uses a pose the
     *                                 library does not cover - both are
     *                                 contract violations, never guessed around
     */
    public static List<KeyPose> solve(AnimationPlan plan, BoneNameResolver.Result bones)
    {
        return solve(plan, bones, 1F, Map.of());
    }

    public static List<KeyPose> solve(AnimationPlan plan, BoneNameResolver.Result bones, float amplitude)
    {
        return solve(plan, bones, amplitude, Map.of());
    }

    /**
     * @param amplitude  动作幅度系数（0.6 含蓄 / 1 自然 / 1.35 夸张），只缩放旋转，
     *                   眨眼的眼皮压缩不放大
     * @param skillPoses 技能姿势（AiSkillLibrary 加载的作者姿态，名字含 @）。
     *                   命中的拍直接采用作者姿势数据（逐骨骼转角度 + 缩放），
     *                   幅度系数不适用于作者调好的姿势。
     */
    public static List<KeyPose> solve(AnimationPlan plan, BoneNameResolver.Result bones, float amplitude, Map<String, mchorse.bbs_mod.utils.pose.Pose> skillPoses)
    {
        if (!bones.isComplete())
        {
            throw new IllegalArgumentException("Bone map is unresolved (missing: " + bones.unresolved + ") - confirm candidates in the UI first");
        }

        List<String> missing = plan.version < AnimationPlan.VERSION_DIRECT
            ? PoseLibrary.missing(plan.beats.stream().map(b -> b.pose).distinct().toList())
            : List.of();

        if (!missing.isEmpty())
        {
            throw new IllegalArgumentException("Plan uses poses the library does not cover: " + missing);
        }

        List<KeyPose> poses = new ArrayList<>();

        /* v2 全骨骼清单（稳定顺序）：直写模式下每拍都要覆盖到 */
        List<String> allGenerics = new ArrayList<>(PoseLibrary.GENERIC_BONES);

        allGenerics.addAll(PoseLibrary.OPTIONAL_BONES);

        /* v2 连续性载体（度数域 {r0,r1,r2, s0,s1,s2, t0,t1,t2}）：某拍没提
         * 的骨骼沿用上一拍的值——关节一旦动起来就不会瞬回绑定姿势 */
        java.util.Map<String, float[]> carried = new java.util.HashMap<>();

        /* v2 位移跨度：beat.move 出现的首尾 tick——生物力学保底层的作用域 */
        int firstMoveTick = Integer.MAX_VALUE;
        int lastMoveTick = Integer.MIN_VALUE;

        for (AnimationPlan.Beat b : plan.beats)
        {
            if (b.move != null)
            {
                firstMoveTick = Math.min(firstMoveTick, b.tick);
                lastMoveTick = Math.max(lastMoveTick, b.tick);
            }
        }

        boolean hasMove = firstMoveTick != Integer.MAX_VALUE;

        /* MC 幅度校准：LLM 自写的腿部幅度决定步行保底档位。写得含蓄
         * （作者风格 ±16° 级）用 subdued 档——仍明显高于作者值，读得出
         * MC 原版那种干净的摆腿；模型已经写大（≥18°）则用标准档，只兜
         * 更低的底，不覆盖作者意图 */
        float authoredLegSwing = 0F;

        for (AnimationPlan.Beat b : plan.beats)
        {
            if (b.poseObject == null)
            {
                continue;
            }

            for (String generic : new String[] {"left_leg", "right_leg"})
            {
                BoneNameResolver.Resolution res = bones.resolved.get(generic);

                for (String key : new String[] {generic, res == null ? null : res.actual})
                {
                    if (key == null || !b.poseObject.has(key))
                    {
                        continue;
                    }

                    float[] r = readVec3(b.poseObject.getMap(key), "r", 0F);

                    authoredLegSwing = Math.max(authoredLegSwing, Math.abs(r[0]));
                }
            }
        }

        boolean subdued = authoredLegSwing < 18F;
        float legFloor = subdued ? 24F : 32F;
        float armFloor = subdued ? 18F : 24F;

        /* 步幅能量表（v1 姿势名路径在循环后整体缩放；v2 直写路径在步行
         * 保底层内逐拍作用于摆动轴）——相邻步 ±6% 的能量差打破机械感 */
        float[] strideEnergy = {1.0F, 0.94F, 1.06F, 0.97F};

        /* 步态展开：相邻走路拍之间插入 walk_pass 过渡帧（passing 位），
         * 并把走路的线性插值升级为 S 曲线——只有左右两个极端姿势来回
         * 跳 + 直线插值，是步态生硬的直接根源 */
        List<AnimationPlan.Beat> expanded = new ArrayList<>();

        for (int i = 0; i < plan.beats.size(); i++)
        {
            AnimationPlan.Beat cur = plan.beats.get(i);

            expanded.add(cur);

            if (i + 1 < plan.beats.size() && PoseLibrary.isWalk(cur.pose))
            {
                AnimationPlan.Beat next = plan.beats.get(i + 1);

                if (PoseLibrary.isWalk(next.pose) && next.tick - cur.tick >= 4)
                {
                    AnimationPlan.Beat pass = new AnimationPlan.Beat();

                    pass.index = -1;
                    pass.tick = (cur.tick + next.tick) / 2;
                    pass.phase = "passing";
                    pass.pose = "walk_pass";
                    pass.spacing = 0;
                    pass.intents.add(mchorse.bbs_mod.ai.curve.PolishKind.EASE_IN_OUT);

                    expanded.add(pass);
                }
            }
        }

        /* @作者姿势的出现计数：同一姿势套用多次时做差异化（隔次镜像 +
         * 逐骨骼微变），套用归套用，不做复读机 */
        java.util.Map<String, Integer> skillSeen = new java.util.HashMap<>();

        /* v2 位移跨度内的步数计数（步行保底层的相位） */
        int spanIndex = 0;

        for (AnimationPlan.Beat beat : expanded)
        {
            /* ══ v2 直写骨骼值：LLM 以动画师身份逐关节创作，不走姿势名库 ══ */
            if (plan.version >= AnimationPlan.VERSION_DIRECT && (beat.poseObject != null || beat.pose.isEmpty()))
            {
                KeyPose v2 = new KeyPose();

                v2.tick = beat.tick;
                v2.phase = beat.phase;
                v2.pose = beat.pose;
                v2.intent = beat.intents == null || beat.intents.isEmpty() ? "ease_in_out" : beat.intents.get(0).name().toLowerCase();

                /* 曲线强制：LLM 给 linear/hold 也升为 S 曲线——生硬感的直接来源 */
                if ("linear".equals(v2.intent) || "hold".equals(v2.intent))
                {
                    v2.intent = "ease_in_out";
                }

                /* @作者姿势仍可混用（模型自带的成品姿势） */
                if (beat.pose.startsWith("@"))
                {
                    mchorse.bbs_mod.utils.pose.Pose skill = skillPoses.get(beat.pose.substring(1));

                    if (skill == null)
                    {
                        skill = skillPoses.get(beat.pose);
                    }

                    if (skill != null)
                    {
                        int seen = skillSeen.merge(beat.pose, 1, Integer::sum);
                        boolean mirror = seen % 2 == 0;

                        for (Map.Entry<String, mchorse.bbs_mod.utils.pose.PoseTransform> entry : skill.transforms.entrySet())
                        {
                            BoneChannel channel = new BoneChannel();

                            channel.bone = mirror ? mirroredBone(entry.getKey()) : entry.getKey();

                            float jx = mirror ? -1F : 1F;
                            float jy = 1F + 0.05F * ((seen - 1) % 3 - 1) * (0.6F + 0.4F * Math.abs(entry.getKey().hashCode() % 7) / 7F);

                            channel.x = entry.getValue().rotate.x;
                            channel.y = entry.getValue().rotate.y * jy;
                            channel.z = entry.getValue().rotate.z * jx;
                            channel.values = new float[] {
                                channel.x, channel.y, channel.z,
                                entry.getValue().scale.x, entry.getValue().scale.y, entry.getValue().scale.z,
                                entry.getValue().translate.x * jx,
                                entry.getValue().translate.y * jy,
                                entry.getValue().translate.z * jx
                            };
                            v2.channels.add(channel);

                            /* 作者姿势也进载体（转回度数）：之后的拍子延续这套造型 */
                            carried.put(entry.getKey(), new float[] {
                                (float) Math.toDegrees(channel.x), (float) Math.toDegrees(channel.y),
                                (float) Math.toDegrees(channel.z),
                                entry.getValue().scale.x, entry.getValue().scale.y, entry.getValue().scale.z,
                                entry.getValue().translate.x, entry.getValue().translate.y, entry.getValue().translate.z
                            });
                        }

                        /* @姿势只摆造型、不重置全身：BBS 的 Pose 插值把键里
                         * 没写的骨骼当绑定姿势（REST），技能姿势往往只有几根
                         * 骨骼——其余已动画关节会在段间垂回零。把 carried 里
                         * 未被技能覆盖（含镜像对应侧）的骨骼并入本拍键 */
                        for (java.util.Map.Entry<String, float[]> entry : carried.entrySet())
                        {
                            String bone = entry.getKey();
                            String mirrorBone = mirroredBone(bone);
                            boolean covered = false;

                            for (BoneChannel existing : v2.channels)
                            {
                                if (existing.bone.equals(bone) || existing.bone.equals(mirrorBone))
                                {
                                    covered = true;

                                    break;
                                }
                            }

                            if (covered)
                            {
                                continue;
                            }

                            float[] v = entry.getValue();
                            BoneChannel channel = new BoneChannel();

                            channel.bone = bone;
                            channel.x = (float) Math.toRadians(v[0]);
                            channel.y = (float) Math.toRadians(v[1]);
                            channel.z = (float) Math.toRadians(v[2]);
                            channel.values = new float[] {channel.x, channel.y, channel.z, v[3], v[4], v[5], v[6], v[7], v[8]};
                            v2.channels.add(channel);
                        }

                        poses.add(v2);

                        continue;
                    }
                }

                v2.direct = true;
                v2.gait = hasMove && beat.tick >= firstMoveTick && beat.tick <= lastMoveTick;

                /* 直写骨骼（度数域）：本拍给值或沿用上一拍——关节不瞬回绑定姿势 */
                java.util.Map<String, float[]> beatVals = new java.util.HashMap<>();

                /* 键 = 模型实际骨骼名（深度适配：跳过泛骨骼层直呼其名）；
                 * 泛骨骼名仍兼容（映射到实际名） */
                if (beat.poseObject != null)
                {
                    for (String key : beat.poseObject.keys())
                    {
                        String actual = key;

                        if (!bones.inventory.contains(key) && bones.resolved.containsKey(key))
                        {
                            actual = bones.resolved.get(key).actual;
                        }

                        if (!bones.inventory.contains(actual))
                        {
                            continue;
                        }

                        MapType bm = beat.poseObject.getMap(key);
                        float[] r = readVec3(bm, "r", 0F);
                        float[] sc = bm.has("s") ? readVec3(bm, "s", 1F) : new float[] {1F, 1F, 1F};
                        float[] t = bm.has("t") ? readVec3(bm, "t", 0F) : new float[] {0F, 0F, 0F};

                        beatVals.put(actual, new float[] {r[0], r[1], r[2], sc[0], sc[1], sc[2], t[0], t[1], t[2]});
                    }
                }

                /* 连续性：没提到的实际骨骼沿用上一拍 */
                for (String actual : bones.inventory)
                {
                    if (!beatVals.containsKey(actual) && carried.containsKey(actual))
                    {
                        beatVals.put(actual, carried.get(actual).clone());
                    }
                }

                /* 生物力学保底层：位移跨度内的拍保证步行关节最低幅度——
                 * LLM 写得含蓄（骨盆 1°、漏骨盆）也不会再是"一块铁板"；
                 * 模型写了更大值时完全尊重。
                 * passing 拍让路：过渡帧的双腿本就该接近并拢（模板/作者
                 * 数据的中间位），保底会把它抬成另一个触地帧 → 铁板步。 */
                if (hasMove && beat.tick >= firstMoveTick && beat.tick <= lastMoveTick
                    && !"passing".equals(beat.phase))
                {
                    float g = (spanIndex % 2 == 0) ? 1F : -1F;

                    for (Map.Entry<String, BoneNameResolver.Resolution> entry : bones.resolved.entrySet())
                    {
                        String genericKey = entry.getKey();
                        String actual = entry.getValue().actual;

                        float targetAxis;
                        int axis;

                        switch (genericKey)
                        {
                            case "left_leg" -> { targetAxis = legFloor * g; axis = 0; }
                            case "right_leg" -> { targetAxis = -legFloor * g; axis = 0; }
                            case "left_knee", "right_knee" -> { targetAxis = 12F; axis = 0; }
                            case "left_arm" -> { targetAxis = -armFloor * g; axis = 0; }
                            case "right_arm" -> { targetAxis = armFloor * g; axis = 0; }
                            case "left_elbow", "right_elbow" -> { targetAxis = -12F; axis = 0; }
                            case "torso_lower" -> { targetAxis = 7F * g; axis = 1; }
                            case "torso" -> { targetAxis = -4.5F * g; axis = 1; }
                            case "body" -> { targetAxis = -2.5F; axis = 0; }
                            default -> { continue; }
                        }

                        /* 只抢救近零值（铁板救底）：阈值远低于目标幅度——
                         * 模板/作者的 16° 平静步幅是刻意设计，不得被强抬到
                         * MC 档（2026-10-06 用户反馈模板失效的根因之一）；
                         * 真正接近零的关节才被托到 MC 可读幅度 */
                        float threshold = switch (genericKey)
                        {
                            case "left_leg", "right_leg" -> 12F;
                            case "left_knee", "right_knee" -> 6F;
                            case "left_arm", "right_arm" -> 10F;
                            case "left_elbow", "right_elbow" -> 6F;
                            case "torso_lower" -> 3F;
                            case "torso" -> 2.5F;
                            case "body" -> 1.5F;
                            default -> 0F;
                        };

                        float[] v = beatVals.get(actual);

                        if (v == null)
                        {
                            v = new float[] {0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 0F};
                            beatVals.put(actual, v);
                        }

                        /* 只兜底：轴幅度低于阈值才接管，作者大值优先 */
                        if (Math.abs(v[axis]) < threshold)
                        {
                            v[axis] = targetAxis;
                        }
                    }

                    /* 正反校正（作者数据推导的符号约定：X 负=前倾/前摆）：
                     * 1) 同侧臂腿同向 = 摆错边，翻转到对侧；
                     * 2) 前进时身体后仰（正 X）改为前倾 */
                    float[] ll = beatVals.get(actualOf(bones, "left_leg"));
                    float[] la = beatVals.get(actualOf(bones, "left_arm"));
                    float[] rl = beatVals.get(actualOf(bones, "right_leg"));
                    float[] ra = beatVals.get(actualOf(bones, "right_arm"));

                    if (ll != null && la != null && Math.abs(ll[0]) >= 8F && Math.abs(la[0]) >= 10F
                        && Math.signum(ll[0]) == Math.signum(la[0]))
                    {
                        la[0] = -la[0];
                    }

                    if (rl != null && ra != null && Math.abs(rl[0]) >= 8F && Math.abs(ra[0]) >= 10F
                        && Math.signum(rl[0]) == Math.signum(ra[0]))
                    {
                        ra[0] = -ra[0];
                    }


                    /* IK 接地辅助：触地腿（X 负=前摆触地）膝盖伸直让脚“踩住”，
                     * 摆动腿屈膝前摆——滑步/悬空的直接来源是反着来 */
                    for (String side : new String[] {"left", "right"})
                    {
                        float[] leg = beatVals.get(actualOf(bones, side + "_leg"));
                        float[] knee = beatVals.get(actualOf(bones, side + "_knee"));

                        if (leg == null || knee == null)
                        {
                            continue;
                        }

                        if (leg[0] < -3F)
                        {
                            /* 触地：膝盖过弯压到 8°（伸直支撑） */
                            if (knee[0] > 10F)
                            {
                                knee[0] = 8F;
                            }
                        }
                        else if (Math.abs(leg[0]) >= 8F)
                        {
                            /* 摆动：膝盖不足则屈到 18° */
                            if (knee[0] < 12F)
                            {
                                knee[0] = 18F;
                            }
                        }
                    }

                    /* 头部/头饰随步微摆（治"走路脖子以上不动"的面具感）：
                     * 缺值时按步相位给 ±1.5°/±0.8° 的摇曳，floor 造出的微值
                     * 逐拍反号形成左右摆；LLM 写的明显头部动作（>1.6°）不动 */
                    for (String micro : new String[] {"head", "headwear"})
                    {
                        BoneNameResolver.Resolution res = bones.resolved.get(micro);

                        if (res == null)
                        {
                            continue;
                        }

                        float[] v = beatVals.get(res.actual);

                        if (v == null)
                        {
                            v = new float[] {0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 0F};
                            beatVals.put(res.actual, v);
                        }

                        float amp = "head".equals(micro) ? 1.5F : 0.8F;

                        if (Math.abs(v[1]) < 0.1F)
                        {
                            v[1] = amp * g;
                        }
                        else if (Math.abs(v[1]) <= amp + 0.1F)
                        {
                            v[1] = -v[1];
                        }
                    }

                    /* 步幅能量：v2 直写域的 ±6% 步差——只作用于摆动轴（腿/
                     * 膝/臂/肘的 X），头/躯干微动与缩放平移不参与 */
                    float energy = strideEnergy[spanIndex % strideEnergy.length];

                    for (String swing : new String[] {"left_leg", "right_leg", "left_knee",
                        "right_knee", "left_arm", "right_arm", "left_elbow", "right_elbow"})
                    {
                        BoneNameResolver.Resolution res = bones.resolved.get(swing);

                        if (res == null)
                        {
                            continue;
                        }

                        float[] v = beatVals.get(res.actual);

                        if (v != null)
                        {
                            v[0] *= energy;
                        }
                    }

                    spanIndex++;
                }

                /* 关节限位强制钳制（提示词的约定在此兜底）：肘 X∈[-150,0]、
                 * 膝 X∈[0,150]——LLM 写出反关节（肘外翻/膝反折）在这里归位 */
                for (String hinge : new String[] {"left_elbow", "right_elbow"})
                {
                    BoneNameResolver.Resolution res = bones.resolved.get(hinge);
                    float[] v = res == null ? null : beatVals.get(res.actual);

                    if (v != null)
                    {
                        v[0] = Math.max(-150F, Math.min(0F, v[0]));
                    }
                }

                for (String hinge : new String[] {"left_knee", "right_knee"})
                {
                    BoneNameResolver.Resolution res = bones.resolved.get(hinge);
                    float[] v = res == null ? null : beatVals.get(res.actual);

                    if (v != null)
                    {
                        v[0] = Math.max(0F, Math.min(150F, v[0]));
                    }
                }

                for (Map.Entry<String, float[]> entry : beatVals.entrySet())
                {
                    String actual = entry.getKey();
                    float[] v = entry.getValue();
                    BoneChannel channel = new BoneChannel();

                    channel.bone = actual;
                    channel.x = (float) Math.toRadians(v[0]) * amplitude;
                    channel.y = (float) Math.toRadians(v[1]) * amplitude;
                    channel.z = (float) Math.toRadians(v[2]) * amplitude;
                    channel.values = new float[] {channel.x, channel.y, channel.z, v[3], v[4], v[5], v[6], v[7], v[8]};

                    carried.put(actual, v.clone());
                    v2.channels.add(channel);
                }

                poses.add(v2);

                continue;
            }

            KeyPose pose = new KeyPose();

            pose.tick = beat.tick;
            pose.phase = beat.phase;
            pose.pose = beat.pose;
            pose.intent = beat.intents == null || beat.intents.isEmpty() ? "linear" : beat.intents.get(0).name().toLowerCase();

            /* 走路家族默认平滑曲线：linear 的来回切换观感生硬 */
            if (PoseLibrary.isWalk(beat.pose) && "linear".equals(pose.intent))
            {
                pose.intent = "ease_in_out";
            }

            /* @ 技能姿势：作者姿态整只替换，不吃幅度 */
            if (beat.pose.startsWith("@"))
            {
                /* 技能表的键不带 @（AiSkillLibrary 以姿势名原样为键）——
                 * 这里必须剥前缀再查，否则所有 @姿势都静默退化成空姿势 */
                mchorse.bbs_mod.utils.pose.Pose skill = skillPoses.get(beat.pose.substring(1));

                if (skill == null)
                {
                    skill = skillPoses.get(beat.pose);
                }

                if (skill != null)
                {
                    /* 曲线运用：进入作者姿势的到达默认走 S 曲线（LLM 给了
                     * elastic/overshoot/snap 等意图时照旧） */
                    if ("linear".equals(pose.intent) || "hold".equals(pose.intent))
                    {
                        pose.intent = "ease_in_out";
                    }

                    /* 差异化：第 2、4、6 次出现整只镜像（左右交换 + Y/Z 轴
                     * 取反），每次再叠逐骨骼微变——同一姿势摆两遍也不复读 */
                    int seen = skillSeen.merge(beat.pose, 1, Integer::sum);
                    boolean mirror = seen % 2 == 0;

                    for (Map.Entry<String, mchorse.bbs_mod.utils.pose.PoseTransform> entry : skill.transforms.entrySet())
                    {
                        BoneChannel channel = new BoneChannel();

                        channel.bone = mirror ? mirroredBone(entry.getKey()) : entry.getKey();

                        float jx = mirror ? -1F : 1F;
                        float jy = 1F + 0.05F * ((seen - 1) % 3 - 1) * (0.6F + 0.4F * Math.abs(entry.getKey().hashCode() % 7) / 7F);

                        channel.x = entry.getValue().rotate.x;
                        channel.y = entry.getValue().rotate.y * jy;
                        channel.z = entry.getValue().rotate.z * jx;

                        /* 作者姿势完整套用：旋转 + 缩放 + 平移（t）——作者靠
                         * 平移摆造型（蹲下前倾、坐姿沉胯），丢 t 就走样 */
                        channel.values = new float[] {
                            channel.x, channel.y, channel.z,
                            entry.getValue().scale.x, entry.getValue().scale.y, entry.getValue().scale.z,
                            entry.getValue().translate.x * jx,
                            entry.getValue().translate.y * jy,
                            entry.getValue().translate.z * jx
                        };
                        pose.channels.add(channel);

                        /* 进载体（转回度数）：v2 计划里 @姿势之后的直写拍从
                         * 该造型自然延续——此前 @字符串姿势从不更新载体，
                         * "从该造型延续"只是提示词的一厢情愿 */
                        carried.put(channel.bone, new float[] {
                            (float) Math.toDegrees(channel.x), (float) Math.toDegrees(channel.y),
                            (float) Math.toDegrees(channel.z),
                            channel.values[3], channel.values[4], channel.values[5],
                            channel.values[6], channel.values[7], channel.values[8]
                        });
                    }

                    /* @姿势只摆造型、不重置全身：BBS 的 Pose 插值把键里没写
                     * 的骨骼当绑定姿势（REST），技能姿势往往只有几根骨骼——
                     * 其余已动画关节会在段间垂回零。把 carried 里未被技能
                     * 覆盖（含镜像对应侧）的骨骼并入本拍键 */
                    for (java.util.Map.Entry<String, float[]> entry : carried.entrySet())
                    {
                        String bone = entry.getKey();
                        String mirrorBone = mirroredBone(bone);
                        boolean covered = false;

                        for (BoneChannel existing : pose.channels)
                        {
                            if (existing.bone.equals(bone) || existing.bone.equals(mirrorBone))
                            {
                                covered = true;

                                break;
                            }
                        }

                        if (covered)
                        {
                            continue;
                        }

                        float[] v = entry.getValue();
                        BoneChannel channel = new BoneChannel();

                        channel.bone = bone;
                        channel.x = (float) Math.toRadians(v[0]);
                        channel.y = (float) Math.toRadians(v[1]);
                        channel.z = (float) Math.toRadians(v[2]);
                        channel.values = new float[] {channel.x, channel.y, channel.z, v[3], v[4], v[5], v[6], v[7], v[8]};
                        pose.channels.add(channel);
                    }

                    poses.add(pose);

                    continue;
                }

                /* 模型编造了技能库里没有的 @名字：退回同名基础姿势（@wave→wave），
                 * 再不行按未知姿势退化——绝不让一次生成整个炸掉 */
                if (PoseLibrary.get(beat.pose.substring(1)) != null)
                {
                    beat.pose = beat.pose.substring(1);
                }
            }

            /* get() 对未知名字返回 null：退化为空姿势（即 idle），只保留该拍的时序与意图 */
            Map<String, float[]> libraryPose = PoseLibrary.get(beat.pose);

            pose.rootY = PoseLibrary.ROOT_Y.getOrDefault(beat.pose, 0F);

            for (Map.Entry<String, float[]> entry : (libraryPose == null ? Map.<String, float[]>of() : libraryPose).entrySet())
            {
                BoneNameResolver.Resolution resolution = bones.resolved.get(entry.getKey());

                /* Optional bones (eyes) simply don't participate when unbound;
                 * core bones are guaranteed complete by the caller's check */
                if (resolution == null)
                {
                    continue;
                }

                BoneChannel channel = new BoneChannel();

                float scale = entry.getValue().length >= 6 ? 1F : amplitude; /* 眨眼的缩放分量不吃幅度 */

                /* PoseLibrary 存角度，PoseTransform 期望弧度——在此转换 */
                float deg = (float) Math.PI / 180F;

                channel.bone = resolution.actual;
                channel.x = entry.getValue()[0] * scale * deg;
                channel.y = entry.getValue()[1] * scale * deg;
                channel.z = entry.getValue()[2] * scale * deg;
                channel.values = new float[] {
                    channel.x, channel.y, channel.z,
                    entry.getValue().length >= 6 ? entry.getValue()[3] : 1F,
                    entry.getValue().length >= 6 ? entry.getValue()[4] : 1F,
                    entry.getValue().length >= 6 ? entry.getValue()[5] : 1F
                };
                pose.channels.add(channel);
            }

            poses.add(pose);
        }

        /* 步幅变化（v1 姿势名路径）：交替给每一步 ±6% 的能量差（作者步态
         * 本身左右不对称），打破"每步一模一样"的机械感——只缩放旋转，
         * 不碰缩放/平移分量 */
        int stride = 0;

        for (KeyPose pose : poses)
        {
            if (!PoseLibrary.isWalk(pose.pose) && !"walk_pass".equals(pose.pose))
            {
                continue;
            }

            float factor = strideEnergy[stride % strideEnergy.length];

            if (!"walk_pass".equals(pose.pose))
            {
                stride++;
            }

            for (BoneChannel channel : pose.channels)
            {
                channel.x *= factor;
                channel.y *= factor;
                channel.z *= factor;

                if (channel.values.length >= 3)
                {
                    channel.values[0] *= factor;
                    channel.values[1] *= factor;
                    channel.values[2] *= factor;
                }
            }
        }

        return poses;
    }

    /**
     * Convert solved poses into channel writes on the given replay's track
     * container. POSE channels are created through the normal
     * {@code FormProperties.getOrCreate} path, so the commit is the M3
     * machinery end to end (one AI operation = one undo entry).
     */
    public static List<FrameCommitter.ChannelWrite> toChannelWrites(List<KeyPose> poses, FormProperties properties)
    {
        return toChannelWrites(poses, Map.of(), properties);
    }

    /** 骨骼组的到达延迟（占段长比例）：腿/躯干先行，头与手臂跟随。 */
    private static float delayOf(String bone)
    {
        String n = bone.toLowerCase();

        if (n.contains("head") || n.contains("headwear")) return 0.12F;
        if (n.contains("arm") || n.contains("elbow")) return 0.18F;
        if (n.contains("eye") || n.contains("brow")) return 0F;
        return 0F; /* 腿/躯干/锚点先行 */
    }

    private static float smoothstep(float t)
    {
        return t * t * (3F - 2F * t);
    }

    /**
     * 段间中点键烘焙：对每对相邻拍键，在段中点插入一帧——各骨骼的插值进度
     * = smoothstep(0.5 - delayOf(bone))，clamp 后 lerp 旋转与缩放。某个骨骼
     * 只在一侧存在时保持原值（出现/消失不补间）。中点键继承后拍的到达意图。
     */
    private static void bakeStagger(List<EditPatch.KeyWrite> keys, List<KeyPose> poses)
    {
        if (keys.size() < 2)
        {
            return;
        }

        List<EditPatch.KeyWrite> baked = new ArrayList<>();
        baked.add(keys.get(0));

        for (int i = 1; i < keys.size(); i++)
        {
            EditPatch.KeyWrite prev = keys.get(i - 1);
            EditPatch.KeyWrite next = keys.get(i);

            mchorse.bbs_mod.utils.pose.Pose a = prev.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pa ? pa : null;
            mchorse.bbs_mod.utils.pose.Pose b = next.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pb ? pb : null;

            /* 连续直写段（走路等循环运动）不插错帧中点——纯 S 曲线段更顺滑；
             * 注意只跳过中点生成，键本身必须保留 */
            boolean directPair = poses != null && i - 1 < poses.size() && i < poses.size()
                && poses.get(i - 1).direct && poses.get(i).direct;

            /* 短段（<4 tick）不插中点键：作者节拍本身 2~3 tick 一拍且
             * 自带 timing，中点重定时反而扭曲；只有更长的自由创作段
             * （4~8 tick）才需要跟随感补间 */
            if (!directPair && a != null && b != null && next.tick - prev.tick >= 4F)
            {
                float d = next.tick - prev.tick;
                mchorse.bbs_mod.utils.pose.Pose mid = new mchorse.bbs_mod.utils.pose.Pose();

                for (String name : union(a.transforms.keySet(), b.transforms.keySet()))
                {
                    mchorse.bbs_mod.utils.pose.PoseTransform ta = a.transforms.get(name);
                    mchorse.bbs_mod.utils.pose.PoseTransform tb = b.transforms.get(name);

                    if (ta == null || tb == null)
                    {
                        mchorse.bbs_mod.utils.pose.PoseTransform kept = mid.getOrCreate(name);

                        kept.copy(ta != null ? ta : tb);

                        continue;
                    }

                    float progress = smoothstep(clamp01(0.5F - delayOf(name)));
                    mchorse.bbs_mod.utils.pose.PoseTransform t = mid.getOrCreate(name);

                    t.rotate.set(
                        ta.rotate.x + (tb.rotate.x - ta.rotate.x) * progress,
                        ta.rotate.y + (tb.rotate.y - ta.rotate.y) * progress,
                        ta.rotate.z + (tb.rotate.z - ta.rotate.z) * progress);
                    t.scale.set(
                        ta.scale.x + (tb.scale.x - ta.scale.x) * progress,
                        ta.scale.y + (tb.scale.y - ta.scale.y) * progress,
                        ta.scale.z + (tb.scale.z - ta.scale.z) * progress);
                }

                EditPatch.KeyWrite midKey = new EditPatch.KeyWrite();

                midKey.tick = prev.tick + d * 0.5F;
                midKey.interpolation = "sine_inout";
                midKey.intent = next.intent;
                midKey.fullValue = mid;
                baked.add(midKey);
            }

            baked.add(next);
        }

        keys.clear();
        keys.addAll(baked);
    }

    private static float clamp01(float v)
    {
        return v < 0F ? 0F : v > 1F ? 1F : v;
    }

    /**
     * 呼吸层：两端都是 v2 直写拍（且都不在步态跨度内）的段，段长 ≥10 tick
     * 时在中点插一个呼吸微动键——torso 侧倾 ±0.8°、骨盆反向 ±0.3°、头微转
     * ±0.5°，按段中点的绝对 tick 取正弦相位，相邻段的呼吸自然连续。只在
     * 两端姿势都含该骨骼时叠加（缺骨骼的段保持原值，不做无中生有）。
     */
    private static void bakeBreathing(List<EditPatch.KeyWrite> keys, List<KeyPose> poses)
    {
        if (keys.size() < 2 || poses == null)
        {
            return;
        }

        List<EditPatch.KeyWrite> baked = new ArrayList<>();
        baked.add(keys.get(0));

        for (int i = 1; i < keys.size(); i++)
        {
            EditPatch.KeyWrite prev = keys.get(i - 1);
            EditPatch.KeyWrite next = keys.get(i);

            boolean bothDirectNonGait = i - 1 < poses.size() && i < poses.size()
                && poses.get(i - 1).direct && !poses.get(i - 1).gait
                && poses.get(i).direct && !poses.get(i).gait;

            mchorse.bbs_mod.utils.pose.Pose a = prev.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pa ? pa : null;
            mchorse.bbs_mod.utils.pose.Pose b = next.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pb ? pb : null;

            if (bothDirectNonGait && a != null && b != null && next.tick - prev.tick >= 10F)
            {
                mchorse.bbs_mod.utils.pose.Pose mid = new mchorse.bbs_mod.utils.pose.Pose();

                for (String name : union(a.transforms.keySet(), b.transforms.keySet()))
                {
                    mchorse.bbs_mod.utils.pose.PoseTransform ta = a.transforms.get(name);
                    mchorse.bbs_mod.utils.pose.PoseTransform tb = b.transforms.get(name);

                    if (ta == null || tb == null)
                    {
                        mid.getOrCreate(name).copy(ta != null ? ta : tb);

                        continue;
                    }

                    mchorse.bbs_mod.utils.pose.PoseTransform t = mid.getOrCreate(name);

                    t.rotate.set(
                        (ta.rotate.x + tb.rotate.x) * 0.5F,
                        (ta.rotate.y + tb.rotate.y) * 0.5F,
                        (ta.rotate.z + tb.rotate.z) * 0.5F);
                    t.scale.set(
                        (ta.scale.x + tb.scale.x) * 0.5F,
                        (ta.scale.y + tb.scale.y) * 0.5F,
                        (ta.scale.z + tb.scale.z) * 0.5F);
                    t.translate.set(
                        (ta.translate.x + tb.translate.x) * 0.5F,
                        (ta.translate.y + tb.translate.y) * 0.5F,
                        (ta.translate.z + tb.translate.z) * 0.5F);
                }

                /* ±3.5°：MC 视角下肉眼可见的呼吸，且大部分相位点能在
                 * 1.5° 抽稀容差下存活（过零点附近被删是正常简化） */
                float phase = (prev.tick + next.tick) * 0.5F;
                float sway = (float) Math.sin(phase * 0.4D) * (float) Math.toRadians(3.5F);

                for (Map.Entry<String, Float> target : Map.of(
                    "torso", sway,
                    "torso_lower", -sway * 0.4F,
                    "head", sway * 0.6F).entrySet())
                {
                    mchorse.bbs_mod.utils.pose.PoseTransform t = mid.transforms.get(target.getKey());

                    if (t != null)
                    {
                        t.rotate.y += target.getValue();
                    }
                }

                EditPatch.KeyWrite midKey = new EditPatch.KeyWrite();

                midKey.tick = (prev.tick + next.tick) * 0.5F;
                midKey.interpolation = "sine_inout";
                midKey.intent = next.intent == null ? "ease_in_out" : next.intent;
                midKey.fullValue = mid;
                baked.add(midKey);
            }

            baked.add(next);
        }

        keys.clear();
        keys.addAll(baked);
    }

    private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b)
    {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(a);

        out.addAll(b);

        return out;
    }

    /**
     * Whole-pose solve: one keyframe per beat on the form's {@code pose}
     * property track — the track the user actually sees and edits (the
     * per-bone {@code pose.bones.*} channels don't even show up as rows for
     * many models). The key value is a full Pose: every solved bone's
     * rotate (+ scale for blink), keyed with the beat's arrival interpolation.
     *
     * <p>Ends: the union of the bone-end paths — root "" and/or body-part
     * ends — each end gets its own pose track keys for the bones it owns.</p>
     */
    public static List<FrameCommitter.ChannelWrite> toPoseTrackWrites(List<KeyPose> poses, Map<String, List<String>> boneEnds, FormProperties properties, Form root)
    {
        /* 哪些端需要写：所有已解析骨骼的归属端并集 */
        java.util.Set<String> ends = new java.util.LinkedHashSet<>();

        for (KeyPose pose : poses)
        {
            for (BoneChannel channel : pose.channels)
            {
                List<String> paths = boneEnds.getOrDefault(channel.bone, List.of(""));

                ends.addAll(paths.isEmpty() ? List.of("") : paths);
            }
        }

        if (ends.isEmpty())
        {
            ends.add("");
        }

        List<FrameCommitter.ChannelWrite> writes = new ArrayList<>();

        for (String end : ends)
        {
            mchorse.bbs_mod.film.replays.tracks.TrackId trackId =
                mchorse.bbs_mod.film.replays.tracks.TrackId.property(end, mchorse.bbs_mod.film.replays.FormProperties.POSE_PROPERTY);
            KeyframeChannel channel = properties.getOrCreate(root, trackId);

            FrameCommitter.ChannelWrite write = new FrameCommitter.ChannelWrite(trackId.toKey(), channel, 0F);

            write.poseChannel = true;
            write.replace = true;

            for (KeyPose pose : poses)
            {
                mchorse.bbs_mod.utils.pose.Pose value = new mchorse.bbs_mod.utils.pose.Pose();

                for (BoneChannel channelData : pose.channels)
                {
                    List<String> paths = boneEnds.getOrDefault(channelData.bone, List.of(""));

                    if (!paths.isEmpty() && !paths.contains(end))
                    {
                        continue;
                    }

                    mchorse.bbs_mod.utils.pose.PoseTransform transform = value.getOrCreate(channelData.bone);

                    /* channelData 已是弧度（solve() 里转过一次）——这里不能
                     * 再 toRadians，否则 16° 的抬腿只剩 0.28°，等于没动 */
                    transform.rotate.set(channelData.x, channelData.y, channelData.z);

                    if (channelData.values.length >= 6)
                    {
                        transform.scale.set(channelData.values[3], channelData.values[4], channelData.values[5]);
                    }

                    /* 作者姿势的平移分量（t）——造型的一部分 */
                    if (channelData.values.length >= 9)
                    {
                        transform.translate.set(channelData.values[6], channelData.values[7], channelData.values[8]);
                    }
                }

                EditPatch.KeyWrite key = new EditPatch.KeyWrite();

                key.tick = pose.tick;
                key.interpolation = Interpolations.LINEAR.getKey();
                key.intent = pose.intent;
                key.fullValue = value;
                write.keys.add(key);
            }

            /* 跟随/错帧烘焙（12 原则的 Overlapping Action）：每个拍间段插入一个
             * 中点键，各骨骼的到达进度按组别延迟（腿/躯干先行，手臂跟随，
             * 头再滞后）——L4 写出的就是带跟随感的成品，而非同拍同速的僵硬插值 */
            bakeStagger(write.keys, poses);

            /* 呼吸层（Puppeteer 后处理管线的确定性版）：非步态的直写段段长
             * ≥10 tick 时自动插一个带呼吸微动的中点键（torso 侧倾/骨盆反向/
             * 头微转，按绝对 tick 相位取正弦）——静止和慢动作不再是一块铁板，
             * 也不再指望 LLM 在每拍里写呼吸 */
            bakeBreathing(write.keys, poses);

            /* 到达意图落前一个键（与逐骨骼路径同一语义）——但 hold/linear
             * 在姿势真正变化时是灾难：CONST = 保持旧姿势到最后一刻再瞬移
             * （动作衔接顿挫的直接来源），LINEAR = 折角。姿势变了就强制
             * auto_clamped（切线连续，无逐段减速脉冲） */
            List<EditPatch.KeyWrite> keys = write.keys;

            for (int j = 1; j < keys.size(); j++)
            {
                String arrival = keys.get(j).intent;

                if (arrival == null)
                {
                    continue;
                }

                String interp = interpFor(arrival).getKey();

                if (("hold".equals(arrival) || "linear".equals(arrival))
                    && posesDiffer(keys.get(j - 1).fullValue, keys.get(j).fullValue))
                {
                    interp = "auto_clamped";
                }

                keys.get(j - 1).interpolation = interp;
            }

            /* 冗余键抽稀：连续三键值全等（carry-forward 的 hold 拍）删中间 */
            dedupeKeys(keys);

            /* 关键帧抽稀（Blender Decimate 思路）：模板节拍 2~3 tick 一个键
             * + 错帧中点 + 呼吸键叠加后，实际键距会压到 1 tick 上下——
             * auto_clamped 在密集噪声数据上切线振荡，动作抽搐。误差容差内
             * 贪心删键（旋转 1.5°/缩放平移 0.005 可感知阈值），打击拍豁免；
             * 删完剩下的稀疏键 + auto_clamped = 干净的平滑曲线 */
            simplifyPoseKeys(keys);

            /* 自动平滑：固定 cubic 段升级为原生 auto_clamped（曲线插件
             * “自动钳制”的 Blender 自动柄移植）——每根骨骼的切线由邻段
             * 斜率自动生成，极限处自动压平不过冲，无缝衔接用户手动微调 */
            for (EditPatch.KeyWrite key : keys)
            {
                if ("cubic_inout".equals(key.interpolation) || "cubic_out".equals(key.interpolation)
                    || "sine_inout".equals(key.interpolation))
                {
                    key.interpolation = "auto_clamped";
                }
            }

            writes.add(write);
        }

        return writes;
    }

    /** 三连等值键去重：中间键与两侧的整只 Pose 逐骨骼全等（±容差）时删除 */
    private static void dedupeKeys(List<EditPatch.KeyWrite> keys)
    {        for (int i = 1; i < keys.size() - 1; )
        {
            if (samePose(keys.get(i - 1).fullValue, keys.get(i).fullValue)
                && samePose(keys.get(i).fullValue, keys.get(i + 1).fullValue))
            {
                keys.remove(i);
            }
            else
            {
                i++;
            }
        }
    }

    /** 两键的整只 Pose 是否逐骨骼全等（旋转 0.06°、缩放/平移 0.0001 容差）
     * ——容差必须远小于呼吸微动的幅度，否则中点键会被当成等值键删掉 */
    private static boolean samePose(Object a, Object b)
    {
        if (!(a instanceof mchorse.bbs_mod.utils.pose.Pose pa)
            || !(b instanceof mchorse.bbs_mod.utils.pose.Pose pb)
            || pa.transforms.size() != pb.transforms.size())
        {
            return false;
        }

        for (Map.Entry<String, PoseTransform> entry : pa.transforms.entrySet())
        {
            PoseTransform tb = pb.transforms.get(entry.getKey());

            if (tb == null)
            {
                return false;
            }

            PoseTransform ta = entry.getValue();

            if (Math.abs(ta.rotate.x - tb.rotate.x) > 0.001F
                || Math.abs(ta.rotate.y - tb.rotate.y) > 0.001F
                || Math.abs(ta.rotate.z - tb.rotate.z) > 0.001F
                || Math.abs(ta.scale.x - tb.scale.x) > 0.0001F
                || Math.abs(ta.scale.y - tb.scale.y) > 0.0001F
                || Math.abs(ta.scale.z - tb.scale.z) > 0.0001F
                || Math.abs(ta.translate.x - tb.translate.x) > 0.0001F
                || Math.abs(ta.translate.y - tb.translate.y) > 0.0001F
                || Math.abs(ta.translate.z - tb.translate.z) > 0.0001F)
            {
                return false;
            }
        }

        return true;
    }

    /** 两键姿势是否有可感知差异（≥2° 任意轴）——hold 到达语义的守卫用 */
    private static boolean posesDiffer(Object a, Object b)
    {
        if (!(a instanceof mchorse.bbs_mod.utils.pose.Pose pa)
            || !(b instanceof mchorse.bbs_mod.utils.pose.Pose pb))
        {
            return true;
        }

        java.util.Set<String> union = new java.util.LinkedHashSet<>(pa.transforms.keySet());
        union.addAll(pb.transforms.keySet());

        for (String bone : union)
        {
            PoseTransform ta = pa.transforms.get(bone) == null ? new PoseTransform() : pa.transforms.get(bone);
            PoseTransform tb = pb.transforms.get(bone) == null ? new PoseTransform() : pb.transforms.get(bone);

            if (Math.abs(ta.rotate.x - tb.rotate.x) > 0.035F
                || Math.abs(ta.rotate.y - tb.rotate.y) > 0.035F
                || Math.abs(ta.rotate.z - tb.rotate.z) > 0.035F)
            {
                return true;
            }
        }

        return false;
    }

    /** 抽稀容差：旋转 1.5°（MC 风格动画的可感知下限）、缩放/平移 0.005 */
    private static final float SIMPLIFY_ROT_TOL = 1.5F;
    private static final float SIMPLIFY_LIN_TOL = 0.005F;

    /**
     * 关键帧抽稀：贪心删除——对每个中间键，评估"删掉它后用前后键平滑
     * 插值"与"原值"的最大逐骨骼偏差，低于可感知阈值就删。snap/impact
     * 打击拍永不删（发力瞬间是动作的灵魂）；循环到一轮无删除为止。
     * 首尾键恒保留。
     */
    private static void simplifyPoseKeys(List<EditPatch.KeyWrite> keys)
    {
        if (keys.size() <= 2)
        {
            return;
        }

        boolean removed = true;

        while (removed)
        {
            removed = false;

            for (int i = 1; i < keys.size() - 1; )
            {
                EditPatch.KeyWrite cur = keys.get(i);

                /* 打击拍是节奏骨架，抽稀绝不碰 */
                if ("snap".equals(cur.intent) || "impact".equals(cur.intent))
                {
                    i++;

                    continue;
                }

                EditPatch.KeyWrite prev = keys.get(i - 1);
                EditPatch.KeyWrite next = keys.get(i + 1);

                if (removalError(prev, cur, next) <= SIMPLIFY_ROT_TOL)
                {
                    keys.remove(i);
                    removed = true;

                    continue;
                }

                i++;
            }
        }
    }

    /**
     * 删除 cur 的运动偏差（度）：原曲线是 prev→cur→next 两段平滑插值，
     * 删后变成 prev→next 一段——逐采样点比较两条曲线的姿态差，取最大
     * 逐骨骼旋转差（度）与缩放越界。平滑插值用 smoothstep 近似（与
     * cubic_inout/auto_clamped 的中段行为一致）。
     */
    private static float removalError(EditPatch.KeyWrite prev, EditPatch.KeyWrite cur, EditPatch.KeyWrite next)
    {
        if (!(prev.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pa)
            || !(cur.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose cb)
            || !(next.fullValue instanceof mchorse.bbs_mod.utils.pose.Pose pb)
            || cur.tick <= prev.tick || next.tick <= cur.tick)
        {
            return Float.MAX_VALUE;
        }

        float span = next.tick - prev.tick;
        float cu = (cur.tick - prev.tick) / span;
        float maxError = 0F;

        for (int s = 1; s <= 4; s++)
        {
            float u = s / 5F;

            /* 前段：绝对 tick = prev + (cur-prev)*u，原值走 prev→cur，
             * 简化值走 prev→next 的同一绝对 tick（=全跨度的 u*cu 处） */
            mchorse.bbs_mod.utils.pose.Pose orig = lerpPose(pa, cb, smoothstep(u));
            mchorse.bbs_mod.utils.pose.Pose simp = lerpPose(pa, pb, smoothstep(u * cu));

            maxError = Math.max(maxError, poseDiff(orig, simp));

            /* 后段：绝对 tick = cur + (next-cur)*u */
            orig = lerpPose(cb, pb, smoothstep(u));
            simp = lerpPose(pa, pb, smoothstep(cu + (1F - cu) * u));

            maxError = Math.max(maxError, poseDiff(orig, simp));
        }

        return maxError;
    }

    private static mchorse.bbs_mod.utils.pose.Pose lerpPose(
        mchorse.bbs_mod.utils.pose.Pose a, mchorse.bbs_mod.utils.pose.Pose b, float u)
    {
        mchorse.bbs_mod.utils.pose.Pose out = new mchorse.bbs_mod.utils.pose.Pose();

        for (Map.Entry<String, PoseTransform> entry : a.transforms.entrySet())
        {
            PoseTransform tb = b.transforms.get(entry.getKey());
            PoseTransform ta = entry.getValue();
            PoseTransform t = out.getOrCreate(entry.getKey());

            if (tb == null)
            {
                t.copy(ta);

                continue;
            }

            t.rotate.set(
                ta.rotate.x + (tb.rotate.x - ta.rotate.x) * u,
                ta.rotate.y + (tb.rotate.y - ta.rotate.y) * u,
                ta.rotate.z + (tb.rotate.z - ta.rotate.z) * u);
            t.scale.set(
                ta.scale.x + (tb.scale.x - ta.scale.x) * u,
                ta.scale.y + (tb.scale.y - ta.scale.y) * u,
                ta.scale.z + (tb.scale.z - ta.scale.z) * u);
            t.translate.set(
                ta.translate.x + (tb.translate.x - ta.translate.x) * u,
                ta.translate.y + (tb.translate.y - ta.translate.y) * u,
                ta.translate.z + (tb.translate.z - ta.translate.z) * u);
        }

        return out;
    }

    /** 两姿势最大逐骨骼旋转差（度）；缩放/平移越界直接判超差 */
    private static float poseDiff(mchorse.bbs_mod.utils.pose.Pose a, mchorse.bbs_mod.utils.pose.Pose b)
    {
        float maxDeg = 0F;

        for (Map.Entry<String, PoseTransform> entry : a.transforms.entrySet())
        {
            PoseTransform ta = entry.getValue();
            PoseTransform tb = b.transforms.get(entry.getKey());

            if (tb == null)
            {
                tb = new PoseTransform();
            }

            float dx = ta.rotate.x - tb.rotate.x;
            float dy = ta.rotate.y - tb.rotate.y;
            float dz = ta.rotate.z - tb.rotate.z;

            maxDeg = Math.max(maxDeg, (float) Math.toDegrees(Math.sqrt(dx * dx + dy * dy + dz * dz)));

            if (Math.abs(ta.scale.x - tb.scale.x) > SIMPLIFY_LIN_TOL
                || Math.abs(ta.scale.y - tb.scale.y) > SIMPLIFY_LIN_TOL
                || Math.abs(ta.translate.x - tb.translate.x) > SIMPLIFY_LIN_TOL
                || Math.abs(ta.translate.y - tb.translate.y) > SIMPLIFY_LIN_TOL
                || Math.abs(ta.translate.z - tb.translate.z) > SIMPLIFY_LIN_TOL)
            {
                maxDeg = Math.max(maxDeg, SIMPLIFY_ROT_TOL + 1F);
            }
        }

        return maxDeg;
    }

    /**
     * Two-ended solve: write each bone's keys to every form path that owns a
     * rig with that bone - the replay root ("") and/or body-part ends - so
     * models nesting their rig under a part (Star 3.6) animate too. Bones with
     * no discovered end fall back to the root, the historical behavior.
     */
    public static List<FrameCommitter.ChannelWrite> toChannelWrites(List<KeyPose> poses, Map<String, List<String>> boneEnds, FormProperties properties)
    {
        Map<String, FrameCommitter.ChannelWrite> writes = new java.util.LinkedHashMap<>();

        for (KeyPose pose : poses)
        {
            for (BoneChannel channel : pose.channels)
            {
                List<String> ends = boneEnds.getOrDefault(channel.bone, List.of(""));
                List<String> paths = ends.isEmpty() ? List.of("") : ends;

                for (String path : paths)
                {
                    TrackId id = TrackId.bone(path, channel.bone);
                    String trackId = id.toKey();

                    FrameCommitter.ChannelWrite write = writes.get(trackId);

                    if (write == null)
                    {
                        write = new FrameCommitter.ChannelWrite(trackId, properties.getOrCreate(null, id), 0F);

                        write.poseChannel = true;
                        writes.put(trackId, write);
                    }

                    EditPatch.KeyWrite key = new EditPatch.KeyWrite();

                    key.tick = pose.tick;
                    key.interpolation = Interpolations.LINEAR.getKey();
                    key.intent = pose.intent;

                    PoseTransform transform = new PoseTransform();

                    transform.rotate.set(channel.x, channel.y, channel.z);

                    /* Six-float channels carry scale after rotation - the blink
                     * pose squashes eye bones on Y; nine-float adds translate */
                    if (channel.values.length >= 6)
                    {
                        transform.scale.set(channel.values[3], channel.values[4], channel.values[5]);
                    }

                    if (channel.values.length >= 9)
                    {
                        transform.translate.set(channel.values[6], channel.values[7], channel.values[8]);
                    }

                    key.poseValue = transform;
                    write.keys.add(key);
                }
            }
        }

        /* BBS 的键插值作用于「离开该键」的段：第 i 拍的到达意图落到第 i-1 个
         * 键上；末键之后没有段，保持原样 */
        for (FrameCommitter.ChannelWrite write : writes.values())
        {
            List<EditPatch.KeyWrite> keys = write.keys;

            for (int j = 1; j < keys.size(); j++)
            {
                String arrival = keys.get(j).intent;

                if (arrival != null)
                {
                    keys.get(j - 1).interpolation = interpFor(arrival).getKey();
                }
            }
        }

        return new ArrayList<>(writes.values());
    }
}
