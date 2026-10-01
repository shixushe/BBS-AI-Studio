package bbsplus.example.bbsplus.mixin;

import mchorse.bbs_mod.utils.keyframes.factories.PoseTransformKeyframeFactory;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Hides the "ghost" keyframes that BBS renders on the value=0 line when a
 * pose-bone track is opened in the curve editor.
 *
 * <p>Our {@link bbsplus.example.bbsplus.client.pose.UIPoseTransformKeyframeGraph}
 * draws the per-component curves itself and never uses {@code getY()}. BBS's own
 * single-channel value rendering, however, plots each keyframe at
 * {@code factory.getY(value)} — which for a {@code PoseTransform} defaults to 0,
 * so every keyframe stacks on the y=0 line, glows on hover, and clutters the
 * view. By overriding {@code getY} to a far off-screen value, those base-rendered
 * points (and their hover hit-test) move out of sight, while our own curves —
 * which read the components directly — are unaffected. The dope-sheet view does
 * not use {@code getY} for positioning, so it is unaffected too.</p>
 */
@Mixin(PoseTransformKeyframeFactory.class)
public abstract class MixinPoseTransformKeyframeFactory
{
    /** Far outside any realistic keyframe value / zoom range. */
    private static final double BBSPLUS_OFFSCREEN = 1.0E7D;

    public double getY(PoseTransform value)
    {
        return BBSPLUS_OFFSCREEN;
    }
}
