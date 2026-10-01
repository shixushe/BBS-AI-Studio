package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.ai.preview.GhostFrameLayer;
import mchorse.bbs_mod.ai.ui.UIAiPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Client-side installation of the AI copilot surface: one subscriber that
 * joins the dashboard taskbar (through the same RegisterDashboardPanelsEvent
 * addons use, section 5.2) and one call that hangs the ghost frame layer on
 * the shared timeline overlay (section 5.4). Called once from
 * {@code BBSModClient} before the first dashboard can be built.
 */
public class AiClientInstall
{
    private static boolean installed;

    public static void install()
    {
        if (installed)
        {
            return;
        }

        installed = true;

        GhostFrameLayer.install();
        BBSMod.events.register(new AiClientInstall());
    }

    @Subscribe
    public void onRegisterDashboardPanels(mchorse.bbs_mod.api.client.events.RegisterDashboardPanelsEvent event)
    {
        event.dashboard.getPanels().registerPanel(new UIAiPanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_TITLE, Icons.GLOBE);
        event.dashboard.getPanels().registerPanel(new mchorse.bbs_mod.ai.ui.UICapturePanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_CAPTURE_TITLE, Icons.CAMERA);
        event.dashboard.getPanels().registerPanel(new mchorse.bbs_mod.ai.ui.UICreativeModePanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_CREATIVE_TITLE, Icons.MORPH);
    }
}
