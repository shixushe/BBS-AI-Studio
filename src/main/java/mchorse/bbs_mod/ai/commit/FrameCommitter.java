package mchorse.bbs_mod.ai.commit;

import java.util.List;

/**
 * L4 commit layer: the ONLY place allowed to write AI results into
 * {@code KeyframeChannel}s. Responsibilities (copilot spec section 3 L4):
 *
 * <ul>
 * <li>apply an {@code EditPatch} at the target replay's tick offset</li>
 * <li>create missing tracks by {@code TrackKind} when necessary</li>
 * <li>wrap the whole write in a single {@code CompoundUndo} pushed onto the
 * {@code UndoManager} through the same path {@code FilmEditorUndo} uses, so
 * {@code FilmEditEvents.CHANGED} broadcasts normally</li>
 * <li>one AI operation = exactly one undo entry</li>
 * </ul>
 *
 * <p>Skeleton milestone (section 13.2): lands in M3 together with
 * {@code FrameDiff} and the undo integration tests (Ctrl+Z round trip,
 * 10x undo/redo without drift).</p>
 */
public class FrameCommitter
{
    /** One keyframe's full target state, already in channel-local units. */
    public static class KeyWrite
    {
        public float tick;
        public float value;
        public String interpolation;
        public float lx;
        public float ly;
        public float rx;
        public float ry;
        public float duration;
        public float motionShift;
    }

    /** One track's writes: a track address plus its keys. */
    public static class TrackWrite
    {
        public String trackId;
        public final List<KeyWrite> keys = new java.util.ArrayList<>();
    }

    /**
     * Not implemented yet (M3): writing needs the undo transaction seam and
     * the replay tick-offset resolution, which are being wired first.
     */
    public static void commit(List<TrackWrite> tracks)
    {
        throw new UnsupportedOperationException("FrameCommitter lands in milestone M3 (undo transaction + diff)");
    }
}
