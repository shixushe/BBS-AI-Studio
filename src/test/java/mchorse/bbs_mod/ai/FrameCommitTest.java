package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.commit.ChannelStateUndo;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.List;

/**
 * Standalone checks for the M3 commit layer
 * ({@code java -cp "build/classes/java/main;build/classes/java/test;<joml>" mchorse.bbs_mod.ai.FrameCommitTest}).
 *
 * <p>The acceptance bar from the copilot spec, milestone M3: after a commit,
 * one undo returns the channel exactly to its previous state, and ten
 * undo/redo round trips leave no drift. Plus: one AI operation is exactly one
 * undo entry, non-numeric and unknown tracks are skipped rather than guessed,
 * and the tick offset lands where it should.</p>
 */
public class FrameCommitTest
{
    private static int checks;
    private static int failures;

    public static void main(String[] args)
    {
        commitUndoRedo();
        singleEntryAndNoDrift();
        skippedAndCreatedTracks();
        tickOffsetAndInterpolation();
        channelStateUndoRoundTrip();

        System.out.println("\n" + (failures == 0 ? "ALL PASS" : failures + " FAILURES") + " (" + checks + " checks)");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    private static FormProperties properties()
    {
        FormProperties properties = new FormProperties("properties");

        KeyframeFactories.setup();
        properties.register(TrackId.parse("pose.bones.head"), KeyframeFactories.FLOAT);

        return properties;
    }

    private static EditPatch patch(float... tickValuePairs)
    {
        EditPatch patch = new EditPatch();
        EditPatch.TrackWrite track = new EditPatch.TrackWrite("pose.bones.head");

        for (int i = 0; i + 1 < tickValuePairs.length; i += 2)
        {
            track.keys.add(new EditPatch.KeyWrite().tick(tickValuePairs[i]).value(tickValuePairs[i + 1]));
        }

        patch.tracks.add(track);

        return patch;
    }

    private static KeyframeChannel head(FormProperties properties)
    {
        return properties.get(TrackId.parse("pose.bones.head"));
    }

    private static void commitUndoRedo()
    {
        FormProperties properties = properties();
        UndoManager<ValueGroup> manager = new UndoManager<>();
        KeyframeChannel channel = head(properties);

        /* Seed one human-made keyframe before the AI touches anything */
        channel.insert(0F, 5F);

        FrameDiff diff = FrameCommitter.commit(properties, null, properties, manager, patch(4F, 1F, 10F, 7F), 0F);

        equal(3, channel.getKeyframes().size(), "commit: seeded + two written keys");
        equal(2, diff.entries.size(), "diff: two entries (1 added, 1 updated)");
        equal(0, diff.createdTracks.size(), "nothing created (track was registered up front)");
        equal(0, diff.skippedTracks.size(), "diff: nothing skipped");
        equal(1, diff.affectedChannels.size(), "diff: one affected channel");

        /* One undo returns EXACTLY to the pre-commit state */
        check(manager.undo(properties), "undo applied");
        equal(1, channel.getKeyframes().size(), "undo: back to the seeded key only");
        equal(5.0, ((Keyframe) channel.getKeyframes().get(0)).getY(), "undo: seeded value intact");
        check(manager.redo(properties), "redo applied");
        equal(3, channel.getKeyframes().size(), "redo: both keys back");
        equal(7.0, ((Keyframe) channel.getKeyframes().get(2)).getY(), "redo: written value intact");
    }

    private static void singleEntryAndNoDrift()
    {
        FormProperties properties = properties();
        UndoManager<ValueGroup> manager = new UndoManager<>();
        KeyframeChannel channel = head(properties);

        FrameCommitter.commit(properties, null, properties, manager, patch(0F, 1F, 6F, 2F, 12F, 3F), 0F);

        /* ONE AI operation = exactly ONE undo entry, however many keys it wrote */
        equal(1, manager.getTotalUndos(), "three-key commit is one undo entry");

        /* Snapshot the applied state, then churn undo/redo ten times */
        MapType applied = channel.toData() instanceof MapType ? (MapType) channel.toData() : null;

        for (int i = 0; i < 10; i++)
        {
            manager.undo(properties);
            manager.redo(properties);
        }

        equal(3, channel.getKeyframes().size(), "10x churn: keys intact");
        equal(applied.toString(), (channel.toData() instanceof MapType ? (MapType) channel.toData() : new MapType()).toString(), "10x churn: state bitwise stable");

        /* Full walk back to empty, then forward again */
        manager.undo(properties);
        equal(0, channel.getKeyframes().size(), "final undo: empty again");
        manager.redo(properties);
        equal(3, channel.getKeyframes().size(), "final redo: restored");
    }

    private static void skippedAndCreatedTracks()
    {
        /* No manual registration here: bone tracks must hit their production
         * factories (POSE = non-numeric) through getOrCreate */
        FormProperties properties = new FormProperties("properties");
        UndoManager<ValueGroup> manager = new UndoManager<>();

        KeyframeFactories.setup();

        /* Bone channels are POSE (non-numeric) in production: both bone writes
         * are refused rather than handed meaningless floats, and nothing is
         * pushed onto the undo stack for a fully refused commit */
        EditPatch bad = new EditPatch();
        EditPatch.TrackWrite unknownBone = new EditPatch.TrackWrite("pose.bones.does_not_exist_anywhere");

        unknownBone.keys.add(new EditPatch.KeyWrite().tick(0F).value(1F));
        bad.tracks.add(unknownBone);
        bad.tracks.add(new EditPatch.TrackWrite("pose.bones.head"));
        bad.tracks.get(1).keys.add(new EditPatch.KeyWrite().tick(0F).value(1F));

        FrameDiff diff = FrameCommitter.commit(properties, null, properties, manager, bad, 0F);

        equal(2, diff.skippedTracks.size(), "bone (pose) tracks refused as non-numeric");
        equal(0, manager.getTotalUndos(), "fully refused commit pushes no undo entry");
        equal(0, diff.createdTracks.size(), "refused tracks count as neither created nor written");

        /* getOrCreate on a fresh numeric track (a material property) creates the channel */
        EditPatch fresh = new EditPatch();
        EditPatch.TrackWrite smoothness = new EditPatch.TrackWrite("materials.*.smoothness");

        smoothness.keys.add(new EditPatch.KeyWrite().tick(0F).value(2F));
        fresh.tracks.add(smoothness);

        FrameDiff created = FrameCommitter.commit(properties, null, properties, manager, fresh, 0F);

        equal(1, created.createdTracks.size(), "fresh track reported as created (skipped: " + created.skippedTracks + ")");
        equal(1, created.affectedChannels.size(), "fresh track affected");
        check(properties.get(TrackId.parse("materials.*.smoothness")) != null, "material prop channel exists");
    }

    private static void tickOffsetAndInterpolation()
    {
        FormProperties properties = properties();
        UndoManager<ValueGroup> manager = new UndoManager<>();
        EditPatch one = patch(0F, 1F);

        one.tracks.get(0).keys.get(0).interp("sine_inout", 1F, 0F, 9F, 0F);

        FrameCommitter.commit(properties, null, properties, manager, one, 100F);

        @SuppressWarnings("rawtypes")
        Keyframe key = (Keyframe) head(properties).getKeyframes().get(0);

        equal(100F, key.getTick(), "tick offset applied");
        equal(1.0, key.getY(), "value written");
        equal(Interpolations.MAP.get("sine_inout"), key.getInterpolation().getInterp(), "interpolation resolved from registry by key");
    }

    private static void channelStateUndoRoundTrip()
    {
        FormProperties properties = properties();
        UndoManager<ValueGroup> manager = new UndoManager<>();

        FrameCommitter.commit(properties, null, properties, manager, patch(0F, 1F, 8F, 9F), 0F);

        /* The pushed entry is our channel snapshot undo, non-mergeable */
        mchorse.bbs_mod.utils.undo.IUndo<ValueGroup> entry = manager.getCurrentUndo();

        check(entry instanceof mchorse.bbs_mod.utils.undo.CompoundUndo, "entry is a compound of channel snapshots");
        check(!entry.isMergeable(new ChannelStateUndo(null, new MapType(), new MapType())), "AI entries never merge");

        ChannelStateUndo child = (ChannelStateUndo) ((mchorse.bbs_mod.utils.undo.CompoundUndo<ValueGroup>) entry).getUndos().get(0);

        manager.undo(properties);

        check(child.getChannel() != null, "resolved channel cached on apply");
        equal("properties", child.getName().strings.get(0), "path starts at the properties group");
    }

    /* --- check harness --- */

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
}
