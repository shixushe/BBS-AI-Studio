package mchorse.bbs_mod.ai.pose;

import mchorse.bbs_mod.ai.plan.AnimationPlan;

import java.util.List;

/**
 * L2 motion layer: turns an {@link AnimationPlan}'s beats into blocking key
 * pose frames on real bone channels - a few extreme poses per beat, never
 * per-frame generation. Bone names must come from the actual
 * {@code ModelForm.bones} enumeration of the target model; an unknown name is
 * an error, never a guess (copilot spec section 3 L2).
 *
 * <p>Skeleton milestone (section 13.2): the contract below is the seam the
 * M5 implementation fills. {@link BoneNameResolver} lands with it.</p>
 */
public class PoseSolver
{
    /** One solved extreme pose: bone channel -> rotation/translation offsets. */
    public static class KeyPose
    {
        public int tick;
        public String phase;
        public String pose;
        public final List<BoneChannel> channels = new java.util.ArrayList<>();
    }

    /** A single bone's contribution to a pose, in degrees / model units. */
    public static class BoneChannel
    {
        public String bone;
        public float x;
        public float y;
        public float z;
    }

    /**
     * Solve blocking poses for a plan against the given bone inventory.
     * Not implemented yet (M5): the inventory source - reading real bone names
     * from a loaded form instead of assuming a humanoid - is being wired first.
     */
    public static List<KeyPose> solve(AnimationPlan plan, List<String> availableBones)
    {
        throw new UnsupportedOperationException("PoseSolver lands in milestone M5 (blocking pose frames)");
    }
}
