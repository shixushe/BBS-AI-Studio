package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * Mirror brush paint helper (spec §5.7 item 2): painting on one side of a
 * symmetric body part automatically mirrors the stroke to the other side.
 * Works on the raw pixel buffer, not the UI — the caller passes the touched
 * local coordinates within a body-part region and this class mirrors them.
 */
public class MirrorBrush
{
    private final Pixels pixels;
    private final int textureWidth;

    public MirrorBrush(Pixels pixels)
    {
        this.pixels = pixels;
        this.textureWidth = pixels.width;
    }

    /**
     * Mirror a stroke pixel from the left side to the right side (or vice
     * versa) within the same body-part row band.
     *
     * @param x       local x of the touched pixel
     * @param y       local y of the touched pixel
     * @param color   the color being painted
     * @param centerX the x centerline of the texture (typically textureWidth / 4 or / 2
     *                depending on the region band)
     */
    public void mirrorStrokePixel(int x, int y, Color color, int centerX)
    {
        int mirrored = centerX + (centerX - x);

        if (mirrored >= 0 && mirrored < this.pixels.width && mirrored != x)
        {
            this.pixels.setColor(mirrored, y, new Color().set(color.r, color.g, color.b, color.a));
        }
    }

    /**
     * Copy a rectangular region from (sx,sy) to (dx,dy) — used for the
     * limb-based mirror where left/right arms occupy different UV rects.
     */
    public void copyRegion(int sx, int sy, int dx, int dy, int w, int h)
    {
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                if (sx + x < this.pixels.width && sy + y < this.pixels.height
                    && dx + x < this.pixels.width && dy + y < this.pixels.height)
                {
                    Color c = this.pixels.getColor(sx + x, sy + y);

                    this.pixels.setColor(dx + x, dy + y, new Color().set(c.r, c.g, c.b, c.a));
                }
            }
        }
    }
}
