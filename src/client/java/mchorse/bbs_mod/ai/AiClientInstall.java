package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.ai.AiDebugServer;
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

        mchorse.bbs_mod.ai.AiBuiltinModels.install();
        GhostFrameLayer.install();
        AiDebugServer.install();
        BBSMod.events.register(new AiClientInstall());

        /* 上一实例关停慢时端口还在占用，初装会静默失败——后台每 2 秒
         * 重试一次直到起来（server 非空后 install 直接返回，开销为零） */
        Thread debugRetry = new Thread(() ->
        {
            for (int i = 0; i < 120; i++)
            {
                try
                {
                    Thread.sleep(2000L);
                }
                catch (InterruptedException e)
                {
                    return;
                }

                mchorse.bbs_mod.ai.AiDebugServer.install();
            }
        }, "BBS AI debug retry");

        debugRetry.setDaemon(true);
        debugRetry.start();
    }

    @Subscribe
    public void onRegisterDashboardPanels(mchorse.bbs_mod.api.client.events.RegisterDashboardPanelsEvent event)
    {
        event.dashboard.getPanels().registerPanel(new UIAiPanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_TITLE, Icons.GLOBE);
        event.dashboard.getPanels().registerPanel(new mchorse.bbs_mod.ai.ui.UICapturePanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_CAPTURE_TITLE, Icons.CAMERA);
        event.dashboard.getPanels().registerPanel(new mchorse.bbs_mod.ai.ui.UIStructureAiPanel(event.dashboard), mchorse.bbs_mod.ui.UIKeys.AI_STRUCTURE_TITLE, Icons.BLOCK);
    }
}
