package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.UndoCursorKeeper;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ValueChangeUndo.class)
public abstract class MixinValueChangeUndoCursor
{
    @Inject(method = "getUIData", at = @At("RETURN"))
    private void bbsplus$preserveCursorForMarkedInterpolationUndo(boolean redo, CallbackInfoReturnable<MapType> cir)
    {
        ValueChangeUndo self = (ValueChangeUndo) (Object) this;
        Integer cursor = UndoCursorKeeper.getActiveCursor();

        if (cursor == null || !UndoCursorKeeper.shouldPreserve(self))
        {
            return;
        }

        MapType data = cir.getReturnValue();

        if (data != null && data.has("film_panel"))
        {
            data.getMap("film_panel").putInt("tick", cursor);
        }
    }
}
