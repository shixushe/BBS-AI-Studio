package mchorse.bbs_mod.ai.preview;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * The preview-side half of the ghost frame visual (copilot spec section
 * 5.4): while a proposal is pending, the preview viewport gets an accent
 * border, and when the cursor sits on (or beside) an AI-touched tick, the
 * tick reads out in the corner.
 *
 * <p>This is deliberately a 2D pass - it can never corrupt GL state, which
 * the spec calls out as the overlay's hard rule. The full t±1 model
 * silhouettes still belong to the form renderer (see GhostFrameLayer's
 * javadoc).</p>
 */
public class AiGhostBorder extends UIElement
{
    private final UIFilmPanel panel;

    public AiGhostBorder(UIFilmPanel panel)
    {
        this.panel = panel;

        this.relative(panel).w(1F).h(1F);
    }

    @Override
    public void render(UIContext context)
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive() || state.getTicks().isEmpty())
        {
            return;
        }

        Area preview = this.panel.preview.area;
        int cursor = this.panel.getCursor();

        int near = -1;

        for (float tick : state.getTicks())
        {
            if (Math.abs(cursor - tick) <= 2F)
            {
                near = (int) tick;

                break;
            }
        }

        if (near < 0)
        {
            return;
        }

        int accent = Colors.opaque(BBSSettings.primaryColor.get());
        int accentSoft = BBSSettings.primaryColor(Colors.A50);
        int x = preview.x;
        int y = preview.y;
        int ex = preview.ex();
        int ey = preview.ey();

        /* Accent frame around the whole preview viewport */
        context.batcher.box(x, y, ex, y + 2, accent);
        context.batcher.box(x, ey - 2, ex, ey, accent);
        context.batcher.box(x, y, x + 2, ey, accent);
        context.batcher.box(ex - 2, y, ex, ey, accent);

        /* Corner readout: which AI tick the cursor is parked on */
        FontRenderer font = context.batcher.getFont();
        String label = "AI - tick " + near;
        int w = font.getWidth(label) + 8;

        context.batcher.box(x + 6, y + 6, x + 6 + w, y + 6 + font.getHeight() + 6, Colors.A75 | (BBSSettings.primaryColor.get() & Colors.RGB));
        context.batcher.textCard(label, x + 10, y + 9, Colors.WHITE, 0, 1, true);
    }
}
