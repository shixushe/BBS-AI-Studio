package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.pose.UIPoseTransformKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UITransformKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(UITransformKeyframeFactory.UIPoseTransforms.class)
public abstract class MixinUITransformKeyframeFactoryPoseTransforms
{
    @Shadow private UITransformKeyframeFactory editor;

    @Inject(method = "applyToSelection", at = @At("HEAD"), cancellable = true)
    private void bbsplus$applyCurveComponentSelection(Consumer<Transform> consumer, CallbackInfo ci)
    {
        AccessorUIKeyframeFactory accessor = (AccessorUIKeyframeFactory) this.editor;
        UIKeyframes keyframes = accessor.bbsplus$getEditor();

        if (keyframes.getGraph() instanceof UIPoseTransformKeyframeGraph graph && graph.hasComponentSelection())
        {
            UIPropTransform self = (UIPropTransform) (Object) this;
            Keyframe panelKeyframe = accessor.bbsplus$getKeyframe();
            Transform synced = graph.applyTransformEditorChange(panelKeyframe, self.getTransform(), consumer);

            if (synced != null)
            {
                self.setTransform(synced);
            }

            ci.cancel();
        }
    }
}
