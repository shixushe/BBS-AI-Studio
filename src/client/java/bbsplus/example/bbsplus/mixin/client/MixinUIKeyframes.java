package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.pose.UIPoseTransformKeyframeGraph;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeState;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Two roles:
 * <ul>
 *   <li>opens the Blender-style curve editor for {@code pose_transform} and
 *       {@code transform} tracks (which BBS excludes from curve editing);</li>
 *   <li>adds ctrl+middle-drag zoom to the multi-track dope sheet view (the curve
 *       graph itself zooms on its own; this covers the dope sheet, e.g. in the
 *       replay editor, where the Y dimension is track height).</li>
 * </ul>
 */
@Mixin(UIKeyframes.class)
public abstract class MixinUIKeyframes
{
    @Shadow private IUIKeyframeGraph currentGraph;

    @Shadow public abstract void resetView();

    @Shadow public abstract void resize();

    @Shadow public abstract boolean isEditing();

    @Shadow private boolean single;

    @Shadow public abstract UIKeyframeDopeSheet getDopeSheet();

    @Unique
    private Scale bbsplus$getXAxis()
    {
        return ((UITimelineCanvas) (Object) this).getXAxis();
    }

    @Unique private static final double BBSPLUS_ZOOM_SENSITIVITY = 0.01D;
    @Unique private boolean bbsplus$zooming;
    @Unique private int bbsplus$startX, bbsplus$startY;
    @Unique private double bbsplus$startXZoom;
    @Unique private double bbsplus$startTrackHeight;

    private static boolean bbsplus$isCurveEditable(IKeyframeFactory<?> factory)
    {
        return factory == KeyframeFactories.POSE_TRANSFORM
            || factory == KeyframeFactories.TRANSFORM;
    }

    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true)
    private void bbsplus$startDopeZoom(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.currentGraph instanceof UIPoseTransformKeyframeGraph graph)
        {
            UIPoseTransformKeyframeGraph.ScalarEditResult result = graph.handleScalarEditingMouse(context);

            if (result.handled())
            {
                cir.setReturnValue(true);
                return;
            }
        }

        /* Ctrl+middle on the dope sheet view: start drag-zoom and swallow the
         * click so the base middle-button pan never engages. The curve graph
         * handles its own zoom, so only act when NOT editing a single track. */
        if (context.mouseButton == 2 && Window.isCtrlPressed() && !this.isEditing())
        {
            UIKeyframes self = (UIKeyframes) (Object) this;

            if (self.area.isInside(context))
            {
                this.bbsplus$zooming = true;
                this.bbsplus$startX = context.mouseX;
                this.bbsplus$startY = context.mouseY;
                this.bbsplus$startXZoom = this.bbsplus$getXAxis().getZoom();
                this.bbsplus$startTrackHeight = this.getDopeSheet().getTrackHeight();
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "handleMouse", at = @At("HEAD"), cancellable = true)
    private void bbsplus$applyDopeZoom(UIContext context, CallbackInfo ci)
    {
        if (!this.bbsplus$zooming)
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;

        int dx = context.mouseX - this.bbsplus$startX;
        int dy = this.bbsplus$startY - context.mouseY; // up is positive

        /* Horizontal: zoom the time axis around the drag start. */
        double targetZoom = MathUtils.clamp(
            this.bbsplus$startXZoom * Math.exp(dx * BBSPLUS_ZOOM_SENSITIVITY),
            0.01D, 1000D
        );
        float anchor = Scale.getAnchorX(context, self.area);
        double amount = targetZoom - this.bbsplus$getXAxis().getZoom();

        if (amount != 0)
        {
            this.bbsplus$getXAxis().zoomAnchor(anchor, amount);
        }

        /* Vertical: zoom the track row height. */
        double targetHeight = this.bbsplus$startTrackHeight * Math.exp(dy * BBSPLUS_ZOOM_SENSITIVITY);
        this.getDopeSheet().setTrackHeight(targetHeight);

        ci.cancel();
    }

    @Inject(method = "subMouseReleased", at = @At("HEAD"))
    private void bbsplus$endDopeZoom(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        this.bbsplus$zooming = false;
    }

    @Inject(method = "pickOrStartSelectingKeyframes", at = @At("TAIL"))
    private void bbsplus$selectPoseCurveComponent(UIContext context, CallbackInfo ci)
    {
        if (context.mouseButton == 0
            && this.currentGraph instanceof UIPoseTransformKeyframeGraph graph)
        {
            graph.selectLastHitComponent(Window.isShiftPressed());
        }
    }

    @Inject(method = "subKeyPressed", at = @At("HEAD"), cancellable = true)
    private void bbsplus$handlePoseCurveScalarEditing(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (!(this.currentGraph instanceof UIPoseTransformKeyframeGraph graph))
        {
            return;
        }

        UIPoseTransformKeyframeGraph.ScalarEditResult result = graph.handleScalarEditingKey(context);

        if (result.handled())
        {
            cir.setReturnValue(true);
            return;
        }

        if (context.isPressed(GLFW.GLFW_KEY_G) && graph.startScalarEditing(context))
        {
            cir.setReturnValue(true);
        }
    }

    /* ===== Keep the viewport fixed across undo/redo ===== */

    @Unique private MapType bbsplus$preUndoView;
    @Unique private MapType bbsplus$preSelectNextView;
    @Unique private final Map<String, MapType> bbsplus$curveViews = new HashMap<>();
    @Unique private MapType bbsplus$dopeSheetView;

    @Inject(method = "applyState", at = @At("HEAD"))
    private void bbsplus$captureView(KeyframeState state, CallbackInfo ci)
    {
        /* Snapshot the CURRENT viewport before undo/redo applies the historic one,
         * so undoing a change never yanks the view to where it was at edit time
         * (e.g. when the changed keyframe is off-screen). */
        UIKeyframes self = (UIKeyframes) (Object) this;

        this.bbsplus$preUndoView = new MapType();
        this.bbsplus$preUndoView.putDouble("x_min", ((UITimelineCanvas)self).getXAxis().getMinValue());
        this.bbsplus$preUndoView.putDouble("x_max", ((UITimelineCanvas)self).getXAxis().getMaxValue());
        this.currentGraph.saveState(this.bbsplus$preUndoView);
    }

    @Inject(method = "applyState", at = @At("TAIL"))
    private void bbsplus$restoreView(KeyframeState state, CallbackInfo ci)
    {
        if (this.bbsplus$preUndoView == null)
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;

        ((UITimelineCanvas)self).getXAxis().view(
            this.bbsplus$preUndoView.getDouble("x_min"),
            this.bbsplus$preUndoView.getDouble("x_max")
        );
        this.currentGraph.restoreState(this.bbsplus$preUndoView);

        this.bbsplus$preUndoView = null;
    }

    @Inject(method = "selectNextKeyframe", at = @At("HEAD"))
    private void bbsplus$captureCurveViewBeforeSelectNext(int direction, CallbackInfo ci)
    {
        this.bbsplus$preSelectNextView = null;

        if (!this.isEditing() || this.currentGraph == null)
        {
            return;
        }

        this.bbsplus$preSelectNextView = new MapType();
        this.currentGraph.saveState(this.bbsplus$preSelectNextView);
    }

    @Inject(method = "selectNextKeyframe", at = @At("RETURN"))
    private void bbsplus$restoreCurveViewAfterSelectNext(int direction, CallbackInfo ci)
    {
        if (this.bbsplus$preSelectNextView == null)
        {
            return;
        }

        if (this.isEditing() && this.currentGraph != null)
        {
            this.currentGraph.restoreState(this.bbsplus$preSelectNextView);
        }

        this.bbsplus$preSelectNextView = null;
    }

    /* ===== Remember curve-editor viewport per track ===== */

    @Inject(method = "editSheet", at = @At("HEAD"))
    private void bbsplus$rememberCurveViewBeforeEdit(UIKeyframeSheet sheet, CallbackInfo ci)
    {
        if (!this.isEditing() && sheet != null)
        {
            this.bbsplus$saveDopeSheetView();
        }

        this.bbsplus$saveCurrentCurveView();
    }

    @Inject(method = "editSheet", at = @At("TAIL"))
    private void bbsplus$restoreCurveViewAfterEdit(UIKeyframeSheet sheet, CallbackInfo ci)
    {
        if (sheet == null)
        {
            this.bbsplus$restoreDopeSheetView();

            return;
        }

        this.bbsplus$restoreCurveView(sheet);
    }

    @Unique
    private void bbsplus$saveDopeSheetView()
    {
        UIKeyframes self = (UIKeyframes) (Object) this;

        this.bbsplus$dopeSheetView = new MapType();
        this.bbsplus$dopeSheetView.putDouble("x_min", ((UITimelineCanvas)self).getXAxis().getMinValue());
        this.bbsplus$dopeSheetView.putDouble("x_max", ((UITimelineCanvas)self).getXAxis().getMaxValue());
        this.getDopeSheet().saveState(this.bbsplus$dopeSheetView);
    }

    @Unique
    private void bbsplus$restoreDopeSheetView()
    {
        if (this.bbsplus$dopeSheetView == null)
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;

        ((UITimelineCanvas)self).getXAxis().view(
            this.bbsplus$dopeSheetView.getDouble("x_min"),
            this.bbsplus$dopeSheetView.getDouble("x_max")
        );
        this.getDopeSheet().restoreState(this.bbsplus$dopeSheetView);
    }

    @Unique
    private void bbsplus$saveCurrentCurveView()
    {
        if (!this.isEditing() || this.currentGraph == null)
        {
            return;
        }

        UIKeyframeSheet current = this.currentGraph.getLastSheet();

        if (current == null)
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;
        MapType state = new MapType();

        state.putDouble("x_min", ((UITimelineCanvas)self).getXAxis().getMinValue());
        state.putDouble("x_max", ((UITimelineCanvas)self).getXAxis().getMaxValue());
        this.currentGraph.saveState(state);

        this.bbsplus$curveViews.put(current.id, state);
    }

    @Unique
    private void bbsplus$restoreCurveView(UIKeyframeSheet sheet)
    {
        if (sheet == null)
        {
            return;
        }

        MapType state = this.bbsplus$curveViews.get(sheet.id);

        if (state == null)
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;

        ((UITimelineCanvas)self).getXAxis().view(state.getDouble("x_min"), state.getDouble("x_max"));
        this.currentGraph.restoreState(state);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsplus$addTransformEditTrack(Consumer<?> callback, CallbackInfo ci)
    {
        UIKeyframes self = (UIKeyframes) (Object) this;

        self.context((menu) ->
        {
            if (this.single || this.isEditing())
            {
                return;
            }

            UIKeyframeSheet sheet = self.getDopeSheet().getSheet(self.getContext().mouseY);

            if (sheet != null && bbsplus$isCurveEditable(sheet.channel.getFactory()))
            {
                menu.action(Icons.EDIT, UIKeys.KEYFRAMES_CONTEXT_EDIT_TRACK.format(sheet.id), () -> self.editSheet(sheet));
            }
        });
    }

    @Inject(method = "editSheet", at = @At("HEAD"), cancellable = true)
    private void bbsplus$editTransformSheet(UIKeyframeSheet sheet, CallbackInfo ci)
    {
        if (sheet == null || !bbsplus$isCurveEditable(sheet.channel.getFactory()))
        {
            return;
        }

        UIKeyframes self = (UIKeyframes) (Object) this;

        self.getDopeSheet().clearSelection();
        self.getDopeSheet().pickSelected();

        if (!this.isEditing())
        {
            this.bbsplus$saveDopeSheetView();
        }

        this.currentGraph = new UIPoseTransformKeyframeGraph(self, sheet);

        this.resetView();
        this.resize();
        this.bbsplus$restoreCurveView(sheet);

        ci.cancel();
    }
}
