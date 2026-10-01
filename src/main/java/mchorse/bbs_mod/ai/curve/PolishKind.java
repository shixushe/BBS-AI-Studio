package mchorse.bbs_mod.ai.curve;

/**
 * Semantic curve intents. These are the ONLY labels a language model may
 * output (plus a [0,1] strength and integer tick bounds) - never floating
 * point handle values. The label -> math table lives in
 * {@link CurvePolisher} and is written by hand, so the same input always
 * yields the same curve.
 */
public enum PolishKind
{
    /** Straight segment, handles zeroed. */
    LINEAR,

    /** Slow arrival into this keyframe. */
    EASE_IN,

    /** Fast departure out of this keyframe, slowing into the next. */
    EASE_OUT,

    /** S-curve on both sides of this keyframe. */
    EASE_IN_OUT,

    /** Hold this keyframe's value (alias of {@link #STOP}). */
    HOLD,

    /** Hold this keyframe's value (alias of {@link #HOLD}). */
    STOP,

    /** Oscillating settle around the target value. */
    ELASTIC,

    /** Single overshoot past the target value. */
    OVERSHOOT,

    /** Hard cut into this keyframe: fast attack, then constant. */
    SNAP,

    /** Same as {@link #SNAP} with a stronger default attack. */
    IMPACT,

    /** Recompute automatic clamped handles through this keyframe. */
    SMOOTH,

    /** Bulge an arc-shaped intermediate keyframe between this and the next. */
    ARC;

    /**
     * Parse a label from a model or user input. Returns null for anything
     * outside the table - callers must treat that as a contract violation,
     * not silently pick a default.
     */
    public static PolishKind fromLabel(String label)
    {
        if (label == null)
        {
            return null;
        }

        switch (label.trim().toLowerCase())
        {
            case "linear": return LINEAR;
            case "ease_in": return EASE_IN;
            case "ease_out": return EASE_OUT;
            case "ease_in_out": return EASE_IN_OUT;
            case "hold": return HOLD;
            case "stop": return STOP;
            case "elastic": return ELASTIC;
            case "overshoot": return OVERSHOOT;
            case "snap": return SNAP;
            case "impact": return IMPACT;
            case "smooth": return SMOOTH;
            case "arc": return ARC;
        }

        return null;
    }
}
