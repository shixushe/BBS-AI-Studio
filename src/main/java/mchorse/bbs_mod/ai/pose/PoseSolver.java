package mchorse.bbs_mod.ai.pose;

import mchorse.bbs_mod.ai.commit.EditPatch;
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
        public final List<BoneChannel> channels = new ArrayList<>();
    }

    /** A single bone's contribution to a pose, in degrees. */
    public static class BoneChannel
    {
        public String bone;
        public float x;
        public float y;
        public float z;
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

            for (Map.Entry<String, float[]> entry : PoseLibrary.get(beat.pose).entrySet())
            {
                BoneNameResolver.Resolution resolution = bones.resolved.get(entry.getKey());
                BoneChannel channel = new BoneChannel();

                channel.bone = resolution.actual;
                channel.x = entry.getValue()[0];
                channel.y = entry.getValue()[1];
                channel.z = entry.getValue()[2];
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
        Map<String, FrameCommitter.ChannelWrite> writes = new java.util.LinkedHashMap<>();

        for (KeyPose pose : poses)
        {
            for (BoneChannel channel : pose.channels)
            {
                String trackId = TrackId.BONE_PREFIX + channel.bone;

                FrameCommitter.ChannelWrite write = writes.get(trackId);

                if (write == null)
                {
                    write = new FrameCommitter.ChannelWrite(trackId, properties.getOrCreate(null, TrackId.parse(trackId)), 0F);

                    write.poseChannel = true;
                    writes.put(trackId, write);
                }

                EditPatch.KeyWrite key = new EditPatch.KeyWrite();

                key.tick = pose.tick;
                key.interpolation = Interpolations.LINEAR.getKey();

                PoseTransform transform = new PoseTransform();

                transform.rotate.set(channel.x, channel.y, channel.z);
                key.poseValue = transform;
                write.keys.add(key);
            }
        }

        return new ArrayList<>(writes.values());
    }
}
