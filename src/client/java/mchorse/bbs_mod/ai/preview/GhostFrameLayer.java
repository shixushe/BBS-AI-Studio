package mchorse.bbs_mod.ai.preview;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.events.TimelineEvents;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.DoubleToIntFunction;

/**
 * Ghost frame visuals for a pending AI proposal (copilot spec section 5.4).
 *
 * <p>Timeline side (this class): every tick the proposal touches gets an
 * accent marker - a 1px vertical line plus a thicker cap at the ruler edge -
 * drawn over both clip and actor-keyframe timelines through the shared
 * {@code TimelineEvents.OVERLAY}. Nothing is drawn when no preview is active,
 * and the layer only ever reads state: an overlay cannot commit.</p>
 *
 * <p>The in-preview silhouettes (t-1 / t / t+1 outlines of the model) need a
 * secondary form render at foreign ticks through the form renderer; that part
 * lands with the M5 pose work, which already re-enters that renderer. The
 * timeline markers are the part other UI reads, so they ship first.</p>
 */
public class GhostFrameLayer implements TimelineEvents.Overlay
{
    private static GhostFrameLayer instance;

    /** Register on the shared timeline overlay event. Called once from client init. */
    public static void install()
    {
        if (instance == null)
        {
            instance = new GhostFrameLayer();
            TimelineEvents.OVERLAY.register(instance);
        }
    }

    @Override
    public void render(mchorse.bbs_mod.film.Film film, UIContext context, Area area, DoubleToIntFunction toX)
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive() || state.getTicks().isEmpty())
        {
            return;
        }

        int accent = Colors.opaque(BBSSettings.primaryColor.get());
        int accentSoft = BBSSettings.primaryColor(Colors.A75);
        int cap = Math.max(3, area.h / 24);

        for (float tick : state.getTicks())
        {
            int x = toX.applyAsInt(tick);

            if (x < area.x || x > area.ex())
            {
                continue;
            }

            /* 1px vertical line over the whole timeline well, plus a thicker
             * cap hugging the ruler - the same "this tick is spoken for"
             * gesture the cursor uses, but in the accent colour */
            context.batcher.box(x, area.y, x + 1, area.ey(), accentSoft);
            context.batcher.box(x - cap / 2, area.y, x + cap / 2 + 1, area.y + cap, accent);
        }
    }
}
