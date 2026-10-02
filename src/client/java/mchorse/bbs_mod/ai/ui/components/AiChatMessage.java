package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * One conversation entry in the film editor's AI chat (the transcript half of
 * the properties column). A flat bubble with a role tag and wrapped text -
 * the human-readable receipt of everything the copilot did, so a result is
 * never lost to the next status line overwriting it.
 *
 * <p>The height depends on how the text wraps, which depends on the width the
 * history gives this bubble - so {@link #rewrap(int)} is called by the history
 * before its own layout pass, and the column resizer then places correct
 * heights and the scroll knows how far the content goes.</p>
 */
public class AiChatMessage extends UIElement
{
    public enum Role
    {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    /** Vertical padding inside the bubble, top and bottom each. */
    private static final int PAD_Y = 4;

    /** Horizontal padding inside the bubble, each side. */
    private static final int PAD_X = 6;

    /** Role tag line: own height on top of the body lines. */
    private static final int TAG_H = FontRenderer.DEFAULT_LINE_HEIGHT;

    private Role role;
    private String text;
    private final List<String> lines = new ArrayList<>();

    public AiChatMessage(Role role, String text)
    {
        this.role = role;
        this.text = text == null ? "" : text;
    }

    /** Restyle an entry in place - a "generating..." bubble that failed turns into an error one. */
    public void setRole(Role role)
    {
        this.role = role;
    }

    public Role getRole()
    {
        return this.role;
    }

    public String getText()
    {
        return this.text;
    }

    /** Replace the body text (used to turn a "generating..." placeholder into the result). */
    public void setText(String text)
    {
        this.text = text == null ? "" : text;
    }

    /**
     * Wrap the text against the given bubble width and size this element to
     * fit. Called before the history's layout pass, on add and on resize.
     */
    public void rewrap(int width)
    {
        FontRenderer font = Batcher2D.getDefaultTextRenderer();
        int textWidth = Math.max(40, width - PAD_X * 2);

        this.lines.clear();
        this.lines.addAll(font.wrap(this.text, textWidth));
        this.h(this.lines.size() * font.getLineHeight() + TAG_H + PAD_Y * 2);
    }

    @Override
    public void render(UIContext context)
    {
        Batcher2D batcher = context.batcher;
        FontRenderer font = batcher.getFont();

        int background = this.bubbleBackground();
        int tagColor = this.tagColor();
        int textColor = this.textColor();

        if (background != 0)
        {
            batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), background);
        }

        String tag = this.role == Role.USER
            ? mchorse.bbs_mod.l10n.L10n.lang("bbs.ui.ai.chat.you").get()
            : this.role == Role.ASSISTANT || this.role == Role.ERROR
                ? mchorse.bbs_mod.l10n.L10n.lang("bbs.ui.ai.chat.assistant").get()
                : "";

        int x = this.area.x + PAD_X;
        int y = this.area.y + PAD_Y;

        if (!tag.isEmpty())
        {
            batcher.text(tag, x, y, tagColor, true);
            y += font.getLineHeight();
        }

        for (String line : this.lines)
        {
            batcher.text(line, x, y, textColor, false);
            y += font.getLineHeight();
        }

        super.render(context);
    }

    private int bubbleBackground()
    {
        switch (this.role)
        {
            case USER: return Colors.setA(Colors.opaque(BBSSettings.primaryColor.get()), 0.35F);
            case ASSISTANT: return Colors.setA(BBSSettings.deepSurface(), 0.85F);
            case ERROR: return Colors.setA(Colors.RED, 0.25F);
        }

        return 0;
    }

    private int tagColor()
    {
        return this.role == Role.USER
            ? Colors.opaque(BBSSettings.primaryColor.get())
            : Colors.LIGHTER_GRAY;
    }

    private int textColor()
    {
        switch (this.role)
        {
            case ERROR: return Colors.setA(Colors.RED, 1F);
            case SYSTEM: return Colors.GRAY;
        }

        return Colors.WHITE;
    }
}
