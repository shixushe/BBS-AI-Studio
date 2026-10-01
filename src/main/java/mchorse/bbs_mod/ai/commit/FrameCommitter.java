package mchorse.bbs_mod.ai.commit;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.DoubleKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.FloatKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.IntegerKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.LongKeyframeFactory;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.ArrayList;
import java.util.List;

/**
 * L4 commit layer: the ONLY place allowed to write AI results into
 * {@code KeyframeChannel}s.
 *
 * <p>Contract (copilot spec sections 2 and 5.8): a whole commit is exactly ONE
 * undo entry ({@code CompoundUndo} of per-channel state snapshots, marked
 * non-mergeable so a later human drag never folds into it), pushed through the
 * same {@code UndoManager} the film editor uses. The tick offset of the target
 * replay is applied here so callers speak film ticks.</p>
 *
 * <p>Scope note: this class is deliberately client-independent (plain data +
 * undo math) so it can be exercised without a game; the UI-facing wrapper that
 * additionally broadcasts {@code FilmEditEvents.CHANGED} lives in the client
 * sources and just delegates here.</p>
 */
public class FrameCommitter
{
    /** Keys closer than this tick distance count as "the same keyframe". */
    private static final float TICK_EPSILON = 0.001F;

    /**
     * Apply an edit patch onto a replay's tracks.
     *
     * @param properties  the replay's track container (creates missing tracks)
     * @param root        the replay's root form (needed to resolve PROPERTY tracks)
     * @param undoContext the value group the undo entries resolve against (the film)
     * @param undoManager the film editor's undo manager
     * @param patch       what to write
     * @param tickOffset  replay shift applied to every written tick
     */
    public static FrameDiff commit(FormProperties properties, Form root, ValueGroup undoContext, UndoManager<ValueGroup> undoManager, EditPatch patch, float tickOffset)
    {
        FrameDiff diff = new FrameDiff();

        if (properties == null || patch == null)
        {
            return diff;
        }

        List<ChannelWrite> writes = new ArrayList<>();

        for (EditPatch.TrackWrite track : patch.tracks)
        {
            TrackId id = TrackId.parse(track.trackId);
            boolean existed = id != null && properties.get(id) != null;
            KeyframeChannel channel = id == null ? null : properties.getOrCreate(root, id);

            if (channel == null)
            {
                diff.skippedTracks.add(track.trackId);

                continue;
            }

            ChannelWrite write = new ChannelWrite(track.trackId, channel, tickOffset);

            write.newlyCreated = !existed;
            write.keys.addAll(track.keys);
            writes.add(write);
        }

        return commit(undoContext, undoManager, writes, diff);
    }

    /**
     * Commit pre-resolved channel writes - the form the polish pipeline uses,
     * where the caller already holds the channels (replay-level entity
     * channels like yaw/x live outside FormProperties and have no TrackId).
     *
     * @param writes      channel + keys to write; non-numeric channels are refused
     * @param diff        collects what happened (usually a fresh instance)
     */
    public static FrameDiff commit(ValueGroup undoContext, UndoManager<ValueGroup> undoManager, List<ChannelWrite> writes)
    {
        return commit(undoContext, undoManager, writes, new FrameDiff());
    }

    private static FrameDiff commit(ValueGroup undoContext, UndoManager<ValueGroup> undoManager, List<ChannelWrite> writes, FrameDiff diff)
    {
        if (writes == null || writes.isEmpty())
        {
            return diff;
        }

        List<ChannelStateUndo> undos = new ArrayList<>();

        for (ChannelWrite write : writes)
        {
            if (write.channel == null)
            {
                diff.skippedTracks.add(write.trackId);

                continue;
            }

            IKeyframeFactory factory = write.channel.getFactory();

            if (!isNumericFactory(factory))
            {
                /* Numeric-only until M5 extends KeyWrite for pose channels */
                diff.skippedTracks.add(write.trackId);

                continue;
            }

            /* Old state is captured before any write so an undo restores the
             * channel bitwise - including keys this commit did not touch. */
            MapType oldState = mchorse.bbs_mod.ai.commit.ChannelStateUndo.capture(write.channel);

            int written = applyWrites(write.channel, factory, write, diff);

            if (written == 0)
            {
                continue;
            }

            MapType newState = mchorse.bbs_mod.ai.commit.ChannelStateUndo.capture(write.channel);

            if (write.newlyCreated)
            {
                diff.createdTracks.add(write.trackId);
            }

            undos.add(new ChannelStateUndo(write.channel.getPath(), oldState, newState));
            diff.affectedChannels.add(write.channel);
        }

        /* One AI operation = exactly one undo entry. */
        if (!undos.isEmpty())
        {
            CompoundUndo<ValueGroup> compound = new CompoundUndo<>(undos.toArray(new IUndo[0]));

            compound.noMerging();
            undoManager.pushUndo(compound);
        }

        return diff;
    }

    /** A pre-resolved channel plus the keys to write onto it. */
    public static class ChannelWrite
    {
        public final String trackId;

        public final KeyframeChannel channel;

        public final float tickOffset;

        /** Set by the FormProperties resolver: the channel did not exist before this commit. */
        public boolean newlyCreated;

        public final List<EditPatch.KeyWrite> keys = new ArrayList<>();

        public ChannelWrite(String trackId, KeyframeChannel channel, float tickOffset)
        {
            this.trackId = trackId;
            this.channel = channel;
            this.tickOffset = tickOffset;
        }
    }

    /** Writes one channel's keys; returns how many keys ended up written. */
    private static int applyWrites(KeyframeChannel channel, IKeyframeFactory factory, ChannelWrite write, FrameDiff diff)
    {
        int written = 0;

        for (EditPatch.KeyWrite key : write.keys)
        {
            float tick = key.tick + write.tickOffset;
            Keyframe existing = findAt(channel, tick);

            if (existing == null)
            {
                int index = channel.insert(tick, toFactoryValue(factory, key.value));

                existing = channel.get(index);
                diff.entries.add(new FrameDiff.Entry(write.trackId, tick, FrameDiff.Change.ADDED, Double.NaN, key.value));
            }
            else
            {
                double old = existing.getY();

                existing.setValue(toFactoryValue(factory, key.value));
                diff.entries.add(new FrameDiff.Entry(write.trackId, tick, FrameDiff.Change.UPDATED, old, key.value));
            }

            if (key.interpolation != null)
            {
                IInterp interp = Interpolations.MAP.get(key.interpolation);

                if (interp != null)
                {
                    existing.getInterpolation().setInterp(interp);
                }
            }

            existing.lx = key.lx;
            existing.ly = key.ly;
            existing.rx = key.rx;
            existing.ry = key.ry;
            existing.setDuration(key.duration);
            existing.setMotionShift(key.motionShift);
            written++;
        }

        return written;
    }

    /** A keyframe at (or within epsilon of) the given tick, or null. Public read for preview diffs. */
    public static Keyframe findKeyAt(KeyframeChannel channel, float tick)
    {
        return findAt(channel, tick);
    }

    /**
     * Whether a keyframe already carries the exact state a write describes -
     * used by preview diffs so a re-run of the same intent reports zero
     * changes instead of a phantom rewrite.
     */
    public static boolean sameState(Keyframe key, EditPatch.KeyWrite write)
    {
        if (Math.abs(key.getY() - write.value) > 0.0001D)
        {
            return false;
        }

        IInterp current = key.getInterpolation().getInterp();
        IInterp wanted = write.interpolation == null ? current : Interpolations.MAP.get(write.interpolation);

        if (current != wanted)
        {
            return false;
        }

        return key.lx == write.lx && key.ly == write.ly
            && key.rx == write.rx && key.ry == write.ry
            && key.getDuration() == write.duration
            && key.getMotionShift() == write.motionShift;
    }

    /** A keyframe at (or within epsilon of) the given tick, or null. */
    private static Keyframe findAt(KeyframeChannel channel, float tick)
    {
        List<Keyframe<?>> keys = channel.getKeyframes();

        for (Keyframe key : keys)
        {
            if (Math.abs(key.getTick() - tick) <= TICK_EPSILON)
            {
                return key;
            }
        }

        return null;
    }

    private static boolean isNumericFactory(IKeyframeFactory factory)
    {
        return factory instanceof FloatKeyframeFactory
            || factory instanceof DoubleKeyframeFactory
            || factory instanceof IntegerKeyframeFactory
            || factory instanceof LongKeyframeFactory;
    }

    /** Box the patch's float value into the channel's numeric type. */
    private static Object toFactoryValue(IKeyframeFactory factory, float value)
    {
        if (factory instanceof DoubleKeyframeFactory)
        {
            return (double) value;
        }

        if (factory instanceof IntegerKeyframeFactory)
        {
            return Math.round(value);
        }

        if (factory instanceof LongKeyframeFactory)
        {
            return (long) Math.round(value);
        }

        return value;
    }
}
