package mchorse.bbs_mod.ai.pose;

import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
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

        /** Values straight from the library entry: rotation [0..2], optional scale [3..5]. */
        public float[] values = new float[] {0F, 0F, 0F};
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

        List<String> missing = PoseLibrary.missing(plan.beats.stream().map(b -> b.pose).distinct().toList());

        if (!missing.isEmpty())
        {
            throw new IllegalArgumentException("Plan uses poses the library does not cover: " + missing);
        }

        List<KeyPose> poses = new ArrayList<>();

        for (AnimationPlan.Beat beat : plan.beats)
        {
            KeyPose pose = new KeyPose();

            pose.tick = beat.tick;
            pose.phase = beat.phase;
            pose.pose = beat.pose;
            pose.intent = beat.intents == null || beat.intents.isEmpty() ? "linear" : beat.intents.get(0).name().toLowerCase();
            pose.rootY = PoseLibrary.ROOT_Y.getOrDefault(beat.pose, 0F);

            /* @ 技能姿势：作者姿态整只替换，不吃幅度 */
            if (beat.pose.startsWith("@"))
            {
                mchorse.bbs_mod.utils.pose.Pose skill = skillPoses.get(beat.pose);

                if (skill != null)
                {
                    for (Map.Entry<String, mchorse.bbs_mod.utils.pose.PoseTransform> entry : skill.transforms.entrySet())
                    {
                        BoneChannel channel = new BoneChannel();

                        channel.bone = entry.getKey();
                        channel.x = entry.getValue().rotate.x;
                        channel.y = entry.getValue().rotate.y;
                        channel.z = entry.getValue().rotate.z;
                        channel.values = new float[] {channel.x, channel.y, channel.z,
                            entry.getValue().scale.x, entry.getValue().scale.y, entry.getValue().scale.z};
                        pose.channels.add(channel);
                    }

                    poses.add(pose);

                    continue;
                }
            }

            for (Map.Entry<String, float[]> entry : PoseLibrary.get(beat.pose).entrySet())
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
    private static void bakeStagger(List<EditPatch.KeyWrite> keys)
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

            if (a != null && b != null && next.tick > prev.tick)
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

                    /* 库姿态存角度，Pose 期望弧度 */
                    transform.rotate.set(
                        (float) Math.toRadians(channelData.x),
                        (float) Math.toRadians(channelData.y),
                        (float) Math.toRadians(channelData.z));

                    if (channelData.values.length >= 6)
                    {
                        transform.scale.set(channelData.values[3], channelData.values[4], channelData.values[5]);
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
            bakeStagger(write.keys);

            /* 到达意图落前一个键（与逐骨骼路径同一语义） */
            List<EditPatch.KeyWrite> keys = write.keys;

            for (int j = 1; j < keys.size(); j++)
            {
                String arrival = keys.get(j).intent;

                if (arrival != null)
                {
                    keys.get(j - 1).interpolation = interpFor(arrival).getKey();
                }
            }

            writes.add(write);
        }

        return writes;
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
                     * pose squashes eye bones on Y */
                    if (channel.values.length >= 6)
                    {
                        transform.scale.set(channel.values[3], channel.values[4], channel.values[5]);
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
