package bbsplus.example.bbsplus.mixin.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(UIKeyframeFactory.class)
public interface AccessorUIKeyframeFactory
{
    @Accessor("editor")
    UIKeyframes bbsplus$getEditor();

    @Accessor("keyframe")
    Keyframe bbsplus$getKeyframe();
}
