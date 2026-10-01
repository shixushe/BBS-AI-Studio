package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.UIClipViewportKeeper;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.film.clips.UICurveClip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops undo/redo from resetting the embedded keyframe editor's viewport in the
 * curve camera clip (same {@code resetView()} issue as the keyframe clip).
 */
@Mixin(UICurveClip.class)
public abstract class MixinUICurveClip
{
    @Inject(method = "applyUndoData", at = @At("HEAD"))
    private void bbsplus$capture(MapType data, CallbackInfo ci)
    {
        UIClipViewportKeeper.capture(((UICurveClip) (Object) this).keyframes);
    }

    @Inject(method = "applyUndoData", at = @At("TAIL"))
    private void bbsplus$restore(MapType data, CallbackInfo ci)
    {
        UIClipViewportKeeper.restore(((UICurveClip) (Object) this).keyframes);
    }
}
