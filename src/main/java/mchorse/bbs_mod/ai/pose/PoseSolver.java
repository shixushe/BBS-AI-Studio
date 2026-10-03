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

                channel.bone = resolution.actual;
                channel.x = entry.getValue()[0];
                channel.y = entry.getValue()[1];
                channel.z = entry.getValue()[2];
                channel.values = entry.getValue();
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

                    transform.rotate.set(channelData.x, channelData.y, channelData.z);

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
