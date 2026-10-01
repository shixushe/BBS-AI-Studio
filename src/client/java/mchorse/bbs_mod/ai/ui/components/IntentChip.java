package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * 解析出的意图标签 chip（mockup ② 左栏底部的 accent 小圆角标签）。
 */
public class IntentChip extends UILabel
{
    public IntentChip(String text)
    {
        super(IKey.constant(text), Colors.WHITE);

        this.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get())).labelAnchor(0.5F, 0.5F);
        this.h(UIConstants.CONTROL_HEIGHT);
    }
}
