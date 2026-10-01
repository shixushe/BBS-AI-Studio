package bbsplus.example.bbsplus.mixin;

import mchorse.bbs_mod.utils.keyframes.factories.TransformKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Same as {@link MixinPoseTransformKeyframeFactory} but for plain transform
 * tracks: hides BBS's ghost keyframes (rendered at the y=0 line via the
 * default {@code getY()}) when the track is opened in the curve editor.
 */
@Mixin(TransformKeyframeFactory.class)
public abstract class MixinTransformKeyframeFactory
{
    private static final double BBSPLUS_OFFSCREEN = 1.0E7D;

    public double getY(Transform value)
    {
        return BBSPLUS_OFFSCREEN;
    }
}
