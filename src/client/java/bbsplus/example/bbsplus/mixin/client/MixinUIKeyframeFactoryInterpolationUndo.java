package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.UndoCursorKeeper;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.context.UIInterpolationContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(UIKeyframeFactory.class)
public abstract class MixinUIKeyframeFactoryInterpolationUndo
{
    @Shadow
    protected UIKeyframes editor;

    @Redirect(
        method = "lambda$new$4",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/context/UIInterpolationContextMenu;callback(Ljava/lang/Runnable;)Lmchorse/bbs_mod/ui/framework/elements/context/UIInterpolationContextMenu;"
        )
    )
    private UIInterpolationContextMenu bbsplus$recordInterpolationUndo(UIInterpolationContextMenu menu, Runnable callback)
    {
        this.editor.cacheKeyframes();

        return menu.callback(() ->
        {
            callback.run();
            this.editor.submitKeyframes();
            this.bbsplus$submitAndMarkUndo();

            /* Keep the same open menu undoable for additional interpolation edits. */
            this.editor.cacheKeyframes();
        });
    }

    private void bbsplus$submitAndMarkUndo()
    {
        UIFilmPanel panel = this.editor.getParent(UIFilmPanel.class);

        if (panel == null || panel.getUndoHandler() == null)
        {
            return;
        }

        panel.getUndoHandler().submitUndo();
        UndoCursorKeeper.mark(panel.getUndoHandler().getUndoManager().getCurrentUndo());
    }
}
