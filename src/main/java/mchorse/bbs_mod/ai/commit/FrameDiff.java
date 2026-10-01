package mchorse.bbs_mod.ai.commit;

import java.util.ArrayList;
import java.util.List;

/**
 * Before/after of one commit: which tracks, which ticks, what moved. This is
 * what the "confirm to timeline" dialog shows the user, and what the ghost
 * frame layer marks - the user must be able to see exactly what an AI
 * operation is about to do before (and after) it lands.
 */
public class FrameDiff
{
    public enum Change
    {
        ADDED, UPDATED, REMOVED
    }

    public static class Entry
    {
        public final String trackId;
        public final float tick;
        public final Change change;
        /** Absent for ADDED. */
        public final double oldValue;
        /** Absent for REMOVED. */
        public final double newValue;

        public Entry(String trackId, float tick, Change change, double oldValue, double newValue)
        {
            this.trackId = trackId;
            this.tick = tick;
            this.change = change;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        public double magnitude()
        {
            return Math.abs(this.newValue - this.oldValue);
        }

        @Override
        public String toString()
        {
            return this.change + " " + this.trackId + "@" + this.tick + (this.change == Change.ADDED
                ? " -> " + this.newValue
                : this.change == Change.REMOVED
                    ? " (was " + this.oldValue + ")"
                    : ": " + this.oldValue + " -> " + this.newValue);
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    /** Tracks whose channel had to be created fresh by this commit. */
    public final List<String> createdTracks = new ArrayList<>();

    /** Tracks from the patch that could not be resolved (unknown track, missing form property). */
    public final List<String> skippedTracks = new ArrayList<>();

    /** The channels this commit touched, in patch order (for change events). */
    public final List<mchorse.bbs_mod.utils.keyframes.KeyframeChannel<?>> affectedChannels = new ArrayList<>();

    public boolean isEmpty()
    {
        return this.entries.isEmpty();
    }

    public int changedKeyCount()
    {
        return this.entries.size();
    }
}
