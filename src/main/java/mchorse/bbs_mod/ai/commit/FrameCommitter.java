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

        List<ChannelStateUndo> undos = new ArrayList<>();

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

            IKeyframeFactory factory = channel.getFactory();

            if (!isNumericFactory(factory))
            {
                /* Numeric-only until M5 extends KeyWrite for pose channels */
                diff.skippedTracks.add(track.trackId);

                continue;
            }

            if (!existed)
            {
                diff.createdTracks.add(track.trackId);
            }

            /* Old state is captured after getOrCreate so a fresh channel
             * snapshots as empty rather than as "absent" - restore then
             * re-creates the same empty channel state through fromData. */
            MapType oldState = mchorse.bbs_mod.ai.commit.ChannelStateUndo.capture(channel);

            int before = applyWrites(channel, factory, track, tickOffset, diff);

            if (before == 0)
            {
                continue;
            }

            MapType newState = mchorse.bbs_mod.ai.commit.ChannelStateUndo.capture(channel);

            undos.add(new ChannelStateUndo(channel.getPath(), oldState, newState));
            diff.affectedChannels.add(channel);
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

    /** Writes one track's keys; returns how many keys ended up written. */
    private static int applyWrites(KeyframeChannel channel, IKeyframeFactory factory, EditPatch.TrackWrite track, float tickOffset, FrameDiff diff)
    {
        int written = 0;

        for (EditPatch.KeyWrite key : track.keys)
        {
            float tick = key.tick + tickOffset;
            Keyframe existing = findAt(channel, tick);

            if (existing == null)
            {
                int index = channel.insert(tick, toFactoryValue(factory, key.value));

                existing = channel.get(index);
                diff.entries.add(new FrameDiff.Entry(track.trackId, tick, FrameDiff.Change.ADDED, Double.NaN, key.value));
            }
            else
            {
                double old = existing.getY();

                existing.setValue(toFactoryValue(factory, key.value));
                diff.entries.add(new FrameDiff.Entry(track.trackId, tick, FrameDiff.Change.UPDATED, old, key.value));
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
