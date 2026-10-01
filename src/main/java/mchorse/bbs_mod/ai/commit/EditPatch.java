package mchorse.bbs_mod.ai.commit;

import java.util.ArrayList;
import java.util.List;

/**
 * L4's input: a fully local, fully explicit description of what should land on
 * the tracks. Produced by this codebase (from L2 poses and L3 polished
 * curves), never by the language model - the model's output stopped at
 * semantic labels several layers ago.
 *
 * <p>Shape follows the copilot spec section 4.3. Ticks are in the target
 * replay's own timeline space; handles are in the measured BBS units (ticks
 * and value offsets, see {@code CurvePolisher}).</p>
 */
public class EditPatch
{
    public final List<TrackWrite> tracks = new ArrayList<>();

    /** One track's writes, addressed the way films address tracks. */
    public static class TrackWrite
    {
        /** TrackId string form, e.g. {@code pose.bones.head} or {@code texture.materials.*}. */
        public final String trackId;

        public final List<KeyWrite> keys = new ArrayList<>();

        public TrackWrite(String trackId)
        {
            this.trackId = trackId;
        }
    }

    /**
     * One keyframe's complete target state. Numeric channels only for now -
     * pose-channel writes arrive with the M5 PoseSolver and extend this shape.
     */
    public static class KeyWrite
    {
        public float tick;
        public float value;
        /** Interpolation registry key ({@code Interpolations.MAP}); null/unknown keeps the existing one. */
        public String interpolation;
        public float lx;
        public float ly;
        public float rx;
        public float ry;
        public float duration;
        public float motionShift;

        /**
         * Direct value object for non-numeric channels (a {@code PoseTransform}
         * for bone tracks, M5). When set, {@code value} is ignored and the
         * object goes to the channel untouched.
         */
        public Object poseValue;

        public KeyWrite tick(float tick)
        {
            this.tick = tick;

            return this;
        }

        public KeyWrite value(float value)
        {
            this.value = value;

            return this;
        }

        public KeyWrite interp(String interpolation, float lx, float ly, float rx, float ry)
        {
            this.interpolation = interpolation;
            this.lx = lx;
            this.ly = ly;
            this.rx = rx;
            this.ry = ry;

            return this;
        }
    }
}
