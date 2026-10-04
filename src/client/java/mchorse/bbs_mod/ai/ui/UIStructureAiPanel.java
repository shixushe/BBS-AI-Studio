package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import net.minecraft.client.MinecraftClient;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.AiArchitecture;
import mchorse.bbs_mod.network.ClientNetwork;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.forms.structure.StructureManager;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 建筑 —— 独立 Dashboard 面板（用户要求；范围按企划书 §9/§10.8：结构【理解】——
 * 枚举 .nbt 结构、读取尺寸与方块统计、语义描述）。
 *
 * <p>以人为本的形态：结构列表可点选（选中高亮），右侧立即给出这一结构的
 * 尺寸/方块数/方块实体数等"看得懂的事实"；配置了后端时「AI 描述」把这些
 * 事实交给模型翻译成一段可读的说明。没有结构时告诉用户把 .nbt 放进哪。</p>
 */
public class UIStructureAiPanel extends UIDashboardPanel
{
    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = AiUi.HEADER;

    private final UIScrollView structures;
    private final UIScrollView description;
    private final UILabel listHeader;
    private final UILabel descHeader;
    private final UIButton describe;

    private final UITextbox buildTheme;
    private final UIButton generateBuild;
    private final UIButton placeBuild;
    private final UIButton exportBuild;
    private final UIButton modePlace;
    private final UIButton modeBlueprint;
    private final UITextbox[] coords = new UITextbox[3];
    private final UIElement coordRow;
    private final UILabel blueprintRow;

    private boolean blueprintMode;
    private String selected;
    private boolean describing;
    private boolean building;
    private int lastListWidth;

    public UIStructureAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.listHeader = AiUi.header(L10n.lang("bbs.ui.ai.structure.list"), "");
        this.descHeader = AiUi.header(L10n.lang("bbs.ui.ai.structure.description"), "");

        this.structures = new UIScrollView();
        this.structures.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        this.description = new UIScrollView();
        this.description.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        this.describe = new UIButton(L10n.lang("bbs.ui.ai.structure.ai_describe"), (b) -> this.aiDescribe());
        this.describe.tooltip(L10n.lang("bbs.ui.ai.structure.ai_describe_tooltip"));

        UILabel pick = new UILabel(L10n.lang("bbs.ui.ai.structure.pick"));

        pick.color(Colors.LIGHTER_GRAY, false);

        /* Absolute layout: fixed 200px list on the left, description + AI button on the right */
        int listWidth = 200;

        listHeader.relative(this).x(0).y(0).w(listWidth).h(HEADER);
        this.structures.relative(this).x(0).y(HEADER).w(listWidth).h(1F, -HEADER);

        int rightX = listWidth + UIConstants.MARGIN;

        descHeader.relative(this).x(rightX).y(0).w(1F, -rightX).h(HEADER);
        this.description.relative(this).x(rightX).y(HEADER).w(1F, -rightX).h(1F, -(HEADER + UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN));

        this.buildTheme = new UITextbox(96, (t) -> {});
        this.buildTheme.placeholder(L10n.lang("bbs.ui.ai.structure.build_placeholder"));

        this.generateBuild = new UIButton(L10n.lang("bbs.ui.ai.structure.build"), (b) -> this.generateBuilding());
        this.generateBuild.tooltip(L10n.lang("bbs.ui.ai.structure.build_tooltip"));

        this.placeBuild = new UIButton(L10n.lang("bbs.ui.ai.structure.place"), (b) -> this.placeBuilding(this.requestedPos()));
        this.placeBuild.tooltip(L10n.lang("bbs.ui.ai.structure.place_tooltip"));

        /* Two modes: place into the world at coordinates, or export the
         * Axiom blueprint file (the .nbt/.schem are always written) */
        this.modePlace = new UIButton(L10n.lang("bbs.ui.ai.structure.mode_place"), (b) -> this.setMode(false));
        this.modeBlueprint = new UIButton(L10n.lang("bbs.ui.ai.structure.mode_blueprint"), (b) -> this.setMode(true));
        this.exportBuild = new UIButton(L10n.lang("bbs.ui.ai.structure.export"), (b) -> this.exportBlueprint());

        this.exportBuild.tooltip(L10n.lang("bbs.ui.ai.structure.export_tooltip"));
        this.modePlace.tooltip(L10n.lang("bbs.ui.ai.structure.mode_place_tooltip"));
        this.modeBlueprint.tooltip(L10n.lang("bbs.ui.ai.structure.mode_blueprint_tooltip"));

        for (int i = 0; i < 3; i++)
        {
            this.coords[i] = new UITextbox(7, (t) -> {});
        }

        this.coordRow = UI.row(UIConstants.MARGIN,
            UI.label(IKey.constant("X"), UIConstants.CONTROL_HEIGHT), this.coords[0].h(UIConstants.CONTROL_HEIGHT),
            UI.label(IKey.constant("Y"), UIConstants.CONTROL_HEIGHT), this.coords[1].h(UIConstants.CONTROL_HEIGHT),
            UI.label(IKey.constant("Z"), UIConstants.CONTROL_HEIGHT), this.coords[2].h(UIConstants.CONTROL_HEIGHT));
        this.coordRow.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);

        this.blueprintRow = UI.label(L10n.lang("bbs.ui.ai.structure.export_where"), UIConstants.CONTROL_HEIGHT);
        this.blueprintRow.color(Colors.LIGHTER_GRAY, false);

        UILabel buildHeader = AiUi.header(L10n.lang("bbs.ui.ai.structure.build_header"), L10n.lang("bbs.ui.ai.structure.build_header_hint").get());

        int stack = AiUi.HEADER + UIConstants.CONTROL_HEIGHT * 3 + UIConstants.MARGIN * 4;

        this.description.relative(this).x(rightX).y(HEADER).w(1F, -rightX).h(1F, -(HEADER + stack + UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN + AiUi.TASKBAR));
        this.describe.relative(this).x(rightX).y(1F, -(stack + UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN + AiUi.TASKBAR)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);
        buildHeader.relative(this).x(rightX).y(1F, -stack).w(1F, -rightX).h(AiUi.HEADER);
        this.buildTheme.relative(this).x(rightX).y(1F, -(UIConstants.CONTROL_HEIGHT * 2 + UIConstants.MARGIN * 2 + AiUi.TASKBAR)).w(1F, -rightX - 60 - UIConstants.MARGIN * 2).h(UIConstants.CONTROL_HEIGHT);
        this.generateBuild.relative(this).x(1F, -(60 + UIConstants.MARGIN)).y(1F, -(UIConstants.CONTROL_HEIGHT * 2 + UIConstants.MARGIN * 2 + AiUi.TASKBAR)).w(60).h(UIConstants.CONTROL_HEIGHT);
        this.modePlace.relative(this).x(rightX).y(1F, -(UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN + AiUi.TASKBAR)).w(0.5F, -UIConstants.MARGIN).h(UIConstants.CONTROL_HEIGHT);
        this.modeBlueprint.relative(this).x(0.5F, 0).y(1F, -(UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN + AiUi.TASKBAR)).w(0.5F, -UIConstants.MARGIN).h(UIConstants.CONTROL_HEIGHT);
        this.coordRow.relative(this).x(rightX).y(1F, -(UIConstants.MARGIN + AiUi.TASKBAR)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);
        this.blueprintRow.relative(this).x(rightX).y(1F, -(UIConstants.MARGIN + AiUi.TASKBAR)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);
        this.placeBuild.relative(this).x(rightX).y(1F, -(UIConstants.MARGIN + AiUi.TASKBAR)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);
        this.exportBuild.relative(this).x(rightX).y(1F, -(UIConstants.MARGIN + AiUi.TASKBAR)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);

        this.setMode(false);

        this.onAppear(this::fillList);

        this.add(listHeader);
        this.add(this.structures);
        this.add(descHeader);
        this.add(this.description);
        this.add(this.describe);
        this.add(buildHeader);
        this.add(this.buildTheme);
        this.add(this.generateBuild);
        this.add(this.modePlace);
        this.add(this.modeBlueprint);
        this.add(this.coordRow);
        this.add(this.blueprintRow);
        this.add(this.placeBuild);
        this.add(this.exportBuild);
    }

    /** Mode toggle: accent the active one, swap the context row and action button. */
    private void setMode(boolean blueprint)
    {
        this.blueprintMode = blueprint;

        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        this.modePlace.color(blueprint ? -1 : accent);
        this.modeBlueprint.color(blueprint ? accent : -1);

        this.coordRow.setVisible(!blueprint);
        this.blueprintRow.setVisible(blueprint);
        this.placeBuild.setVisible(!blueprint);
        this.exportBuild.setVisible(blueprint);

        this.resize();
    }

    /** Parse the X/Y/Z inputs; unparsable/empty falls back to the player-relative spot. */
    private net.minecraft.util.math.BlockPos requestedPos()
    {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();

        try
        {
            int x = Integer.parseInt(this.coords[0].getText().trim());
            int y = Integer.parseInt(this.coords[1].getText().trim());
            int z = Integer.parseInt(this.coords[2].getText().trim());

            return new net.minecraft.util.math.BlockPos(x, y, z);
        }
        catch (NumberFormatException e)
        {
            net.minecraft.client.network.ClientPlayerEntity player = client.player;

            if (player == null)
            {
                return net.minecraft.util.math.BlockPos.ORIGIN;
            }

            net.minecraft.util.math.BlockPos base = player.getBlockPos().offset(player.getHorizontalFacing(), 6);

            return new net.minecraft.util.math.BlockPos(base.getX(), player.getBlockPos().getY(), base.getZ());
        }
    }

    /** Export the latest generation as Axiom/WorldEdit blueprints (no world write). */
    private void exportBlueprint()
    {
        if (AiArchitecture.lastResult == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.export_nothing").get()));

            return;
        }

        List<String> lines = new ArrayList<>();
        AiArchitecture.Result result = AiArchitecture.lastResult;

        lines.add(L10n.lang("bbs.ui.ai.structure.export_done").format(result.name).get());

        if (AiArchitecture.lastAxiom != null)
        {
            lines.add(L10n.lang("bbs.ui.ai.structure.export_axiom").format(AiArchitecture.lastAxiom.getAbsolutePath()).get());
        }

        this.showLines(lines);
    }

    /** 生成建筑: the theme becomes a strict build order, expanded into blueprints. */
    private void generateBuilding()
    {
        if (this.building)
        {
            return;
        }

        String theme = this.buildTheme.getText().trim();

        if (theme.isEmpty())
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.build_empty").get()));

            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.chat.unconfigured").get()));

            return;
        }

        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        java.io.File generated = client.getServer() == null
            ? null
            : client.getServer().getSavePath(net.minecraft.util.WorldSavePath.GENERATED).resolve("bbs/structures").toFile();

        if (generated == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.build_no_world").get()));

            return;
        }

        this.building = true;
        this.generateBuild.setEnabled(false);

        /* Chain of thought: the description area narrates every stage */
        this.showLines(List.of(
            L10n.lang("bbs.ui.ai.structure.cot_analyze").format(theme).get(),
            L10n.lang("bbs.ui.ai.structure.cot_draft").get()));

        /* 建筑风格技能：按主题关键词匹配风格，注入调色板与结构提示 */
        String styleHint = "";
        String lowered = theme == null ? "" : theme.toLowerCase();
        var styles = mchorse.bbs_mod.ai.AiBuildSkills.styles();

        for (var style : styles)
        {
            boolean hit = false;

            for (String alias : style.aliases)
            {
                if (!alias.isBlank() && lowered.contains(alias.toLowerCase()))
                {
                    hit = true;

                    break;
                }
            }

            if (hit)
            {
                styleHint = "\n\n风格参考（" + style.id + "）: wall=" + style.walls
                    + ", accent(混贴)=" + style.accent + ", trim(线脚/立柱)=" + style.trim
                    + ", roof=" + style.roof + ", roof_style=" + style.roofStyle
                    + ", floor=" + style.floor + "。提示: " + style.paletteHint + ";" + style.hints;

                break;
            }
        }

            /* 少样本：主题命中示例时附带完整 spec 让模型照葫芦画瓢 */
            var example = mchorse.bbs_mod.ai.AiBuildSkills.matchExample(theme);
            String fewShot = "";

            if (example != null)
            {
                fewShot = "\n\n参考示例（主题「" + example.theme + "」→ spec）:\n" + example.spec
                    + "\n以上仅为结构参考，尺寸/材料按用户描述调整。";
            }

            String biomeRef = mchorse.bbs_mod.ai.AiBiomeSkills.summary();
            String principles = mchorse.bbs_mod.ai.AiBuildSkills.designPrinciples();

            /* 已学习的建筑参考（notre_dame）：材质比例+密度+比例参数 */
            String learnedRef = "";
            var learnedData = mchorse.bbs_mod.ai.AiBuildSkills.loadSkill("learned/notre_dame.json");

            if (learnedData != null)
            {
                String title = learnedData.has("title") ? learnedData.get("title").getAsString() : "";
                var dims = learnedData.getAsJsonObject("dimensions");
                var props = learnedData.getAsJsonObject("proportions");

                learnedRef = "\n\n建筑参考（" + title + "）: "
                    + dims.get("width").getAsInt() + "×" + dims.get("height").getAsInt() + "×" + dims.get("length").getAsInt()
                    + " · 密度 " + props.get("density").getAsDouble()
                    + " · 材质: ";

                var pal = learnedData.getAsJsonArray("palette");

                for (var pi : pal)
                {
                    var po = pi.getAsJsonObject();
                    learnedRef += po.get("block").getAsString() + "(" + po.get("ratio").getAsDouble() * 100 + "%) ";
                }
            }

            AiChatRequest request = new AiChatRequest(
                L10n.lang("bbs.ui.ai.structure.build_system").get(),
                L10n.lang("bbs.ui.ai.structure.build_prompt").format(theme).get() + principles + styleHint + fewShot + biomeRef + learnedRef);

        request.temperature(0.6F);
        /* 0 = the field is omitted, so the provider's own output cap applies */
        request.maxTokens(mchorse.bbs_mod.ai.AiSettings.maxTokens.get());

        AiClient.get().chat(request, (response) -> MinecraftClient.getInstance().execute(() ->
        {
            this.building = false;
            this.generateBuild.setEnabled(true);

            try
            {
                java.io.File schematics = new java.io.File(client.runDirectory, "config/worldedit/schematics");
                this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.cot_expand").get()));

                AiArchitecture.Result result = AiArchitecture.generate(response.content, generated, schematics);

                AiArchitecture.lastGenerated = result.name;
                AiArchitecture.lastResult = result;
                this.fillList();
                AiUi.headerText(this.descHeader, L10n.lang("bbs.ui.ai.structure.description"), result.name);

                List<String> done = new ArrayList<>(List.of(
                    L10n.lang("bbs.ui.ai.structure.build_done").format(result.title, result.blocks).get(),
                    L10n.lang("bbs.ui.ai.structure.build_where").get()));

                if (AiArchitecture.lastAxiom != null)
                {
                    done.add(L10n.lang("bbs.ui.ai.structure.export_axiom").format(AiArchitecture.lastAxiom.getAbsolutePath()).get());
                }

                this.showLines(done);
            }
            catch (Exception e)
            {
                this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.build_fail").format(
                    e.getMessage() == null ? "json" : e.getMessage()).get()));
            }
        }), (error) -> MinecraftClient.getInstance().execute(() ->
        {
            this.building = false;
            this.generateBuild.setEnabled(true);
            this.showLines(List.of(L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get()));
        }));
    }

    /** 放置: the last generated structure, six blocks in front of the player. */
    private void placeBuilding(net.minecraft.util.math.BlockPos pos)
    {
        if (AiArchitecture.lastGenerated == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.place_nothing").get()));

            return;
        }

        AiArchitecture.onPlaced = (message) ->
        {
            this.showLines(List.of(message));
            this.fillList();
            AiArchitecture.onPlaced = null;
        };

        net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();

        buf.writeString(AiArchitecture.lastGenerated);
        buf.writeBlockPos(pos);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(ServerNetwork.SERVER_AI_PLACE_STRUCTURE, buf);
    }


    private void fillList()
    {
        List<UIElement> rows = new ArrayList<>();
        List<String> ids;

        try
        {
            ids = StructureManager.getStructureIds();
        }
        catch (Exception e)
        {
            ids = new ArrayList<>();
        }

        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        for (String id : ids)
        {
            String captured = id;
            UIButton row = new UIButton(L10n.lang("bbs.ui.ai.structure.row").format(id), (b) -> this.select(captured));

            row.color(captured.equals(this.selected) ? accent : -1);
            rows.add(row);
        }

        if (rows.isEmpty())
        {
            rows.add(UI.label(L10n.lang("bbs.ui.ai.structure.none_hint"), UIConstants.CONTROL_HEIGHT * 2));
        }

        this.structures.removeAll();
        this.structures.add(UI.column(1, rows.toArray(new UIElement[0])));
        AiUi.headerText(this.listHeader, L10n.lang("bbs.ui.ai.structure.list"), L10n.lang("bbs.ui.ai.structure.count").format(ids.size()).get());

        /* Auto-select the first structure that actually reads - an unreadable
         * asset pack entry must not be the panel's first impression */
        if (this.selected == null || !ids.contains(this.selected))
        {
            this.selected = null;

            for (String id : ids)
            {
                if (StructureManager.get(id) != null)
                {
                    this.select(id);

                    break;
                }
            }
        }

        if (this.selected == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.pick").get()));
        }
    }

    /** Selecting a structure immediately shows its human-readable facts. */
    private void select(String id)
    {
        this.selected = id;
        AiUi.headerText(this.descHeader, L10n.lang("bbs.ui.ai.structure.description"), id);
        this.describe.setEnabled(true);
        this.fillList();

        StructureRenderData data = StructureManager.get(id);

        if (data == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.unreadable").format(id).get()));

            return;
        }

        List<String> lines = new ArrayList<>();

        lines.add(L10n.lang("bbs.ui.ai.structure.facts_id").format(id).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_size").format(data.size.getX(), data.size.getY(), data.size.getZ()).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_blocks").format(data.getBlocks().size()).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_entities").format(data.getBlockEntities().size()).get());

        /* What it is made of: the five most common blocks, so the description
         * area reads like a material list even before the AI says anything */
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (net.minecraft.block.BlockState state : data.getBlocks().values())
        {
            String name = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
            counts.merge(name, 1, Integer::sum);
        }

        if (!counts.isEmpty())
        {
            lines.add("");
            lines.add(L10n.lang("bbs.ui.ai.structure.facts_makeup").get());

            counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .forEach((e) -> lines.add("  " + L10n.lang("bbs.ui.ai.structure.facts_block_row").format(
                    e.getKey().substring(e.getKey().lastIndexOf('.') + 1), e.getValue()).get()));
        }

        this.showLines(lines);
    }

    /** AI 描述: the facts become an LLM prompt; without a backend the button says so. */
    private void aiDescribe()
    {
        if (this.describing)
        {
            return;
        }

        if (this.selected == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.pick").get()));

            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.chat.unconfigured").get()));

            return;
        }

        StructureRenderData data = StructureManager.get(this.selected);

        if (data == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.unreadable").format(this.selected).get()));

            return;
        }

        this.describing = true;
        this.showLines(List.of(L10n.lang("bbs.ui.ai.chat.thinking").get()));

        String user = L10n.lang("bbs.ui.ai.structure.ai_prompt").format(
            this.selected,
            data.size.getX(), data.size.getY(), data.size.getZ(),
            data.getBlocks().size(),
            data.getBlockEntities().size()).get();

        AiChatRequest request = new AiChatRequest(L10n.lang("bbs.ui.ai.structure.ai_system").get(), user);

        request.temperature(0.4F);
        request.maxTokens(mchorse.bbs_mod.ai.AiSettings.maxTokens.get());

        AiClient.get().chat(request, (response) -> MinecraftClient.getInstance().execute(() ->
        {
            this.describing = false;
            this.showLines(FontLines.split(response.content, this.lastListWidth));
        }), (error) -> MinecraftClient.getInstance().execute(() ->
        {
            this.describing = false;
            this.showLines(List.of(L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get()));
        }));
    }

    /** Show pre-wrapped lines in the description scroll. */
    private void showLines(List<String> lines)
    {
        this.description.removeAll();

        for (String line : lines)
        {
            UILabel label = new UILabel(IKey.constant(line));

            label.color(Colors.WHITE, false).h(mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer.DEFAULT_LINE_HEIGHT + 2);
            this.description.add(label);
        }

        this.description.resize();
    }

    @Override
    public void resize()
    {
        this.lastListWidth = Math.max(80, this.description.area.w - UIConstants.MARGIN * 4);

        super.resize();
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());
        AiUi.topEdge(context, this.area.x, this.area.y, this.area.w);

        super.render(context);
    }

    /** Static wrap helper (keeps showLines call sites short). */
    private static final class FontLines
    {
        static List<String> split(String text, int width)
        {
            try
            {
                FontRenderer font = Batcher2D.getDefaultTextRenderer();
                List<String> out = new ArrayList<>();

                for (String paragraph : text.replace("\r", "").split("\n"))
                {
                    out.addAll(font.wrap(paragraph, Math.max(80, width)));
                }

                return out;
            }
            catch (Exception e)
            {
                List<String> fallback = new ArrayList<>();

                fallback.add(text);

                return fallback;
            }
        }
    }
}
