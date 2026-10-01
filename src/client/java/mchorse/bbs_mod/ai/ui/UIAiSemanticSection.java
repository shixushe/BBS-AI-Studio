package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * The semantic adjustment section inside the form editor's options column
 * (copilot spec section 5.3) - the red-box slot in the mockup.
 *
 * <p>Every constraint the spec derives from the panel-rebuild mechanism is
 * met by construction:</p>
 * <ul>
 * <li>mounted through the base class's own {@code section(id, ...)} on
 * {@code UIFormPanel.options}, so pose/IK/constraints/physics panels all get
 * it, with fold state remembered across rebuilds and no touch on the
 * draggable width share</li>
 * <ul>
 */
public class UIAiSemanticSection
{
    /** Attach the AI section to a form panel's options column. */
    public static void attach(UIFormPanel panel)
    {
        var section = panel.section(L10n.lang("bbs.ui.ai.semantic.title"), "ai.semantic", false);

        UILabel hint = UI.label(L10n.lang("bbs.ui.ai.semantic.hint"), UIConstants.CONTROL_HEIGHT * 2);

        UITextbox input = new UITextbox(256, (t) -> {});

        input.placeholder(L10n.lang("bbs.ui.ai.bar.placeholder"));

        UILabel status = new UILabel(L10n.lang("bbs.ui.ai.bar.preview"));
        status.color(Colors.LIGHTER_GRAY, false);

        UIButton apply = new UIButton(L10n.lang("bbs.ui.ai.bar.execute"), (b) ->
        {
            UIFilmPanel film = findFilmPanel(panel);

            if (film == null)
            {
                status.label = L10n.lang("bbs.ui.ai.creative.no_replay");

                return;
            }

            AiPolishFlow.run(film, input.getText().trim(), status);
            input.setText("");
        });

        UIButton commit = new UIButton(L10n.lang("bbs.ui.ai.bar.commit"), (b) ->
        {
            UIFilmPanel film = findFilmPanel(panel);

            if (film != null)
            {
                AiPolishFlow.confirm(film, status);
            }
        });

        commit.color(BBSSettings.primaryColor.get() | Colors.A100);

        UIButton discard = new UIButton(L10n.lang("bbs.ui.ai.bar.discard"), (b) -> AiPolishFlow.discard(status));

        UIElement row = UI.row(UIConstants.MARGIN, commit, discard);

        row.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);

        input.h(UIConstants.CONTROL_HEIGHT);
        status.h(UIConstants.CONTROL_HEIGHT);
        hint.h(UIConstants.CONTROL_HEIGHT * 2);
        section.add(hint);
        section.add(input);
        section.add(row);
        section.add(status);
        panel.options.add(section);
    }

    /** The film editor owning the dashboard this panel lives in, when open. */
    private static UIFilmPanel findFilmPanel(UIFormPanel panel)
    {
        if (mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu() instanceof mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard)
        {
            return dashboard.getPanel(UIFilmPanel.class);
        }

        return null;
    }
}
