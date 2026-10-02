package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.capture.CapturedFrame;
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
 * section header, a status line, a teaching hint and a capture frame strip
 * look like, so the panels read as one product instead of four prototypes.
 */
public final class AiUi
{
    /** Standard bottom action bar height. */
    public static final int BAR = UIConstants.CONTROL_HEIGHT + 10;

    /** Section header strip height. */
    public static final int HEADER = UIConstants.CONTROL_HEIGHT + 6;

    /** Thumbnail strip height. */
    public static final int STRIP = 72;

    /** The dashboard taskbar overlays a panel's bottom edge - bottom-anchored rows keep this clearance. */
    public static final int TASKBAR = 20;

    private AiUi()
    {}

    /** Section header without a caption. */
    public static Header header(IKey title)
    {
        return new Header(title);
    }

    /** Muted teaching copy. */
    public static UILabel hint(IKey text)
    {
        UILabel label = new UILabel(text);

        label.color(Colors.LIGHTER_GRAY, false);

        return label;
    }

    /** A vertically scrolling, stretched content column. */
    public static UIScrollView scrollColumn()
    {
        UIScrollView scroll = new UIScrollView(ScrollDirection.VERTICAL);

        scroll.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.SCROLL_PADDING);

        return scroll;
    }

    /** The 2px brand line along a panel's top edge - one per panel, not per block. */
    public static void topEdge(UIContext context, int x, int y, int w)
    {
        context.batcher.box(x, y, x + w, y + 2, Colors.opaque(BBSSettings.primaryColor.get()));
    }

    /**
     * Section header, refined: dark strip, 3px accent edge on the left, the
     * title on the left and an optional muted caption on the right (counts,
     * state - call {@link #caption(String)} whenever it changes).
     */
    public static class Header extends UIElement
    {
        private final IKey title;
        private String caption = "";

        public Header(IKey title)
        {
            this.title = title;

            this.h(HEADER);
        }

        public Header caption(String caption)
        {
            this.caption = caption == null ? "" : caption;

            return this;
        }

        @Override
        public void render(UIContext context)
        {
            int accent = Colors.opaque(BBSSettings.primaryColor.get());

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.chromeSurface());
            context.batcher.box(this.area.x, this.area.y, this.area.x + 3, this.area.ey(), accent);

            mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();
            String title = this.title.get();

            context.batcher.textCard(title, this.area.x(0F, 8), this.area.y(0.5F, -font.getHeight() / 2), Colors.WHITE, 0, 1, false);

            if (!this.caption.isEmpty())
            {
                int captionWidth = font.getWidth(this.caption);

                context.batcher.textCard(this.caption, this.area.ex() - 8 - captionWidth, this.area.y(0.5F, -font.getHeight() / 2), Colors.LIGHTER_GRAY, 0, 1, false);
            }

            super.render(context);
        }
    }

    /**
     * A status line with a state dot: gray while idle, accent while working,
     * green on success, red on failure - the same language everywhere.
     */
    public static class StatusLine extends UILabel
    {
        public enum State
        {
            IDLE(Colors.LIGHTER_GRAY), WORKING(Colors.opaque(BBSSettings.primaryColor.get())), OK(Colors.GREEN), FAIL(Colors.RED);

            public final int color;

            State(int color)
            {
                this.color = color;
            }
        }

        private State state = State.IDLE;

        public StatusLine(IKey text)
        {
            super(text, Colors.LIGHTER_GRAY);

            this.color(Colors.LIGHTER_GRAY, false);
        }

        /** Set the message and its state in one call. */
        public void set(String message, State state)
        {
            this.state = state;
            this.label = IKey.constant("● " + message);
            this.color(state.color, false);
        }

        /** Keep the current state, replace only the message. */
        public void set(String message)
        {
            this.set(message, this.state);
        }
    }

    /**
     * Horizontal strip of captured-frame thumbnails with tick captions.
     * Frames upload lazily, one GL texture each, on first render after
     * {@link #setFrames} - never during capture, so the strip costs nothing
     * while the timeline is being walked.
     */
    public static class FrameStrip extends UIElement
    {
        private final List<CapturedFrame> frames = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private boolean uploaded;

        public FrameStrip()
        {
            this.h(STRIP);
        }

        public void setFrames(List<CapturedFrame> frames)
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
                for (CapturedFrame frame : this.frames)
                {
                    try
                    {
                        this.textures.add(Texture.textureFromPixels(frame.image, 0x2601));
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
                mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();
                String text = mchorse.bbs_mod.l10n.L10n.lang("bbs.ui.ai.capture.strip_empty").get();

                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());
                context.batcher.textCard(text, this.area.x(0.5F, -font.getWidth(text) / 2), this.area.y(0.5F, -font.getHeight() / 2), Colors.LIGHTER_GRAY, 0, 1, false);
            }
            else if (this.uploaded)
            {
                int count = this.frames.size();
                int gap = UIConstants.MARGIN;
                int w = (this.area.w - gap * (count - 1)) / Math.max(1, count);
                int h = this.area.h;
                mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();

                for (int i = 0; i < count && i < this.textures.size(); i++)
                {
                    Texture texture = this.textures.get(i);
                    CapturedFrame frame = this.frames.get(i);
                    int x = this.area.x + i * (w + gap);

                    context.batcher.box(x - 1, this.area.y - 1, x + w + 1, this.area.y + h + 1, BBSSettings.dividerColor());

                    if (texture != null)
                    {
                        context.batcher.texturedBox(texture, Colors.WHITE, x, this.area.y, w, h, 0, texture.height, texture.width, 0, texture.width, texture.height);
                    }

                    /* The tick caption: which timeline moment this frame is */
                    String caption = "t=" + frame.tick;
                    int captionWidth = Math.min(w, font.getWidth(caption) + 6);

                    context.batcher.box(x, this.area.y + h - font.getHeight() - 3, x + captionWidth, this.area.y + h, Colors.A75);
                    context.batcher.textCard(caption, x + 3, this.area.y + h - font.getHeight() - 2, Colors.WHITE, 0, 1, false);
                }
            }

            super.render(context);
        }
    }
}
