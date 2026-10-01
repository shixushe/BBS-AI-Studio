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

        Resolution(String generic, String actual, double score, List<String> candidates)
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
