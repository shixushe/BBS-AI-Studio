package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨骼绑定试动姿势：AI 绑定页签里点「试动」时，把该通用骨骼的
 * 测试旋转叠加到模型编辑器的实时预览上——动通用骨骼，原模型跟着动，
 * 用于肉眼校验"通用骨骼 → 真实骨骼"绑定是否正确（绑错骨头，动的
 * 就是错误的部位）。
 *
 * <p>静态覆盖表，键=模型真实骨骼名。ModelFormRenderer.getPose() 在
 * 叠完全部姿势层后应用本表——不落盘、不进撤销、不影响任何保存数据。</p>
 */
public class AiBindingTestPose
{
    private static final Map<String, PoseTransform> TEST_POSES = new LinkedHashMap<>();

    /** 每个泛骨骼的校验姿势（度）：方向固定，绑对了动的部位一目了然 */
    public static float testAngleFor(String generic)
    {
        return switch (generic)
        {
            case "head" -> 28F;
            case "headwear" -> 18F;
            case "body" -> -14F;
            case "torso" -> -10F;
            case "torso_lower" -> 10F;
            case "left_arm", "right_arm" -> -65F;
            case "left_elbow", "right_elbow" -> -55F;
            case "left_leg", "right_leg" -> -30F;
            case "left_knee", "right_knee" -> 40F;
            default -> 30F;
        };
    }

    /** 眨眼类用缩放校验（转不动的小骨头） */
    public static boolean isScaleTest(String generic)
    {
        return generic.contains("eye") || generic.contains("brow")
            || generic.contains("眼") || generic.contains("眉");
    }

    /**
     * 切换某泛骨骼的试动姿势。返回切换后是否处于开启状态。
     */
    public static boolean toggle(String generic, String actualBone)
    {
        if (actualBone == null || actualBone.isEmpty())
        {
            return false;
        }

        if (TEST_POSES.remove(actualBone) != null)
        {
            return false;
        }

        PoseTransform transform = new PoseTransform();

        if (isScaleTest(generic))
        {
            transform.scale.set(1F, 0.1F, 1F);
        }
        else
        {
            float rad = (float) Math.toRadians(testAngleFor(generic));

            transform.rotate.set(rad, 0F, 0F);
        }

        TEST_POSES.put(actualBone, transform);

        return true;
    }

    /** 清空全部试动姿势（页签重建/复位按钮） */
    public static void clear()
    {
        TEST_POSES.clear();
    }

    /** 叠加到最终姿势上（ModelFormRenderer.getPose 末尾调用） */
    public static void apply(Pose pose)
    {
        if (TEST_POSES.isEmpty())
        {
            return;
        }

        for (Map.Entry<String, PoseTransform> entry : TEST_POSES.entrySet())
        {
            PoseTransform target = pose.getOrCreate(entry.getKey());
            PoseTransform value = entry.getValue();

            target.rotate.add(value.rotate);
            target.scale.add(value.scale).sub(1F, 1F, 1F);
        }
    }
}
