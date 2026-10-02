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
    private static final Map<String, List<String>> ALIASES = Map.of(
        "head", List.of("head", "neck", "headtop"),
        "body", List.of("body", "torso", "chest", "spine", "bodylower", "torsolower", "bodyupper"),
        "left_arm", List.of("leftarm", "armleft", "larm", "arml"),
        "right_arm", List.of("rightarm", "armright", "rarm", "armr"),
        "left_leg", List.of("leftleg", "legleft", "lleg", "legl"),
        "right_leg", List.of("rightleg", "legright", "rleg", "legr")
    );

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
        Result result = new Result();

        List<String> normalized = new ArrayList<>();

        for (String bone : actualBones)
        {
            normalized.add(bone);
        }

        for (String generic : PoseLibrary.GENERIC_BONES)
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
                result.unresolved.add(generic);
            }
            else
            {
                result.resolved.put(generic, best);
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
