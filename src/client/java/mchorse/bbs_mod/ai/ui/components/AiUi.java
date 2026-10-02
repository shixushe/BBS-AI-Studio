package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.resources.Pixels;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI panels' shared visual language - the one place that decides what a
 * section header, a teaching hint and a capture frame strip look like, so the
 * four panels read as one product instead of four prototypes.
 */
public final class AiUi
{
    /** Standard bottom action bar height. */
    public static final int BAR = UIConstants.CONTROL_HEIGHT + 10;

    /** Section header strip height. */
    public static final int HEADER = UIConstants.CONTROL_HEIGHT + 6;

    /** Thumbnail strip height. */
    public static final int STRIP = 72;

    private AiUi()
    {}

    /** The accent section header every panel titles its areas with. */
    public static UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get()));
        label.h(HEADER).w(1F);

        return label;
    }

    /** Muted teaching copy. */
    public static UILabel hint(IKey text)
    {
        UILabel label = new UILabel(text);

        label.color(Colors.LIGHTER_GRAY, false);

        return label;
    }

    /** A vertically scrolling, stretched content column - every list lives in one of these. */
    public static UIScrollView scrollColumn()
    {
        UIScrollView scroll = new UIScrollView(ScrollDirection.VERTICAL);

        scroll.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.SCROLL_PADDING);

        return scroll;
    }

    /**
     * Horizontal strip of captured-frame thumbnails. Frames upload lazily, one
     * GL texture each, on first render after {@link #setFrames} - never during
     * capture, so the strip costs nothing while the timeline is being walked.
     */
    public static class FrameStrip extends UIElement
    {
        private final List<Pixels> frames = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private boolean uploaded;

        public FrameStrip()
        {
            this.h(STRIP);
        }

        public void setFrames(List<Pixels> frames)
        {
            this.release();

            this.frames.addAll(frames);
            this.uploaded = false;
        }

        public void release()
        {
            for (Texture texture : this.textures)
            {
                try
                {
                    texture.delete();
                }
                catch (Exception e)
                {}
            }

            this.textures.clear();
            this.frames.clear();
            this.uploaded = false;
        }

        @Override
        public void render(UIContext context)
        {
            if (!this.frames.isEmpty() && !this.uploaded)
            {
                for (Pixels frame : this.frames)
                {
                    try
                    {
                        this.textures.add(Texture.textureFromPixels(frame, 0x2601));
                    }
                    catch (Exception e)
                    {
                        this.textures.add(null);
                    }
                }

                this.uploaded = true;
            }

            if (this.frames.isEmpty())
            {
                Font font = new Font(context);

                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());
                context.batcher.textCard(mchorse.bbs_mod.l10n.L10n.lang("bbs.ui.ai.capture.strip_empty").get(),
                    this.area.x(0.5F, -font.width / 2), this.area.y(0.5F, -font.height / 2), Colors.LIGHTER_GRAY, 0, 1, false);
            }
            else if (this.uploaded)
            {
                int count = this.frames.size();
                int gap = UIConstants.MARGIN;
                int w = (this.area.w - gap * (count - 1)) / Math.max(1, count);
                int h = this.area.h;

                for (int i = 0; i < count && i < this.textures.size(); i++)
                {
                    Texture texture = this.textures.get(i);
                    int x = this.area.x + i * (w + gap);

                    context.batcher.box(x - 1, this.area.y - 1, x + w + 1, this.area.y + h + 1, BBSSettings.dividerColor());

                    if (texture != null)
                    {
                        context.batcher.texturedBox(texture, Colors.WHITE, x, this.area.y, w, h, 0, texture.height, texture.width, 0, texture.width, texture.height);
                    }
                }
            }

            super.render(context);
        }

        /** One measured string, so the empty state centers its label. */
        private static final class Font
        {
            final int width;
            final int height;

            Font(UIContext context)
            {
                mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();
                String text = mchorse.bbs_mod.l10n.L10n.lang("bbs.ui.ai.capture.strip_empty").get();

                this.width = font.getWidth(text);
                this.height = font.getHeight();
            }
        }
    }
}
