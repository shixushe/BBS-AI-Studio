package bbsplus.example.bbsplus.mixin.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeMotionShift;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the motion-shift drag handle for BEZIER keyframes inside the
 * curve editor. Works in tandem with {@code MixinKeyframe.supportsMotionShift()}
 * to guarantee the handle never appears for BEZIER segments.
 */
@Mixin(UIKeyframeMotionShift.class)
public abstract class MixinUIKeyframeMotionShift
{
    @Inject(method = "visibleHandle", at = @At("HEAD"), cancellable = true)
    private void bbsplus$hideMotionShiftForBezier(
        UIKeyframeSheet sheet, Keyframe<?> key, Keyframe<?> next, CallbackInfoReturnable cir)
    {
        if (key.getInterpolation().getInterp() == Interpolations.BEZIER)
        {
            cir.setReturnValue(null);
        }
    }
}
