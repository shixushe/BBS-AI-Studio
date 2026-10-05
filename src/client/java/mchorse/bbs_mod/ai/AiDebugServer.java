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

                    /* spec= 参数：完整 JSON 直测（不依赖 LLM） */
                    String spec = query(exchange, "spec", "");

                    if (!spec.isEmpty())
                    {
                        json = new String(java.util.Base64.getDecoder().decode(spec), java.nio.charset.StandardCharsets.UTF_8);
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

            /* aiSelfTest 含模型加载轮询（可达 10 秒+），绕开 onClient 的 10 秒
             * 上限：HTTP 线程直接向渲染线程提交并长等（在渲染线程内提交会死锁） */
            if (op.equals("aiSelfTest"))
            {
                java.util.concurrent.Future<String> future = MinecraftClient.getInstance().submit(AiDebugServer::runAiSelfTest);

                String selfResult;

                try
                {
                    selfResult = future.get(90, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    selfResult = "(self test " + (e instanceof java.util.concurrent.TimeoutException ? "still running on the render thread" : "failed: " + e) + ")";
                }

                respond(exchange, jsonMap("result", selfResult));

                return;
            }

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

                    if (op.equals("generate"))
                    {
                        var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
                        var panel = dashboard.getPanels().panel;

                        if (!(panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel)
                            || filmPanel.aiChatBar == null)
                        {
                            return "not the film panel";
                        }

                        String script = map == null || map.getString("script").isEmpty()
                            ? "角色向前走" : map.getString("script");

                        filmPanel.aiChatBar.executeGenerate(script);

                        return "generate queued: " + script;
                    }

                    if (op.equals("aiChat"))
                    {
                        var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
                        var panel = dashboard.getPanels().panel;

                        if (!(panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel)
                            || filmPanel.aiChatBar == null)
                        {
                            return "not the film panel";
                        }

                        var bar = filmPanel.aiChatBar;
                        StringBuilder sb = new StringBuilder();

                        try
                        {
                            java.lang.reflect.Field busyField = bar.getClass().getDeclaredField("busy");

                            busyField.setAccessible(true);
                            sb.append("busy=").append(busyField.getBoolean(bar)).append(" | ");
                        }
                        catch (Exception ignored)
                        {}

                        java.lang.reflect.Field historyField;

                        try
                        {
                            historyField = bar.getClass().getDeclaredField("history");

                            historyField.setAccessible(true);
                        }
                        catch (Exception e)
                        {
                            return "no history field: " + e;
                        }

                        Object history = historyField.get(bar);
                        java.lang.reflect.Field statusField = null;

                        try
                        {
                            statusField = bar.getClass().getDeclaredField("status");

                            statusField.setAccessible(true);
                            Object status = statusField.get(bar);

                            sb.append("status=").append(status.getClass().getField("label").get(status)).append(" | ");
                        }
                        catch (Exception ignored)
                        {}

                        @SuppressWarnings("unchecked")
                        java.util.List<Object> children = (java.util.List<Object>) history.getClass()
                            .getMethod("getChildren").invoke(history);

                        for (Object child : children)
                        {
                            if (child.getClass().getSimpleName().equals("AiChatMessage"))
                            {
                                Object role = child.getClass().getMethod("getRole").invoke(child);
                                Object text = child.getClass().getMethod("getText").invoke(child);

                                sb.append("\n[").append(role).append("] ").append(text);
                            }
                        }

                        return sb.length() == 0 ? "(no chat messages)" : sb.toString();
                    }

                    if (op.equals("aiReport"))
                    {
                        var state = mchorse.bbs_mod.ai.preview.AiPreviewState.get();

                        if (!state.isActive())
                        {
                            return "preview inactive";
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("diff entries=").append(state.getDiff().entries.size())
                            .append(" changedKeys=").append(state.getDiff().changedKeyCount())
                            .append(" skipped=").append(state.getDiff().skippedTracks)
                            .append(" | ");

                        for (mchorse.bbs_mod.ai.commit.FrameCommitter.ChannelWrite write : state.getPlan())
                        {
                            int keys = write.channel == null ? -1 : write.channel.getKeyframes().size();

                            sb.append(" [").append(write.trackId)
                                .append(" keys=").append(write.keys.size())
                                .append(" channelNull=").append(write.channel == null)
                                .append(" channelKeyCount=").append(keys)
                                .append(" poseCh=").append(write.poseChannel).append("]");
                        }

                        return sb.toString();
                    }

                    if (op.equals("filmInfo"))
                    {
                        var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
                        var panel = dashboard.getPanels().panel;

                        if (!(panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel) || filmPanel.getData() == null)
                        {
                            return "no film open";
                        }

                        StringBuilder sb = new StringBuilder();
                        var film = filmPanel.getData();

                        sb.append("replays=").append(film.replays.getList().size()).append(" | ");

                        for (var replay : film.replays.getList())
                        {
                            sb.append(replay.getId())
                                .append(":").append(replay.form.get() == null ? "null" : replay.form.get().getClass().getSimpleName())
                                .append(" ");
                        }

                        return sb.toString();
                    }

                    if (op.equals("poseDump"))
                    {
                        var dashboard = mchorse.bbs_mod.BBSModClient.getDashboard();
                        var panel = dashboard.getPanels().panel;

                        if (!(panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel) || filmPanel.getData() == null)
                        {
                            return "no film open";
                        }

                        var film = filmPanel.getData();
                        StringBuilder sb = new StringBuilder();
                        int dumpIndex = map == null ? 0 : map.getInt("replay");

                        var replay = film.replays.getList().isEmpty() ? null : film.replays.getList()
                            .get(Math.min(dumpIndex, film.replays.getList().size() - 1));

                        if (replay == null || !(replay.form.get() instanceof mchorse.bbs_mod.forms.forms.ModelForm modelForm))
                        {
                            return "no model replay at index " + dumpIndex;
                        }

                        /* 模型骨骼归属：每根骨骼在哪些表单端 */
                        var boneEnds = mchorse.bbs_mod.ai.AiFormWalker.collectBoneEnds(modelForm);

                        sb.append("model=").append(modelForm.model.get()).append(" bones=");
                        sb.append(boneEnds.size()).append("\n");

                        for (var entry : boneEnds.entrySet())
                        {
                            sb.append("  ").append(entry.getKey()).append(" -> ");

                            boolean first = true;

                            for (String end : entry.getValue())
                            {
                                if (!first)
                                {
                                    sb.append(",");
                                }

                                sb.append(end.isEmpty() ? "root" : end);
                                first = false;
                            }

                            sb.append("\n");
                        }

                        /* 世界探针：演员位置处脚/头方块与地表采样——验证
                         * 碰壁截断与贴地采样所用的坐标是否与真实场景对齐 */
                        try
                        {
                            var client = net.minecraft.client.MinecraftClient.getInstance();

                            if (client.world != null)
                            {
                                double px = replay.keyframes.x.getKeyframes().isEmpty() ? 0D
                                    : replay.keyframes.x.getKeyframes().get(0).getValue();
                                double py = replay.keyframes.y.getKeyframes().isEmpty() ? 0D
                                    : replay.keyframes.y.getKeyframes().get(0).getValue();
                                double pz = replay.keyframes.z.getKeyframes().isEmpty() ? 0D
                                    : replay.keyframes.z.getKeyframes().get(0).getValue();

                                sb.append("probe pos=(").append(String.format("%.1f,%.1f,%.1f", px, py, pz)).append(")\n");

                                var panelAny = dashboard.getPanels().panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel fp2
                                    ? fp2 : null;
                                var anchor = panelAny != null && panelAny.getController() != null
                                    ? panelAny.getController().getEntities().get(replay.getId()) : null;

                                if (anchor != null)
                                {
                                    sb.append("  worldAnchor=(")
                                        .append(String.format("%.1f,%.1f,%.1f", anchor.getX(), anchor.getY(), anchor.getZ()))
                                        .append(")\n");

                                    px = anchor.getX();
                                    py = anchor.getY();
                                    pz = anchor.getZ();
                                }
                                else
                                {
                                    sb.append("  worldAnchor=NULL\n");
                                }

                                for (int dy = -1; dy <= 1; dy++)
                                {
                                    var pos = net.minecraft.util.math.BlockPos.ofFloored(px, py + dy, pz);

                                    sb.append("  block@").append(dy >= 0 ? "+" : "").append(dy).append("=")
                                        .append(client.world.getBlockState(pos).getBlock().getName().getString())
                                        .append(" collision=")
                                        .append(!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty())
                                        .append("\n");
                                }

                                sb.append("  topY(heightmap)=").append(client.world.getTopY(
                                        net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                                        (int) Math.floor(px), (int) Math.floor(pz)))
                                    .append("\n");

                                /* 与 groundYAt 相同的局部扫描：脚下 1 格向下 6 格找碰撞面 */
                                var col = net.minecraft.util.math.BlockPos.ofFloored(px, py, pz);

                                for (int dy = 1; dy >= -5; dy--)
                                {
                                    var scan = col.add(0, dy, 0);

                                    if (!client.world.getBlockState(scan).getCollisionShape(client.world, scan).isEmpty())
                                    {
                                        sb.append("  scanGround=").append(scan.getY() + 1).append("\n");

                                        break;
                                    }
                                }

                                /* y 通道键值：验证走路贴地是否贴住地表、有无跳动 */
                                var yCh = replay.keyframes.y;

                                sb.append("  yKeys=");

                                int shownY = 0;

                                for (var yk : yCh.getKeyframes())
                                {
                                    if (shownY++ >= 12)
                                    {
                                        break;
                                    }

                                    sb.append(yk.getTick()).append(":").append(String.format("%.2f", yk.getValue())).append(" ");
                                }

                                sb.append("\n");
                            }
                            else
                            {
                                sb.append("probe: no world\n");
                            }
                        }
                        catch (Exception e)
                        {
                            sb.append("probe failed: ").append(e).append("\n");
                        }

                        /* 已存的 pose 轨道：每个表单端一条，列前 3 个键的骨骼与角度 */
                        for (String end : new java.util.LinkedHashSet<>(boneEnds.values().stream()
                            .flatMap(java.util.List::stream).collect(java.util.stream.Collectors.toList())))
                        {
                            var trackId = mchorse.bbs_mod.film.replays.tracks.TrackId.property(end,
                                mchorse.bbs_mod.film.replays.FormProperties.POSE_PROPERTY);
                            mchorse.bbs_mod.utils.keyframes.KeyframeChannel<mchorse.bbs_mod.utils.pose.Pose> channel =
                                replay.properties.get(trackId);

                            sb.append("pose[").append(end.isEmpty() ? "root" : end).append("] ")
                                .append(channel == null ? "ABSENT" : "keys=" + channel.getKeyframes().size()).append("\n");

                            if (channel == null)
                            {
                                continue;
                            }

                            int shown = 0;

                            for (var keyframe : channel.getKeyframes())
                            {
                                if (shown++ >= 3)
                                {
                                    break;
                                }

                                mchorse.bbs_mod.utils.pose.Pose pose = keyframe.getValue();

                                sb.append("  @").append(keyframe.getTick()).append(": ");

                                int bones = 0;

                                for (var t : pose.transforms.entrySet())
                                {
                                    if (bones++ > 0)
                                    {
                                        sb.append(", ");
                                    }

                                    sb.append(t.getKey()).append("(")
                                        .append(String.format("%.0f,%.0f,%.0f",
                                            Math.toDegrees(t.getValue().rotate.x),
                                            Math.toDegrees(t.getValue().rotate.y),
                                            Math.toDegrees(t.getValue().rotate.z))).append(")");
                                }

                                sb.append("\n");
                            }
                        }

                        return sb.toString();
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
    /**
     * AI 功能自动测试：不依赖 LLM（ canned 计划），在打开的影片回放上跑完整
     * 管线——解析→骨骼解析→求解→预览应用→入框撤销→回滚还原，逐项 PASS/FAIL。
     * 结束后撤销回原状态（不留测试残留）。
     */
    /**
     * AI 功能自动测试（数据级管线）：直读内置 Star 3.6 模型 JSON（不碰实时
     * 回放/不做任何 sleep——渲染线程绝不阻塞），走 解析→遍历→骨骼解析→求解
     * →整只 Pose 轨道写入→灯光 fx→入框 diff，逐项 PASS/FAIL。
     */
    private static String runAiSelfTest()
    {
        int[] tally = {0, 0};
        StringBuilder report = new StringBuilder();
        java.util.function.BiConsumer<String, Boolean> step = (name, ok) ->
        {
            report.append(ok ? "PASS " : "FAIL ").append(name).append(" | ");

            tally[ok ? 0 : 1]++;
        };

        try
        {
            /* 1 计划解析 */
            mchorse.bbs_mod.ai.plan.AnimationPlan plan;

            try
            {
                plan = mchorse.bbs_mod.ai.plan.AnimationPlan.parse("{\"version\":1,\"fps\":20,\"total_ticks\":20,"
                    + "\"beats\":[{\"index\":0,\"tick\":0,\"phase\":\"hold\",\"pose\":\"idle\",\"spacing\":0,\"intents\":[\"hold\"]},"
                    + "{\"index\":1,\"tick\":6,\"phase\":\"down\",\"pose\":\"crouch\",\"spacing\":6,\"intents\":[\"ease_in_out\"]},"
                    + "{\"index\":2,\"tick\":14,\"phase\":\"hold\",\"pose\":\"idle\",\"spacing\":8,\"intents\":[\"hold\"]}]}");
                step.accept("plan parse (3 beats)", plan.beats.size() == 3);

                /* v2 直写骨骼值计划：对象 pose + move 位移 */
                var v2plan = mchorse.bbs_mod.ai.plan.AnimationPlan.parse(
                    "{\"version\":2,\"fps\":20,\"total_ticks\":8,\"beats\":["
                    + "{\"index\":0,\"tick\":0,\"phase\":\"hold\",\"move\":[0.4,0,0],\"pose\":{\"head\":{\"r\":[10,0,0]}}},"
                    + "{\"index\":1,\"tick\":8,\"phase\":\"hold\",\"move\":[0.9,0,0],\"pose\":{\"head\":{\"r\":[12,0,0]}}}]}");

                step.accept("v2 plan parse (direct bones + move)",
                    v2plan.version == 2 && v2plan.beats.size() == 2
                    && v2plan.beats.get(0).poseObject != null
                    && v2plan.beats.get(0).move != null
                    && Math.abs(v2plan.beats.get(1).move[0] - 0.9F) < 0.001F);
            }
            catch (Exception e)
            {
                step.accept("plan parse", false);

                return report.insert(0, "AI SELF TEST: " + tally[0] + " passed, " + tally[1] + " failed | ").toString();
            }

            /* 2 读取内置 Star 3.6 模型（打包资源，非实时加载） */
            java.io.InputStream stream = AiDebugServer.class.getResourceAsStream(
                "/ai_models/star36/slim_eyes/model.bbs.json");

            if (stream == null)
            {
                return report.insert(0, "AI SELF TEST: builtin model resource missing | ").toString();
            }

            byte[] raw = stream.readAllBytes();
            stream.close();

            mchorse.bbs_mod.data.types.MapType modelMap = mchorse.bbs_mod.data.DataToString.mapFromString(
                new String(raw, java.nio.charset.StandardCharsets.UTF_8));

            List<String> inventory = new java.util.ArrayList<>();
            collectModelGroups(modelMap.get("model").asMap().get("groups"), inventory);

            step.accept("builtin model walk (" + inventory.size() + " bones)", inventory.size() >= 48);

            /* 3 骨骼解析 */
            var bones = mchorse.bbs_mod.ai.pose.BoneNameResolver.resolve(inventory);
            mchorse.bbs_mod.ai.pose.AiBoneBindings.apply("star36/slim_eyes", inventory, bones);

            step.accept("bone resolution complete", bones.isComplete());
            step.accept("eyes bound (左眼瞳/右眼瞳)",
                bones.resolved.get("left_eye") != null && "左眼瞳".equals(bones.resolved.get("left_eye").actual));

            /* 4 求解（自然幅度） */
            var poses = mchorse.bbs_mod.ai.pose.PoseSolver.solve(plan, bones,
                mchorse.bbs_mod.ai.ui.UIAiGenerateAskPanel.AMPLITUDES[1]);

            step.accept("solve (" + poses.size() + " key poses)", poses.size() == 3);

            /* 5 整只 Pose 轨道写入（两端：根 + 部位） */
            var boneEnds = mchorse.bbs_mod.ai.AiFormWalker.collectBoneEnds(modelFormOf(inventory));
            var writes = mchorse.bbs_mod.ai.pose.PoseSolver.toPoseTrackWrites(poses, boneEnds,
                new mchorse.bbs_mod.film.replays.FormProperties("test"), null);

            step.accept("pose track writes (" + writes.size() + " channels, " +
                writes.stream().mapToInt(w -> w.keys.size()).sum() + " keys)",
                !writes.isEmpty() && writes.get(0).keys.size() >= 3);

            /* 6 打光 fx 通道（2.5 亮起） */
            var lightWrite = new mchorse.bbs_mod.ai.commit.FrameCommitter.ChannelWrite("lighting",
                new mchorse.bbs_mod.film.replays.FormProperties("light").getOrCreate(null,
                    mchorse.bbs_mod.film.replays.tracks.TrackId.property("", "lighting")), 0F);

            mchorse.bbs_mod.ai.commit.EditPatch.KeyWrite flash = new mchorse.bbs_mod.ai.commit.EditPatch.KeyWrite();

            flash.tick = 6;
            flash.value = 2.5F;
            lightWrite.keys.add(flash);
            writes.add(lightWrite);

            step.accept("lighting fx key", writes.get(writes.size() - 1).keys.size() == 1);

            /* 7 内置资产读取：oak_tree（曾因 tag-soup 根损坏而不可读） */
            var oakData = mchorse.bbs_mod.forms.structure.StructureManager.get("assets:oak_tree");

            step.accept("builtin oak_tree readable",
                oakData != null && oakData.getBlocks() != null && !oakData.getBlocks().isEmpty());

            report.insert(0, "AI SELF TEST: " + tally[0] + " passed, " + tally[1] + " failed | ");

            return report.toString();
        }
        catch (Exception e)
        {
            report.append("CRASH: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());

            return report.insert(0, "AI SELF TEST: " + tally[0] + " passed, " + tally[1] + " failed | ").toString();
        }
    }

    /** 供自测的临时 ModelForm（骨骼清单来源标记）。 */
    private static mchorse.bbs_mod.forms.forms.ModelForm modelFormOf(List<String> inventory)
    {
        return new mchorse.bbs_mod.forms.forms.ModelForm();
    }

    /** 递归收集 model.bbs.json 的 groups 键（含嵌套）。 */
    private static void collectModelGroups(mchorse.bbs_mod.data.types.BaseType groupsValue, List<String> out)
    {
        if (groupsValue == null || !mchorse.bbs_mod.data.types.BaseType.isMap(groupsValue))
        {
            return;
        }

        for (String name : groupsValue.asMap().keys())
        {
            out.add(name);

            mchorse.bbs_mod.data.types.BaseType nested = groupsValue.asMap().get(name).asMap().get("groups");

            collectModelGroups(nested, out);
        }
    }

    private static int countKeys(mchorse.bbs_mod.film.replays.Replay replay, String property)
    {
        var channel = replay.properties.get(mchorse.bbs_mod.film.replays.tracks.TrackId.property("", property));

        return channel == null ? 0 : channel.getKeyframes().size();
    }

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
