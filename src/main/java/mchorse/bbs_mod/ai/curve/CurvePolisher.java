package mchorse.bbs_mod.ai.curve;

import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 curve layer: turns semantic intents into interpolation types and bezier
 * handles. A pure function - same keys and same intents in, bitwise same
 * handles out. No network, no clock, no randomness, ever.
 *
 * <p>The intent -> math table is the heart of this class and it is HAND
 * WRITTEN. A language model may only ever produce {@link PolishOp}s (label +
 * normalized strength + tick bounds); every {@code lx/ly/rx/ry}, interpolation
 * type, duration and insertion here is derived locally and deterministically.</p>
 *
 * <p>Handle coordinate system (measured from {@code BezierUtils.get}, the only
 * consumer): for a segment {@code a -> b} of width {@code w} ticks and height
 * {@code h} value units, the curve uses {@code a.rx / w} and {@code a.ry / h}
 * as the first control point and {@code (w - b.lx) / w} and {@code (h + b.ly) / h}
 * as the second. So handle x distances are TICKS (kept clamped to [0, w]) and
 * handle y offsets are VALUE units.</p>
 *
 * <p>Ease families are anchored to CSS's standard curves at the unspecified
 * strength and deepen monotonically with it:</p>
 *
 * <ul>
 * <li>{@code EASE_IN} (on arrival): P1=(0.42, 0), P2=(1 - 0.42 * max(s, 0.05), 1)</li>
 * <li>{@code EASE_OUT} (on departure): P1=(0.42 * max(s, 0.05), 0), P2=(0.58, 1)</li>
 * <li>{@code EASE_IN_OUT}: P1=(0.5 - k, 0), P2=(0.5 + k, 1) with k = 0.08 + 0.34 * s,
 * which reproduces CSS ease-in-out (0.42, 0.58) at s = 0</li>
 * </ul>
 *
 * <p>Deliberate deviation from the copilot spec: the spec maps
 * {@code elastic}/{@code overshoot} to the AUTO interpolation (its rationale -
 * AUTO_CLAMPED clamps overshoot - predates the discovery that this fork
 * registers {@code elastic_out}/{@code back_out} as first-class easings with a
 * live strength parameter). Those are strictly better: deterministic,
 * parameterizable via the interpolation's v1 argument, and overshooting by
 * construction. The spec's own rule (section 13.1: reality of the codebase
 * wins) applies.</p>
 */
public class CurvePolisher
{
    /**
     * Numeric factories only: a channel whose values have no in-betweens
     * (strings, block states, links) must never receive bezier handles.
     *
     * <p>Mirrors {@code KeyframeFactories.isNumeric} - deliberately without
     * calling it, because that class's initialiser constructs the whole
     * factory registry (block states, actions, ...) and drags Minecraft
     * classes into any context that only needs the numeric check, such as the
     * plain-JVM sanity tests. Keep the two in sync; if a numeric factory is
     * ever added there, add it here.</p>
     */
    public static boolean isPolishable(IKeyframeFactory<?> factory)
    {
        return factory instanceof mchorse.bbs_mod.utils.keyframes.factories.FloatKeyframeFactory
            || factory instanceof mchorse.bbs_mod.utils.keyframes.factories.DoubleKeyframeFactory
            || factory instanceof mchorse.bbs_mod.utils.keyframes.factories.IntegerKeyframeFactory
            || factory instanceof mchorse.bbs_mod.utils.keyframes.factories.LongKeyframeFactory;
    }

    /**
     * Apply intents in order to the given keys (must be sorted by tick).
     * Mutates the keyframes and - for {@link PolishKind#ARC} - may insert
     * keyframes into the list. Callers own the undo snapshot; the committer
     * (L4) is the one that wraps this in a transaction.
     */
    public static void polish(List<Keyframe<Float>> keys, List<PolishOp> ops)
    {
        if (keys == null || keys.isEmpty() || ops == null)
        {
            return;
        }

        /* Hard runtime boundary: whatever slipped past the channel-level check
         * gets silently skipped here rather than handed meaningless floats. */
        if (!isPolishable(keys.get(0).getFactory()))
        {
            return;
        }

        for (PolishOp op : ops)
        {
            applyOp(keys, op);
        }
    }

    /**
     * Pure variant: deep-copies the keys (values included through the factory),
     * polishes the copies, returns them. The input list is left untouched,
     * which is what A/B preview needs.
     */
    public static List<Keyframe<Float>> polished(List<Keyframe<Float>> keys, IKeyframeFactory<Float> factory, List<PolishOp> ops)
    {
        List<Keyframe<Float>> copies = new ArrayList<>();

        for (Keyframe<Float> key : keys)
        {
            Keyframe<Float> copy = new Keyframe<>("key", factory, key.getTick(), factory.copy(key.getValue()));

            copy.copy(key);
            copies.add(copy);
        }

        polish(copies, ops);

        return copies;
    }

    private static void applyOp(List<Keyframe<Float>> keys, PolishOp op)
    {
        /* Collect target indices up front: ARC changes the list while it runs. */
        List<Integer> targets = new ArrayList<>();

        for (int i = 0; i < keys.size(); i++)
        {
            if (op.contains(keys.get(i).getTick()))
            {
                targets.add(i);
            }
        }

        switch (op.kind)
        {
            case LINEAR: targets.forEach(i -> linear(keys, i)); break;
            case EASE_IN: targets.forEach(i -> easeIn(keys, i, op)); break;
            case EASE_OUT: targets.forEach(i -> easeOut(keys, i, op)); break;
            case EASE_IN_OUT: targets.forEach(i -> easeInOut(keys, i, op)); break;
            case HOLD:
            case STOP: targets.forEach(i -> hold(keys, i, op)); break;
            case ELASTIC: targets.forEach(i -> elastic(keys, i, op)); break;
            case OVERSHOOT: targets.forEach(i -> overshoot(keys, i, op)); break;
            case SNAP: targets.forEach(i -> snap(keys, i, op, 0.5F)); break;
            case IMPACT: targets.forEach(i -> snap(keys, i, op, 0.3F)); break;
            case SMOOTH: targets.forEach(i -> keys.get(i).getInterpolation().setInterp(Interpolations.AUTO_CLAMPED)); break;
            case ARC: arc(keys, targets, op); break;
        }
    }

    /* Interpolation mutators. Segment width w is the neighbor distance; handles
     * are clamped into [0, w] because BezierUtils divides by it. */

    private static void linear(List<Keyframe<Float>> keys, int i)
    {
        Keyframe<Float> key = keys.get(i);

        key.getInterpolation().setInterp(Interpolations.LINEAR);
        key.lx = 0F;
        key.ly = 0F;
        key.rx = 0F;
        key.ry = 0F;
    }

    private static void easeIn(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        if (i <= 0)
        {
            return;
        }

        Keyframe<Float> prev = keys.get(i - 1);
        Keyframe<Float> key = keys.get(i);
        float w = key.getTick() - prev.getTick();
        float h = key.getValue() - prev.getValue();

        if (w <= 0F)
        {
            return;
        }

        float s = op.strengthOr(0.5F);
        float x1 = 0.42F;
        float x2 = 1F - 0.42F * Math.max(s, 0.05F);

        prev.getInterpolation().setInterp(Interpolations.BEZIER);
        key.getInterpolation().setInterp(Interpolations.BEZIER);
        prev.rx = clamp01(x1 * w, w);
        prev.ry = 0F * h;
        key.lx = clamp01((1F - x2) * w, w);
        key.ly = h * (1F - 1F);
    }

    private static void easeOut(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        if (i >= keys.size() - 1)
        {
            return;
        }

        Keyframe<Float> key = keys.get(i);
        Keyframe<Float> next = keys.get(i + 1);
        float w = next.getTick() - key.getTick();
        float h = next.getValue() - key.getValue();

        if (w <= 0F)
        {
            return;
        }

        float s = op.strengthOr(0.5F);
        float x1 = 0.42F * Math.max(s, 0.05F);
        float x2 = 0.58F;

        key.getInterpolation().setInterp(Interpolations.BEZIER);
        next.getInterpolation().setInterp(Interpolations.BEZIER);
        key.rx = clamp01(x1 * w, w);
        key.ry = 0F * h;
        next.lx = clamp01((1F - x2) * w, w);
        next.ly = h * (1F - 1F);
    }

    private static void easeInOut(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        float k = 0.08F + 0.34F * op.strengthOr(0.5F);
        float x1 = 0.5F - k;
        float x2 = 0.5F + k;

        if (i > 0)
        {
            Keyframe<Float> prev = keys.get(i - 1);
            Keyframe<Float> key = keys.get(i);
            float w = key.getTick() - prev.getTick();

            if (w > 0F)
            {
                float h = key.getValue() - prev.getValue();

                prev.getInterpolation().setInterp(Interpolations.BEZIER);
                key.getInterpolation().setInterp(Interpolations.BEZIER);
                prev.rx = clamp01(x1 * w, w);
                prev.ry = 0F;
                key.lx = clamp01((1F - x2) * w, w);
                key.ly = 0F;
            }
        }

        if (i < keys.size() - 1)
        {
            Keyframe<Float> key = keys.get(i);
            Keyframe<Float> next = keys.get(i + 1);
            float w = next.getTick() - key.getTick();

            if (w > 0F)
            {
                key.getInterpolation().setInterp(Interpolations.BEZIER);
                next.getInterpolation().setInterp(Interpolations.BEZIER);
                key.rx = clamp01(x1 * w, w);
                key.ry = 0F;
                next.lx = clamp01((1F - x2) * w, w);
                next.ly = 0F;
            }
        }
    }

    private static void hold(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        Keyframe<Float> key = keys.get(i);

        key.getInterpolation().setInterp(Interpolations.CONST);
        key.getInterpolation().setV1(0F);

        /* A specified strength becomes a forced hold length in ticks - that is
         * the only way "hold" gets to outlive the next keyframe. Unspecified
         * keeps the default: constant until the next key. */
        if (op.strength >= 0F && i < keys.size() - 1)
        {
            float gap = keys.get(i + 1).getTick() - key.getTick();

            key.setDuration(Math.max(0F, (float) Math.round(op.strength * gap)));
        }
    }

    private static void elastic(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        Keyframe<Float> key = keys.get(i);

        key.getInterpolation().setInterp(Interpolations.ELASTIC_OUT);

        /* Easings.ELASTIC adds v1 to the amplitude base (10); more negative
         * v1 = wilder, longer oscillation. Unspecified strength keeps 0. */
        if (op.strength >= 0F)
        {
            key.getInterpolation().setV1(-6F * op.strength);
        }
    }

    private static void overshoot(List<Keyframe<Float>> keys, int i, PolishOp op)
    {
        Keyframe<Float> key = keys.get(i);

        key.getInterpolation().setInterp(Interpolations.BACK_OUT);

        /* Easings.BACK adds v1 to c1 (default 1.70158); more v1 = deeper
         * single overshoot. Unspecified strength keeps 0 (classic back). */
        if (op.strength >= 0F)
        {
            key.getInterpolation().setV1(3F * op.strength);
        }
    }

    /**
     * Hard cut into this keyframe: the incoming segment front-loads its motion
     * into the first {@code attackFraction} of ticks (value reaches the target
     * early), then this keyframe holds constant. {@code defaultAttack} differs
     * between SNAP (0.5) and IMPACT (0.3).
     */
    private static void snap(List<Keyframe<Float>> keys, int i, PolishOp op, float defaultAttack)
    {
        Keyframe<Float> key = keys.get(i);

        key.getInterpolation().setInterp(Interpolations.CONST);
        key.setDuration(0F);

        if (i > 0)
        {
            Keyframe<Float> prev = keys.get(i - 1);
            float w = key.getTick() - prev.getTick();

            if (w > 0F)
            {
                float h = key.getValue() - prev.getValue();
                float attack = defaultAttack + (0.15F - defaultAttack) * op.strengthOr(0.5F);

                prev.getInterpolation().setInterp(Interpolations.BEZIER);
                prev.rx = clamp01((1F - attack) * w, w);
                prev.ry = h;
                key.lx = clamp01(0.42F * w, w);
                key.ly = 0F;
            }
        }
    }

    /**
     * Insert an intermediate keyframe halfway between each targeted key and
     * its successor, pushed off the straight line by a parabola bulge. The
     * inserted frame interpolates linearly; compose with SMOOTH afterwards for
     * a rounded arc. Bulge grows with strength and defaults to half.
     */
    private static void arc(List<Keyframe<Float>> keys, List<Integer> targets, PolishOp op)
    {
        float s = op.strengthOr(0.5F);

        /* Walk targets backwards: insertions shift the indices of later targets. */
        for (int t = targets.size() - 1; t >= 0; t--)
        {
            int i = targets.get(t);

            if (i >= keys.size() - 1)
            {
                continue;
            }

            Keyframe<Float> a = keys.get(i);
            Keyframe<Float> b = keys.get(i + 1);
            float w = b.getTick() - a.getTick();

            if (w <= 0F)
            {
                continue;
            }

            float ya = a.getValue();
            float yb = b.getValue();
            float h = yb - ya;
            float bulge = s * (0.25F * Math.abs(h) + 0.1F * w);
            float midTick = a.getTick() + w * 0.5F;
            float midValue = (ya + yb) * 0.5F + bulge;
            Keyframe<Float> mid = new Keyframe<>("arc", a.getFactory(), midTick, midValue);

            mid.getInterpolation().setInterp(Interpolations.LINEAR);
            keys.add(i + 1, mid);
        }
    }

    private static float clamp01(float value, float w)
    {
        return Math.max(0F, Math.min(w, value));
    }
}
