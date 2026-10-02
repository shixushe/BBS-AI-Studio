package mchorse.bbs_mod.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.network.ServerNetwork;
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
 * BBS action), POST /mouse {"x":0,"y":0,"button":0} (synthetic click into the
 * open screen; coordinates are window pixels, button 0=left 1=right).
 * Bound to 127.0.0.1 only, off by default, toggled with the
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

        server.createContext("/mouse", (exchange) ->
        {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(body);
            int x = map == null ? 0 : map.getInt("x");
            int y = map == null ? 0 : map.getInt("y");
            int button = map == null ? 0 : map.getInt("button");

            String result = onClient(() ->
            {
                MinecraftClient client = MinecraftClient.getInstance();

                if (client.currentScreen == null)
                {
                    return "no screen open";
                }

                /* Callers speak window pixels (screenshot space); the UI tree
                 * speaks GUI units, so divide by the current GUI scale */
                double scale = client.getWindow().getScaleFactor();
                int gx = (int) Math.round(x / scale);
                int gy = (int) Math.round(y / scale);

                try
                {
                    boolean pressed = client.currentScreen.mouseClicked(gx, gy, button);

                    client.currentScreen.mouseReleased(gx, gy, button);

                    return "click " + gx + "," + gy + " btn=" + button
                        + " handled=" + pressed
                        + " screen=" + client.currentScreen.getClass().getSimpleName();
                }
                catch (Exception e)
                {
                    return "(click failed: " + e + ")";
                }
            });

            respond(exchange, jsonMap("result", result));
        });

        server.createContext("/wheel", (exchange) ->
        {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(body);
            int x = map == null ? 0 : map.getInt("x");
            int y = map == null ? 0 : map.getInt("y");
            double amount = map == null ? 0D : map.getDouble("amount");

            String result = onClient(() ->
            {
                MinecraftClient client = MinecraftClient.getInstance();

                if (client.currentScreen == null)
                {
                    return "no screen open";
                }

                double scale = client.getWindow().getScaleFactor();
                int gx = (int) Math.round(x / scale);
                int gy = (int) Math.round(y / scale);

                try
                {
                    boolean handled = client.currentScreen.mouseScrolled(gx, gy, amount);

                    return "wheel " + gx + "," + gy + " amount=" + amount
                        + " handled=" + handled
                        + " screen=" + client.currentScreen.getClass().getSimpleName();
                }
                catch (Exception e)
                {
                    return "(wheel failed: " + e + ")";
                }
            });

            respond(exchange, jsonMap("result", result));
        });

        server.createContext("/ai_build", (exchange) ->
        {
            String theme = query(exchange, "theme", "watchtower");
            String xs = query(exchange, "x", "");
            String ys = query(exchange, "y", "");
            String zs = query(exchange, "z", "");
            boolean place = query(exchange, "place", "true").equalsIgnoreCase("true");

            String result = onClient(() ->
            {
                /* Deterministic demo build order - tests the whole pipeline
                 * (parse -> expand -> blueprints -> placement) with no LLM */
                String json = "{\"name\":\"debug_" + System.currentTimeMillis() % 100000L + "\",\"title\":\"Debug build: " + theme.replace("\"", "") + "\","
                    + "\"size\":[7,9,7],"
                    + "\"shell\":{\"from\":[0,0,0],\"to\":[6,8,6],\"wall\":\"minecraft:stone_bricks\",\"floor\":\"minecraft:stone\",\"roof\":\"minecraft:dark_oak_slab\",\"windows\":true,\"door\":\"south\"},"
                    + "\"boxes\":[{\"from\":[2,0,2],\"to\":[4,0,4],\"block\":\"minecraft:polished_andesite\"}],"
                    + "\"towers\":[{\"from\":[0,0,0],\"to\":[1,8,1],\"block\":\"minecraft:cobblestone\",\"roof\":true},{\"from\":[5,0,5],\"to\":[6,8,6],\"block\":\"minecraft:cobblestone\",\"roof\":true}]}";

                try
                {
                    net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                    java.io.File generated = client.getServer() == null
                        ? null
                        : client.getServer().getSavePath(net.minecraft.util.WorldSavePath.GENERATED).resolve("bbs/structures").toFile();

                    if (generated == null)
                    {
                        return "not in a world";
                    }

                    AiArchitecture.Result result1 = AiArchitecture.generate(json, generated,
                        new java.io.File(client.runDirectory, "config/worldedit/schematics"));

                    if (place)
                    {
                        net.minecraft.util.math.BlockPos pos;

                        if (!xs.isEmpty() && !ys.isEmpty() && !zs.isEmpty())
                        {
                            pos = new net.minecraft.util.math.BlockPos(Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs));
                        }
                        else if (client.player != null)
                        {
                            pos = client.player.getBlockPos().offset(client.player.getHorizontalFacing(), 6);
                        }
                        else
                        {
                            return "generated " + result1.blocks + " blocks, but no player to place near";
                        }

                        AiArchitecture.onPlaced = (message) -> System.out.println("[AI build] " + message);

                        net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();

                        buf.writeString(result1.name);
                        buf.writeBlockPos(pos);
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            ServerNetwork.SERVER_AI_PLACE_STRUCTURE, buf);

                        return "generated " + result1.blocks + " blocks (" + result1.name + "), placement requested at "
                            + pos.toShortString();
                    }

                    return "generated " + result1.blocks + " blocks (" + result1.name + ")";
                }
                catch (Exception e)
                {
                    return "failed: " + e.getMessage();
                }
            });

            respond(exchange, jsonMap("result", result));
        });

        server.createContext("/debug", (exchange) ->
        {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(body);
            String op = map == null ? "" : map.getString("op");
            String track = map == null ? "" : map.getString("track");

            String result = onClient(() ->
            {
                try
                {
                    if (op.equals("editSheet") || op.equals("exitSheet"))
                    {
                        var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
                        var panel = dashboard.getPanels().panel;

                        if (!(panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel))
                        {
                            return "not the film panel";
                        }

                        var editor = filmPanel.replayEditor;

                        if (editor == null || editor.keyframeEditor == null)
                        {
                            return "replay keyframe editor is not open";
                        }

                        var view = editor.keyframeEditor.view;

                        if (op.equals("exitSheet"))
                        {
                            view.editSheet(null);

                            return "exited";
                        }

                        var sheet = view.getDopeSheet().getSheet(track);

                        if (sheet == null)
                        {
                            return "no track named " + track;
                        }

                        view.editSheet(sheet);

                        String graphName = "dope sheet";

                        try
                        {
                            java.lang.reflect.Field gField =
                                mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes.class.getDeclaredField("currentGraph");

                            gField.setAccessible(true);
                            graphName = gField.get(view).getClass().getSimpleName();
                        }
                        catch (Exception ignored)
                        {}

                        return "editSheet(" + track + ") done, editing=" + view.isEditing() + ", graph=" + graphName;
                    }

                    return "unknown op " + op;
                }
                catch (Exception e)
                {
                    return "(failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
                }
            });

            respond(exchange, jsonMap("result", result));
        });

        server.createContext("/uistate", (exchange) ->
        {
            String result = onClient(() ->
            {
                MinecraftClient client = MinecraftClient.getInstance();

                if (!(client.currentScreen instanceof mchorse.bbs_mod.ui.framework.UIScreen screen))
                {
                    return "no UIScreen open (current: " + (client.currentScreen == null ? "null" : client.currentScreen.getClass().getSimpleName()) + ")";
                }

                try
                {
                    java.lang.reflect.Field f = mchorse.bbs_mod.ui.framework.UIScreen.class.getDeclaredField("menu");

                    f.setAccessible(true);

                    mchorse.bbs_mod.ui.framework.UIBaseMenu menu = (mchorse.bbs_mod.ui.framework.UIBaseMenu) f.get(screen);
                    StringBuilder sb = new StringBuilder();
                    int[] budget = {600};

                    sb.append("== main ==\n");

                    dumpTree(sb, menu.main, 0, budget);

                    sb.append("== overlay ==\n");

                    dumpTree(sb, menu.overlay, 0, budget);

                    return sb.toString();
                }
                catch (Exception e)
                {
                    return "(dump failed: " + e + ")";
                }
            });

            respond(exchange, jsonMap("tree", result));
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

    /**
     * Depth-first dump of the UI tree: class, area and any readable text per
     * element. Skips invisible subtrees; capped so a huge dashboard can't
     * produce megabytes of JSON.
     */
    private static void dumpTree(StringBuilder sb, Object node, int depth, int[] budget)
    {
        if (budget[0] <= 0 || depth > 14 || !(node instanceof mchorse.bbs_mod.ui.framework.elements.UIElement element))
        {
            return;
        }

        budget[0]--;

        if (!element.isVisible())
        {
            return;
        }

        mchorse.bbs_mod.ui.utils.Area a = element.area;

        sb.append("  ".repeat(depth))
            .append(element.getClass().getSimpleName())
            .append(" (").append(a.x).append(",").append(a.y).append(" ").append(a.w).append("x").append(a.h).append(")");

        if (element instanceof mchorse.bbs_mod.ui.framework.elements.utils.UILabel label)
        {
            sb.append(" \"").append(label.label.get()).append("\"");
        }

        if (element instanceof mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes keyframes)
        {
            try
            {
                java.lang.reflect.Field dsField =
                    mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes.class.getDeclaredField("dopeSheet");

                dsField.setAccessible(true);

                Object dopeSheetObj = dsField.get(keyframes);

                sb.append("\n").append("  ".repeat(depth + 1));

                try
                {
                    java.lang.reflect.Method yMethod = dopeSheetObj.getClass().getMethod("getDopeSheetY");

                    sb.append("dopeSheetY=").append(yMethod.invoke(dopeSheetObj)).append(" ");
                }
                catch (Exception e)
                {
                    sb.append("(dopeSheetY unavailable) ");
                }

                java.lang.reflect.Field cacheField = dopeSheetObj.getClass().getDeclaredField("sheetYCache");

                cacheField.setAccessible(true);

                Object cache = cacheField.get(dopeSheetObj);

                if (cache instanceof java.util.Map<?, ?> map)
                {
                    for (java.util.Map.Entry<?, ?> entry : map.entrySet())
                    {
                        if (entry.getKey() instanceof mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet sheet)
                        {
                            sb.append("\n").append("  ".repeat(depth + 1))
                                .append("track id=").append(sheet.id)
                                .append(" relY=").append(entry.getValue())
                                .append(" factory=").append(sheet.channel.getFactory().getClass().getSimpleName());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                sb.append("\n").append("  ".repeat(depth + 1)).append("(tracks unavailable: ").append(e).append(")");
            }
        }

        sb.append("\n");

        for (Object child : new java.util.ArrayList<>(element.getChildren()))
        {
            dumpTree(sb, child, depth + 1, budget);
        }
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
