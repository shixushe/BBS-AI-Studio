package mchorse.bbs_mod.ai.ui;


import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 生成前向用户补充细节（spec 5.6 向用户提问）：动作幅度 + 地面识别。
 * 全部下拉/开关——没有自由文本框（用户要求一切可选项用选择件）。
 * 回调把答案带回生成管线：幅度缩放姿态旋转，地面识别开时蹲/落地拍
 * 自动压低重心。
 */
public class UIAiGenerateAskPanel extends UIOverlayPanel
{
    public static final float[] AMPLITUDES = {0.65F, 1F, 1.35F};

    private final UIChoiceButton<String> amplitude;
    private final UIToggle ground;
    private int amplitudeIndex = 1;

    public UIAiGenerateAskPanel(UIContext context, Consumer<int[]> onGenerate)
    {
        super(L10n.lang("bbs.ui.ai.ask_generate.title"));

        UILabel hint = UI.label(L10n.lang("bbs.ui.ai.ask_generate.hint"), UIConstants.CONTROL_HEIGHT * 2);

        hint.color(mchorse.bbs_mod.utils.colors.Colors.LIGHTER_GRAY, false);

        this.amplitude = new UIChoiceButton<>(Arrays.asList(L10n.lang("bbs.ui.ai.ask_generate.subtle").get(),
            L10n.lang("bbs.ui.ai.ask_generate.natural").get(),
            L10n.lang("bbs.ui.ai.ask_generate.exaggerated").get()),
            (choice) -> Icons.POSE,
            (choice) -> mchorse.bbs_mod.l10n.keys.IKey.constant(choice == null ? "" : choice));

        this.amplitude.setValue(L10n.lang("bbs.ui.ai.ask_generate.natural").get());
        this.amplitude.h(UIConstants.CONTROL_HEIGHT);
        this.amplitude.callback((choice) ->
        {
            if (choice != null)
            {
                for (int i = 0; i < AMPLITUDES.length; i++)
                {
                    String label = i == 0 ? L10n.lang("bbs.ui.ai.ask_generate.subtle").get()
                        : i == 1 ? L10n.lang("bbs.ui.ai.ask_generate.natural").get()
                        : L10n.lang("bbs.ui.ai.ask_generate.exaggerated").get();

                    if (label.equals(choice))
                    {
                        this.amplitudeIndex = i;

                        break;
                    }
                }
            }
        });

        this.ground = new UIToggle(L10n.lang("bbs.ui.ai.ask_generate.ground"), true, (t) -> {});
        this.ground.h(UIConstants.CONTROL_HEIGHT);

        UIButton go = new UIButton(L10n.lang("bbs.ui.ai.ask_generate.go"), (b) ->
        {
            this.close();

            onGenerate.accept(new int[] {this.amplitudeIndex, this.ground.getValue() ? 1 : 0});
        });

        UIButton cancel = new UIButton(L10n.lang("bbs.ui.ai.ask.cancel"), (b) -> this.close());

        UIElement bottom = UI.row(UIConstants.MARGIN, go, cancel);

        bottom.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);
        bottom.h(UIConstants.CONTROL_HEIGHT);

        this.content.column(UIConstants.MARGIN).vertical().stretch();
        this.content.add(hint);
        this.content.add(UI.labelRow(L10n.lang("bbs.ui.ai.ask_generate.amplitude"), this.amplitude));
        this.content.add(UI.labelRow(L10n.lang("bbs.ui.ai.ask_generate.ground_label"), this.ground));
        this.content.add(bottom);
    }
}
