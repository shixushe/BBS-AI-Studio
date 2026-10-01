package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.keyframes.AutoBezierHandles;
import bbsplus.example.bbsplus.client.keyframes.AutoVectorHandles;
import bbsplus.example.bbsplus.client.keyframes.InterpolationInheritance;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.line.LineBuilder;
import mchorse.bbs_mod.graphics.line.SolidColorLineRenderer;
import bbsplus.example.bbsplus.client.pose.UIPoseTransformKeyframeGraph;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.KeyframeType;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.IKeyframeShapeRenderer;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the same automatic Bezier handle tools to BBS's built-in curve editors
 * that the pose/transform graph already exposes.
 */
@Mixin(UIKeyframeGraph.class)
public abstract class MixinUIKeyframeGraph
{
    @Shadow protected UIKeyframes keyframes;
    @Shadow protected UIKeyframeSheet sheet;
    @Shadow protected Scale yAxis;
    @Shadow public abstract int toGraphY(double value);
    @Shadow public abstract double fromGraphY(int mouseY);
    @Shadow protected abstract boolean isNear(double x, double y, int mouseX, int mouseY);

    @Unique private static final int BBSPLUS_BUTTON_WIDTH = 118;
    @Unique private static final int BBSPLUS_BUTTON_HEIGHT = 16;
    @Unique private static final int BBSPLUS_BUTTON_GAP = 3;
    @Unique private static final int BBSPLUS_ACTION_COUNT = 3;
    @Unique private static final double BBSPLUS_ZOOM_SENSITIVITY = 0.01D;
    @Unique private boolean bbsplus$zooming;
    @Unique private int bbsplus$zoomStartX;
    @Unique private int bbsplus$zoomStartY;
    @Unique private double bbsplus$zoomStartXZoom;
    @Unique private double bbsplus$zoomStartYZoom;
    @Unique private boolean bbsplus$areaShifted;
    @Unique private int bbsplus$areaX;
    @Unique private int bbsplus$areaY;
    @Unique private int bbsplus$areaW;
    @Unique private int bbsplus$areaH;
    @Unique private Keyframe bbsplus$inheritInterpolationFrom;
    @Unique private boolean bbsplus$selectionViewportCaptured;
    @Unique private double bbsplus$selectionViewXMin;
    @Unique private double bbsplus$selectionViewXMax;
    @Unique private double bbsplus$selectionViewYMin;
    @Unique private double bbsplus$selectionViewYMax;
    @Unique private final int[] bbsplus$actionX = new int[BBSPLUS_ACTION_COUNT];
    @Unique private final int[] bbsplus$actionY = new int[BBSPLUS_ACTION_COUNT];
    @Unique private final int[] bbsplus$actionW = new int[BBSPLUS_ACTION_COUNT];
    @Unique private int bbsplus$actionPanelX;
    @Unique private int bbsplus$actionPanelY;
    @Unique private int bbsplus$actionPanelW;
    @Unique private int bbsplus$actionPanelH;

    @Inject(method = "addKeyframe", at = @At("HEAD"))
    private void bbsplus$captureInterpolationInheritanceSource(int mouseX, int mouseY, CallbackInfoReturnable<Boolean> cir)
    {
        this.bbsplus$inheritInterpolationFrom = null;

        if (this.sheet == null)
        {
            return;
        }

        float tick = (float) this.keyframes.fromGraphX(mouseX);

        if (!Window.isShiftPressed())
        {
            tick = Math.round(tick);
        }

        this.bbsplus$inheritInterpolationFrom = InterpolationInheritance.previousForNewKeyframe(this.sheet.channel.getKeyframes(), tick);
    }

    @Inject(method = "addKeyframe", at = @At("TAIL"))
    private void bbsplus$inheritInterpolationForAddedKeyframe(int mouseX, int mouseY, CallbackInfoReturnable<Boolean> cir)
    {
        if (Boolean.TRUE.equals(cir.getReturnValue()))
        {
            InterpolationInheritance.inheritSelected(this.sheet, this.bbsplus$inheritInterpolationFrom);
        }

        this.bbsplus$inheritInterpolationFrom = null;
    }

    @Inject(method = "selectKeyframe", at = @At("HEAD"))
    private void bbsplus$captureCurveViewportBeforeSelection(Keyframe keyframe, CallbackInfo ci)
    {
        this.bbsplus$selectionViewportCaptured = keyframe != null
            && this.keyframes != null
            && this.keyframes.isEditing();

        if (!this.bbsplus$selectionViewportCaptured)
        {
            return;
        }

        this.bbsplus$selectionViewXMin = this.keyframes.getXAxis().getMinValue();
        this.bbsplus$selectionViewXMax = this.keyframes.getXAxis().getMaxValue();
        this.bbsplus$selectionViewYMin = this.yAxis.getMinValue();
        this.bbsplus$selectionViewYMax = this.yAxis.getMaxValue();
    }

    @Inject(method = "selectKeyframe", at = @At("TAIL"))
    private void bbsplus$restoreCurveViewportAfterSelection(Keyframe keyframe, CallbackInfo ci)
    {
        if (!this.bbsplus$selectionViewportCaptured)
        {
            return;
        }

        this.keyframes.getXAxis().view(this.bbsplus$selectionViewXMin, this.bbsplus$selectionViewXMax);
        this.yAxis.view(this.bbsplus$selectionViewYMin, this.bbsplus$selectionViewYMax);
        this.bbsplus$selectionViewportCaptured = false;
    }

    @Inject(method = "resize", at = @At("TAIL"))
    private void bbsplus$reserveTrackListColumn(CallbackInfo ci)
    {
        if (this.sheet == null)
        {
            return;
        }

        int width = this.bbsplus$trackListWidth();

        this.keyframes.graphArea.copy(this.keyframes.area);

        if (width > 0 && this.keyframes.area.w > width + 80)
        {
            this.keyframes.graphArea.x += width;
            this.keyframes.graphArea.w -= width;
        }
    }

    @Inject(method = "renderGrid", at = @At("HEAD"))
    private void bbsplus$shiftGraphAreaForCurveRender(UIContext context, CallbackInfo ci)
    {
        if (this.sheet == null || this.bbsplus$areaShifted)
        {
            return;
        }

        this.bbsplus$areaX = this.keyframes.area.x;
        this.bbsplus$areaY = this.keyframes.area.y;
        this.bbsplus$areaW = this.keyframes.area.w;
        this.bbsplus$areaH = this.keyframes.area.h;
        this.bbsplus$areaShifted = true;

        this.keyframes.area.copy(this.keyframes.graphArea);
    }

    @Inject(method = "postRender", at = @At("HEAD"))
    private void bbsplus$renderAutoHandleButtons(UIContext context, CallbackInfo ci)
    {
        this.bbsplus$restoreGraphAreaAfterCurveRender();
        this.bbsplus$renderTrackList(context);

        if (this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        this.bbsplus$layoutActionButtons();
        int color = this.bbsplus$hasSelection()
            ? Colors.WHITE
            : (Colors.mulRGB(Colors.WHITE, 0.4F) | Colors.A100);

        context.batcher.box(
            this.bbsplus$actionPanelX,
            this.bbsplus$actionPanelY,
            this.bbsplus$actionPanelX + this.bbsplus$actionPanelW,
            this.bbsplus$actionPanelY + this.bbsplus$actionPanelH,
            Colors.A75
        );

        this.bbsplus$renderActionButton(context, 0, "auto smooth", "smooth", "S", color);
        this.bbsplus$renderActionButton(context, 1, "auto clamped", "clamped", "C", color);
        this.bbsplus$renderActionButton(context, 2, "auto vector", "vector", "V", color);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void bbsplus$clickAutoHandleButtons(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (context.mouseButton == 0 && this.bbsplus$isTrackListInside(context))
        {
            UIKeyframeSheet track = this.keyframes.getDopeSheet().getSheet(context.mouseY);

            if ((Object) this instanceof UIPoseTransformKeyframeGraph graph
                && Window.isShiftPressed())
            {
                if (track == this.sheet && graph.closeLinkedSheets())
                {
                    UIUtils.playClick();
                }
                else if (graph.canToggleLinkedSheet(track) && graph.toggleLinkedSheet(track))
                {
                    UIUtils.playClick();
                }

                cir.setReturnValue(true);
                return;
            }

            if (track != null && track != this.sheet && this.bbsplus$isCurveEditable(track))
            {
                this.keyframes.editSheet(track);
                UIUtils.playClick();
            }

            cir.setReturnValue(true);
            return;
        }

        if (this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        if (context.mouseButton == 2 && Window.isCtrlPressed()
            && this.keyframes.graphArea.isInside(context))
        {
            this.bbsplus$zooming = true;
            this.bbsplus$zoomStartX = context.mouseX;
            this.bbsplus$zoomStartY = context.mouseY;
            this.bbsplus$zoomStartXZoom = this.keyframes.getXAxis().getZoom();
            this.bbsplus$zoomStartYZoom = this.yAxis.getZoom();
            cir.setReturnValue(true);
            return;
        }

        if (context.mouseButton != 0)
        {
            return;
        }

        this.bbsplus$layoutActionButtons();

        if (context.mouseX < this.bbsplus$actionPanelX
            || context.mouseX > this.bbsplus$actionPanelX + this.bbsplus$actionPanelW
            || context.mouseY < this.bbsplus$actionPanelY
            || context.mouseY > this.bbsplus$actionPanelY + this.bbsplus$actionPanelH)
        {
            return;
        }

        for (int i = 0; i < BBSPLUS_ACTION_COUNT; i++)
        {
            if (!this.bbsplus$isActionButtonInside(i, context.mouseX, context.mouseY))
            {
                continue;
            }

            if (this.bbsplus$hasSelection())
            {
                UIUtils.playClick();

                if (i == 0)
                {
                    this.bbsplus$applyAutoHandles(false);
                }
                else if (i == 1)
                {
                    this.bbsplus$applyAutoHandles(true);
                }
                else
                {
                    this.bbsplus$applyAutoVector();
                }
            }

            cir.setReturnValue(true);
            return;
        }

        cir.setReturnValue(true);
    }

    @Inject(method = "handleMouse", at = @At("HEAD"), cancellable = true)
    private void bbsplus$applyCtrlMiddleZoom(UIContext context, int lastX, int lastY, CallbackInfo ci)
    {
        if (!this.bbsplus$zooming || this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        int dx = context.mouseX - this.bbsplus$zoomStartX;
        int dy = this.bbsplus$zoomStartY - context.mouseY;

        double xZoom = this.bbsplus$zoomStartXZoom * Math.exp(dx * BBSPLUS_ZOOM_SENSITIVITY);
        double yZoom = this.bbsplus$zoomStartYZoom * Math.exp(dy * BBSPLUS_ZOOM_SENSITIVITY);

        this.bbsplus$zoomAxisTo(this.keyframes.getXAxis(), Scale.getAnchorX(context, this.keyframes.graphArea), xZoom);
        this.bbsplus$zoomAxisTo(this.yAxis, Scale.getAnchorY(context, this.keyframes.graphArea), yZoom);

        ci.cancel();
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void bbsplus$endCtrlMiddleZoom(UIContext context, CallbackInfo ci)
    {
        this.bbsplus$zooming = false;
    }

    @Inject(method = "findKeyframe", at = @At("HEAD"), cancellable = true)
    private void bbsplus$findAutoBezierHandle(int mouseX, int mouseY, CallbackInfoReturnable<Pair<Keyframe, KeyframeType>> cir)
    {
        if (this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        List<Keyframe> keyframes = this.sheet.channel.getKeyframes();

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe keyframe = keyframes.get(i);
            int x = this.keyframes.toGraphX(keyframe.getTick());
            int y = this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()));

            if (this.isNear(x, y, mouseX, mouseY))
            {
                cir.setReturnValue(new Pair<>(keyframe, KeyframeType.REGULAR));
                return;
            }

            AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, i, 0, -1, this::bbsplus$getRawValue);

            if (left != null)
            {
                int lx = this.keyframes.toGraphX(keyframe.getTick() - left.lx);
                int ly = this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + left.ly);

                if (this.isNear(lx, ly, mouseX, mouseY))
                {
                    cir.setReturnValue(new Pair<>(keyframe, KeyframeType.LEFT_HANDLE));
                    return;
                }
            }

            AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, i, 0, -1, this::bbsplus$getRawValue);

            if (right != null)
            {
                int rx = this.keyframes.toGraphX(keyframe.getTick() + right.rx);
                int ry = this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + right.ry);

                if (this.isNear(rx, ry, mouseX, mouseY))
                {
                    cir.setReturnValue(new Pair<>(keyframe, KeyframeType.RIGHT_HANDLE));
                    return;
                }
            }
        }

        cir.setReturnValue(null);
    }

    @Inject(method = "dragKeyframes", at = @At("HEAD"))
    private void bbsplus$seedAutoBezierBeforeHandleDrag(UIContext context, Pair<Keyframe, KeyframeType> type, int originalX, int originalY, float originalT, Object originalV, CallbackInfo ci)
    {
        if (this.bbsplus$skipAutoHandleTools() || type == null)
        {
            return;
        }

        List<Keyframe> keyframes = this.sheet.channel.getKeyframes();
        int index = keyframes.indexOf(type.a);

        if (type.b == KeyframeType.LEFT_HANDLE)
        {
            AutoBezierHandles.seedIncomingAuto(keyframes, index, 1, this::bbsplus$getRawValue);
        }
        else if (type.b == KeyframeType.RIGHT_HANDLE)
        {
            AutoBezierHandles.seedOutgoingAuto(keyframes, index, 1, this::bbsplus$getRawValue);
        }
    }

    @Inject(method = "renderGraphPointShapes", at = @At("HEAD"), cancellable = true)
    private void bbsplus$renderDimmedBezierHandles(UIContext context, BufferBuilder builder, Matrix4f matrix, List keyframes, CallbackInfo ci)
    {
        if (this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        int forcedIndex = 0;

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe frame = (Keyframe) keyframes.get(i);
            Keyframe prev = i > 0 ? (Keyframe) keyframes.get(i - 1) : null;
            float tick = frame.getTick();
            int x1 = this.keyframes.toGraphX(tick);
            int x2 = this.keyframes.toGraphX(tick + frame.getDuration());
            int y = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()));

            if (x1 != x2)
            {
                int y1 = y - 8 + (forcedIndex % 2 == 1 ? -4 : 0);
                int color = this.sheet.selection.has(i) ? Colors.WHITE : Colors.setA(Colors.mulRGB(this.sheet.color, 0.9F), 0.75F);

                context.batcher.fillRect(builder, matrix, x1, y1 - 2, 1, 5, color, color, color, color);
                context.batcher.fillRect(builder, matrix, x2, y1 - 2, 1, 5, color, color, color, color);
                context.batcher.fillRect(builder, matrix, x1 + 1, y1, x2 - x1, 1, color, color, color, color);

                forcedIndex += 1;
            }

            boolean isPointHover = this.isNear(this.keyframes.toGraphX(frame.getTick()), y, context.mouseX, context.mouseY);
            boolean toRemove = Window.isCtrlPressed() && isPointHover;

            if (this.keyframes.isSelecting())
            {
                isPointHover = isPointHover || this.keyframes.getGrabbingArea(context).isInside(x1, y);
            }

            int kc = frame.getStyle().getColor() != null ? frame.getStyle().getColor().getRGBColor() | Colors.A100 : this.sheet.color;
            int c = (this.sheet.selection.has(i) || isPointHover ? Colors.WHITE : kc) | Colors.A100;

            if (toRemove)
            {
                c = Colors.RED | Colors.A100;
            }

            UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, x1, y, toRemove ? 4 : 3, c);

            int handleColor = this.bbsplus$handleColor(c);

            if (frame.getInterpolation().getInterp() == Interpolations.BEZIER)
            {
                int rx = this.keyframes.toGraphX(frame.getTick() + frame.rx);
                int ry = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()) + frame.ry);

                UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, rx, ry, 2, handleColor);
            }

            if (prev != null && prev.getInterpolation().getInterp() == Interpolations.BEZIER)
            {
                int lx = this.keyframes.toGraphX(frame.getTick() - frame.lx);
                int ly = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()) + frame.ly);

                UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, lx, ly, 2, handleColor);
            }
        }

        for (int j = 0; j < keyframes.size(); j++)
        {
            Keyframe frame = (Keyframe) keyframes.get(j);
            Keyframe prev = j > 0 ? (Keyframe) keyframes.get(j - 1) : null;
            int y = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()));
            int c = this.sheet.selection.has(j) ? Colors.ACTIVE : 0;
            int mx = this.keyframes.toGraphX(frame.getTick());
            int mc = c | Colors.A100;
            IKeyframeShapeRenderer shapeResult = UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, mx, y, 2, mc);

            shapeResult.renderKeyframeBackground(context, builder, matrix, mx, y, 2, mc);

            int handleColor = this.bbsplus$handleColor(c | Colors.A100);

            if (frame.getInterpolation().getInterp() == Interpolations.BEZIER)
            {
                int rx = this.keyframes.toGraphX(frame.getTick() + frame.rx);
                int ry = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()) + frame.ry);

                shapeResult = UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, rx, ry, 1, handleColor);
                shapeResult.renderKeyframeBackground(context, builder, matrix, rx, ry, 1, handleColor);
            }

            if (prev != null && prev.getInterpolation().getInterp() == Interpolations.BEZIER)
            {
                int lx = this.keyframes.toGraphX(frame.getTick() - frame.lx);
                int ly = this.toGraphY(this.sheet.channel.getFactory().getY(frame.getValue()) + frame.ly);

                shapeResult = UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, lx, ly, 1, handleColor);
                shapeResult.renderKeyframeBackground(context, builder, matrix, lx, ly, 1, handleColor);
            }
        }

        ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void bbsplus$renderAutoBezierHandles(UIContext context, CallbackInfo ci)
    {
        if (this.bbsplus$skipAutoHandleTools())
        {
            return;
        }

        List<Keyframe> keyframes = this.sheet.channel.getKeyframes();

        if (keyframes.size() < 2)
        {
            return;
        }

        context.batcher.clip(this.keyframes.graphArea, context);

        LineBuilder lineBuilder = new LineBuilder(0.5F);

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe keyframe = keyframes.get(i);
            int x = this.keyframes.toGraphX(keyframe.getTick());
            int y = this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()));

            if (AutoBezierHandles.isAuto(keyframe))
            {
                AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, i, 0, -1, this::bbsplus$getRawValue);

                if (right != null)
                {
                    lineBuilder.push();
                    lineBuilder.add(x, y);
                    lineBuilder.add(this.keyframes.toGraphX(keyframe.getTick() + right.rx), this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + right.ry));
                }
            }

            if (i > 0 && AutoBezierHandles.isAuto(keyframes.get(i - 1)))
            {
                AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, i, 0, -1, this::bbsplus$getRawValue);

                if (left != null)
                {
                    lineBuilder.push();
                    lineBuilder.add(x, y);
                    lineBuilder.add(this.keyframes.toGraphX(keyframe.getTick() - left.lx), this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + left.ly));
                }
            }
        }

        lineBuilder.render(context.batcher, SolidColorLineRenderer.get(Colors.COLOR.set(Colors.setA(this.sheet.color, 0.75F))));

        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Matrix4f matrix = context.batcher.getContext().getMatrices().peek().getPositionMatrix();

        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe keyframe = keyframes.get(i);
            int c = (this.sheet.selection.has(i) ? Colors.WHITE : this.sheet.color) | Colors.A100;

            if (AutoBezierHandles.isAuto(keyframe))
            {
                AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, i, 0, -1, this::bbsplus$getRawValue);

                if (right != null)
                {
                    UIKeyframeDopeSheet.renderShape(
                        keyframe,
                        context,
                        builder,
                        matrix,
                        this.keyframes.toGraphX(keyframe.getTick() + right.rx),
                        this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + right.ry),
                        2,
                        c
                    );
                }
            }

            if (i > 0 && AutoBezierHandles.isAuto(keyframes.get(i - 1)))
            {
                AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, i, 0, -1, this::bbsplus$getRawValue);

                if (left != null)
                {
                    UIKeyframeDopeSheet.renderShape(
                        keyframe,
                        context,
                        builder,
                        matrix,
                        this.keyframes.toGraphX(keyframe.getTick() - left.lx),
                        this.toGraphY(keyframe.getFactory().getY(keyframe.getValue()) + left.ly),
                        2,
                        c
                    );
                }
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(builder.end());
        context.batcher.unclip(context);
    }

    @Unique
    private boolean bbsplus$skipAutoHandleTools()
    {
        return (Object) this instanceof UIPoseTransformKeyframeGraph || this.sheet == null;
    }

    @Unique
    private boolean bbsplus$hasSelection()
    {
        return this.sheet.selection.hasAny();
    }

    @Unique
    private int bbsplus$handleColor(int color)
    {
        return Colors.setA(Colors.mulRGB(color, 0.68F), Math.min(Colors.getA(color), 0.72F));
    }

    @Unique
    private void bbsplus$layoutActionButtons()
    {
        int top = this.keyframes.area.y + 29;
        int verticalBottom = top
            + BBSPLUS_ACTION_COUNT * BBSPLUS_BUTTON_HEIGHT
            + (BBSPLUS_ACTION_COUNT - 1) * BBSPLUS_BUTTON_GAP
            + 2;

        if (verticalBottom <= this.keyframes.area.ey())
        {
            int x = this.keyframes.area.ex() - BBSPLUS_BUTTON_WIDTH - 4;

            for (int i = 0; i < BBSPLUS_ACTION_COUNT; i++)
            {
                this.bbsplus$actionX[i] = x;
                this.bbsplus$actionY[i] = top + i * (BBSPLUS_BUTTON_HEIGHT + BBSPLUS_BUTTON_GAP);
                this.bbsplus$actionW[i] = BBSPLUS_BUTTON_WIDTH;
            }

            this.bbsplus$actionPanelX = x - 2;
            this.bbsplus$actionPanelY = top - 2;
            this.bbsplus$actionPanelW = BBSPLUS_BUTTON_WIDTH + 2;
            this.bbsplus$actionPanelH = verticalBottom - top + 2;

            return;
        }

        int right = this.keyframes.area.ex() - 4;
        int left = right - BBSPLUS_BUTTON_WIDTH;
        int availableWidth = BBSPLUS_BUTTON_WIDTH;
        int columns = Math.max(1, Math.min(BBSPLUS_ACTION_COUNT,
            (availableWidth + BBSPLUS_BUTTON_GAP) / (24 + BBSPLUS_BUTTON_GAP)));
        int buttonWidth = Math.min(BBSPLUS_BUTTON_WIDTH,
            Math.max(1, (availableWidth - (columns - 1) * BBSPLUS_BUTTON_GAP) / columns));
        int usedWidth = columns * buttonWidth + (columns - 1) * BBSPLUS_BUTTON_GAP;
        int rows = (BBSPLUS_ACTION_COUNT + columns - 1) / columns;
        int panelHeight = rows * BBSPLUS_BUTTON_HEIGHT + (rows - 1) * BBSPLUS_BUTTON_GAP + 4;
        int adjustedTop = top;

        if (adjustedTop - 2 + panelHeight > this.keyframes.area.ey())
        {
            adjustedTop = Math.max(this.keyframes.area.y + 2, this.keyframes.area.ey() - panelHeight + 2);
        }

        int startX = right - usedWidth;

        for (int i = 0; i < BBSPLUS_ACTION_COUNT; i++)
        {
            int column = i % columns;
            int row = i / columns;

            this.bbsplus$actionX[i] = startX + column * (buttonWidth + BBSPLUS_BUTTON_GAP);
            this.bbsplus$actionY[i] = adjustedTop + row * (BBSPLUS_BUTTON_HEIGHT + BBSPLUS_BUTTON_GAP);
            this.bbsplus$actionW[i] = buttonWidth;
        }

        this.bbsplus$actionPanelX = startX - 2;
        this.bbsplus$actionPanelY = adjustedTop - 2;
        this.bbsplus$actionPanelW = usedWidth + 4;
        this.bbsplus$actionPanelH = panelHeight;
    }

    @Unique
    private void bbsplus$renderActionButton(UIContext context, int index, String fullLabel, String compactLabel, String shortLabel, int color)
    {
        int x = this.bbsplus$actionX[index];
        int y = this.bbsplus$actionY[index];
        int width = this.bbsplus$actionW[index];

        context.batcher.icon(Icons.CURVES, color, x, y);

        if (width >= 26)
        {
            context.batcher.text(width >= 90 ? fullLabel : (width >= 58 ? compactLabel : shortLabel), x + 18, y + 3, color, true);
        }
    }

    @Unique
    private boolean bbsplus$isActionButtonInside(int index, int mouseX, int mouseY)
    {
        return mouseX >= this.bbsplus$actionX[index]
            && mouseX <= this.bbsplus$actionX[index] + this.bbsplus$actionW[index]
            && mouseY >= this.bbsplus$actionY[index]
            && mouseY <= this.bbsplus$actionY[index] + BBSPLUS_BUTTON_HEIGHT;
    }

    @Unique
    private void bbsplus$zoomAxisTo(Scale axis, float anchor, double targetZoom)
    {
        double clamped = MathUtils.clamp(targetZoom, 0.01D, 1000D);
        double amount = clamped - axis.getZoom();

        if (amount != 0D)
        {
            axis.zoomAnchor(anchor, amount);
        }
    }

    @Unique
    private void bbsplus$applyAutoHandles(boolean clamped)
    {
        List<Keyframe> keyframes = this.sheet.channel.getKeyframes();
        int n = keyframes.size();

        if (n < 2)
        {
            return;
        }

        this.sheet.channel.preNotify();

        for (Integer idx : this.sheet.selection.getIndices())
        {
            Keyframe keyframe = this.sheet.channel.get(idx);

            if (keyframe == null)
            {
                continue;
            }

            keyframe.getInterpolation().setInterp(Interpolations.BEZIER);

            Keyframe prev = idx > 0 ? keyframes.get(idx - 1) : null;
            Keyframe next = idx < n - 1 ? keyframes.get(idx + 1) : null;
            int axes = this.bbsplus$getAxisCount(keyframe);

            for (int axis = 0; axis < axes; axis++)
            {
                this.bbsplus$applyAutoHandlesForAxis(keyframes, idx, axis, clamped);
            }
        }

        this.sheet.channel.postNotify();
        this.keyframes.triggerChange();
    }

    @Unique
    private void bbsplus$applyAutoVector()
    {
        List<Keyframe> keyframes = this.sheet.channel.getKeyframes();

        if (keyframes.size() < 2 || !this.sheet.selection.hasAny())
        {
            return;
        }

        List<Integer> selected = new ArrayList<>(this.sheet.selection.getIndices());
        int axes = this.bbsplus$getAxisCount(keyframes.get(0));
        boolean changed = false;

        this.sheet.channel.preNotify();

        for (int axis = 0; axis < axes; axis++)
        {
            final int axisIndex = axis;
            int handleAxis = axes == 1 ? -1 : axis;

            changed |= AutoVectorHandles.apply(
                keyframes,
                selected,
                handleAxis,
                (keyframe) -> this.bbsplus$getValue(keyframe, axisIndex),
                (keyframe, value) -> this.bbsplus$setValue(keyframe, axisIndex, value)
            );
        }

        this.sheet.channel.postNotify();

        if (changed)
        {
            this.keyframes.triggerChange();
        }
    }

    @Unique
    private void bbsplus$applyAutoHandlesForAxis(List<Keyframe> keyframes, int idx, int axis, boolean clamped)
    {
        int n = keyframes.size();
        Keyframe keyframe = keyframes.get(idx);
        Keyframe prev = idx > 0 ? keyframes.get(idx - 1) : null;
        Keyframe next = idx < n - 1 ? keyframes.get(idx + 1) : null;

        float curV = this.bbsplus$getValue(keyframe, axis);
        float curT = keyframe.getTick();

        float prevV = prev != null ? this.bbsplus$getValue(prev, axis) : curV;
        float nextV = next != null ? this.bbsplus$getValue(next, axis) : curV;
        float prevT = prev != null ? prev.getTick() : curT;
        float nextT = next != null ? next.getTick() : curT;

        float tangent;

        if (prev != null && next != null)
        {
            tangent = (nextV - prevV) / (nextT - prevT);
        }
        else if (next != null)
        {
            tangent = (nextV - curV) / (nextT - curT);
        }
        else
        {
            tangent = (curV - prevV) / (curT - prevT);
        }

        if (clamped && prev != null && next != null)
        {
            boolean isMax = curV >= prevV && curV >= nextV;
            boolean isMin = curV <= prevV && curV <= nextV;

            if (isMax || isMin)
            {
                tangent = 0F;
            }
        }

        float rx = (nextT - curT) / 3F;
        float lx = (curT - prevT) / 3F;

        if (prev == null) lx = rx;
        if (next == null) rx = lx;

        int handleAxis = this.bbsplus$getAxisCount(keyframe) == 1 ? -1 : axis;

        keyframe.rx = rx;
        keyframe.ry = tangent * rx;
        keyframe.lx = lx;
        keyframe.ly = -(tangent * lx);
    }

    @Unique
    private int bbsplus$getAxisCount(Keyframe keyframe)
    {
        return keyframe.getValue() instanceof Vector3f ? 3 : 1;
    }

    @Unique
    private float bbsplus$getValue(Keyframe keyframe, int axis)
    {
        Object value = keyframe.getValue();

        if (value instanceof Vector3f vector)
        {
            if (axis == 0) return vector.x;
            if (axis == 1) return vector.y;

            return vector.z;
        }

        IKeyframeFactory factory = keyframe.getFactory();

        return (float) factory.getY(value);
    }

    @Unique
    private float bbsplus$getRawValue(Keyframe keyframe, int axis)
    {
        return (float) keyframe.getFactory().getY(keyframe.getValue());
    }

    @Unique
    private void bbsplus$setValue(Keyframe keyframe, int axis, float newValue)
    {
        Object value = keyframe.getValue();

        if (value instanceof Vector3f vector)
        {
            Vector3f copy = new Vector3f(vector);

            if (axis == 0) copy.x = newValue;
            else if (axis == 1) copy.y = newValue;
            else copy.z = newValue;

            keyframe.setValue(copy, false);

            return;
        }

        keyframe.setValue(keyframe.getFactory().yToValue(newValue), false);
    }

    @Unique
    private int bbsplus$trackListWidth()
    {
        int labelWidth = this.keyframes.getLabelWidth();

        if (labelWidth <= 0)
        {
            return 0;
        }

        return Math.min(labelWidth, Math.max(0, this.keyframes.area.w - 80));
    }

    @Unique
    private boolean bbsplus$isTrackListInside(UIContext context)
    {
        int width = this.bbsplus$trackListWidth();

        return width > 0
            && context.mouseX >= this.keyframes.area.x
            && context.mouseX < this.keyframes.area.x + width
            && context.mouseY >= this.keyframes.area.y + 25
            && context.mouseY < this.keyframes.area.ey();
    }

    @Unique
    private void bbsplus$renderTrackList(UIContext context)
    {
        if (this.sheet == null)
        {
            return;
        }

        this.bbsplus$renderCurrentTrackHighlight(context);

        /* Reuse BBS's own dope-sheet label renderer so the curve editor's left
         * track column matches the normal timeline page, including current
         * visibility, colors, pose tab indentation, icons, and hover styling.
         * BBS considers the whole keyframe area hovered by row; while editing a
         * curve, keep that hover effect limited to the track-name column. */
        int mouseX = context.mouseX;

        if (!this.bbsplus$isMouseInsideTrackList(context))
        {
            context.mouseX = this.keyframes.area.ex() + 1;
        }

        try
        {
            this.keyframes.getDopeSheet().postRender(context);
        }
        finally
        {
            context.mouseX = mouseX;
        }
    }

    @Unique
    private boolean bbsplus$isMouseInsideTrackList(UIContext context)
    {
        int width = this.bbsplus$trackListWidth();

        return width > 0
            && context.mouseX >= this.keyframes.area.x
            && context.mouseX < this.keyframes.area.x + width
            && context.mouseY >= this.keyframes.area.y
            && context.mouseY < this.keyframes.area.ey();
    }

    @Unique
    private void bbsplus$renderCurrentTrackHighlight(UIContext context)
    {
        if ((Object) this instanceof UIPoseTransformKeyframeGraph graph)
        {
            for (UIKeyframeSheet active : graph.getActiveSheets())
            {
                this.bbsplus$renderTrackHighlight(context, active, graph.isPrimarySheet(active) ? 0.35F : 0.22F);
            }

            return;
        }

        this.bbsplus$renderTrackHighlight(context, this.sheet, 0.35F);
    }

    @Unique
    private void bbsplus$renderTrackHighlight(UIContext context, UIKeyframeSheet sheet, float alpha)
    {
        UIKeyframeDopeSheet dopeSheet = this.keyframes.getDopeSheet();
        int width = this.bbsplus$trackListWidth();

        if (sheet == null || width <= 0)
        {
            return;
        }

        int y = dopeSheet.getDopeSheetY(sheet);
        int h = (int) dopeSheet.getTrackHeight();

        if (h <= 0 || dopeSheet.getSheet(y + h / 2) != sheet)
        {
            return;
        }

        if (y + h < this.keyframes.area.y || y > this.keyframes.area.ey())
        {
            return;
        }

        int x = this.keyframes.area.x;
        int ex = x + width;

        context.batcher.gradientHBox(
            x,
            y,
            ex,
            y + h,
            Colors.setA(sheet.color, alpha),
            Colors.setA(sheet.color, 0.08F)
        );
    }

    @Unique
    private boolean bbsplus$isCurveEditable(UIKeyframeSheet track)
    {
        if (track == null)
        {
            return false;
        }

        IKeyframeFactory<?> factory = track.channel.getFactory();

        return KeyframeFactories.isNumeric(factory)
            || factory == KeyframeFactories.POSE_TRANSFORM
            || factory == KeyframeFactories.TRANSFORM;
    }

    @Unique
    private void bbsplus$restoreGraphAreaAfterCurveRender()
    {
        if (!this.bbsplus$areaShifted)
        {
            return;
        }

        this.keyframes.area.set(this.bbsplus$areaX, this.bbsplus$areaY, this.bbsplus$areaW, this.bbsplus$areaH);
        this.bbsplus$areaShifted = false;
    }
}
