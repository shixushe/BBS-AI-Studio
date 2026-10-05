package mchorse.bbs_mod.ai.pose;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the pose library's generic humanoid bone names onto a model's REAL
 * bone names (read from {@code ModelForm.bones} / the model config), by
 * normalized exact and substring matching, with scored candidates.
 *
 * <p>Hard rule from the copilot spec (section 12.2): never fall back to a
 * generic name. Anything this resolver cannot match confidently lands in
 * {@link Result#unresolved} and the UI must ask the user to pick from
 * {@link Resolution#candidates} (or declare the model lacks the bone) before
 * a single keyframe is written.</p>
 */
public class BoneNameResolver
{
    /** Alias tables per generic bone, checked in order, first hit wins. */
    private static final Map<String, List<String>> ALIASES = buildAliases();

    private static Map<String, List<String>> buildAliases()
    {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();

        map.put("head", List.of("head", "neck", "headtop", "头", "头部", "脑袋"));
        map.put("body", List.of("body", "chest", "spine", "bodyupper", "身体", "躯干", "上身", "torso", "torsolower", "bodylower"));
        map.put("left_arm", List.of("leftarm", "armleft", "larm", "arml", "左臂", "左手", "左胳膊", "左上臂"));
        map.put("right_arm", List.of("rightarm", "armright", "rarm", "armr", "右臂", "右手", "右胳膊", "右上臂"));
        map.put("left_leg", List.of("leftleg", "legleft", "lleg", "legl", "左腿", "左脚", "左足", "左大腿"));
        map.put("right_leg", List.of("rightleg", "legright", "rleg", "legr", "右腿", "右脚", "右足", "右大腿"));
        /* 深度适配：骨盆与上半身独立驱动（Star 3.6 的 torso/torso_lower），
         * 没有这两根骨骼的模型按可选骨骼静默跳过 */
        map.put("torso", List.of("torso", "chest", "spine", "上半身", "胸"));
        map.put("torso_lower", List.of("torsolower", "bodylower", "pelvis", "骨盆", "下身", "下躯干"));
        map.put("left_eye", List.of("lefteye", "eyeleft", "leye", "左眼", "左眼球", "左眼瞳", "瞳左", "左瞳"));
        map.put("right_eye", List.of("righteye", "eyeright", "reye", "右眼", "右眼球", "右眼瞳", "瞳右", "右瞳"));
        map.put("left_elbow", List.of("leftelbow", "elbowleft", "左肘", "左手肘"));
        map.put("right_elbow", List.of("rightelbow", "elbowright", "右肘", "右手肘"));
        map.put("left_knee", List.of("leftknee", "kneeleft", "左膝", "左膝盖"));
        map.put("right_knee", List.of("rightknee", "kneeright", "右膝", "右膝盖"));
        map.put("headwear", List.of("headwear", "hat", "helmet", "帽子", "头饰"));
        map.put("left_eyebrow", List.of("lefteyebrow", "eyebrowleft", "左眉", "左眉毛"));
        map.put("right_eyebrow", List.of("righteyebrow", "eyebrowright", "右眉", "右眉毛"));

        return map;
    }

    public static class Resolution
    {
        public final String generic;
        public final String actual;
        public final double score;
        public final List<String> candidates;

        public Resolution(String generic, String actual, double score, List<String> candidates)
        {
            this.generic = generic;
            this.actual = actual;
            this.score = score;
            this.candidates = candidates;
        }
    }

    public static class Result
    {
        public final Map<String, Resolution> resolved = new LinkedHashMap<>();

        /** Generic bones with no confident match - the user must resolve these. */
        public final List<String> unresolved = new ArrayList<>();

        /** 模型的实际骨骼清单（v2 直写模式直接用这些名字驱动） */
        public final java.util.Set<String> inventory = new java.util.LinkedHashSet<>();

        public boolean isComplete()
        {
            return this.unresolved.isEmpty();
        }
    }

    /**
     * Resolve every generic bone the pose library uses against the model's
     * actual bone names. Pure and deterministic.
     *
     * @param actualBones every bone name the model really declares
     */
    public static Result resolve(Collection<String> actualBones)
    {
        /* Optional bones (eyes) are best effort and must never block: they sit
         * out of the unresolved list unless a caller explicitly asks for them */
        return resolve(actualBones, false);
    }

    /**
     * @param askForOptional true lists missing optional bones (eyes) in
     *                       unresolved; the default flow leaves them out
     */
    public static Result resolve(Collection<String> actualBones, boolean askForOptional)
    {
        Result result = new Result();

        List<String> normalized = new ArrayList<>();

        for (String bone : actualBones)
        {
            normalized.add(bone);
            result.inventory.add(bone);
        }

        List<String> generics = new ArrayList<>(PoseLibrary.GENERIC_BONES);
        generics.addAll(PoseLibrary.OPTIONAL_BONES);

        for (String generic : generics)
        {
            Resolution best = null;

            for (String actual : normalized)
            {
                double score = score(normalize(actual), ALIASES.get(generic));

                if (score <= 0D)
                {
                    continue;
                }

                if (best == null || score > best.score || (score == best.score && actual.length() < best.actual.length()))
                {
                    best = new Resolution(generic, actual, score, candidatesOf(normalize(actual), generic));
                }
            }

            if (best == null)
            {
                boolean optional = PoseLibrary.OPTIONAL_BONES.contains(generic);

                if (!optional || askForOptional)
                {
                    result.unresolved.add(generic);
                }
            }
            else
            {
                result.resolved.put(generic, best);

                /* 占用去重：这根实际骨骼已被认领，后续 generic 不再抢
                 * （body 先认领 body，torso 泛骨骼才能独立驱动 torso） */
                normalized.remove(best.actual);
            }
        }

        return result;
    }

    /**
     * Best-effort guess for the confirmation dialog: alias scoring first, then
     * plain edit-distance similarity over normalized names, so the dropdown can
     * preselect the most likely real bone instead of "skip". Returns the
     * original (unnormalized) inventory name, or null when nothing scores high
     * enough to be worth preselecting.
     */
    public static String suggest(String generic, Collection<String> actualBones)
    {
        List<String> aliases = ALIASES.get(generic);

        if (aliases == null)
        {
            return null;
        }

        String normalizedGeneric = normalize(generic);
        String best = null;
        double bestScore = 0D;

        for (String actual : actualBones)
        {
            String normalized = normalize(actual);
            double score = score(normalized, aliases);

            if (score <= 0D)
            {
                /* Alias miss: fall back to edit distance so near-misses like
                 * "HeadTop"/"heaad" or prefixed rig names still preselect */
                score = 0.5D * similarity(normalized, normalizedGeneric);
            }

            /* Prefer higher score, then the shorter (less decorated) name */
            if (score > bestScore || (score == bestScore && best != null && actual.length() < best.length()))
            {
                best = actual;
                bestScore = score;
            }
        }

        return bestScore >= 0.3D ? best : null;
    }

    /** 1 - levenshtein/maxLen, i.e. 1 for identical strings, 0 for disjoint ones. */
    private static double similarity(String a, String b)
    {
        if (a.isEmpty() || b.isEmpty())
        {
            return 0D;
        }

        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++)
        {
            dp[i][0] = i;
        }

        for (int j = 0; j <= b.length(); j++)
        {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++)
        {
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;

                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }

        return 1D - dp[a.length()][b.length()] / (double) Math.max(a.length(), b.length());
    }

    /** Candidates for the confirmation UI: every alias-based match, best first. */
    private static List<String> candidatesOf(String normalizedActual, String generic)
    {
        List<String> candidates = new ArrayList<>();

        for (String alias : ALIASES.get(generic))
        {
            if (normalizedActual.contains(alias) || alias.contains(normalizedActual))
            {
                candidates.add(normalizedActual);
            }
        }

        return candidates;
    }

    /**
     * User-confirmed resolution: the dialog path constructs these directly
     * for generics the resolver could not match on its own.
     */
    public static Resolution confirmed(String generic, String actual)
    {
        return new Resolution(generic, actual, 1D, new ArrayList<>(List.of(actual)));
    }

    /** 1 for an exact alias match, 0.75 for containment, 0 for nothing. */
    private static double score(String normalizedActual, List<String> aliases)
    {
        for (String alias : aliases)
        {
            if (normalizedActual.equals(alias))
            {
                return 1D;
            }
        }

        for (String alias : aliases)
        {
            if (normalizedActual.contains(alias) || alias.contains(normalizedActual))
            {
                return 0.75D;
            }
        }

        return 0D;
    }

    private static String normalize(String name)
    {
        return name == null ? "" : name.toLowerCase().replace(" ", "").replace("_", "").replace(".", "");
    }
}
