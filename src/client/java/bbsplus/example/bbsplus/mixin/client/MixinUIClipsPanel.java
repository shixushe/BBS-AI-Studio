package bbsplus.example.bbsplus.mixin.client;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the clip timeline's viewport fixed across undo/redo in the camera and
 * action editors.
 *
 * <p>{@code UIClipsPanel.applyUndoData} restores the timeline's horizontal scale
 * and vertical scroll from the snapshot taken when the edit was made, so undoing
 * a change snaps the view back to where it was at edit time — jarring when the
 * affected clip/keyframe is off-screen. We capture the current viewport before
 * the undo runs and restore it afterwards, so undo only reverts data, never the
 * view.</p>
 */
@Mixin(UIClipsPanel.class)
public abstract class MixinUIClipsPanel
{
    @Shadow private UIClip panel;

    @Unique private double bbsplus$xMin, bbsplus$xMax, bbsplus$scroll;
    @Unique private boolean bbsplus$hasView;

    @Inject(method = "applyUndoData", at = @At("HEAD"))
    private void bbsplus$captureView(MapType data, CallbackInfo ci)
    {
        UIClipsPanel self = (UIClipsPanel) (Object) this;

        this.bbsplus$xMin = self.clips.getXAxis().getMinValue();
        this.bbsplus$xMax = self.clips.getXAxis().getMaxValue();
        this.bbsplus$scroll = self.clips.vertical.getScroll();
        this.bbsplus$hasView = this.bbsplus$xMin < this.bbsplus$xMax;
    }

    @Inject(method = "applyUndoData", at = @At("TAIL"))
    private void bbsplus$restoreView(MapType data, CallbackInfo ci)
    {
        if (!this.bbsplus$hasView)
        {
            return;
        }

        UIClipsPanel self = (UIClipsPanel) (Object) this;

        self.clips.getXAxis().view(this.bbsplus$xMin, this.bbsplus$xMax);
        self.clips.vertical.setScroll(this.bbsplus$scroll);
        self.clips.vertical.updateTarget();

        this.bbsplus$hasView = false;
    }
}
