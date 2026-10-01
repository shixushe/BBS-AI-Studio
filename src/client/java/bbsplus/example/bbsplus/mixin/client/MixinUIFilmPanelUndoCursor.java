package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.UndoCursorKeeper;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(UIFilmPanel.class)
public abstract class MixinUIFilmPanelUndoCursor
{
    @Inject(method = {"undo", "redo"}, at = @At("HEAD"))
    private void bbsplus$rememberCursorBeforeUndo(CallbackInfo ci)
    {
        UndoCursorKeeper.begin(((UIFilmPanel) (Object) this).getCursor());
    }

    @Inject(method = {"undo", "redo"}, at = @At("RETURN"))
    private void bbsplus$clearCursorAfterUndo(CallbackInfo ci)
    {
        UndoCursorKeeper.end();
    }
}
