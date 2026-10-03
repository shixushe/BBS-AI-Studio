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

    /**
     * 每个姿态的根重心偏移（方块）：蹲/落地类压低重心让脚贴地。
     * 仅当地面识别开启且检测到地面时应用。
     */
    public static final Map<String, Float> ROOT_Y = Map.of(
        "crouch", -0.45F,
        "compress", -0.18F,
        "land", -0.22F
    );

    private static final Map<String, Map<String, float[]>> POSES = Map.ofEntries(
        Map.entry("idle", Map.of()),
        /* 走路左右两步：LLM 交替使用才有步态 */
        Map.entry("walk_step", Map.of(
            "left_leg", new float[] {24F, 0F, 0F},
            "right_leg", new float[] {-16F, 0F, 0F},
            "left_arm", new float[] {-12F, 0F, 3F},
            "right_arm", new float[] {13F, 0F, -3F}
        )),
        Map.entry("walk_step_b", Map.of(
            "left_leg", new float[] {-16F, 0F, 0F},
            "right_leg", new float[] {24F, 0F, 0F},
            "left_arm", new float[] {13F, 0F, 3F},
            "right_arm", new float[] {-12F, 0F, -3F}
        )),
        Map.entry("crouch", Map.of(
            "body", new float[] {19F, 0F, 0F},
            "left_leg", new float[] {-30F, 0F, 0F},
            "right_leg", new float[] {-30F, 0F, 0F},
            "head", new float[] {-12F, 0F, 0F}
        )),
        Map.entry("compress", Map.of(
            "body", new float[] {10F, 0F, 0F},
            "left_leg", new float[] {-14F, 0F, 0F},
            "right_leg", new float[] {-14F, 0F, 0F}
        )),
        Map.entry("rise", Map.of(
            "body", new float[] {-6F, 0F, 0F},
            "left_arm", new float[] {0F, 0F, 8F},
            "right_arm", new float[] {0F, 0F, -8F}
        )),
        Map.entry("fall", Map.of(
            "left_arm", new float[] {0F, 0F, 62F},
            "right_arm", new float[] {0F, 0F, -62F},
            "left_leg", new float[] {9F, 0F, 0F},
            "right_leg", new float[] {-6F, 0F, 0F}
        )),
        Map.entry("punch", Map.of(
            "right_arm", new float[] {-68F, 0F, -5F},
            "left_arm", new float[] {14F, 0F, 6F},
            "body", new float[] {0F, -12F, 0F},
            "head", new float[] {0F, 8F, 0F}
        )),
        Map.entry("kick", Map.of(
            "right_leg", new float[] {-58F, 0F, 0F},
            "left_leg", new float[] {-5F, 0F, 0F},
            "body", new float[] {10F, 6F, 0F},
            "left_arm", new float[] {0F, 0F, 22F},
            "right_arm", new float[] {0F, 0F, -18F}
        )),
        Map.entry("turn", Map.of(
            "body", new float[] {0F, 28F, 0F},
            "head", new float[] {0F, 18F, 0F},
            "left_arm", new float[] {0F, 0F, 7F}
        )),
        Map.entry("land", Map.of(
            "left_leg", new float[] {-22F, 0F, 0F},
            "right_leg", new float[] {-22F, 0F, 0F},
            "body", new float[] {16F, 0F, 0F},
            "left_arm", new float[] {17F, 0F, 12F},
            "right_arm", new float[] {17F, 0F, -12F}
        )),
        Map.entry("reach", Map.of(
            "right_arm", new float[] {-96F, 0F, -3F},
            "left_arm", new float[] {-8F, 0F, 4F},
            "head", new float[] {-5F, 0F, 0F}
        )),
        Map.entry("point", Map.of(
            "right_arm", new float[] {-62F, 0F, -10F},
            "head", new float[] {0F, -10F, 0F},
            "left_arm", new float[] {0F, 0F, 4F}
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
