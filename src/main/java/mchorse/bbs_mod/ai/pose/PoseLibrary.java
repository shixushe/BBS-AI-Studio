package mchorse.bbs_mod.ai.pose;

import java.util.List;
import java.util.Map;

/**
 * The built-in blocking pose library: what each {@code AnimationPlan} pose
 * label roughly means on a humanoid rig, in bone-local euler DEGREES.
 *
 * <p>This is authored deterministic content, not model output - the plan only
 * ever names a pose (iron rule 1). Angles are deliberately conservative
 * blocking extremes: enough silhouette to read the intent, nothing that
 * pretends to be final animation. The generic bone names are resolved to the
 * model's real bones by {@link BoneNameResolver} before anything is written.</p>
 */
public class PoseLibrary
{
    /** Generic humanoid bones the library speaks. */
    public static final List<String> GENERIC_BONES = List.of("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");

    /**
     * Optional generic bones: resolved and driven when the model has them
     * (Star 3.6 ships eyes), silently skipped otherwise - never blocking.
     */
    public static final List<String> OPTIONAL_BONES = List.of("left_eye", "right_eye");

    private static final Map<String, Map<String, float[]>> POSES = Map.ofEntries(
        Map.entry("idle", Map.of()),
        Map.entry("crouch", Map.of(
            "body", new float[] {28F, 0F, 0F},
            "left_leg", new float[] {-42F, 0F, 0F},
            "right_leg", new float[] {-42F, 0F, 0F},
            "head", new float[] {-18F, 0F, 0F}
        )),
        Map.entry("compress", Map.of(
            "body", new float[] {14F, 0F, 0F},
            "left_leg", new float[] {-20F, 0F, 0F},
            "right_leg", new float[] {-20F, 0F, 0F}
        )),
        Map.entry("rise", Map.of(
            "body", new float[] {-8F, 0F, 0F},
            "left_arm", new float[] {0F, 0F, 12F},
            "right_arm", new float[] {0F, 0F, -12F}
        )),
        Map.entry("fall", Map.of(
            "left_arm", new float[] {0F, 0F, 135F},
            "right_arm", new float[] {0F, 0F, -135F},
            "left_leg", new float[] {12F, 0F, 0F},
            "right_leg", new float[] {-8F, 0F, 0F}
        )),
        Map.entry("punch", Map.of(
            "right_arm", new float[] {-92F, 0F, -6F},
            "left_arm", new float[] {18F, 0F, 8F},
            "body", new float[] {0F, -18F, 0F},
            "head", new float[] {0F, 12F, 0F}
        )),
        Map.entry("kick", Map.of(
            "right_leg", new float[] {-85F, 0F, 0F},
            "left_leg", new float[] {-6F, 0F, 0F},
            "body", new float[] {14F, 8F, 0F},
            "left_arm", new float[] {0F, 0F, 32F},
            "right_arm", new float[] {0F, 0F, -28F}
        )),
        Map.entry("turn", Map.of(
            "body", new float[] {0F, 42F, 0F},
            "head", new float[] {0F, 28F, 0F},
            "left_arm", new float[] {0F, 0F, 10F}
        )),
        Map.entry("walk_step", Map.of(
            "left_leg", new float[] {34F, 0F, 0F},
            "right_leg", new float[] {-24F, 0F, 0F},
            "left_arm", new float[] {-18F, 0F, 4F},
            "right_arm", new float[] {20F, 0F, -4F}
        )),
        Map.entry("land", Map.of(
            "left_leg", new float[] {-32F, 0F, 0F},
            "right_leg", new float[] {-32F, 0F, 0F},
            "body", new float[] {24F, 0F, 0F},
            "left_arm", new float[] {26F, 0F, 18F},
            "right_arm", new float[] {26F, 0F, -18F}
        )),
        Map.entry("reach", Map.of(
            "right_arm", new float[] {-158F, 0F, -4F},
            "left_arm", new float[] {-12F, 0F, 6F},
            "head", new float[] {-8F, 0F, 0F}
        )),
        Map.entry("point", Map.of(
            "right_arm", new float[] {-88F, 0F, -14F},
            "head", new float[] {0F, -16F, 0F},
            "left_arm", new float[] {0F, 0F, 6F}
        )),
        /* 眨眼：眼骨 Y 压缩到 0.12（rotation 三位 + scale 三位），仅当眼睛已绑定时参与 */
        Map.entry("blink", Map.of(
            "left_eye", new float[] {0F, 0F, 0F, 1F, 0.12F, 1F},
            "right_eye", new float[] {0F, 0F, 0F, 1F, 0.12F, 1F}
        ))
    );

    /** Rotations for a pose, or null when the label is not in the library. */
    public static Map<String, float[]> get(String pose)
    {
        return POSES.get(pose);
    }

    /** Whether every pose label of the plan has library coverage. */
    public static List<String> missing(List<String> poses)
    {
        return poses.stream().filter(p -> !POSES.containsKey(p)).toList();
    }
}
