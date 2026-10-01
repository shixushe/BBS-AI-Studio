package bbsplus.example.bbsplus.mixin.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseTransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UITransformKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(UIKeyframeFactory.class)
public abstract class MixinUIKeyframeFactorySync
{
    @Inject(method = "update", at = @At("TAIL"))
    private void bbsplus$syncTransformPanels(CallbackInfo ci)
    {
        Keyframe keyframe = ((AccessorUIKeyframeFactory) this).bbsplus$getKeyframe();

        if (keyframe == null)
        {
            return;
        }

        Object self = this;

        if (self instanceof UITransformKeyframeFactory factory && keyframe.getValue() instanceof Transform value)
        {
            factory.transform.setTransform(value);
        }
        else if (self instanceof UIPoseTransformKeyframeFactory factory && keyframe.getValue() instanceof PoseTransform value)
        {
            factory.transform.setTransform(value);
            factory.fix.setValue(value.fix);
            factory.color.setColor(value.color.getARGBColor());
            factory.lighting.setValue(value.lighting);
        }
    }
}
