package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.ai.pose.BoneNameResolver;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The "ask the user" dialog of the AI assistant (copilot spec sections 5.3
 * note and 12.2's hard rule): when the bone resolver cannot confidently map
 * the pose library's generic bones onto the model's real ones, the assistant
 * MUST ask - this panel lists every unresolved generic bone with a text field
 * for the real name (pre-filled with nothing, never guessed), and only lets
 * the user proceed once every field names a bone that actually exists.
 */
public class UIAiAskOverlayPanel extends UIOverlayPanel
{
    public static final String SKIP = "(跳过 / skip)";

    private final Map<String, mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String>> fields = new LinkedHashMap<>();
    private final List<String> inventory;
    private final Consumer<BoneNameResolver.Result> onConfirmed;

    public UIAiAskOverlayPanel(UIContext context, List<String> unresolved, List<String> inventory, Consumer<BoneNameResolver.Result> onConfirmed)
    {
        super(L10n.lang("bbs.ui.ai.ask.title"));

        this.inventory = inventory;
        this.onConfirmed = onConfirmed;

        UILabel hint = UI.label(L10n.lang("bbs.ui.ai.ask.hint"), UIConstants.CONTROL_HEIGHT * 2);

        hint.color(Colors.LIGHTER_GRAY, false);

        UIElement rows = UI.column(UIConstants.MARGIN);

        List<String> options = new ArrayList<>();

        options.add(SKIP);
        options.addAll(inventory);

        for (String generic : unresolved)
        {
            UILabel label = UI.label(L10n.lang("bbs.ui.ai.ask.bone").format(generic), UIConstants.CONTROL_HEIGHT);

            mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> pick =
                new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                    options,
                    (choice) -> mchorse.bbs_mod.ui.utils.icons.Icons.POSE,
                    (choice) -> mchorse.bbs_mod.l10n.keys.IKey.constant(choice));

            pick.setValue(SKIP);
            pick.h(UIConstants.CONTROL_HEIGHT);
            this.fields.put(generic, pick);
            rows.add(label);
            rows.add(pick);
        }

        UIButton confirm = new UIButton(L10n.lang("bbs.ui.ai.ask.confirm"), (b) -> this.confirm(context));
        UIButton cancel = new UIButton(L10n.lang("bbs.ui.ai.ask.cancel"), (b) -> this.close());

        UIElement bottom = UI.row(UIConstants.MARGIN, confirm, cancel);

        bottom.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);

        this.column(UIConstants.MARGIN).height(-1);
        this.add(hint);
        this.add(rows);
        this.add(bottom);
    }

    private void confirm(UIContext context)
    {
        Map<String, BoneNameResolver.Resolution> confirmed = new LinkedHashMap<>();

        for (Map.Entry<String, mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String>> entry : this.fields.entrySet())
        {
            String actual = entry.getValue().getValue();

            if (SKIP.equals(actual))
            {
                /* 留空=跳过:该骨骼不参与本次生成 */
                continue;
            }

            if (!this.inventory.contains(actual))
            {
                return;
            }

            confirmed.put(entry.getKey(), BoneNameResolver.confirmed(entry.getKey(), actual));
        }

        BoneNameResolver.Result result = BoneNameResolver.resolve(this.inventory);

        result.resolved.putAll(confirmed);
        result.unresolved.removeAll(confirmed.keySet());

        this.close();
        this.onConfirmed.accept(result);
    }

}
