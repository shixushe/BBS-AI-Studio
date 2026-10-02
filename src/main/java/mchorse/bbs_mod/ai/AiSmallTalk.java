package mchorse.bbs_mod.ai;

import java.util.List;
import java.util.Locale;

/**
 * Tiny deterministic small-talk filter for the chat bar (user request: a bare
 * "你好" must not fire the action-generation pipeline). Pure greetings, thanks
 * and identity questions are answered locally in the transcript; anything that
 * carries a verb or a noun phrase falls through to the real pipelines.
 */
public class AiSmallTalk
{
    private static final List<List<String>> PATTERNS = List.of(
        List.of("你好", "您好", "你们好", "哈喽", "哈罗", "嗨", "嗨嗨", "hi", "hello", "hey", "halo"),
        List.of("谢谢", "多谢", "感谢", "thanks", "thank you", "thx", "3q"),
        List.of("你是谁", "你叫什么", "你能干什么", "你会什么", "who are you", "what can you do"),
        List.of("在吗", "在不在", "在么", "are you there", "you there"),
        List.of("再见", "拜拜", "晚安", "bye", "good night", "goodbye")
    );

    /**
     * Whether the whole sentence is small talk: it contains a greeting-ish
     * keyword and carries nothing else of substance (no latin verb-ish length,
     * no digits/ranges a polisher could want, under 12 characters per CJK word
     * rule of thumb). "你好，帮我把走路改得更柔和" is NOT small talk - it names
     * an action (走路) and a curve keyword (柔和).
     */
    public static boolean isSmallTalk(String text)
    {
        if (text == null || text.isBlank())
        {
            return false;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        String trimmed = lower.trim();

        boolean mentionsKeyword = false;

        for (List<String> group : PATTERNS)
        {
            for (String keyword : group)
            {
                if (trimmed.contains(keyword))
                {
                    mentionsKeyword = true;

                    break;
                }
            }

            if (mentionsKeyword)
            {
                break;
            }
        }

        if (!mentionsKeyword)
        {
            return false;
        }

        /* Strip every known keyword; what remains must be pure filler */
        String rest = trimmed;

        for (List<String> group : PATTERNS)
        {
            for (String keyword : group)
            {
                rest = rest.replace(keyword, " ");
            }
        }

        rest = rest.replaceAll("[\\s，。！？!?.,~～…—\\-]+", "");

        /* Anything curvy or numeric left means real work: polish words, ranges,
         * strengths - or a longer request the greeter has no business eating */
        if (rest.isEmpty())
        {
            return true;
        }

        if (rest.matches(".*[0-9].*"))
        {
            return false;
        }

        return mchorse.bbs_mod.ai.curve.PolishCommandParser.parse(trimmed).isEmpty() && rest.length() <= 4;
    }

    /** The locally generated reply for a small-talk sentence. */
    public static String reply(String text)
    {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);

        for (String thanks : List.of("谢谢", "多谢", "感谢", "thanks", "thank you", "thx", "3q"))
        {
            if (lower.contains(thanks))
            {
                return "不客气！有动作要生成或曲线要打磨，随时说。";
            }
        }

        for (String bye : List.of("再见", "拜拜", "晚安", "bye", "goodbye"))
        {
            if (lower.contains(bye))
            {
                return "再见，随时回来继续做片子。";
            }
        }

        for (String who : List.of("你是谁", "你叫什么", "你能干什么", "你会什么", "who are you", "what can you do"))
        {
            if (lower.contains(who))
            {
                return "我是 BBS AI 副驾：描述一段动作我帮你生成姿态关键帧，说「更柔和」「缓入缓出 0.7」这类话我帮你打磨已有曲线（离线可用）。";
            }
        }

        return "你好！把想做的动作写成一句话发给我（例如「角色向前走三步然后挥手」）；要调已有曲线就说「更柔和」「缓入缓出 0.7」。";
    }
}
