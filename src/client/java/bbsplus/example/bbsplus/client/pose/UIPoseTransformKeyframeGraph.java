package bbsplus.example.bbsplus.client.pose;

import bbsplus.example.bbsplus.client.keyframes.AutoBezierHandles;
import bbsplus.example.bbsplus.client.keyframes.AutoVectorHandles;
import bbsplus.example.bbsplus.client.keyframes.InterpolationInheritance;
import bbsplus.example.bbsplus.pose.PoseComponent;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.line.LineBuilder;
import mchorse.bbs_mod.graphics.line.SolidColorLineRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.KeyframeType;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeGraph;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Blender-style curve editor for a single transform / pose-bone track. Renders one coloured curve per
 * {@link PoseComponent} and lets each component's keyframe points and bezier
 * handles be dragged independently, reusing the multi-handle support already
 * present on {@link Keyframe} ({@code getRx(axis)} / {@code setRx(axis, v)}).
 *
 * <p>Modelled on BBS-FS's {@code UIVector3KeyframeGraph}; the three fixed axes
 * are generalised to an arbitrary list of pose components.</p>
 */
public class UIPoseTransformKeyframeGraph extends UIKeyframeGraph
{
    private static final String ACTIVE_SHEETS_STATE_KEY = "bbsplus_pose_active_sheets";

    /** Primary sheet plus any Shift-clicked linked bone sheets. */
    private final List<UIKeyframeSheet> activeSheets = new ArrayList<>();

    /** Components currently shown as curves. */
    protected final List<PoseComponent> components = new ArrayList<>();

    /** Visibility / lock state per component (Blender-style channel toggles). */
    protected final PoseChannelState channelState;

    /** Component whose point/handle is currently being dragged. */
    private PoseComponent draggingComponent;
    private KeyframeType lastHitType;
    /** The keyframe + component most recently hit by {@link #findKeyframe}. */
    private UIKeyframeSheet lastHitSheet;
    private Keyframe lastHitKeyframe;
    private PoseComponent lastHitComponent;

    /* Ctrl+middle-drag zoom (Blender-style) */
    private boolean zooming;
    private int zoomStartX, zoomStartY;
    private double zoomStartXZoom, zoomStartYZoom;
    private static final double ZOOM_SENSITIVITY = 0.01D;

    /**
     * Per-component selection layered on top of {@code sheet.selection}.
     * Maps a selected sheet + keyframe <em>index</em> to the set of component axes
     * selected on it. Indexed (not by object) to survive the keyframe-object
     * rebuild that {@code submitKeyframes -> channel.fromData} performs on every
     * drag release, mirroring how {@code sheet.selection} stores indices.
     */
    private final Map<UIKeyframeSheet, Map<Integer, Set<PoseComponent>>> axisSelection = new HashMap<>();

    /** Per-index original values captured at the start of a vertical drag. */
    private final Map<UIKeyframeSheet, Map<Integer, Map<PoseComponent, Float>>> dragSnapshot = new HashMap<>();
    private UIKeyframeSheet draggingSheet;

    /* G-key scalar editing, matching numeric curve editor gestures. */
    private boolean scalarEditing;
    private int scalarLastMouseX;
    private final Map<UIKeyframeSheet, Map<Integer, Map<PoseComponent, Float>>> scalarSnapshot = new HashMap<>();

    /* Channel/track sidebar layout */
    private static final int SIDEBAR_WIDTH = 150;
    private static final int ROW_HEIGHT = 14;
    private static final int ICON_SIZE = 16;
    private static final int ACTION_COUNT = 3;
    private static final int COMPACT_ROW_HEIGHT = 18;
    private static final int COMPACT_GAP = 2;
    private static final int COMPACT_PADDING = 2;
    /** Top margin mirroring {@code IUIKeyframeGraph.TOP_MARGIN} (time ruler). */
    private static final int TOP_MARGIN = 25;
    private final ControlPanelLayout controlPanelLayout = new ControlPanelLayout();

    public UIPoseTransformKeyframeGraph(UIKeyframes keyframes, UIKeyframeSheet sheet)
    {
        super(keyframes, sheet);

        this.activeSheets.add(sheet);
        this.channelState = PoseChannelState.get(sheet);

        /* Auto-hide unchanging curves on entry — unless the user has already
         * taken manual control of visibility, in which case we preserve their
         * chosen view across rebuilds (e.g. undo). */
        if (!this.channelState.isUserOverridden())
        {
            this.autoHideFlat(sheet);
        }

        this.rebuildComponents();
    }

    /**
     * Hide components whose value is constant across all keyframes, and reveal
     * the ones that do change. Runs on entry only while the user hasn't manually
     * toggled visibility; once they have, their view is kept as-is.
     */
    private void autoHideFlat(UIKeyframeSheet sheet)
    {
        List<Keyframe> keyframes = sheet.channel.getKeyframes();

        if (keyframes.size() < 2)
        {
            return;
        }

        for (PoseComponent component : PoseComponent.VALUES)
        {
            float reference = component.get(this.getTransform(keyframes.get(0).getValue()));
            boolean flat = true;

            for (int i = 1; i < keyframes.size(); i++)
            {
                float value = component.get(this.getTransform(keyframes.get(i).getValue()));

                if (Math.abs(value - reference) > 1.0E-5F)
                {
                    flat = false;
                    break;
                }
            }

            this.channelState.setHiddenAuto(component, flat);
        }
    }

    /** Rebuild the active component list from the current channel state (hidden/locked flags). */
    protected void rebuildComponents()
    {
        this.components.clear();

        for (PoseComponent component : PoseComponent.VALUES)
        {
            if (this.channelState.isEnabled(component))
            {
                this.components.add(component);
            }
        }
    }

    @Override
    public UIKeyframeSheet getLastSheet()
    {
        return this.sheet;
    }

    @Override
    public List<UIKeyframeSheet> getSheets()
    {
        return Collections.unmodifiableList(this.activeSheets);
    }

    public List<UIKeyframeSheet> getActiveSheets()
    {
        return this.getSheets();
    }

    public boolean isPrimarySheet(UIKeyframeSheet sheet)
    {
        return sheet == this.sheet;
    }

    public boolean isActiveSheet(UIKeyframeSheet sheet)
    {
        return this.activeSheets.contains(sheet);
    }

    public boolean canToggleLinkedSheet(UIKeyframeSheet sheet)
    {
        return sheet != null
            && sheet != this.sheet
            && this.sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM
            && sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM;
    }

    public boolean toggleLinkedSheet(UIKeyframeSheet sheet)
    {
        if (!this.canToggleLinkedSheet(sheet))
        {
            return false;
        }

        if (this.activeSheets.remove(sheet))
        {
            sheet.selection.clear();
            this.axisSelection.remove(sheet);
        }
        else
        {
            this.activeSheets.add(sheet);
        }

        this.pickSelected();
        this.saveViewport();

        return true;
    }

    public boolean closeLinkedSheets()
    {
        if (this.activeSheets.size() <= 1)
        {
            return false;
        }

        this.activeSheets.removeIf((sheet) ->
        {
            if (sheet == this.sheet)
            {
                return false;
            }

            sheet.selection.clear();
            this.axisSelection.remove(sheet);

            return true;
        });

        this.pickSelected();
        this.saveViewport();

        return true;
    }

    @Override
    public void saveState(MapType extra)
    {
        super.saveState(extra);

        ListType active = new ListType();

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            active.addString(sheet.id);
        }

        extra.put(ACTIVE_SHEETS_STATE_KEY, active);
    }

    @Override
    public void restoreState(MapType extra)
    {
        super.restoreState(extra);

        if (!extra.has(ACTIVE_SHEETS_STATE_KEY))
        {
            return;
        }

        ListType active = extra.getList(ACTIVE_SHEETS_STATE_KEY);

        this.activeSheets.clear();
        this.activeSheets.add(this.sheet);

        for (int i = 0; i < active.size(); i++)
        {
            UIKeyframeSheet sheet = this.keyframes.getDopeSheet().getSheet(active.getString(i));

            if (sheet == null || this.activeSheets.contains(sheet))
            {
                continue;
            }

            if (sheet == this.sheet || this.canToggleLinkedSheet(sheet))
            {
                this.activeSheets.add(sheet);
            }
        }

        this.axisSelection.keySet().removeIf((sheet) -> !this.activeSheets.contains(sheet));
    }

    private boolean isHidden(PoseComponent component)
    {
        return this.channelState.isHidden(component);
    }

    private boolean isLocked(PoseComponent component)
    {
        return this.channelState.isLocked(component);
    }

    private float getValue(Object value, PoseComponent component)
    {
        Transform transform = this.getTransform(value);

        return transform == null ? 0F : this.toDisplayValue(component, component.get(transform));
    }

    private float getRawComponentValue(Keyframe keyframe, int axis)
    {
        Transform transform = this.getTransform(keyframe.getValue());

        return transform == null ? 0F : PoseComponent.VALUES[axis].get(transform);
    }

    private Transform getTransform(Object value)
    {
        return value instanceof Transform transform ? transform : null;
    }

    private Object copyValue(Object value)
    {
        return this.copyValue(this.sheet, value);
    }

    private Object copyValue(UIKeyframeSheet sheet, Object value)
    {
        return sheet.channel.getFactory().copy(value);
    }

    private Transform copyTransformValue(Object value)
    {
        Transform transform = this.getTransform(value);

        return transform == null ? null : this.copyTransform(transform);
    }

    private Transform copyTransform(Transform transform)
    {
        Transform copy = new Transform();

        copy.copy(transform);

        return copy;
    }

    private Object copyValueWithTransform(Object sourceValue, Transform transform)
    {
        return this.copyValueWithTransform(this.sheet, sourceValue, transform);
    }

    private Object copyValueWithTransform(UIKeyframeSheet sheet, Object sourceValue, Transform transform)
    {
        Object copy = this.copyValue(sheet, sourceValue);
        Transform target = this.getTransform(copy);

        if (target != null)
        {
            target.copy(transform);
        }

        return copy;
    }

    private float toDisplayValue(PoseComponent component, float raw)
    {
        return this.isRotation(component) ? (float) Math.toDegrees(raw) : raw;
    }

    private float toRawValue(PoseComponent component, float display)
    {
        return this.isRotation(component) ? (float) Math.toRadians(display) : display;
    }

    private float toDisplayDelta(PoseComponent component, float raw)
    {
        return this.isRotation(component) ? (float) Math.toDegrees(raw) : raw;
    }

    private float toRawDelta(PoseComponent component, float display)
    {
        return this.isRotation(component) ? (float) Math.toRadians(display) : display;
    }

    private void setDisplayValue(Transform transform, PoseComponent component, float display)
    {
        component.set(transform, this.toRawValue(component, display));
    }

    private boolean isRotation(PoseComponent component)
    {
        return component.group == PoseComponent.Group.ROTATE;
    }

    /* View fitting across all visible components */

    @Override
    public void resetView()
    {
        /* Honour a remembered viewport instead of always fitting to all
         * keyframes, so zoom/pan survives leaving and re-entering the editor. */
        if (this.channelState.hasViewport())
        {
            this.keyframes.getXAxis().view(this.channelState.getViewXMin(), this.channelState.getViewXMax());
            this.yAxis.view(this.channelState.getViewYMin(), this.channelState.getViewYMax());
            return;
        }

        super.resetView();
    }

    /** Persist the current viewport so it can be restored on the next entry. */
    private void saveViewport()
    {
        this.channelState.saveViewport(
            this.keyframes.getXAxis().getMinValue(),
            this.keyframes.getXAxis().getMaxValue(),
            this.yAxis.getMinValue(),
            this.yAxis.getMaxValue()
        );
    }

    @Override
    public void resetViewY(UIKeyframeSheet current)
    {
        this.yAxis.set(0, 2);

        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();

            for (Keyframe frame : keyframes)
            {
                for (PoseComponent component : this.components)
                {
                    if (this.isHidden(component))
                    {
                        continue;
                    }

                    float value = this.getValue(frame.getValue(), component);

                    minY = Math.min(minY, value);
                    maxY = Math.max(maxY, value);
                }
            }
        }

        if (minY == Double.POSITIVE_INFINITY)
        {
            minY = -10;
            maxY = 10;
        }

        if (Math.abs(maxY - minY) < 0.01F)
        {
            this.yAxis.setShift(minY);
            this.yAxis.anchor(0.5F);
        }
        else
        {
            this.yAxis.viewOffset(minY, maxY, this.keyframes.area.h, 30);
        }
    }

    @Override
    public void render(UIContext context)
    {
        if (this.scalarEditing)
        {
            int dx = context.mouseX - this.scalarLastMouseX;

            if (dx != 0)
            {
                this.applyScalarDelta(dx);
                this.keyframes.triggerChange();
                this.scalarLastMouseX = context.mouseX;
            }
        }

        context.batcher.clip(this.keyframes.graphArea, context);
        this.renderGrid(context);
        this.renderGraph(context);
        context.batcher.unclip(context);

        if (this.scalarEditing)
        {
            String label = mchorse.bbs_mod.ui.UIKeys.TRANSFORMS_EDITING.get();
            mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();
            int x = this.keyframes.area.mx(font.getWidth(label));
            int y = this.keyframes.area.my(font.getHeight());

            context.batcher.textCard(label, x, y, Colors.WHITE, Colors.A50);
        }
    }

    @Override
    public void renderTopmostKeyframes(UIContext context)
    {
        /* Replaced by per-component points in renderGraphPoints. */
    }

    @Override
    protected void renderGraph(UIContext context)
    {
        KeyframeSegment segment = new KeyframeSegment();

        /* Curves */
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            for (PoseComponent component : this.components)
            {
                if (!this.isHidden(component))
                {
                    this.renderGraphLine(context, sheet, component, segment);
                }
            }
        }

        /* Points + handles */
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Matrix4f matrix = context.batcher.getContext().getMatrices().peek().getPositionMatrix();

        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            for (PoseComponent component : this.components)
            {
                if (!this.isHidden(component))
                {
                    this.renderGraphPoints(context, builder, matrix, sheet, component);
                }
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(builder.end());
    }

    private void renderGraphLine(UIContext context, UIKeyframeSheet sheet, PoseComponent component, KeyframeSegment segment)
    {
        List<Keyframe> keyframes = sheet.channel.getKeyframes();
        LineBuilder curveBuilder  = new LineBuilder(0.7F);
        LineBuilder handleBuilder = new LineBuilder(0.5F);
        int axis = component.axis();

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe frame = keyframes.get(i);
            Keyframe prev = i > 0 ? keyframes.get(i - 1) : null;
            int x = this.keyframes.toGraphX(frame.getTick());
            int y = this.toGraphY(this.getValue(frame.getValue(), component));

            if (i == 0 && x > this.keyframes.area.x)
            {
                curveBuilder.add(this.keyframes.area.x, y);
            }

            if (prev != null)
            {
                this.renderInterpolation(curveBuilder, segment, prev, frame, x, component);
            }

            curveBuilder.add(x, y);

            if (i == keyframes.size() - 1 && x < this.keyframes.area.ex())
            {
                curveBuilder.add(this.keyframes.area.ex(), y);
            }

            this.renderBezierHandles(handleBuilder, keyframes, i, prev, frame, x, y, axis, component);
        }

        int baseColor = sheet == this.sheet ? component.color : Colors.mulRGB(component.color, 0.68F);
        int curveColor = (this.isLocked(component)
            ? Colors.mulRGB(baseColor, 0.4F)
            : baseColor) | Colors.A100;

        int handleColor = Colors.mulRGB(baseColor, 0.55F) | Colors.A100;

        curveBuilder.render(context.batcher, SolidColorLineRenderer.get(Colors.COLOR.set(curveColor)));
        handleBuilder.render(context.batcher, SolidColorLineRenderer.get(Colors.COLOR.set(handleColor)));
    }

    private void renderInterpolation(LineBuilder lineBuilder, KeyframeSegment segment, Keyframe prev, Keyframe frame, int x, PoseComponent component)
    {
        IInterp interp = prev.getInterpolation().getInterp();
        int px = this.keyframes.toGraphX(prev.getTick());
        int py = this.toGraphY(this.getValue(prev.getValue(), component));

        if (interp == Interpolations.CONST)
        {
            lineBuilder.add(x, py);
            lineBuilder.push();
        }
        else if (interp != Interpolations.LINEAR)
        {
            float steps = 50F;

            for (int j = 1; j <= steps; j++)
            {
                float a = j / steps;

                segment.setup(prev, frame, prev.getTick() + a * (frame.getTick() - prev.getTick()));
                float interpolate = this.toGraphY(this.getValue(segment.createInterpolated(), component));

                lineBuilder.add(Lerps.lerp(px, x, a), interpolate);
            }
        }
    }

    private void renderBezierHandles(LineBuilder lineBuilder, List<Keyframe> keyframes, int index, Keyframe prev, Keyframe frame, int x, int y, int axis, PoseComponent component)
    {
        boolean add = false;
        AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, index, axis, axis, this::getRawComponentValue);

        if (right != null)
        {
            int rx = this.keyframes.toGraphX(frame.getTick() + right.rx);
            int ry = this.toGraphY(this.getValue(frame.getValue(), component) + this.toDisplayDelta(component, right.ry));

            lineBuilder.push();
            lineBuilder.add(x, y);
            lineBuilder.add(rx, ry);
            add = true;
        }

        AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, index, axis, axis, this::getRawComponentValue);

        if (prev != null && left != null)
        {
            int lx = this.keyframes.toGraphX(frame.getTick() - left.lx);
            int ly = this.toGraphY(this.getValue(frame.getValue(), component) + this.toDisplayDelta(component, left.ly));

            lineBuilder.push();
            lineBuilder.add(x, y);
            lineBuilder.add(lx, ly);
            add = true;
        }

        if (add)
        {
            lineBuilder.push();
            lineBuilder.add(x, y);
        }
    }

    private void renderGraphPoints(UIContext context, BufferBuilder builder, Matrix4f matrix, UIKeyframeSheet sheet, PoseComponent component)
    {
        List<Keyframe> keyframes = sheet.channel.getKeyframes();
        int axis = component.axis();
        int baseColor = sheet == this.sheet ? component.color : Colors.mulRGB(component.color, 0.68F);

        for (int i = 0; i < keyframes.size(); i++)
        {
            Keyframe frame = keyframes.get(i);
            Keyframe prev = i > 0 ? keyframes.get(i - 1) : null;
            int x1 = this.keyframes.toGraphX(frame.getTick());
            int y = this.toGraphY(this.getValue(frame.getValue(), component));

            boolean isPointHover = !this.isLocked(component)
                && this.isNear(x1, y, context.mouseX, context.mouseY);
            boolean toRemove = Window.isCtrlPressed() && isPointHover;

            if (this.keyframes.isSelecting())
            {
                isPointHover = isPointHover || this.keyframes.getGrabbingArea(context).isInside(x1, y);
            }

            boolean selected = this.isAxisSelected(sheet, i, component);
            int c = (selected || isPointHover ? Colors.WHITE : baseColor) | Colors.A100;

            if (toRemove)
            {
                c = Colors.RED | Colors.A100;
            }

            int offset = toRemove ? 4 : 3;

            UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, x1, y, offset, c);

            /* Handle endpoint dots: smaller and dimmed relative to the keyframe color */
            int handleDotColor = Colors.mulRGB(baseColor, 0.70F) | Colors.A100;
            AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, i, axis, axis, this::getRawComponentValue);

            if (right != null)
            {
                int rx = this.keyframes.toGraphX(frame.getTick() + right.rx);
                int ry = this.toGraphY(this.getValue(frame.getValue(), component) + this.toDisplayDelta(component, right.ry));

                UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, rx, ry, 2, handleDotColor);
            }

            AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, i, axis, axis, this::getRawComponentValue);

            if (prev != null && left != null)
            {
                int lx = this.keyframes.toGraphX(frame.getTick() - left.lx);
                int ly = this.toGraphY(this.getValue(frame.getValue(), component) + this.toDisplayDelta(component, left.ly));

                UIKeyframeDopeSheet.renderShape(frame, context, builder, matrix, lx, ly, 2, handleDotColor);
            }
        }
    }

    @Override
    public Pair<Keyframe, KeyframeType> findKeyframe(int mouseX, int mouseY)
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();

            for (Keyframe keyframe : keyframes)
            {
                int x = this.keyframes.toGraphX(keyframe.getTick());

                for (PoseComponent component : this.components)
                {
                    if (this.isHidden(component) || this.isLocked(component))
                    {
                        continue;
                    }

                    if (this.checkKeyframeHit(sheet, keyframe, x, mouseX, mouseY, component))
                    {
                        return new Pair<>(keyframe, this.lastHitType);
                    }
                }
            }
        }

        return null;
    }

    private boolean checkKeyframeHit(UIKeyframeSheet sheet, Keyframe keyframe, int x, int mouseX, int mouseY, PoseComponent component)
    {
        int axis = component.axis();
        int y = this.toGraphY(this.getValue(keyframe.getValue(), component));

        if (this.isNear(x, y, mouseX, mouseY))
        {
            this.draggingSheet = sheet;
            this.draggingComponent = component;
            this.lastHitType = KeyframeType.REGULAR;
            this.lastHitSheet = sheet;
            this.lastHitKeyframe = keyframe;
            this.lastHitComponent = component;
            return true;
        }

        List<Keyframe> keyframes = sheet.channel.getKeyframes();
        int index = keyframes.indexOf(keyframe);
        AutoBezierHandles.Handles left = AutoBezierHandles.left(keyframes, index, axis, axis, this::getRawComponentValue);

        if (left != null)
        {
            int lx = this.keyframes.toGraphX(keyframe.getTick() - left.lx);
            int ly = this.toGraphY(this.getValue(keyframe.getValue(), component) + this.toDisplayDelta(component, left.ly));

            if (this.isNear(lx, ly, mouseX, mouseY))
            {
                this.draggingSheet = sheet;
                this.draggingComponent = component;
                this.lastHitType = KeyframeType.LEFT_HANDLE;
                this.lastHitSheet = sheet;
                this.lastHitKeyframe = keyframe;
                this.lastHitComponent = component;
                return true;
            }
        }

        AutoBezierHandles.Handles right = AutoBezierHandles.right(keyframes, index, axis, axis, this::getRawComponentValue);

        if (right != null)
        {
            int rx = this.keyframes.toGraphX(keyframe.getTick() + right.rx);
            int ry = this.toGraphY(this.getValue(keyframe.getValue(), component) + this.toDisplayDelta(component, right.ry));

            if (this.isNear(rx, ry, mouseX, mouseY))
            {
                this.draggingSheet = sheet;
                this.draggingComponent = component;
                this.lastHitType = KeyframeType.RIGHT_HANDLE;
                this.lastHitSheet = sheet;
                this.lastHitKeyframe = keyframe;
                this.lastHitComponent = component;
                return true;
            }
        }

        return false;
    }

    /* ===== Per-component (axis-level) selection ===== */

    private boolean isAxisSelected(UIKeyframeSheet sheet, int index, PoseComponent component)
    {
        Set<PoseComponent> set = this.axisSelectionFor(sheet).get(index);
        return set != null && set.contains(component);
    }

    private void addAxisSelection(UIKeyframeSheet sheet, int index, PoseComponent component)
    {
        if (index >= 0)
        {
            this.axisSelectionFor(sheet).computeIfAbsent(index, k -> new HashSet<>()).add(component);
        }
    }

    private Map<Integer, Set<PoseComponent>> axisSelectionFor(UIKeyframeSheet sheet)
    {
        return this.axisSelection.computeIfAbsent(sheet, key -> new HashMap<>());
    }

    private void clearAxisSelection()
    {
        this.axisSelection.clear();
    }

    public boolean selectLastHitComponent(boolean additive)
    {
        if (this.lastHitSheet == null || this.lastHitKeyframe == null || this.lastHitComponent == null)
        {
            return false;
        }

        int index = this.indexOf(this.lastHitSheet, this.lastHitKeyframe);

        if (index < 0 || !this.lastHitSheet.selection.has(index))
        {
            return false;
        }

        if (!additive)
        {
            this.clearAxisSelection();
        }

        this.addAxisSelection(this.lastHitSheet, index, this.lastHitComponent);

        return true;
    }

    public boolean hasComponentSelection()
    {
        for (Map<Integer, Set<PoseComponent>> sheetSelection : this.axisSelection.values())
        {
            for (Set<PoseComponent> components : sheetSelection.values())
            {
                if (components != null && !components.isEmpty())
                {
                    return true;
                }
            }
        }

        return false;
    }

    public Transform applyTransformEditorChange(Keyframe panelKeyframe, Transform baseTransform, Consumer<Transform> transformer)
    {
        if (!this.hasComponentSelection())
        {
            return null;
        }

        UIKeyframeSheet panelSheet = this.getSheet(panelKeyframe);
        int panelIndex = panelSheet == null ? -1 : this.indexOf(panelSheet, panelKeyframe);
        Transform synced = baseTransform == null ? null : this.copyTransform(baseTransform);
        Transform editedBase = baseTransform == null ? null : this.copyTransform(baseTransform);
        boolean changed = false;

        if (editedBase != null)
        {
            transformer.accept(editedBase);
        }

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();
            Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

            for (Integer index : sheet.selection.getIndices())
            {
                if (index == null || index < 0 || index >= keyframes.size())
                {
                    continue;
                }

                Set<PoseComponent> selectedComponents = sheetAxes.get(index);

                if (selectedComponents == null || selectedComponents.isEmpty())
                {
                    continue;
                }

                Keyframe keyframe = keyframes.get(index);
                Object currentValue = keyframe.getValue();
                Transform current = this.getTransform(currentValue);

                if (current == null)
                {
                    continue;
                }

                Transform next = this.copyTransform(current);
                boolean keyframeChanged = false;

                if (editedBase == null || baseTransform == null)
                {
                    transformer.accept(next);
                }

                for (PoseComponent component : selectedComponents)
                {
                    if (!this.isLocked(component))
                    {
                        if (editedBase != null && baseTransform != null)
                        {
                            this.addComponentDelta(next, baseTransform, editedBase, component);
                        }

                        keyframeChanged = true;

                        if (synced != null && sheet == panelSheet && index == panelIndex)
                        {
                            this.copyComponent(synced, next, component);
                        }
                    }
                }

                if (!keyframeChanged)
                {
                    continue;
                }

                keyframe.preNotify();
                keyframe.setValue(this.copyValueWithTransform(sheet, currentValue, next), false);
                keyframe.postNotify();
                changed = true;
            }
        }

        if (changed)
        {
            this.keyframes.triggerChange();
        }

        return synced;
    }

    private void copyComponent(Transform target, Transform source, PoseComponent component)
    {
        component.set(target, component.get(source));
    }

    private void addComponentDelta(Transform target, Transform base, Transform edited, PoseComponent component)
    {
        component.set(target, component.get(target) + component.get(edited) - component.get(base));
    }

    private int indexOf(Keyframe keyframe)
    {
        return this.indexOf(this.sheet, keyframe);
    }

    private int indexOf(UIKeyframeSheet sheet, Keyframe keyframe)
    {
        return sheet.channel.getKeyframes().indexOf(keyframe);
    }

    private void seedAutoHandleDrag(Keyframe keyframe, KeyframeType type)
    {
        UIKeyframeSheet sheet = this.draggingSheet == null ? this.sheet : this.draggingSheet;

        if (sheet == null)
        {
            return;
        }

        List<Keyframe> keyframes = sheet.channel.getKeyframes();
        int index = keyframes.indexOf(keyframe);

        if (index < 0)
        {
            return;
        }

        if (type == KeyframeType.LEFT_HANDLE)
        {
            AutoBezierHandles.seedIncomingAuto(keyframes, index, PoseComponent.VALUES.length, this::getRawComponentValue);
        }
        else if (type == KeyframeType.RIGHT_HANDLE)
        {
            AutoBezierHandles.seedOutgoingAuto(keyframes, index, PoseComponent.VALUES.length, this::getRawComponentValue);
        }
    }

    @Override
    public void dragKeyframes(UIContext context, Pair<Keyframe, KeyframeType> type, int originalX, int originalY, float originalT, Object originalV)
    {
        if (type == null || this.draggingSheet == null || this.draggingComponent == null)
        {
            return;
        }

        Keyframe keyframe = type.a;
        PoseComponent component = this.draggingComponent;
        int axis = component.axis();
        float originalValue = this.getValue(originalV, component);

        if (type.b == KeyframeType.REGULAR)
        {
            this.ensureDragSnapshot();

            float offsetX = (float) this.keyframes.fromGraphX(originalX) - originalT;
            double offsetY = this.fromGraphY(originalY) - originalValue;

            float fx = (float) this.keyframes.fromGraphX(context.mouseX) - offsetX;
            double fy = this.fromGraphY(context.mouseY) - offsetY;

            if (!Window.isShiftPressed())
            {
                fx = Math.round(this.keyframes.fromGraphX(context.mouseX) - offsetX);
            }

            /* Horizontal: shift every selected keyframe by the same tick delta. */
            this.moveSelectedBy(fx - keyframe.getTick(), false);

            /* Vertical: shift every selected (keyframe, component) by the same
             * value delta, relative to each one's own snapshotted start value —
             * never collapsing them to a single shared value. */
            double valueDelta = fy - originalValue;
            this.applyValueDelta(valueDelta);
        }
        else if (type.b == KeyframeType.LEFT_HANDLE)
        {
            this.seedAutoHandleDrag(keyframe, KeyframeType.LEFT_HANDLE);

            float lx = -(float) (this.keyframes.fromGraphX(context.mouseX) - keyframe.getTick());
            float ly = (float) (this.fromGraphY(context.mouseY) - originalValue);

            keyframe.lx = lx;
            keyframe.ly = this.toRawDelta(component, ly);

            if (!Window.isShiftPressed())
            {
                keyframe.rx = lx;
                keyframe.ry = this.toRawDelta(component, -ly);
            }
        }
        else if (type.b == KeyframeType.RIGHT_HANDLE)
        {
            this.seedAutoHandleDrag(keyframe, KeyframeType.RIGHT_HANDLE);

            float rx = (float) (this.keyframes.fromGraphX(context.mouseX) - keyframe.getTick());
            float ry = (float) (this.fromGraphY(context.mouseY) - originalValue);

            keyframe.rx = rx;
            keyframe.ry = this.toRawDelta(component, ry);

            if (!Window.isShiftPressed())
            {
                keyframe.lx = rx;
                keyframe.ly = this.toRawDelta(component, -ry);
            }
        }

        this.keyframes.triggerChange();
    }

    /* ===== Vertical drag: snapshot + per-component relative delta ===== */

    /**
     * Determine which components a vertical drag should move for the sheet/keyframe
     * at the given index. Uses the axis-level selection when present; otherwise
     * falls back to the single clicked component (so a plain click-drag on one
     * point moves just that point).
     */
    private Set<PoseComponent> draggedAxes(UIKeyframeSheet sheet, int index, Keyframe keyframe)
    {
        Set<PoseComponent> set = this.axisSelectionFor(sheet).get(index);

        if (set != null && !set.isEmpty())
        {
            return set;
        }

        if (sheet == this.draggingSheet && this.draggingComponent != null && keyframe == this.lastHitKeyframe)
        {
            return java.util.Collections.singleton(this.draggingComponent);
        }

        return java.util.Collections.emptySet();
    }

    /** Capture each dragged component's starting value once per drag gesture. */
    private void ensureDragSnapshot()
    {
        if (!this.dragSnapshot.isEmpty())
        {
            return;
        }

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            Map<Integer, Map<PoseComponent, Float>> sheetSnapshot = new HashMap<>();

            for (Integer index : sheet.selection.getIndices())
            {
                Keyframe keyframe = sheet.channel.get(index);

                if (keyframe == null)
                {
                    continue;
                }

                Set<PoseComponent> axes = this.draggedAxes(sheet, index, keyframe);

                if (axes.isEmpty())
                {
                    continue;
                }

                Transform value = this.getTransform(keyframe.getValue());

                if (value == null)
                {
                    continue;
                }

                Map<PoseComponent, Float> values = new HashMap<>();

                for (PoseComponent component : axes)
                {
                    if (!this.isLocked(component))
                    {
                        values.put(component, this.getValue(value, component));
                    }
                }

                if (!values.isEmpty())
                {
                    sheetSnapshot.put(index, values);
                }
            }

            if (!sheetSnapshot.isEmpty())
            {
                this.dragSnapshot.put(sheet, sheetSnapshot);
            }
        }
    }

    /** Apply a shared value delta to every snapshotted (index, component). */
    private void applyValueDelta(double valueDelta)
    {
        for (Map.Entry<UIKeyframeSheet, Map<Integer, Map<PoseComponent, Float>>> sheetEntry : this.dragSnapshot.entrySet())
        {
            UIKeyframeSheet sheet = sheetEntry.getKey();

            for (Map.Entry<Integer, Map<PoseComponent, Float>> entry : sheetEntry.getValue().entrySet())
            {
                Keyframe keyframe = sheet.channel.get(entry.getKey());

                if (keyframe == null)
                {
                    continue;
                }

                Object sourceValue = keyframe.getValue();
                Transform pose = this.copyTransformValue(sourceValue);

                if (pose == null)
                {
                    continue;
                }

                for (Map.Entry<PoseComponent, Float> axisEntry : entry.getValue().entrySet())
                {
                    this.setDisplayValue(pose, axisEntry.getKey(), (float) (axisEntry.getValue() + valueDelta));
                }

                keyframe.setValue(this.copyValueWithTransform(sheet, sourceValue, pose), false);
            }
        }
    }

    public boolean startScalarEditing(UIContext context)
    {
        if (this.scalarEditing)
        {
            return true;
        }

        if (!this.hasComponentSelection())
        {
            return false;
        }

        if (!this.hasEditableScalarSelection())
        {
            return false;
        }

        this.captureSelectedComponentSnapshot(this.scalarSnapshot);
        this.keyframes.cacheKeyframes();
        this.scalarLastMouseX = context.mouseX;
        this.scalarEditing = true;

        return true;
    }

    public boolean isScalarEditing()
    {
        return this.scalarEditing;
    }

    public ScalarEditResult handleScalarEditingKey(UIContext context)
    {
        if (!this.scalarEditing)
        {
            return ScalarEditResult.NONE;
        }

        if (context.isPressed(GLFW.GLFW_KEY_ENTER))
        {
            this.stopScalarEditing(true);
            return ScalarEditResult.ACCEPT;
        }

        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            this.stopScalarEditing(false);
            return ScalarEditResult.CANCEL;
        }

        return ScalarEditResult.CONSUME;
    }

    public ScalarEditResult handleScalarEditingMouse(UIContext context)
    {
        if (!this.scalarEditing)
        {
            return ScalarEditResult.NONE;
        }

        if (context.mouseButton == 0)
        {
            this.stopScalarEditing(true);
            return ScalarEditResult.ACCEPT;
        }

        if (context.mouseButton == 1)
        {
            this.stopScalarEditing(false);
            return ScalarEditResult.CANCEL;
        }

        return ScalarEditResult.CONSUME;
    }

    private void stopScalarEditing(boolean accept)
    {
        if (!this.scalarEditing)
        {
            return;
        }

        if (!accept)
        {
            this.restoreScalarSnapshot();
            this.keyframes.triggerChange();
        }

        this.scalarEditing = false;
        this.scalarSnapshot.clear();
        this.keyframes.submitKeyframes();
    }

    private boolean hasEditableScalarSelection()
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

            for (Integer index : sheet.selection.getIndices())
            {
                Set<PoseComponent> axes = sheetAxes.get(index);

                if (axes == null || axes.isEmpty())
                {
                    continue;
                }

                for (PoseComponent component : axes)
                {
                    if (!this.isLocked(component))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void captureSelectedComponentSnapshot(Map<UIKeyframeSheet, Map<Integer, Map<PoseComponent, Float>>> target)
    {
        target.clear();

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            Map<Integer, Map<PoseComponent, Float>> sheetSnapshot = new HashMap<>();
            Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

            for (Integer index : sheet.selection.getIndices())
            {
                Keyframe keyframe = sheet.channel.get(index);

                if (keyframe == null)
                {
                    continue;
                }

                Set<PoseComponent> axes = sheetAxes.get(index);

                if (axes == null || axes.isEmpty())
                {
                    continue;
                }

                Transform value = this.getTransform(keyframe.getValue());

                if (value == null)
                {
                    continue;
                }

                Map<PoseComponent, Float> values = new HashMap<>();

                for (PoseComponent component : axes)
                {
                    if (!this.isLocked(component))
                    {
                        values.put(component, component.get(value));
                    }
                }

                if (!values.isEmpty())
                {
                    sheetSnapshot.put(index, values);
                }
            }

            if (!sheetSnapshot.isEmpty())
            {
                target.put(sheet, sheetSnapshot);
            }
        }
    }

    private void restoreScalarSnapshot()
    {
        for (Map.Entry<UIKeyframeSheet, Map<Integer, Map<PoseComponent, Float>>> sheetEntry : this.scalarSnapshot.entrySet())
        {
            UIKeyframeSheet sheet = sheetEntry.getKey();

            for (Map.Entry<Integer, Map<PoseComponent, Float>> entry : sheetEntry.getValue().entrySet())
            {
                Keyframe keyframe = sheet.channel.get(entry.getKey());

                if (keyframe == null)
                {
                    continue;
                }

                Object sourceValue = keyframe.getValue();
                Transform pose = this.copyTransformValue(sourceValue);

                if (pose == null)
                {
                    continue;
                }

                for (Map.Entry<PoseComponent, Float> axisEntry : entry.getValue().entrySet())
                {
                    axisEntry.getKey().set(pose, axisEntry.getValue());
                }

                keyframe.setValue(this.copyValueWithTransform(sheet, sourceValue, pose), false);
            }
        }
    }

    private void applyScalarDelta(double dx)
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

            for (Integer index : sheet.selection.getIndices())
            {
                Keyframe keyframe = sheet.channel.get(index);

                if (keyframe == null)
                {
                    continue;
                }

                Object sourceValue = keyframe.getValue();
                Transform pose = this.copyTransformValue(sourceValue);

                if (pose == null)
                {
                    continue;
                }

                Set<PoseComponent> axes = sheetAxes.get(index);

                if (axes == null || axes.isEmpty())
                {
                    continue;
                }

                for (PoseComponent component : axes)
                {
                    if (!this.isLocked(component))
                    {
                        component.set(pose, component.get(pose) + (float) (dx * this.scalarModifier(component)));
                    }
                }

                keyframe.setValue(this.copyValueWithTransform(sheet, sourceValue, pose), false);
            }
        }
    }

    private double scalarModifier(PoseComponent component)
    {
        double normal;
        double weak;
        double strong;

        switch (component.group)
        {
            case SCALE:
                normal = 0.05D;
                weak = 0.015D;
                strong = 0.25D;
                break;
            case ROTATE:
                normal = Math.toRadians(1D);
                weak = Math.toRadians(0.1D);
                strong = Math.toRadians(5D);
                break;
            case TRANSLATE:
            default:
                normal = 1D / 32D;
                weak = 1D / 128D;
                strong = 1D / 2D;
                break;
        }

        if (Window.isAltPressed())
        {
            return weak;
        }

        if (Window.isShiftPressed())
        {
            return strong;
        }

        return normal;
    }

    public enum ScalarEditResult
    {
        NONE,
        CONSUME,
        ACCEPT,
        CANCEL;

        public boolean handled()
        {
            return this != NONE;
        }
    }

    @Override
    public void setValue(Object value, boolean unmergeable)
    {
        Keyframe selected = this.getSelected();

        if (selected == null)
        {
            return;
        }

        IKeyframeFactory factory = this.sheet.channel.getFactory();
        UIKeyframeSheet selectedSheet = this.getSheet(selected);

        if (selectedSheet == null)
        {
            return;
        }

        Object selectedValue = selected.getValue();
        Transform pose = this.copyTransformValue(selectedValue);

        if (pose == null)
        {
            return;
        }

        if (value instanceof Transform)
        {
            pose.copy((Transform) value);
        }
        else if (value instanceof Number && this.draggingComponent != null)
        {
            this.setDisplayValue(pose, this.draggingComponent, ((Number) value).floatValue());
        }

        selectedSheet.setValue(this.copyValueWithTransform(selectedSheet, selectedValue, pose), selectedSheet.channel.getFactory().copy(selectedValue), unmergeable);
    }

    /* ===== Selection lifecycle: keep axisSelection in sync, reset drag snapshot ===== */

    @Override
    public void clearSelection()
    {
        super.clearSelection();
        this.clearAxisSelection();
    }

    /**
     * Select all keyframes — and on each one, every visible (non-hidden) component
     * — so "select all" lights up and can move every curve point, not just the
     * keyframe rows.
     */
    @Override
    public void selectAll()
    {
        this.clearAxisSelection();
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            sheet.selection.all();
        }

        this.selectVisibleAxes(0F, 0);
        this.pickSelected();
    }

    /**
     * Select all keyframes on one side of {@code tick} (direction &lt; 0 = left,
     * &gt; 0 = right), and their visible components. This is the "select left /
     * right" action used heavily for trimming animations.
     */
    @Override
    public void selectAfter(float tick, int direction)
    {
        this.clearAxisSelection();
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            sheet.selection.after(tick, direction);
        }

        this.selectVisibleAxes(tick, direction);
        this.pickSelected();
    }

    /**
     * For every currently-selected keyframe index, add all visible, non-locked
     * components to the axis selection. When {@code direction} is non-zero, the
     * keyframe must also lie on the requested side of {@code tick} (mirroring
     * {@link mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeSelection#after}).
     */
    private void selectVisibleAxes(float tick, int direction)
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            for (Integer index : sheet.selection.getIndices())
            {
                Keyframe keyframe = sheet.channel.get(index);

                if (keyframe == null)
                {
                    continue;
                }

                if (direction < 0 && keyframe.getTick() > tick) continue;
                if (direction > 0 && keyframe.getTick() < tick) continue;

                for (PoseComponent component : this.components)
                {
                    if (!this.isHidden(component) && !this.isLocked(component))
                    {
                        this.addAxisSelection(sheet, index, component);
                    }
                }
            }
        }
    }

    @Override
    public void pickKeyframe(Keyframe keyframe)
    {
        super.pickKeyframe(keyframe);

        /* BBS selects keyframes as whole rows. This graph has an extra component
         * selection layer, so a plain click must explicitly select the hit
         * component; otherwise the keyframe is selected but no curve point is
         * highlighted or moved as a component. */
        if (this.lastHitKeyframe != null && this.lastHitComponent != null)
        {
            int index = this.indexOf(this.lastHitSheet, this.lastHitKeyframe);

            if (this.lastHitSheet.selection.has(index))
            {
                if (!Window.isShiftPressed())
                {
                    this.clearAxisSelection();
                }

                this.addAxisSelection(this.lastHitSheet, index, this.lastHitComponent);
            }
        }
    }

    @Override
    public void selectByX(int mouseX)
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();

            for (int i = 0; i < keyframes.size(); i++)
            {
                Keyframe keyframe = keyframes.get(i);
                int x = this.keyframes.toGraphX(keyframe.getTick());

                for (PoseComponent component : this.components)
                {
                    if (this.isHidden(component) || this.isLocked(component))
                    {
                        continue;
                    }

                    int y = this.toGraphY(this.getValue(keyframe.getValue(), component));

                    if (this.isNear(x, y, mouseX, 0))
                    {
                        sheet.selection.add(i);
                        this.addAxisSelection(sheet, i, component);
                    }
                }
            }
        }

        this.pickSelected();
    }

    @Override
    public void selectInArea(mchorse.bbs_mod.ui.utils.Area area)
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();

            for (int i = 0; i < keyframes.size(); i++)
            {
                Keyframe keyframe = keyframes.get(i);
                int x = this.keyframes.toGraphX(keyframe.getTick());

                for (PoseComponent component : this.components)
                {
                    if (this.isHidden(component) || this.isLocked(component))
                    {
                        continue;
                    }

                    int y = this.toGraphY(this.getValue(keyframe.getValue(), component));

                    if (area.isInside(x, y))
                    {
                        sheet.selection.add(i);
                        this.addAxisSelection(sheet, i, component);
                    }
                }
            }
        }

        this.pickSelected();
    }

    @Override
    public void handleMouse(UIContext context, int lastX, int lastY)
    {
        if (this.zooming)
        {
            /* Horizontal mouse motion zooms the time (X) axis, vertical motion
             * zooms the value (Y) axis. Right/up = zoom in, left/down = zoom out.
             * Diagonal motion zooms both at once. Anchored at the drag start so
             * that point stays put. */
            int dx = context.mouseX - this.zoomStartX;
            int dy = this.zoomStartY - context.mouseY; // up is positive

            double xZoom = this.zoomStartXZoom * Math.exp(dx * ZOOM_SENSITIVITY);
            double yZoom = this.zoomStartYZoom * Math.exp(dy * ZOOM_SENSITIVITY);

            float xAnchor = mchorse.bbs_mod.ui.utils.Scale.getAnchorX(context, this.keyframes.graphArea);
            float yAnchor = mchorse.bbs_mod.ui.utils.Scale.getAnchorY(context, this.keyframes.graphArea);

            this.zoomAxisTo(this.keyframes.getXAxis(), xAnchor, xZoom);
            this.zoomAxisTo(this.yAxis, yAnchor, yZoom);

            return;
        }

        super.handleMouse(context, lastX, lastY);
    }

    /** Set an axis to an absolute zoom level while keeping the anchor point fixed. */
    private void zoomAxisTo(mchorse.bbs_mod.ui.utils.Scale axis, float anchor, double targetZoom)
    {
        double current = axis.getZoom();
        double clamped = mchorse.bbs_mod.utils.MathUtils.clamp(targetZoom, 0.01D, 1000D);
        double amount = clamped - current;

        if (amount != 0)
        {
            axis.zoomAnchor(anchor, amount);
        }
    }

    @Override
    public void mouseReleased(UIContext context)
    {
        super.mouseReleased(context);
        this.dragSnapshot.clear();
        this.zooming = false;
        this.saveViewport();
    }

    @Override
    public void mouseScrolled(UIContext context)
    {
        super.mouseScrolled(context);
        this.saveViewport();
    }

    @Override
    public boolean addKeyframe(int mouseX, int mouseY)
    {
        float tick = (float) this.keyframes.fromGraphX(mouseX);

        if (!Window.isShiftPressed())
        {
            tick = Math.round(tick);
        }

        UIKeyframeSheet sheet = this.sheet;
        float y = (float) this.fromGraphY(mouseY);
        IKeyframeFactory factory = sheet.channel.getFactory();

        Object poseValue;
        KeyframeSegment segment = sheet.channel.find(tick);

        if (segment != null)
        {
            poseValue = factory.copy(segment.createInterpolated());
            Transform pose = this.getTransform(poseValue);

            if (pose == null)
            {
                return false;
            }

            this.applyClosestComponent(pose, y);
        }
        else
        {
            poseValue = factory.createEmpty();
            Transform pose = this.getTransform(poseValue);

            if (pose == null)
            {
                return false;
            }

            this.applyClosestComponent(pose, y);
        }

        Keyframe previous = InterpolationInheritance.previousForNewKeyframe(sheet.channel.getKeyframes(), tick);
        Keyframe keyframe = this.addKeyframe(sheet, tick, poseValue);

        InterpolationInheritance.inherit(keyframe, previous);

        return true;
    }

    /** Snap the click's Y value onto whichever visible component is nearest. */
    private void applyClosestComponent(Transform pose, float y)
    {
        PoseComponent closest = null;
        float closestDist = Float.MAX_VALUE;

        for (PoseComponent component : this.components)
        {
            if (this.isHidden(component) || this.isLocked(component))
            {
                continue;
            }

            float dist = Math.abs(this.getValue(pose, component) - y);

            if (dist < closestDist)
            {
                closestDist = dist;
                closest = component;
            }
        }

        if (closest != null)
        {
            this.setDisplayValue(pose, closest, y);
        }
    }

    /* ===== Auto-smooth (Blender "Free / Auto" handle mode) ===== */

    /**
     * For every selected (keyframe, axis) pair, set the interpolation to BEZIER
     * and compute Catmull-Rom-style automatic handles so the curve passes through
     * each point with a smooth tangent derived from its neighbours.
     *
     * <p>Handle formula (matching Blender's "Auto" handle):
     * <pre>
     *   tangent = (nextValue - prevValue) / (nextTick - prevTick)
     *   rx = (nextTick - curTick) / 3       right handle time offset
     *   ry = tangent * rx                   right handle value offset
     *   lx = (curTick - prevTick) / 3       left handle time offset (stored positive)
     *   ly = -(tangent * lx)                left handle value offset
     * </pre>
     * For endpoints (no prev or next), the tangent is derived from the single
     * available neighbour so the curve still leaves/arrives smoothly.</p>
     */
    private void applyAutoSmooth()
    {
        this.applyAutoHandles(false);
    }

    private void applyAutoClamped()
    {
        this.applyAutoHandles(true);
    }

    private void applyAutoVector()
    {
        boolean changed = false;

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();

            if (keyframes.size() < 2 || !sheet.selection.hasAny())
            {
                continue;
            }

            sheet.channel.preNotify();

            for (PoseComponent component : PoseComponent.VALUES)
            {
                if (this.isLocked(component))
                {
                    continue;
                }

                List<Integer> selected = this.selectedIndicesFor(sheet, component);

                if (selected.isEmpty())
                {
                    continue;
                }

                for (Integer leftIndex : AutoVectorHandles.affectedSegmentLeftIndices(
                    keyframes,
                    selected,
                    (keyframe) -> component.get(this.getTransform(keyframe.getValue()))
                ))
                {
                    AutoBezierHandles.seedOutgoingAuto(keyframes, leftIndex, PoseComponent.VALUES.length, this::getRawComponentValue);
                }

                changed |= AutoVectorHandles.apply(
                    keyframes,
                    selected,
                    component.axis(),
                    (keyframe) -> component.get(this.getTransform(keyframe.getValue())),
                    (keyframe, value) ->
                    {
                        Object sourceValue = keyframe.getValue();
                        Transform transform = this.copyTransformValue(sourceValue);

                        if (transform == null)
                        {
                            return;
                        }

                        component.set(transform, value);
                        keyframe.setValue(this.copyValueWithTransform(sheet, sourceValue, transform), false);
                    }
                );
            }

            sheet.channel.postNotify();
        }

        if (changed)
        {
            this.keyframes.triggerChange();
        }
    }

    private List<Integer> selectedIndicesFor(UIKeyframeSheet sheet, PoseComponent component)
    {
        List<Integer> indices = new ArrayList<>();
        Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

        for (Integer index : sheet.selection.getIndices())
        {
            Set<PoseComponent> axes = sheetAxes.get(index);

            if (axes != null && axes.contains(component))
            {
                indices.add(index);
            }
        }

        return indices;
    }

    /**
     * Set automatic bezier handles on the selected (keyframe, component) pairs.
     *
     * <p>{@code clamped == false} = Blender "Auto / Free": the handle tangent is
     * the Catmull-Rom slope through the neighbours, giving a smooth pass-through.</p>
     *
     * <p>{@code clamped == true} = Blender "Auto Clamped": same as Auto, except a
     * keyframe that is a local maximum or minimum gets a flat (horizontal) handle,
     * so the curve never overshoots past the keyframe's own value. This keeps
     * eased motion from bouncing past peaks/valleys.</p>
     */
    private void applyAutoHandles(boolean clamped)
    {
        boolean changed = false;

        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            List<Keyframe> keyframes = sheet.channel.getKeyframes();
            int n = keyframes.size();

            if (n < 2 || !sheet.selection.hasAny())
            {
                continue;
            }

            sheet.channel.preNotify();

            Map<Integer, Set<PoseComponent>> sheetAxes = this.axisSelectionFor(sheet);

            for (Integer idx : sheet.selection.getIndices())
            {
                Keyframe keyframe = sheet.channel.get(idx);

                if (keyframe == null)
                {
                    continue;
                }

                if (AutoBezierHandles.isAuto(keyframe))
                {
                    AutoBezierHandles.seedOutgoingAuto(keyframes, idx, PoseComponent.VALUES.length, this::getRawComponentValue);
                }
                else
                {
                    keyframe.getInterpolation().setInterp(Interpolations.BEZIER);
                }

                changed = true;

                Keyframe prev = idx > 0     ? keyframes.get(idx - 1) : null;
                Keyframe next = idx < n - 1 ? keyframes.get(idx + 1) : null;

                Set<PoseComponent> axes = sheetAxes.get(idx);
                if (axes == null || axes.isEmpty()) continue;

                for (PoseComponent component : axes)
                {
                    if (this.isLocked(component))
                    {
                        continue;
                    }

                    int axis = component.axis();
                    Transform currentTransform = this.getTransform(keyframe.getValue());

                    if (currentTransform == null)
                    {
                        continue;
                    }

                    float curV  = component.get(currentTransform);
                    float curT  = keyframe.getTick();

                    Transform prevTransform = prev != null ? this.getTransform(prev.getValue()) : null;
                    Transform nextTransform = next != null ? this.getTransform(next.getValue()) : null;
                    float prevV = prevTransform != null ? component.get(prevTransform) : curV;
                    float nextV = nextTransform != null ? component.get(nextTransform) : curV;
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

                    /* Auto Clamped: flatten handles at local extrema to avoid overshoot. */
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

                    keyframe.rx = rx;
                    keyframe.ry = tangent * rx;
                    keyframe.lx = lx;
                    keyframe.ly = -(tangent * lx);
                }
            }

            sheet.channel.postNotify();
        }

        if (changed)
        {
            this.keyframes.triggerChange();
        }
    }

    /* ===== Blender-style channel sidebar (show/hide/lock) ===== */

    private int bbsplus$controlPanelWidth()
    {
        return Math.min(SIDEBAR_WIDTH, this.keyframes.area.w / 2);
    }

    private boolean hasAnySelection()
    {
        for (UIKeyframeSheet sheet : this.activeSheets)
        {
            if (sheet.selection.hasAny())
            {
                return true;
            }
        }

        return false;
    }

    private int sidebarX()
    {
        return this.keyframes.area.ex() - this.bbsplus$controlPanelWidth();
    }

    private int sidebarRowY(int index)
    {
        return this.keyframes.area.y + TOP_MARGIN + index * ROW_HEIGHT;
    }

    private ControlPanelLayout bbsplus$layoutControlPanel()
    {
        int fullBottom = this.bbsplus$vectorButtonY() + ICON_SIZE + 4;

        if (fullBottom <= this.keyframes.area.ey())
        {
            int x = this.sidebarX();
            int width = this.bbsplus$controlPanelWidth();

            this.controlPanelLayout.vertical = true;
            this.controlPanelLayout.x = x;
            this.controlPanelLayout.y = this.sidebarRowY(0) - 2;
            this.controlPanelLayout.w = width;
            this.controlPanelLayout.h = fullBottom - this.controlPanelLayout.y;

            for (int i = 0; i < PoseComponent.VALUES.length; i++)
            {
                this.controlPanelLayout.setChannel(i, x, this.sidebarRowY(i) - 2, width, ROW_HEIGHT);
            }

            this.controlPanelLayout.setAction(0, x, this.bbsplus$smoothButtonY(), width, ICON_SIZE);
            this.controlPanelLayout.setAction(1, x, this.bbsplus$clampedButtonY(), width, ICON_SIZE);
            this.controlPanelLayout.setAction(2, x, this.bbsplus$vectorButtonY(), width, ICON_SIZE);

            return this.controlPanelLayout;
        }

        int right = this.keyframes.area.ex() - 2;
        int left = right - this.bbsplus$controlPanelWidth();
        int top = this.keyframes.area.y + TOP_MARGIN + 2;
        int availableHeight = Math.max(1, this.keyframes.area.ey() - top - 2);

        this.controlPanelLayout.flow(left, right, top, 72, 46);

        if (this.controlPanelLayout.h > availableHeight)
        {
            /* Preserve every visibility and lock control in very narrow
             * layouts. Labels disappear automatically when a cell is too
             * narrow, but the two controls remain available. */
            this.controlPanelLayout.flow(left, right, top, 34, 46);
        }

        if (this.controlPanelLayout.h > availableHeight)
        {
            int adjustedTop = Math.max(this.keyframes.area.y, this.keyframes.area.ey() - this.controlPanelLayout.h);
            this.controlPanelLayout.offsetY(adjustedTop - this.controlPanelLayout.y);
        }

        return this.controlPanelLayout;
    }

    @Override
    public void postRender(UIContext context)
    {
        super.postRender(context);

        ControlPanelLayout layout = this.bbsplus$layoutControlPanel();

        /* Panel background — extends one extra row for the smooth button */
        context.batcher.box(layout.x, layout.y, layout.x + layout.w, layout.y + layout.h, Colors.A75);

        for (int i = 0; i < PoseComponent.VALUES.length; i++)
        {
            PoseComponent component = PoseComponent.VALUES[i];
            int x = layout.channelX[i];
            int y = layout.channelY[i];
            int width = layout.channelW[i];
            boolean hidden = this.isHidden(component);
            boolean locked = this.isLocked(component);

            int eyeX = x + (layout.vertical ? 2 : 1);
            int lockX = eyeX + ICON_SIZE;
            int labelX = lockX + ICON_SIZE + 2;

            Icon eye = hidden ? Icons.INVISIBLE : Icons.VISIBLE;
            Icon lock = locked ? Icons.LOCKED : Icons.UNLOCKED;

            int eyeColor = hidden ? (Colors.mulRGB(Colors.WHITE, 0.4F) | Colors.A100) : Colors.WHITE;
            int lockColor = locked ? Colors.YELLOW : (Colors.mulRGB(Colors.WHITE, 0.6F) | Colors.A100);

            context.batcher.icon(eye, eyeColor, eyeX, y);
            context.batcher.icon(lock, lockColor, lockX, y);

            int labelColor = hidden
                ? Colors.mulRGB(component.color, 0.4F) | Colors.A100
                : component.color | Colors.A100;

            if (labelX + 18 <= x + width)
            {
                context.batcher.text(component.id, labelX, y + 3, labelColor, true);
            }
        }

        /* Separator + handle-type buttons below the component rows */
        boolean hasSelection = this.hasAnySelection();

        if (layout.vertical)
        {
            int sepY = this.sidebarRowY(PoseComponent.VALUES.length) + 2;
            context.batcher.box(layout.x + 2, sepY, layout.x + layout.w - 2, sepY + 1, Colors.setA(Colors.WHITE, 0.15F));
        }

        /* Dimmed state uses an opaque gray RGB (alpha tinting alone doesn't
         * reliably dim textured icons). */
        int buttonColor = hasSelection ? Colors.WHITE : (Colors.mulRGB(Colors.WHITE, 0.4F) | Colors.A100);

        this.bbsplus$renderAction(context, layout, 0, "auto smooth", "smooth", "S", buttonColor);
        this.bbsplus$renderAction(context, layout, 1, "auto clamped", "clamped", "C", buttonColor);
        this.bbsplus$renderAction(context, layout, 2, "auto vector", "vector", "V", buttonColor);
    }

    private void bbsplus$renderAction(UIContext context, ControlPanelLayout layout, int index, String fullLabel, String compactLabel, String shortLabel, int color)
    {
        int x = layout.actionX[index];
        int y = layout.actionY[index];
        int width = layout.actionW[index];
        String label = width >= 90 ? fullLabel : (width >= 58 ? compactLabel : shortLabel);

        context.batcher.icon(Icons.CURVES, color, x + 2, y);

        if (width >= 26)
        {
            context.batcher.text(label, x + ICON_SIZE + 4, y + 3, color, true);
        }
    }

    private int bbsplus$smoothButtonY()
    {
        return this.sidebarRowY(PoseComponent.VALUES.length) + 5;
    }

    private int bbsplus$clampedButtonY()
    {
        return this.bbsplus$smoothButtonY() + ROW_HEIGHT + 2;
    }

    private int bbsplus$vectorButtonY()
    {
        return this.bbsplus$clampedButtonY() + ROW_HEIGHT + 2;
    }

    @Override
    public boolean mouseClicked(UIContext context)
    {
        if (this.handleScalarEditingMouse(context).handled())
        {
            return true;
        }

        /* Ctrl + middle button: start Blender-style drag-zoom (intercepts the
         * base class's middle-button pan). */
        if (context.mouseButton == 2 && Window.isCtrlPressed()
            && this.keyframes.graphArea.isInside(context))
        {
            this.zooming = true;
            this.zoomStartX = context.mouseX;
            this.zoomStartY = context.mouseY;
            this.zoomStartXZoom = this.keyframes.getXAxis().getZoom();
            this.zoomStartYZoom = this.yAxis.getZoom();
            return true;
        }

        ControlPanelLayout layout = this.bbsplus$layoutControlPanel();

        if (!layout.contains(context.mouseX, context.mouseY))
        {
            return super.mouseClicked(context);
        }

        for (int i = 0; i < PoseComponent.VALUES.length; i++)
        {
            PoseComponent component = PoseComponent.VALUES[i];
            int x = layout.channelX[i];

            if (!layout.containsChannel(i, context.mouseX, context.mouseY))
            {
                continue;
            }

            int eyeX  = x + (layout.vertical ? 2 : 1);
            int lockX = eyeX + ICON_SIZE;

            if (context.mouseX >= eyeX && context.mouseX < eyeX + ICON_SIZE)
            {
                if (Window.isCtrlPressed())
                {
                    this.channelState.soloOrReveal(component);
                }
                else
                {
                    this.channelState.setHidden(component, !this.isHidden(component));
                }

                return true;
            }

            if (context.mouseX >= lockX && context.mouseX < lockX + ICON_SIZE)
            {
                this.channelState.setLocked(component, !this.isLocked(component));
                return true;
            }

            return true;
        }

        for (int i = 0; i < ACTION_COUNT; i++)
        {
            if (!layout.containsAction(i, context.mouseX, context.mouseY))
            {
                continue;
            }

            if (this.hasAnySelection())
            {
                UIUtils.playClick();

                if (i == 0)
                {
                    this.applyAutoSmooth();
                }
                else if (i == 1)
                {
                    this.applyAutoClamped();
                }
                else
                {
                    this.applyAutoVector();
                }
            }

            return true;
        }

        return true;
    }

    private static class ControlPanelLayout
    {
        private final int[] channelX = new int[PoseComponent.VALUES.length];
        private final int[] channelY = new int[PoseComponent.VALUES.length];
        private final int[] channelW = new int[PoseComponent.VALUES.length];
        private final int[] channelH = new int[PoseComponent.VALUES.length];
        private final int[] actionX = new int[ACTION_COUNT];
        private final int[] actionY = new int[ACTION_COUNT];
        private final int[] actionW = new int[ACTION_COUNT];
        private final int[] actionH = new int[ACTION_COUNT];
        private boolean vertical;
        private int x;
        private int y;
        private int w;
        private int h;

        private void flow(int left, int right, int top, int channelWidth, int actionWidth)
        {
            this.vertical = false;

            int totalWidth = PoseComponent.VALUES.length * channelWidth
                + ACTION_COUNT * actionWidth
                + (PoseComponent.VALUES.length + ACTION_COUNT - 1) * COMPACT_GAP
                + COMPACT_PADDING * 2;
            int panelWidth = Math.min(Math.max(1, right - left), totalWidth);
            int panelX = right - panelWidth;
            int innerWidth = Math.max(1, panelWidth - COMPACT_PADDING * 2);
            int cursorX = 0;
            int row = 0;

            for (int i = 0; i < PoseComponent.VALUES.length + ACTION_COUNT; i++)
            {
                int desiredWidth = i < PoseComponent.VALUES.length ? channelWidth : actionWidth;
                int itemWidth = Math.min(desiredWidth, innerWidth);

                if (cursorX > 0 && cursorX + itemWidth > innerWidth)
                {
                    cursorX = 0;
                    row++;
                }

                int itemX = panelX + COMPACT_PADDING + cursorX;
                int itemY = top + COMPACT_PADDING + row * COMPACT_ROW_HEIGHT;

                if (i < PoseComponent.VALUES.length)
                {
                    this.setChannel(i, itemX, itemY, itemWidth, ICON_SIZE);
                }
                else
                {
                    this.setAction(i - PoseComponent.VALUES.length, itemX, itemY, itemWidth, ICON_SIZE);
                }

                cursorX += itemWidth + COMPACT_GAP;
            }

            this.x = panelX;
            this.y = top;
            this.w = panelWidth;
            this.h = COMPACT_PADDING * 2 + (row + 1) * COMPACT_ROW_HEIGHT;
        }

        private void setChannel(int index, int x, int y, int w, int h)
        {
            this.channelX[index] = x;
            this.channelY[index] = y;
            this.channelW[index] = w;
            this.channelH[index] = h;
        }

        private void setAction(int index, int x, int y, int w, int h)
        {
            this.actionX[index] = x;
            this.actionY[index] = y;
            this.actionW[index] = w;
            this.actionH[index] = h;
        }

        private void offsetY(int amount)
        {
            this.y += amount;

            for (int i = 0; i < this.channelY.length; i++)
            {
                this.channelY[i] += amount;
            }

            for (int i = 0; i < this.actionY.length; i++)
            {
                this.actionY[i] += amount;
            }
        }

        private boolean contains(int mouseX, int mouseY)
        {
            return mouseX >= this.x && mouseX <= this.x + this.w
                && mouseY >= this.y && mouseY <= this.y + this.h;
        }

        private boolean containsChannel(int index, int mouseX, int mouseY)
        {
            return mouseX >= this.channelX[index] && mouseX <= this.channelX[index] + this.channelW[index]
                && mouseY >= this.channelY[index] && mouseY <= this.channelY[index] + this.channelH[index];
        }

        private boolean containsAction(int index, int mouseX, int mouseY)
        {
            return mouseX >= this.actionX[index] && mouseX <= this.actionX[index] + this.actionW[index]
                && mouseY >= this.actionY[index] && mouseY <= this.actionY[index] + this.actionH[index];
        }
    }
}
