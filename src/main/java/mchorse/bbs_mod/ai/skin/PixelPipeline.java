package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.utils.resources.Pixels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pixel-art post pipeline between the image model's 1024px output and a
 * usable skin (copilot spec section 10.7). Steps 3-4 live here as pure
 * deterministic integer math; the UV step is a no-op by construction because
 * the generation prompt demands an unwrapped layout (the model is TOLD the
 * layout; guessing UV regions from an arbitrary image is exactly the
 * "let the model guess positions" the spec forbids).
 *
 * <p>The命根子 of pixel art is the hard edge: box filtering only (no
 * bilinear), palette quantization, zero dithering.</p>
 */
public class PixelPipeline
{
    /**
     * Downscale to {@code size}x{@code size} with a box filter, then
     * quantize to {@code colors} - the full pipeline, in that order.
     */
    public static Pixels process(Pixels source, int size, int colors)
    {
        return quantize(boxDownscale(source, size), colors);
    }

    /** Box-filter downscale to a square (box = no intermediate colors). Upscaling is refused. */
    public static Pixels boxDownscale(Pixels source, int size)
    {
        if (Math.max(source.width, source.height) <= size)
        {
            return source;
        }

        int[] out = new int[size * size];

        for (int y = 0; y < size; y++)
        {
            int y0 = y * source.height / size;
            int y1 = Math.max(y0 + 1, (y + 1) * source.height / size);

            for (int x = 0; x < size; x++)
            {
                int x0 = x * source.width / size;
                int x1 = Math.max(x0 + 1, (x + 1) * source.width / size);

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

                out[y * size + x] = ((int) (a / count) << 24) | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
            }
        }

        Pixels pixels = Pixels.fromSize(size, size);

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                pixels.setColor(x, y, new mchorse.bbs_mod.utils.colors.Color().set(out[y * size + x]));
            }
        }

        return pixels;
    }

    /**
     * Quantize to a frequency-ranked palette (top {@code colors} most common,
     * ties broken by ARGB value so ties stay deterministic), every pixel
     * mapped to its nearest palette entry by squared RGB distance. Alpha at
     * or below 8 quantizes to fully transparent (skin margins stay hollow).
     */
    public static Pixels quantize(Pixels source, int colors)
    {
        Map<Integer, Integer> frequency = new HashMap<>();

        for (int i = 0, c = source.getCount(); i < c; i++)
        {
            int argb = source.getColor(i).getARGBColor();

            if (((argb >> 24) & 0xff) <= 8)
            {
                continue;
            }

            frequency.merge(argb & 0xffffff, 1, Integer::sum);
        }

        List<Integer> palette = new ArrayList<>(frequency.keySet());

        palette.sort((a, b) ->
        {
            int by = frequency.get(b) - frequency.get(a);

            return by != 0 ? by : Integer.compare(a, b);
        });

        if (palette.size() > colors)
        {
            palette.subList(colors, palette.size()).clear();
        }

        Map<Integer, Integer> nearest = new HashMap<>();
        Pixels out = Pixels.fromSize(source.width, source.height);

        for (int i = 0, c = source.getCount(); i < c; i++)
        {
            int argb = source.getColor(i).getARGBColor();
            int alpha = (argb >> 24) & 0xff;
            int x = i % source.width;
            int y = i / source.width;

            if (alpha <= 8)
            {
                out.setColor(x, y, new mchorse.bbs_mod.utils.colors.Color().set(0, 0, 0, 0));

                continue;
            }

            int key = argb & 0xffffff;
            int mapped = nearest.computeIfAbsent(key, k -> nearestOf(k, palette));

            out.setColor(x, y, new mchorse.bbs_mod.utils.colors.Color().set(0xff000000 | mapped));
        }

        return out;
    }

    private static int nearestOf(int rgb, List<Integer> palette)
    {
        int best = palette.get(0);
        long bestDistance = Long.MAX_VALUE;

        for (int candidate : palette)
        {
            long distance = distance(rgb, candidate);

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private static long distance(int a, int b)
    {
        long dr = ((a >> 16) & 0xff) - ((b >> 16) & 0xff);
        long dg = ((a >> 8) & 0xff) - ((b >> 8) & 0xff);
        long db = (a & 0xff) - (b & 0xff);

        return dr * dr + dg * dg + db * db;
    }
}
