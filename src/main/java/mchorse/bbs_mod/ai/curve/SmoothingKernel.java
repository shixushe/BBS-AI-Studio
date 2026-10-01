package mchorse.bbs_mod.ai.curve;

import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.List;

/**
 * Sliding-window value smoothing for "this whole segment is too jittery"
 * requests. Ticks are preserved; only the values are re-computed as a
 * triangular-weighted average of the keys within the window. Deterministic
 * and idempotent-safe: smoothing an already smooth (constant) channel is a
 * no-op within float rounding.
 */
public class SmoothingKernel
{
    /**
     * @param window half-width in keys (1 = each value averages with its
     *               immediate neighbors); values below 1 are a no-op
     */
    public static void smooth(List<Keyframe<Float>> keys, int window)
    {
        if (keys == null || keys.size() < 3 || window < 1)
        {
            return;
        }

        int n = keys.size();
        float[] smoothed = new float[n];

        /* Endpoints are anchors - poses and contacts live on them - so they
         * keep their exact values and only the interior gets averaged. */
        smoothed[0] = keys.get(0).getValue();
        smoothed[n - 1] = keys.get(n - 1).getValue();

        for (int i = 1; i < n - 1; i++)
        {
            double total = 0D;
            double weight = 0D;

            for (int j = Math.max(0, i - window); j <= Math.min(n - 1, i + window); j++)
            {
                /* Triangular kernel: closest samples dominate. */
                double w = 1D - Math.abs(i - j) / (double) (window + 1);

                total += keys.get(j).getValue() * w;
                weight += w;
            }

            smoothed[i] = (float) (total / weight);
        }

        for (int i = 0; i < n; i++)
        {
            keys.get(i).setValue(smoothed[i]);
        }
    }
}
