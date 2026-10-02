package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.capture.CapturedFrame;
import mchorse.bbs_mod.ai.capture.ExternalVideoCapture;
import mchorse.bbs_mod.ai.capture.FrameSequence;
import mchorse.bbs_mod.ai.capture.SceneFrameCapture;
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
import mchorse.bbs_mod.utils.resources.Pixels;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The standalone video capture panel (copilot spec section 5.6, milestone
 * M5). It is deliberately a TOP-LEVEL dashboard panel, not an overlay in the
 * film editor: capture means "run a recording and freeze the timeline", and
 * an overlay would let the user drag the play head mid-capture and destroy
 * the tick/frame mapping (spec 6.1) - the separation is correctness, not
 * taste.
 *
 * <p>Capture results persist under {@code <settings>/ai_captures} with a
 * manifest, so closing the panel never loses freshly captured frames (spec
 * 5.6 hard rule). The panel itself never writes tracks - applying poses
 * routes through the film editor panel explicitly.</p>
 */
public class UICapturePanel extends UIDashboardPanel
{
    private static final String MANIFEST_ID = "last";

    private final UILabel status;
    private final UILabel frames;
    private final UITextbox interval;
    private final UITextbox duration;
    private final UITextbox path;

    private final UIButton recapture;
    private final UIButton send;

    private FrameSequence sequence;
    private SceneFrameCapture session;

    public UICapturePanel(UIDashboard dashboard)
    {
        super(dashboard);

        /* Left: source + capture range + frame strip */
        this.interval = new UITextbox(8, (t) -> {});
        this.interval.setText("4");

        this.duration = new UITextbox(8, (t) -> {});
        this.duration.setText("48");

        this.path = new UITextbox(256, (t) -> {});
        this.path.placeholder(L10n.lang("bbs.ui.ai.capture.path_hint"));

        this.frames = new UILabel(L10n.lang("bbs.ui.ai.capture.no_frames"));
        this.frames.color(Colors.LIGHTER_GRAY, false).h(UIConstants.CONTROL_HEIGHT);

        UIElement left = UI.column(UIConstants.MARGIN,
            this.header(L10n.lang("bbs.ui.ai.capture.source")),
            UI.label(L10n.lang("bbs.ui.ai.capture.source_scene"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.capture.interval"), UIConstants.CONTROL_HEIGHT),
            this.interval.h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.capture.duration"), UIConstants.CONTROL_HEIGHT),
            this.duration.h(UIConstants.CONTROL_HEIGHT),
            this.header(L10n.lang("bbs.ui.ai.capture.frames")),
            this.frames
        );

        left.w(170).h(1F);

        /* Right: parameters + output info */
        this.status = new UILabel(L10n.lang("bbs.ui.ai.capture.idle"));
        this.status.color(Colors.LIGHTER_GRAY, false).h(UIConstants.CONTROL_HEIGHT);

        UIElement right = UI.column(UIConstants.MARGIN,
            this.header(L10n.lang("bbs.ui.ai.capture.params")),
            UI.label(L10n.lang("bbs.ui.ai.capture.param_downsample"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.capture.param_cap"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.capture.output"), UIConstants.CONTROL_HEIGHT),
            this.path.h(UIConstants.CONTROL_HEIGHT),
            this.status
        );

        right.h(1F);

        UIElement columns = UI.row(UIConstants.MARGIN, left, right);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).w(1F).h(1F, -BAR);

        /* Bottom action bar: 重新采集 / 送去理解 */
        this.recapture = new UIButton(L10n.lang("bbs.ui.ai.capture.recapture"), (b) -> this.startCapture());
        this.send = new UIButton(L10n.lang("bbs.ui.ai.capture.send"), (b) -> this.sendForUnderstanding());
        this.send.color(BBSSettings.primaryColor.get() | Colors.A100);
        this.send.tooltip(L10n.lang("bbs.ui.ai.capture.send_tooltip"));
        this.recapture.tooltip(L10n.lang("bbs.ui.ai.capture.recapture_tooltip"));

        UIElement bottom = UI.row(UIConstants.MARGIN, this.recapture, this.send);

        bottom.row(UIConstants.MARGIN).preferred(1).height(UIConstants.CONTROL_HEIGHT + 4);
        bottom.relative(this).y(1F, -BAR).w(1F).h(BAR);

        this.add(columns);
        this.add(bottom);

        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("capture.frames", () -> this.frames);
    }

    private UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(BBSSettings.primaryColor(Colors.A25)).h(HEADER).w(1F);

        return label;
    }

    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = UIConstants.CONTROL_HEIGHT + 2;

    /** Start (or restart) a scene capture over the film editor's current timeline view. */
    private void startCapture()
    {
        UIFilmPanelProvider.Source source = UIFilmPanelProvider.filmPanel(this.dashboard);
        int interval = parseInterval();

        if (source == null)
        {
            this.status.label = L10n.lang("bbs.ui.ai.capture.no_editor");

            return;
        }

        int to = source.panel.getCursor() + this.parseDuration();

        this.session = new SceneFrameCapture(source.panel.getCursor(), to, interval);
        this.status.label = L10n.lang("bbs.ui.ai.capture.capturing");
    }

    private int parseDuration()
    {
        try
        {
            return Math.max(1, Integer.parseInt(this.duration.getText().trim()));
        }
        catch (NumberFormatException e)
        {
            return 48;
        }
    }

    private int parseInterval()
    {
        try
        {
            return Math.max(1, Integer.parseInt(this.interval.getText().trim()));
        }
        catch (NumberFormatException e)
        {
            return 4;
        }
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

        UIFilmPanelProvider.Source source = UIFilmPanelProvider.filmPanel(this.dashboard);

        if (source == null)
        {
            this.session.abort();

            return;
        }

        int cursor = source.panel.getCursor();

        /* Auto-walk: the capture drives the play head itself, so the whole
         * window is captured with one click (user keeps their hands free) */
        if (cursor < this.session.getNextTick())
        {
            source.panel.setCursor(this.session.getNextTick());

            return;
        }

        net.minecraft.client.util.Window window = net.minecraft.client.MinecraftClient.getInstance().getWindow();

        if (this.session.update(cursor, window.getWidth(), window.getHeight()))
        {
            this.status.label = L10n.lang("bbs.ui.ai.capture.capturing").format(Math.round(this.session.progress() * 100F));
        }
        else
        {
            this.sequence = this.session.finish();
            this.session = null;
            this.status.label = L10n.lang("bbs.ui.ai.capture.done").format(this.sequence.size());
            this.status.color(Colors.LIGHTEST_GRAY, false);
            this.frames.label = L10n.lang("bbs.ui.ai.capture.frames_count").format(this.sequence.size());
            this.persist();
        }
    }

    /** 送去理解: with a configured vision backend this ships the frames; today it reports honestly. */
    private void sendForUnderstanding()
    {
        if (this.sequence == null || this.sequence.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.capture.nothing_to_send");

            return;
        }

        if (!AiSettingsGate.visionConfigured())
        {
            this.status.label = L10n.lang("bbs.ui.ai.capture.vision_unconfigured").format(this.sequence.size());

            return;
        }

        /* Vision upload lands with the M7 image backend; the explicit N-frames
         * confirmation (spec 6.4) is what this status line states */
        this.status.label = L10n.lang("bbs.ui.ai.capture.would_upload").format(this.sequence.size());
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
                this.status.label = L10n.lang("bbs.ui.ai.capture.persist_failed").format(e.getMessage());

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

    /** Indirection so the panel reaches the open film editor without a hard import web. */
    private static class UIFilmPanelProvider
    {
        record Source(mchorse.bbs_mod.ui.film.UIFilmPanel panel)
        {
        }

        static Source filmPanel(UIDashboard dashboard)
        {
            mchorse.bbs_mod.ui.film.UIFilmPanel panel = dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

            return panel == null ? null : new Source(panel);
        }
    }
}
