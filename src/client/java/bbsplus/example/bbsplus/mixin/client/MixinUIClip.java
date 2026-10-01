package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.UIClipViewportKeeper;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops undo/redo from resetting the embedded envelope keyframe editor's viewport
 * in any clip (the base {@code UIClip.applyUndoData} re-embeds the envelope editor
 * and calls {@code resetView()}).
 */
@Mixin(UIClip.class)
public abstract class MixinUIClip
{
    @Inject(method = "applyUndoData", at = @At("HEAD"))
    private void bbsplus$capture(MapType data, CallbackInfo ci)
    {
        UIClipViewportKeeper.capture(((UIClip<?>) (Object) this).envelope.channel);
    }

    @Inject(method = "applyUndoData", at = @At("TAIL"))
    private void bbsplus$restore(MapType data, CallbackInfo ci)
    {
        UIClipViewportKeeper.restore(((UIClip<?>) (Object) this).envelope.channel);
    }
}
