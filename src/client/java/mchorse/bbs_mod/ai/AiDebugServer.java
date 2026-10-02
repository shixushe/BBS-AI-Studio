package mchorse.bbs_mod.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ai.ui.UIAiPanel;
import mchorse.bbs_mod.ai.ui.UICapturePanel;
import mchorse.bbs_mod.ai.ui.UIStructureAiPanel;
import net.minecraft.client.util.Window;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The in-game half of the MCP debugging loop (copilot spec section 12.4):
 * a localhost-only HTTP endpoint the external MCP server calls, so screenshots
 * and commands execute INSIDE the game on the client thread - no OS focus
 * stealing, no clicking choreography, the user keeps their desktop while the
 * assistant tests.
 *
 * <p>Endpoints: GET /ping (status), GET /log?lines=N, GET /screenshot[&path=],
 * POST /command {"command":"/aiui ai"} (chat message, slash command or
 * BBS action). Bound to 127.0.0.1 only, off by default, toggled with the
 * ai_debug_server setting (dev-time tooling, never shipped enabled).</p>
 */
public class AiDebugServer
{
    private static final int PORT = 17878;

    private static HttpServer server;
    private static boolean capturing;
    private static Class<? extends mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel> pendingPanel;
    private static int pendingAttempts;

    public static void install()
    {
        if (server != null || !AiSettings.debugServer.get())
        {
            return;
        }

        try
        {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        }
        catch (IOException e)
        {
            /* Port taken by another instance of the debug server - fine */
            return;
        }

        server.createContext("/ping", (exchange) -> respond(exchange, json("ok")));
        server.createContext("/log", (exchange) ->
        {
            int lines = queryInt(exchange, "lines", 80);
            java.util.List<String> tail = new java.util.ArrayList<>();

            try
            {
                File log = new File(new File(System.getProperty("user.dir")), "logs/latest.log");
                List<String> all = java.nio.file.Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);

                tail.addAll(all.subList(Math.max(0, all.size() - lines), all.size()));
            }
            catch (Exception e)
            {
                tail.add("(log unavailable: " + e + ")");
            }

            respond(exchange, jsonMap("lines", tail));
        });
        server.createContext("/screenshot", (exchange) ->
        {
            String path = query(exchange, "path", "");
            String result = onClient(() ->
            {
                Window window = MinecraftClient.getInstance().getWindow();
                File out = path.isEmpty()
                    ? new File(mchorse.bbs_mod.BBSMod.getSettingsFolder(), "ai_shots/shot_" + System.currentTimeMillis() + ".png")
                    : new File(path);

                mchorse.bbs_mod.utils.ScreenshotRecorder recorder = new mchorse.bbs_mod.utils.ScreenshotRecorder(out.getParentFile());

                recorder.takeScreenshot(out, window.getFramebufferWidth(), window.getFramebufferHeight());

                return out.getAbsolutePath();
            });

            respond(exchange, jsonMap("path", result));
        });
        server.createContext("/openui", (exchange) ->
        {
            String panel = query(exchange, "panel", "dashboard");
            String result = onClient(() ->
            {
                var dash = mchorse.bbs_mod.BBSModClient.getDashboard();

                mchorse.bbs_mod.ui.framework.UIScreen.open(dash);

                pendingPanel = switch (panel)
                {
                    case "film" -> UIFilmPanel.class;
                    case "ai" -> UIAiPanel.class;
                    case "capture" -> UICapturePanel.class;
                    case "creative" -> UIAiPanel.class;
                    case "structure" -> UIStructureAiPanel.class;
                    default -> null;
                };
                pendingAttempts = 0;

                return "opened dashboard, switching to " + panel;
            });

            respond(exchange, jsonMap("result", result));
        });

        server.createContext("/command", (exchange) ->
        {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String command = extractJsonString(body, "command");

            String result = onClient(() ->
            {
                MinecraftClient client = MinecraftClient.getInstance();

                if (client.player == null || client.getNetworkHandler() == null)
                {
                    return "not in a world";
                }

                if (command.startsWith("/"))
                {
                    client.getNetworkHandler().sendCommand(command.substring(1));
                }
                else
                {
                    client.getNetworkHandler().sendChatMessage(command);
                }

                return "sent";
            });

            respond(exchange, jsonMap("result", result));
        });

        server.start();

        /* The dashboard builds its panels a few frames at a time after the
         * screen opens; retry the switch each tick until the panel exists */
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            if (pendingPanel == null || !(client.currentScreen instanceof mchorse.bbs_mod.ui.framework.UIScreen))
            {
                return;
            }

            var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
            var panel = dashboard.getPanel(pendingPanel);

            if (panel != null)
            {
                dashboard.setPanel(panel);
                pendingPanel = null;
            }
            else if (++pendingAttempts > 600)
            {
                pendingPanel = null;
            }
        });
    }

    private static void respond(HttpExchange exchange, String body)
    {
        try
        {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(bytes);
            }
        }
        catch (IOException e)
        {}
    }

    private static int queryInt(HttpExchange exchange, String key, int orDefault)
    {
        try
        {
            return Integer.parseInt(query(exchange, key, String.valueOf(orDefault)));
        }
        catch (NumberFormatException e)
        {
            return orDefault;
        }
    }

    private static String json(String value)
    {
        return "{\"status\":\"" + value + "\"}";
    }

    private static String jsonMap(String key, Object value)
    {
        return "{\"" + key + "\":" + mchorse.bbs_mod.data.DataToString.toString(
            new mchorse.bbs_mod.data.types.StringType(String.valueOf(value)), true) + "}";
    }

    private static String query(HttpExchange exchange, String key, String orDefault)
    {
        String query = exchange.getRequestURI().getQuery();

        if (query == null)
        {
            return orDefault;
        }

        for (String pair : query.split("&"))
        {
            String[] kv = pair.split("=", 2);

            if (kv[0].equals(key) && kv.length > 1)
            {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }

        return orDefault;
    }

    private static String extractJsonString(String body, String key)
    {
        mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(body);

        return map == null ? "" : map.getString(key);
    }

    /**
     * Run a task on the client thread and wait for its result (bounded), so
     * HTTP callers get deterministic answers without racing the render loop.
     */
    private static String onClient(java.util.function.Supplier<String> task)
    {
        Future<String> future = MinecraftClient.getInstance().submit(task);

        try
        {
            return future.get(10, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            future.cancel(true);

            return "(timed out waiting for the client thread)";
        }
        catch (Exception e)
        {
            return "(failed: " + e + ")";
        }
    }
}
