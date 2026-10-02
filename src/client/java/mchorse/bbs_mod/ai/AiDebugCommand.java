package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Dev-time navigation for the MCP debugging loop (copilot spec section 12.4):
 * {@code /aiui <surface>} opens the dashboard and jumps straight to a panel,
 * no click choreography needed. Debug tooling only - not part of the user
 * facing surface.
 */
public class AiDebugCommand
{
    public static void install()
    {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
        {
            dispatcher.register(ClientCommandManager.literal("aiui")
                .then(ClientCommandManager.literal("dashboard").executes((ctx) -> open(null)))
                .then(ClientCommandManager.literal("film").executes((ctx) -> open(UIFilmPanel.class)))
                .then(ClientCommandManager.literal("ai").executes((ctx) -> open(mchorse.bbs_mod.ai.ui.UIAiPanel.class)))
                .then(ClientCommandManager.literal("capture").executes((ctx) -> open(mchorse.bbs_mod.ai.ui.UICapturePanel.class)))
                .then(ClientCommandManager.literal("creative").executes((ctx) -> open(mchorse.bbs_mod.ai.ui.UIAiPanel.class))));
        });
    }

    private static int open(Class<? extends UIDashboardPanel> panel)
    {
        UIDashboard dashboard = BBSModClient.getDashboard();

        UIScreen.open(dashboard);

        if (panel != null)
        {
            UIDashboardPanel target = dashboard.getPanel(panel);

            if (target != null)
            {
                dashboard.setPanel(target);
            }
        }

        return 1;
    }
}
