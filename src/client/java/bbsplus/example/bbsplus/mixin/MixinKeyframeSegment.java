package bbsplus.example.bbsplus.mixin;

import bbsplus.example.bbsplus.pose.PoseComponent;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.BezierUtils;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.PoseTransformKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.TransformKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes per-component bezier handles on {@code transform} and
 * {@code pose_transform} keyframes actually affect playback.
 *
 * <p>BBS-FS routes both through {@code Transform.lerp}, which uses the generic
 * interpolation path where {@code BEZIER} degrades to cubic-hermite and handle
 * data ({@code lx/ly/rx/ry} per axis) is ignored. We post-process
 * {@link KeyframeSegment#createInterpolated()} to recompute each transform
 * component with {@link BezierUtils} when the
 * keyframe uses BEZIER interpolation. Non-bezier keyframes and all other
 * factory types are left completely untouched.</p>
 */
@Mixin(KeyframeSegment.class)
public abstract class MixinKeyframeSegment
{
    @Inject(method = "createInterpolated", at = @At("RETURN"), cancellable = true)
    private void bbsplus$transformBezier(CallbackInfoReturnable<Object> cir)
    {
        KeyframeSegment<?> self = (KeyframeSegment<?>) (Object) this;
        Keyframe<?> a = self.a;
        Keyframe<?> b = self.b;

        if (a == null || b == null || a == b)
        {
            return;
        }

        IKeyframeFactory<?> factory = a.getFactory();

        if (!(factory instanceof PoseTransformKeyframeFactory)
            && !(factory instanceof TransformKeyframeFactory))
        {
            return;
        }

        if (a.getInterpolation().getInterp() != Interpolations.BEZIER)
        {
            return;
        }

        Object value = cir.getReturnValue();

        if (!(value instanceof Transform result)
            || !(a.getValue() instanceof Transform av)
            || !(b.getValue() instanceof Transform bv))
        {
            return;
        }

        float x = self.x;

        for (PoseComponent component : PoseComponent.VALUES)
        {
            double interpolated = BezierUtils.get(
                component.get(av), component.get(bv),
                a.getTick(), b.getTick(),
                a.rx, a.ry,
                b.lx, b.ly,
                x
            );

            component.set(result, (float) interpolated);
        }

        cir.setReturnValue(result);
    }
}
