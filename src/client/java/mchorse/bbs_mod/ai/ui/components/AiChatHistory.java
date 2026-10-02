package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UIConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * The transcript half of the film editor's AI chat: a vertically scrolling
 * column of {@link AiChatMessage} bubbles with a scrollbar, newest at the
 * bottom, always following the latest entry - the receipt of what the copilot
 * just did should never require scrolling to find.
 *
 * <p>Bubble heights depend on how their text wraps at the current width, so
 * {@link #resize()} rewraps every message before laying out - the column
 * resizer then reads correct heights and derives the scroll extent from them.</p>
 */
public class AiChatHistory extends UIScrollView
{
    /** Keep the transcript bounded - old entries fall off the top. */
    private static final int MAX_MESSAGES = 100;

    public AiChatHistory()
    {
        super(ScrollDirection.VERTICAL);

        this.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.SCROLL_PADDING);
    }

    /** Append an entry, trim the oldest and follow to the bottom. */
    public AiChatMessage log(AiChatMessage.Role role, String text)
    {
        AiChatMessage message = new AiChatMessage(role, text);

        this.add(message);
        this.trim();
        this.refresh();

        return message;
    }

    /** Re-wrap (text may have changed) and follow the latest entry. */
    public void refresh()
    {
        this.resize();
        this.scroll.setScroll(this.scroll.scrollSize);
    }

    private void trim()
    {
        List<AiChatMessage> messages = this.collect();

        for (int i = 0; i < messages.size() - MAX_MESSAGES; i++)
        {
            messages.get(i).removeFromParent();
        }
    }

    private List<AiChatMessage> collect()
    {
        List<AiChatMessage> messages = new ArrayList<>();

        for (IUIElement child : this.getChildren())
        {
            if (child instanceof AiChatMessage)
            {
                messages.add((AiChatMessage) child);
            }
        }

        return messages;
    }

    @Override
    public void resize()
    {
        int width = this.area.w - UIConstants.SCROLL_PADDING * 2;

        for (AiChatMessage message : this.collect())
        {
            message.rewrap(width);
        }

        super.resize();
    }
}
