package bbsplus.example.bbsplus.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;

/**
 * Viewport preservation across undo/redo.
 *
 * <p>Stores the current horizontal range so that undo/redo doesn't snap the
 * viewport when the clip/keyframe editor hierarchy is rebuilt.</p>
 */
public final class UIClipViewportKeeper
{
    private UIClipViewportKeeper()
    {}

    private static double xMin;
    private static double xMax;
    private static boolean hasView;

    public static void capture(UIKeyframeEditor editor)
    {
        if (editor != null && editor.view != null)
        {
            xMin = editor.view.getXAxis().getMinValue();
            xMax = editor.view.getXAxis().getMaxValue();
            hasView = xMin < xMax;
        }
    }

    public static void restore(UIKeyframeEditor editor)
    {
        if (hasView && editor != null && editor.view != null)
        {
            editor.view.getXAxis().view(xMin, xMax);
        }
    }
}
