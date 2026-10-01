package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.skin.PixelPipeline;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;

/**
 * Standalone checks for the M7 pixel pipeline
 * ({@code java -cp "main;test;slf4j;logging;joml;dfu;mc" mchorse.bbs_mod.ai.PixelPipelineTest}).
 *
 * <p>The bar from the copilot spec (section 10.7): hard edges only - no
 * anti-aliasing middle colors survive quantization - and the same input
 * produces the same output, every time.</p>
 */
public class PixelPipelineTest
{
    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        downscaleDeterminism();
        quantizeHardness();
        quantizeDeterminism();
        alphaStaysHollow();

        System.out.println("\n" + (failures == 0 ? "ALL PASS" : failures + " FAILURES") + " (" + checks + " checks)");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    private static Pixels noise(int w, int h)
    {
        Pixels pixels = Pixels.fromSize(w, h);
        long seed = 42;

        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                seed = seed * 6364136223846793005L + 1442695040888963407L;

                int r = (int) ((seed >> 33) & 0xff);
                int g = (int) ((seed >> 41) & 0xff);
                int b = (int) ((seed >> 49) & 0xff);

                pixels.setColor(x, y, new Color().set(0xff000000 | (r << 16) | (g << 8) | b));
            }
        }

        return pixels;
    }

    private static void downscaleDeterminism()
    {
        Pixels first = PixelPipeline.boxDownscale(noise(128, 128), 64);
        Pixels second = PixelPipeline.boxDownscale(noise(128, 128), 64);

        equal(64, first.width, "downscale: target size");
        check(same(first, second), "downscale deterministic");

        /* Upscaling requests are refused (return the source) */
        Pixels small = Pixels.fromSize(16, 16);

        equal(16, PixelPipeline.boxDownscale(small, 64).width, "no upscale");
    }

    private static void quantizeHardness()
    {
        /* Two pure halves: after quantization to 2 colors, zero mixed pixels */
        Pixels half = Pixels.fromSize(32, 32);

        for (int y = 0; y < 32; y++)
        {
            for (int x = 0; x < 32; x++)
            {
                half.setColor(x, y, new Color().set(x < 16 ? 0xffff0000 : 0xff00ff00));
            }
        }

        Pixels quantized = PixelPipeline.quantize(half, 4);

        for (int i = 0, c = quantized.getCount(); i < c; i++)
        {
            int argb = quantized.getColor(i).getARGBColor() & 0xffffff;

            check(argb == 0xff0000 || argb == 0xff00, "quantized pixel is a palette member");
        }

        /* Same input, same output */
        check(same(quantized, PixelPipeline.quantize(half, 4)), "quantize deterministic");
    }

    private static void quantizeDeterminism()
    {
        Pixels first = PixelPipeline.process(noise(64, 64), 32, 16);
        Pixels second = PixelPipeline.process(noise(64, 64), 32, 16);

        equal(32, first.width, "process: target size");
        check(same(first, second), "process deterministic");

        /* Palette cap actually caps: count distinct colors */
        java.util.Set<Integer> distinct = new java.util.HashSet<>();

        for (int i = 0, c = first.getCount(); i < c; i++)
        {
            distinct.add(first.getColor(i).getARGBColor() & 0xffffff);
        }

        check(distinct.size() <= 16, "palette cap respected (" + distinct.size() + ")");
    }

    private static void alphaStaysHollow()
    {
        Pixels transparent = Pixels.fromSize(8, 8);

        for (int i = 0, c = transparent.getCount(); i < c; i++)
        {
            transparent.setColor(i % 8, i / 8, new Color().set(0, 0, 0, 0));
        }

        Pixels quantized = PixelPipeline.quantize(transparent, 8);

        for (int i = 0, c = quantized.getCount(); i < c; i++)
        {
            equal(0, (quantized.getColor(i).getARGBColor() >> 24) & 0xff, "transparent stays transparent");
        }
    }

    private static boolean same(Pixels a, Pixels b)
    {
        if (a.width != b.width || a.height != b.height)
        {
            return false;
        }

        for (int i = 0, c = a.getCount(); i < c; i++)
        {
            if (a.getColor(i).getARGBColor() != b.getColor(i).getARGBColor())
            {
                return false;
            }
        }

        return true;
    }

    private static void check(boolean condition, String label)
    {
        checks++;

        if (!condition)
        {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    private static void equal(Object expected, Object actual, String label)
    {
        checks++;

        if (!java.util.Objects.equals(expected, actual))
        {
            failures++;
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
