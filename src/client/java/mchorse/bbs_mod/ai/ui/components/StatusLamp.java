package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * 后端状态灯（mockup ② 底部的 ● 未配置/已配置/超时）。
 * 圆点 + 文案一体，颜色由状态决定。
 */
public class StatusLamp extends UILabel
{
    public enum State
    {
        OFF("● 未配置", Colors.LIGHTER_GRAY),
        READY("● 已配置", Colors.GREEN),
        TIMEOUT("● 超时", Colors.RED);

        public final String text;
        public final int color;

        State(String text, int color)
        {
            this.text = text;
            this.color = color;
        }
    }

    public StatusLamp()
    {
        super(IKey.constant(State.OFF.text));

        this.color(Colors.LIGHTER_GRAY, false).labelAnchor(0F, 0.5F);
    }

    public void set(State state)
    {
        this.label = IKey.constant("● " + state.text);
        this.color(state.color, false);
    }

    public void set(String text, int color)
    {
        this.label = IKey.constant(text);
        this.color(color, false);
    }
}
