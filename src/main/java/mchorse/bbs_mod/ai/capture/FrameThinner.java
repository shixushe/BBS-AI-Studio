package mchorse.bbs_mod.ai.capture;

import mchorse.bbs_mod.utils.resources.Pixels;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic frame thinning and downsampling (copilot spec sections 6.1 /
 * 10.7): over the hard upload cap means even-spacing THINNING, never dropping
 * at random; downsampling box-filters the long edge so no resolution beyond
 * what the vision call needs is ever retained.
 */
public class FrameThinner
{
    /**
     * Pick at most {@code max} frames by even spacing over capture order
     * (first and last always survive). Same input, same selection.
     */
    public static List<CapturedFrame> thin(List<CapturedFrame> frames, int max)
    {
        if (frames.size() <= max)
        {
            return new ArrayList<>(frames);
        }

        List<CapturedFrame> picked = new ArrayList<>();
        int n = frames.size();

        for (int i = 0; i < max; i++)
        {
            int index = Math.round(i * (n - 1) / (float) (max - 1));

            picked.add(frames.get(index));
        }

        return picked;
    }

    /** Enforce the hard cap on a sequence, in place. */
    public static void enforceCap(FrameSequence sequence)
    {
        if (sequence.frames.size() > FrameSequence.HARD_LIMIT)
        {
            List<CapturedFrame> kept = thin(sequence.frames, FrameSequence.HARD_LIMIT);

            sequence.frames.clear();
            sequence.frames.addAll(kept);
        }
    }

    /**
     * Box-filter a frame so its LONG edge equals {@code longEdge} (aspect
     * preserved). Pure integer math over the pixel arrays - no anti-aliasing
     * secrets, deterministic by construction.
     */
    public static Pixels downscale(Pixels source, int longEdge)
    {
        int longSide = Math.max(source.width, source.height);

        if (longSide <= longEdge)
        {
            return source;
        }

        float scale = longEdge / (float) longSide;
        int w = Math.max(1, Math.round(source.width * scale));
        int h = Math.max(1, Math.round(source.height * scale));
        int[] out = new int[w * h];

        for (int y = 0; y < h; y++)
        {
            int y0 = (int) (y / scale);
            int y1 = Math.min(source.height, Math.max(y0 + 1, (int) ((y + 1) / scale)));

            for (int x = 0; x < w; x++)
            {
                int x0 = (int) (x / scale);
                int x1 = Math.min(source.width, Math.max(x0 + 1, (int) ((x + 1) / scale)));

                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int count = 0;

                for (int sy = y0; sy < y1; sy++)
                {
                    for (int sx = x0; sx < x1; sx++)
                    {
                        int argb = source.getColor(sx, sy).getARGBColor();

                        a += (argb >> 24) & 0xff;
                        r += (argb >> 16) & 0xff;
                        g += (argb >> 8) & 0xff;
                        b += argb & 0xff;
                        count++;
                    }
                }

                out[y * w + x] = ((int) (a / count) << 24) | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
            }
        }

        return Pixels.fromIntArray(w, h, out);
    }
}
