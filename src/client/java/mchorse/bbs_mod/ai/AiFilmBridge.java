package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.api.client.events.FilmEditEvents;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.List;

/**
 * The thin client seam between the AI commit core and the film editor: runs
 * {@link FrameCommitter} against the editor's own undo manager and then
 * broadcasts the same {@code FilmEditEvents.CHANGED(EDIT)} the native edit
 * path broadcasts. Nothing else about the editor is special - undo merging,
 * change events and Ctrl+Z all come from the standard machinery.
 */
public class AiFilmBridge
{
    /**
     * Commit an edit patch onto a replay of the film the given editor has open.
     * Must run on the client thread (it mutates the editor's live undo stack).
     */
    public static FrameDiff commit(UIFilmPanel panel, Replay replay, EditPatch patch)
    {
        Film film = panel.getData();
        UndoManager<ValueGroup> undoManager = panel.getUndoHandler().getUndoManager();
        FrameDiff diff = FrameCommitter.commit(replay.properties, replay.form.get(), film, undoManager, patch, 0F);

        if (!diff.affectedChannels.isEmpty())
        {
            FilmEditEvents.notifyChanges(new java.util.ArrayList<mchorse.bbs_mod.settings.values.base.BaseValue>(diff.affectedChannels), FilmEditEvents.Cause.EDIT);
        }

        return diff;
    }

    /**
     * Commit pre-resolved channel writes (the polish path) against the editor's
     * open film. Client thread only, same as above.
     */
    public static FrameDiff commit(UIFilmPanel panel, List<FrameCommitter.ChannelWrite> writes)
    {
        Film film = panel.getData();
        UndoManager<ValueGroup> undoManager = panel.getUndoHandler().getUndoManager();
        FrameDiff diff = FrameCommitter.commit(film, undoManager, writes);

        if (!diff.affectedChannels.isEmpty())
        {
            FilmEditEvents.notifyChanges(new java.util.ArrayList<mchorse.bbs_mod.settings.values.base.BaseValue>(diff.affectedChannels), FilmEditEvents.Cause.EDIT);
        }

        return diff;
    }
}
