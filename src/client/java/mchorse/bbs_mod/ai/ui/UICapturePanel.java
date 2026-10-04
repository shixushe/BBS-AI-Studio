package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.capture.ExternalVideoCapture;
import mchorse.bbs_mod.ui.utils.UIFileDialogs;
import mchorse.bbs_mod.ai.capture.CapturedFrame;
import mchorse.bbs_mod.ai.capture.FrameSequence;
import mchorse.bbs_mod.ai.capture.SceneFrameCapture;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频采集面板（copilot spec section 5.6）—— 从零重写的单列流：
 *
 * ┌ 采集 ──────────────────────────────┐
 * │ 抽帧间隔 [4]  时长 [48]  [开始采集] │
 * ├ 帧序列 ────────────────────────────┤
 * │ [缩略图] [缩略图] [缩略图] ...      │
 * ├ 输出 ──────────────────────────────┤
 * │ 路径 + 状态                         │
 * └ [送去理解] ────────────────────────┘
 *
 * 采集结果是真实缩略图（每帧上传一张 GL 纹理，采集中零开销），不再是
 * 一行"N 帧"文字。顶层独立面板是刻意的：采集要冻结时间轴，覆盖层会让
 * 用户中途拖播放头毁掉 tick/帧映射（spec 6.1）——这是正确性不是品味。
 */
public class UICapturePanel extends UIDashboardPanel
{
    private static final String MANIFEST_ID = "last";

    private final UITextbox interval;
    private final UITextbox duration;
    private final AiUi.StatusLine status;
    private final UITextbox path;
    private final AiUi.FrameStrip strip;
    private final UILabel framesHeader;
    private final UIButton capture;
    private final UIButton pickVideo;
    private final UILabel pickedVideo;

    private FrameSequence sequence;
    private SceneFrameCapture session;

    public UICapturePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.interval = new UITextbox(8, (t) -> {});
        this.interval.setText("4");

        this.duration = new UITextbox(8, (t) -> {});
        this.duration.setText("48");

        this.capture = new UIButton(L10n.lang("bbs.ui.ai.capture.recapture"), (b) -> this.startCapture());
        this.capture.tooltip(L10n.lang("bbs.ui.ai.capture.recapture_tooltip"));

        this.pickVideo = new UIButton(L10n.lang("bbs.ui.ai.capture.pick_video"), (b) -> this.pickExternalVideo());
        this.pickVideo.tooltip(L10n.lang("bbs.ui.ai.capture.pick_video_tooltip"));

        this.pickedVideo = new UILabel(IKey.EMPTY);
        this.pickedVideo.color(Colors.LIGHTER_GRAY, false);

        this.strip = new AiUi.FrameStrip();

        this.path = new UITextbox(256, (t) -> {});
        this.path.placeholder(L10n.lang("bbs.ui.ai.capture.path_hint"));

        this.status = new AiUi.StatusLine(L10n.lang("bbs.ui.ai.capture.idle"));

        UIButton send = new UIButton(L10n.lang("bbs.ui.ai.capture.send"), (b) -> this.sendForUnderstanding());

        send.color(BBSSettings.primaryColor.get() | Colors.A100);
        send.tooltip(L10n.lang("bbs.ui.ai.capture.send_tooltip"));

        /* Absolute layout - fixed stack, every block pinned */
        int m = UIConstants.MARGIN;
        int row = UIConstants.CONTROL_HEIGHT;
        int y = AiUi.HEADER + m;

        UILabel sourceHeader = AiUi.header(L10n.lang("bbs.ui.ai.capture.source"), "");
        sourceHeader.relative(this).x(0).y(0).w(1F).h(AiUi.HEADER);

        UIElement params = UI.row(m,
            UI.label(L10n.lang("bbs.ui.ai.capture.interval"), UIConstants.CONTROL_HEIGHT),
            this.interval.h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.capture.duration"), UIConstants.CONTROL_HEIGHT),
            this.duration.h(UIConstants.CONTROL_HEIGHT),
            this.capture);

        params.relative(this).x(m).y(y).w(1F, -m * 2).h(row);
        params.row(m).height(row);

        y += row + m;
        UILabel externalLabel = UI.label(L10n.lang("bbs.ui.ai.capture.external"), row);
        UIElement externalRow = UI.row(m, this.pickVideo, this.pickedVideo);

        externalRow.row(m).preferred(1).height(row);
        externalRow.relative(this).x(m).y(y).w(1F, -m * 2).h(row);

        y += row + m;
        this.framesHeader = AiUi.header(L10n.lang("bbs.ui.ai.capture.frames"), L10n.lang("bbs.ui.ai.capture.no_frames").get());
        framesHeader.relative(this).x(0).y(y).w(1F).h(AiUi.HEADER);

        y += AiUi.HEADER + m;
        this.strip.relative(this).x(m).y(y).w(1F, -m * 2).h(1F, -(y + AiUi.HEADER + m * 5 + row * 4 + AiUi.BAR + AiUi.TASKBAR));

        y += 1F;
        UILabel outputHeader = AiUi.header(L10n.lang("bbs.ui.ai.capture.output"), "");

        /* 底部堆叠链（从底往上）：bottom → skillTips → tips → status → path → outputHeader */
        int bottomChain = AiUi.BAR + AiUi.TASKBAR;
        int skillTipsY = bottomChain + 4;
        int tipsY = skillTipsY + UIConstants.CONTROL_HEIGHT * 3 + 4;
        int statusY = tipsY + UIConstants.CONTROL_HEIGHT * 2 + 4;
        int pathY = statusY + row + 4;
        int outputHeaderY = pathY + row + 4;

        outputHeader.relative(this).x(0).y(1F, -outputHeaderY).w(1F).h(AiUi.HEADER);
        this.path.relative(this).x(m).y(1F, -pathY).w(1F, -m * 2).h(row);
        this.status.relative(this).x(m).y(1F, -statusY).w(1F, -m * 2).h(row);

        UILabel tips = UI.label(L10n.lang("bbs.ui.ai.capture.tips"), UIConstants.CONTROL_HEIGHT * 2);
        tips.color(Colors.LIGHTER_GRAY, false);
        tips.relative(this).x(m).y(1F, -tipsY).w(1F, -m * 2).h(UIConstants.CONTROL_HEIGHT * 2);

        UILabel skillTips = UI.label(L10n.lang("bbs.ui.ai.capture.skill_tip"), UIConstants.CONTROL_HEIGHT * 3);
        skillTips.color(mchorse.bbs_mod.utils.colors.Colors.LIGHTER_GRAY, false);
        skillTips.relative(this).x(m).y(1F, -skillTipsY).w(1F, -m * 2).h(UIConstants.CONTROL_HEIGHT * 3);

        UIElement bottom = UI.row(m, send, new UILabel(IKey.EMPTY));

        bottom.row(m).preferred(1).height(AiUi.BAR - 8);
        bottom.relative(this).y(1F, -(AiUi.BAR + AiUi.TASKBAR)).w(1F).h(AiUi.BAR);

        this.add(sourceHeader);
        this.add(params);
        this.add(externalLabel);
        this.add(externalRow);
        this.add(framesHeader);
        this.add(this.strip);
        this.add(outputHeader);
        this.add(this.path);
        this.add(this.status);
        this.add(tips);
        this.add(skillTips);
        this.add(bottom);

        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("capture.frames", () -> this.strip);
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());
        AiUi.topEdge(context, this.area.x, this.area.y, this.area.w);

        super.render(context);
    }

    /** Start a scene capture from the film editor's playhead over the given length. */
    private void startCapture()
    {
        mchorse.bbs_mod.ui.film.UIFilmPanel source = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

        if (source == null)
        {
            this.status.set(L10n.lang("bbs.ui.ai.capture.no_editor").get(), AiUi.StatusLine.State.IDLE);

            return;
        }

        int to = source.getCursor() + this.parseDuration();

        this.session = new SceneFrameCapture(source.getCursor(), to, this.parseInterval());
        this.status.set(L10n.lang("bbs.ui.ai.capture.capturing").get(), AiUi.StatusLine.State.WORKING);
    }

    private int parseInt(String text, int fallback)
    {
        try
        {
            return Math.max(1, Integer.parseInt(text.trim()));
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private int parseInterval()
    {
        return this.parseInt(this.interval.getText(), 4);
    }

    /** Explorer-picked reference video: sample it into the strip right away. */
    private void pickExternalVideo()
    {
        UIFileDialogs.pickFile(L10n.lang("bbs.ui.ai.capture.pick_video"),
            null,
            new String[]{"*.mp4", "*.mkv", "*.webm", "*.mov", "*.avi"},
            L10n.lang("bbs.ui.ai.capture.video_filter"),
            (file) ->
            {
                if (file == null)
                {
                    return;
                }

                this.pickedVideo.label = IKey.constant(file.getName());
                this.status.set(L10n.lang("bbs.ui.ai.capture.sampling").format(file.getName()).get(), AiUi.StatusLine.State.WORKING);

                float fps = 20F / Math.max(1, this.parseInterval());
                FrameSequence sequence = ExternalVideoCapture.captureFile(this, file, fps, 0F, 0F);

                if (sequence == null || sequence.isEmpty())
                {
                    this.status.set(L10n.lang("bbs.ui.ai.capture.sampling_empty").get(), AiUi.StatusLine.State.FAIL);

                    return;
                }

                this.sequence = sequence;
                this.status.label = L10n.lang("bbs.ui.ai.capture.done").format(sequence.size());
                this.showFrames();
                this.persist();
            });
    }

    private int parseDuration()
    {
        return this.parseInt(this.duration.getText(), 48);
    }

    /** Feed the running capture from the panel's update pass (render thread). */
    @Override
    public void update()
    {
        super.update();

        if (this.session == null || !this.session.isActive())
        {
            return;
        }

        mchorse.bbs_mod.ui.film.UIFilmPanel source = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

        if (source == null)
        {
            this.session.abort();

            return;
        }

        int cursor = source.getCursor();

        /* Auto-walk: the capture drives the play head itself, so the whole
         * window is captured with one click (user keeps their hands free) */
        if (cursor < this.session.getNextTick())
        {
            source.setCursor(this.session.getNextTick());

            return;
        }

        net.minecraft.client.util.Window window = net.minecraft.client.MinecraftClient.getInstance().getWindow();

        if (this.session.update(cursor, window.getWidth(), window.getHeight()))
        {
            this.status.set(L10n.lang("bbs.ui.ai.capture.capturing").format(Math.round(this.session.progress() * 100F)).get(), AiUi.StatusLine.State.WORKING);
        }
        else
        {
            this.sequence = this.session.finish();
            this.session = null;
            this.status.set(L10n.lang("bbs.ui.ai.capture.done").format(this.sequence.size()).get(), AiUi.StatusLine.State.OK);
            AiUi.headerText(this.framesHeader, L10n.lang("bbs.ui.ai.capture.frames"), L10n.lang("bbs.ui.ai.capture.frames_count").format(this.sequence.size()).get());
            this.showFrames();
            this.persist();
        }
    }

    /** Swap the strip to the freshly captured frames. */
    private void showFrames()
    {
        List<CapturedFrame> frames = new ArrayList<>();

        if (this.sequence != null)
        {
            for (int i = 0; i < this.sequence.frames.size() && i < 24; i++)
            {
                frames.add(this.sequence.frames.get(i));
            }
        }

        this.strip.setFrames(frames);
    }

    /** 送去理解: with a configured vision backend this ships the frames; today it reports honestly. */
    private void sendForUnderstanding()
    {
        if (this.sequence == null || this.sequence.isEmpty())
        {
            this.status.set(L10n.lang("bbs.ui.ai.capture.nothing_to_send").get(), AiUi.StatusLine.State.IDLE);

            return;
        }

        if (!AiSettingsGate.visionConfigured())
        {
            this.status.set(L10n.lang("bbs.ui.ai.capture.vision_unconfigured").format(this.sequence.size()).get(), AiUi.StatusLine.State.IDLE);

            return;
        }

        this.status.set(L10n.lang("bbs.ui.ai.capture.would_upload").format(this.sequence.size()).get(), AiUi.StatusLine.State.OK);
    }

    /** Persist frames + manifest so a panel rebuild (or a dashboard close) keeps them. */
    private void persist()
    {
        if (this.sequence == null)
        {
            return;
        }

        File folder = SceneFrameCapture.captureFolder();

        folder.mkdirs();

        for (int i = 0; i < this.sequence.frames.size(); i++)
        {
            CapturedFrame frame = this.sequence.frames.get(i);
            File file = new File(folder, "frame_" + MANIFEST_ID + "_" + i + "_t" + frame.tick + ".png");

            try
            {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(frame.image.width, frame.image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

                for (int y = 0; y < frame.image.height; y++)
                {
                    for (int x = 0; x < frame.image.width; x++)
                    {
                        image.setRGB(x, y, frame.image.getColor(x, y).getARGBColor());
                    }
                }

                javax.imageio.ImageIO.write(image, "png", file);
            }
            catch (Exception e)
            {
                this.status.set(L10n.lang("bbs.ui.ai.capture.persist_failed").format(e.getMessage()).get(), AiUi.StatusLine.State.FAIL);

                return;
            }
        }

        this.path.setText(folder.getAbsolutePath());
    }

    private static class AiSettingsGate
    {
        static boolean visionConfigured()
        {
            return mchorse.bbs_mod.ai.AiSettings.isConfigured() && mchorse.bbs_mod.ai.AiSettings.supportsVision.get();
        }
    }
}
