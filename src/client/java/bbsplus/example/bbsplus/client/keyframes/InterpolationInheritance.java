package bbsplus.example.bbsplus.client.keyframes;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.List;

public final class InterpolationInheritance
{
    private static final float EPSILON = 0.0001F;

    private InterpolationInheritance()
    {}

    public static Keyframe previousForNewKeyframe(List<Keyframe> keyframes, float tick)
    {
        Keyframe previous = null;

        if (keyframes == null)
        {
            return null;
        }

        for (Keyframe keyframe : keyframes)
        {
            float keyframeTick = keyframe.getTick();

            if (Math.abs(keyframeTick - tick) <= EPSILON)
            {
                return null;
            }

            if (keyframeTick < tick && (previous == null || keyframeTick > previous.getTick()))
            {
                previous = keyframe;
            }
        }

        return previous;
    }

    public static void inherit(Keyframe keyframe, Keyframe previous)
    {
        if (keyframe == null || previous == null || keyframe.getTick() <= previous.getTick())
        {
            return;
        }

        keyframe.getInterpolation().copy(previous.getInterpolation());
    }

    public static void inheritSelected(UIKeyframeSheet sheet, Keyframe previous)
    {
        if (sheet == null || previous == null)
        {
            return;
        }

        for (Integer index : sheet.selection.getIndices())
        {
            Keyframe keyframe = sheet.channel.get(index);

            inherit(keyframe, previous);
        }
    }
}
