package mchorse.bbs_mod.api;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.undo.UndoManager;

/**
 * A skill an addon declares to the AI copilot (copilot spec section 10.4).
 * Registry traversal alone answers "what data exists"; skills answer "what
 * can be DONE" - a cloth simulation addon declaring its cloth sim as
 * callable, for example.
 *
 * <p>Contract:</p>
 * <ul>
 * <li>{@code id} MUST be namespaced ({@code myaddon:cloth_sim}); un-namespaced
 * ids are rejected at registration with a readable log line</li>
 * <li>{@code handler} MUST do its writes inside the undo transaction the
 * {@link Context} provides; an exception rolls the whole operation back</li>
 * <li>handlers never run inside {@code FormPoseEvents} callbacks (no I/O in
 * pose callbacks, ever)</li>
 * <li>the user confirms {@code label} + {@code description} +
 * {@code paramsSchema} before the first execution - this addon is about to
 * touch their scene</li>
 * </ul>
 */
public class AiSkill
{
    public final String id;
    public final IKey label;
    public final IKey description;

    /** JSON Schema for the parameters a model may generate. */
    public final String paramsSchema;

    public final Handler handler;

    public AiSkill(String id, IKey label, IKey description, String paramsSchema, Handler handler)
    {
        this.id = id;
        this.label = label;
        this.description = description;
        this.paramsSchema = paramsSchema;
        this.handler = handler;
    }

    /** Whether the id is properly namespaced and not BBS's own namespace. */
    public boolean isNamespaced()
    {
        int colon = this.id.indexOf(':');

        return colon > 0 && colon < this.id.length() - 1 && !this.id.startsWith("bbs:");
    }

    /**
     * Execution context: the film to act on and the undo transaction to act
     * within. Handlers write through {@link FormProperties} / value groups
     * and let {@code FrameCommitter}-style transactions own the undo entry.
     */
    public interface Context
    {
        public ValueGroup film();

        public FormProperties properties();

        public UndoManager<ValueGroup> undoManager();
    }

    public interface Handler
    {
        /**
         * Execute the skill. Throw for any failure - the transaction wraps
         * this call, so a throw leaves the scene bitwise as it was.
         */
        public void execute(MapType params, Context context) throws Exception;
    }
}
