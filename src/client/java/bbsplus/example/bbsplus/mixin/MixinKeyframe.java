package bbsplus.example.bbsplus.mixin;

import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables motion-shift for BEZIER keyframes.
 *
 * <p>BEZIER keyframes already provide full timing control through their
 * left/right interpolation handles, so the separate motion-shift trackpad
 * and drag handle are hidden to avoid conflicting double time-remapping.</p>
 */
@Mixin(Keyframe.class)
public abstract class MixinKeyframe
{
    @Shadow public abstract Interpolation getInterpolation();

    @Inject(method = "supportsMotionShift", at = @At("HEAD"), cancellable = true)
    private void bbsplus$disableMotionShiftForBezier(CallbackInfoReturnable<Boolean> cir)
    {
        if (this.getInterpolation().getInterp() == Interpolations.BEZIER)
        {
            cir.setReturnValue(false);
        }
    }
}
