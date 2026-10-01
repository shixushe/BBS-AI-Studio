package bbsplus.example.bbsplus.mixin.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UINumericKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(UINumericKeyframeFactory.class)
public abstract class MixinUINumericKeyframeFactory
{
    @Inject(method = "setValue(D)V", at = @At("HEAD"), cancellable = true)
    private void bbsplus$applyNumericValueToSelection(double value, CallbackInfo ci)
    {
        AccessorUIKeyframeFactory accessor = (AccessorUIKeyframeFactory) this;
        UIKeyframes editor = accessor.bbsplus$getEditor();
        Keyframe keyframe = accessor.bbsplus$getKeyframe();

        if (editor == null || keyframe == null || editor.getGraph() == null)
        {
            return;
        }

        IUIKeyframeGraph graph = editor.getGraph();
        IKeyframeFactory factory = keyframe.getFactory();
        Object oldValue = factory.copy(keyframe.getValue());
        Object newValue = factory.yToValue(value);

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            if (sheet.channel.getFactory() == factory)
            {
                sheet.setValue(newValue, oldValue, true);
            }
        }

        ci.cancel();
    }
}
