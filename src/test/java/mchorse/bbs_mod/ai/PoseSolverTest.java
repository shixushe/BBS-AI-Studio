package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.pose.BoneNameResolver;
import mchorse.bbs_mod.ai.pose.PoseLibrary;
import mchorse.bbs_mod.ai.pose.PoseSolver;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.List;
import java.util.Set;

/**
 * Standalone checks for the M5 pose pipeline
 * ({@code java -cp "main;test;slf4j;logging;joml;dfu;mc-common;mc-client" mchorse.bbs_mod.ai.PoseSolverTest}).
 *
 * <p>The bar: bone resolution never guesses (unresolved = error, candidates
 * supplied), the plan's pose labels all have library coverage, solving is
 * deterministic on the model's real bone names, and pose writes go through
 * the M3 commit machinery - one undo entry, full rollback.</p>
 */
public class PoseSolverTest
{
    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        KeyframeFactories.setup();

        boneResolution();
        solverContract();
        poseWritesThroughCommit();
        libraryCoversContract();

        System.out.println("\n" + (failures == 0 ? "ALL PASS" : failures + " FAILURES") + " (" + checks + " checks)");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    /** Star-model-shaped inventory: real names, plus decoys that must not win. */
    private static final List<String> STAR_BONES = List.of(
        "head", "headwear", "body", "torso_lower", "left_arm", "left_elbow", "left_arm_end",
        "right_arm", "right_elbow", "right_arm_end", "left_leg", "left_knee", "left_leg_end",
        "right_leg", "right_knee", "right_leg_end", "anchor", "Move_X", "1", "2", "3"
    );

    private static void boneResolution()
    {
        BoneNameResolver.Result result = BoneNameResolver.resolve(STAR_BONES);

        check(result.isComplete(), "star inventory resolves completely");
        equal("head", result.resolved.get("head").actual, "head exact");
        equal("body", result.resolved.get("body").actual, "body exact");
        equal("left_arm", result.resolved.get("left_arm").actual, "left_arm beats left_arm_end (exact first)");
        equal("right_leg", result.resolved.get("right_leg").actual, "right_leg exact");
        check(result.resolved.get("head").score == 1D, "exact match scores 1");

        /* An inventory without legs must report them, not invent them */
        BoneNameResolver.Result partial = BoneNameResolver.resolve(List.of("head", "body", "left_arm", "right_arm"));

        check(!partial.isComplete(), "missing bones are reported");
        check(partial.unresolved.contains("left_leg") && partial.unresolved.contains("right_leg"), "legs unresolved");

        /* Determinism */
        BoneNameResolver.Result again = BoneNameResolver.resolve(STAR_BONES);

        for (String generic : result.resolved.keySet())
        {
            equal(result.resolved.get(generic).actual, again.resolved.get(generic).actual, "resolution deterministic: " + generic);
        }
    }

    private static AnimationPlan plan()
    {
        try
        {
            return AnimationPlan.parse("""
                {
                  "version": 1, "fps": 20, "total_ticks": 40,
                  "beats": [
                    { "index": 0, "tick": 0, "phase": "hold", "pose": "idle", "spacing": 0, "intents": ["hold"] },
                    { "index": 1, "tick": 8, "phase": "anticipation", "pose": "crouch", "spacing": 8, "intents": ["ease_in_out"] },
                    { "index": 2, "tick": 20, "phase": "contact", "pose": "punch", "spacing": 12, "intents": ["snap", "impact"] }
                  ]
                }
                """);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void solverContract()
    {
        BoneNameResolver.Result complete = BoneNameResolver.resolve(STAR_BONES);
        List<PoseSolver.KeyPose> poses = PoseSolver.solve(plan(), complete);

        equal(3, poses.size(), "one key pose per beat");
        equal(0, poses.get(0).tick, "first beat tick");
        equal(20, poses.get(2).tick, "last beat tick");

        /* punch moves four bones (right_arm, left_arm, body, head) */
        equal(4, poses.get(2).channels.size(), "punch channel count");
        check(poses.get(2).channels.stream().anyMatch(c -> c.bone.equals("right_arm")), "punch uses the RESOLVED right_arm name");

        /* idles carry no bones - nothing to write */
        equal(0, poses.get(0).channels.size(), "idle pose is empty by design");

        /* Determinism: same input, same angles */
        List<PoseSolver.KeyPose> again = PoseSolver.solve(plan(), complete);

        for (int i = 0; i < poses.size(); i++)
        {
            equal(poses.get(i).channels.size(), again.get(i).channels.size(), "solve deterministic: channel count " + i);
        }

        /* Contract violations throw, never guess */
        BoneNameResolver.Result partial = BoneNameResolver.resolve(List.of("head"));

        try
        {
            PoseSolver.solve(plan(), partial);
            fail("unresolved bone map must throw");
        }
        catch (IllegalArgumentException e)
        {
            check(e.getMessage().contains("unresolved"), "unresolved error names the problem");
        }
    }

    private static void poseWritesThroughCommit()
    {
        FormProperties properties = new FormProperties("properties");
        UndoManager<ValueGroup> manager = new UndoManager<>();

        BoneNameResolver.Result bones = BoneNameResolver.resolve(STAR_BONES);
        List<PoseSolver.KeyPose> poses = PoseSolver.solve(plan(), bones);
        List<FrameCommitter.ChannelWrite> writes = PoseSolver.toChannelWrites(poses, properties);

        equal(6, writes.size(), "six bone channels touched (crouch: body+legs+head, punch: arms+body+head)");

        KeyframeChannel arm = properties.get(TrackId.parse("pose.bones.right_arm"));

        check(arm != null, "right_arm channel created");
        check(!CurvePolishGuard.isNumeric(arm), "bone channel is the POSE factory, not numeric");

        FrameDiffProbe.diff = FrameCommitter.commit(properties, manager, writes);

        equal(1, arm.getKeyframes().size(), "right_arm only appears in the punch beat");
        equal(1, manager.getTotalUndos(), "one AI operation = one undo entry");

        manager.undo(properties);
        equal(0, arm.getKeyframes().size(), "undo rolls the pose channel back to empty");
        manager.redo(properties);
        equal(1, arm.getKeyframes().size(), "redo restores the punch key");

        /* FrameDiff saw every written tick */
        equal(8, FrameDiffProbe.diff.changedKeyCount(), "diff covers punch's 4 + crouch's 4 + idle's 0 keys");
    }

    private static void libraryCoversContract()
    {
        List<String> missing = PoseLibrary.missing(AnimationPlan.POSES);

        equal(0, missing.size(), "library covers every pose the contract allows: " + missing);
        check(PoseLibrary.GENERIC_BONES.size() == 6, "six generic bones");
        check(Set.copyOf(PoseLibrary.GENERIC_BONES).size() == 6, "generic bones unique");
    }

    /* helpers */

    private static class CurvePolishGuard
    {
        static boolean isNumeric(KeyframeChannel channel)
        {
            return mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory());
        }
    }

    private static class FrameDiffProbe
    {
        static mchorse.bbs_mod.ai.commit.FrameDiff diff;
    }

    private static void check(boolean condition, String label)
    {
        checks++;

        if (!condition)
        {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    private static void equal(Object expected, Object actual, String label)
    {
        checks++;

        if (!java.util.Objects.equals(expected, actual))
        {
            failures++;
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String label)
    {
        checks++;
        failures++;
        System.out.println("FAIL: " + label);
    }
}
