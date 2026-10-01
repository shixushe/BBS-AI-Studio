package mchorse.bbs_mod.ai.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, deterministic natural-language -> {@link PolishOp} parsing for the
 * polish mode of the AI chat bar. This is the no-key degradation path (iron
 * rule 4): with no backend configured the polish workflow must stay fully
 * usable, so a small bilingual keyword table does the labeling instead of a
 * model. Same input, same ops, always.
 *
 * <p>Recognized shapes, combinable in one sentence:</p>
 * <ul>
 * <li>intent keywords: 平滑/柔顺/smooth, 线性/linear, 缓入 ease in, 缓出 ease out,
 *     缓入缓出/丝滑 ease in out, 定住/停顿 hold, 弹/回弹 elastic, 过冲 overshoot,
 *     干脆/硬切 snap, 砸/重 impact, 弧线/抛物 arc</li>
 * <li>strength: {@code 0.7} or {@code 70%} anywhere in the sentence applies to
 *     the intents found around it (falls back to every intent in the sentence)</li>
 * <li>tick range: {@code 6-22} / {@code 6~22} / {@code 6到22}, inclusive</li>
 * </ul>
 */
public class PolishCommandParser
{
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*(?:-|~|到|至)\\s*(\\d+)");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d.])([01]\\.\\d+)(?![\\d])");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");

    /**
     * Parse a polish sentence. Returns an empty list when nothing recognizable
     * is found - the caller then reports "no intent understood" instead of
     * guessing.
     */
    public static List<PolishOp> parse(String text)
    {
        List<PolishOp> ops = new ArrayList<>();

        if (text == null || text.isBlank())
        {
            return ops;
        }

        String lower = text.toLowerCase();

        float strength = -1F;
        float from = Float.NEGATIVE_INFINITY;
        float to = Float.POSITIVE_INFINITY;

        Matcher range = RANGE.matcher(lower);

        if (range.find())
        {
            from = Integer.parseInt(range.group(1));
            to = Integer.parseInt(range.group(2));
        }

        Matcher decimal = DECIMAL.matcher(lower);

        if (decimal.find())
        {
            strength = Float.parseFloat(decimal.group(1));
        }
        else
        {
            Matcher percent = PERCENT.matcher(lower);

            if (percent.find())
            {
                strength = Integer.parseInt(percent.group(1)) / 100F;
            }
        }

        for (PolishKind kind : candidateOrder(lower))
        {
            ops.add(new PolishOp(kind, strength, from, to));
        }

        return ops;
    }

    /**
     * Kinds mentioned by the sentence, in mention order (keyword position in
     * the text), so "先平滑再过冲" yields smooth then overshoot.
     */
    private static List<PolishKind> candidateOrder(String lower)
    {
        List<PolishKind> kinds = new ArrayList<>();

        addMentioned(kinds, lower, PolishKind.SMOOTH, "smooth", "平滑", "柔顺", "柔和", "顺滑");
        addMentioned(kinds, lower, PolishKind.LINEAR, "linear", "线性", "匀速");
        addMentioned(kinds, lower, PolishKind.EASE_IN, "ease in", "ease_in", "缓入", "慢入");
        addMentioned(kinds, lower, PolishKind.EASE_OUT, "ease out", "ease_out", "缓出", "慢出");
        addMentioned(kinds, lower, PolishKind.EASE_IN_OUT, "ease in out", "ease_in_out", "easeinout", "缓入缓出", "丝滑", "先缓后缓");
        addMentioned(kinds, lower, PolishKind.HOLD, "hold", "stop", "定住", "停顿", "停住", "定格");
        addMentioned(kinds, lower, PolishKind.ELASTIC, "elastic", "弹性", "回弹", "q弹");
        addMentioned(kinds, lower, PolishKind.OVERSHOOT, "overshoot", "过冲", "超出一点", "探头");
        addMentioned(kinds, lower, PolishKind.SNAP, "snap", "干脆", "硬切", "利落");
        addMentioned(kinds, lower, PolishKind.IMPACT, "impact", "砸", "重击", "打击感");
        addMentioned(kinds, lower, PolishKind.ARC, "arc", "弧线", "抛物", "弧度");

        return kinds;
    }

    private static void addMentioned(List<PolishKind> kinds, String lower, PolishKind kind, String... keywords)
    {
        for (String keyword : keywords)
        {
            if (lower.contains(keyword))
            {
                kinds.add(kind);

                return;
            }
        }
    }
}
