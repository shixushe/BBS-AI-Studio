package mchorse.bbs_mod.ai.curve;

/**
 * One polish operation: a semantic label plus a normalized strength, applied
 * to the keyframes whose tick falls inside {@link #fromTick}..{@link #toTick}.
 *
 * <p>Strength is the only free parameter and it lives in [0, 1]. It is always
 * normalized at construction; NaN/whatever-beyond means "unspecified" and
 * every consumer falls back to its documented default, keeping the whole
 * pipeline reproducible.</p>
 */
public class PolishOp
{
    public final PolishKind kind;

    /** Normalized [0, 1]; negative means unspecified. */
    public final float strength;

    /** Inclusive tick window; NaN on either side means "unbounded". */
    public final float fromTick;

    public final float toTick;

    public PolishOp(PolishKind kind)
    {
        this(kind, -1F);
    }

    public PolishOp(PolishKind kind, float strength)
    {
        this(kind, strength, Float.NaN, Float.NaN);
    }

    public PolishOp(PolishKind kind, float strength, float fromTick, float toTick)
    {
        this.kind = kind;
        this.strength = Float.isFinite(strength) ? Math.max(0F, Math.min(1F, strength)) : -1F;
        this.fromTick = Float.isFinite(fromTick) ? fromTick : Float.NEGATIVE_INFINITY;
        this.toTick = Float.isFinite(toTick) ? toTick : Float.POSITIVE_INFINITY;
    }

    public boolean contains(float tick)
    {
        return tick >= this.fromTick && tick <= this.toTick;
    }

    /** Strength with an op specific fallback for the "unspecified" case. */
    public float strengthOr(float fallback)
    {
        return this.strength < 0F ? fallback : this.strength;
    }

    @Override
    public String toString()
    {
        return this.kind + "(strength=" + this.strength + ", range=" + this.fromTick + ".." + this.toTick + ")";
    }
}
