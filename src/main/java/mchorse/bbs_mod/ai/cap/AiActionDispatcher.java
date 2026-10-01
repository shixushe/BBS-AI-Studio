package mchorse.bbs_mod.ai.cap;

import mchorse.bbs_mod.ai.commit.EditPatchBuilder;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.ai.curve.PolishKind;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes the model's DECLARATIVE operations (copilot spec section 10.2):
 * the model never calls Java methods - it describes data operations, and this
 * dispatcher resolves them onto real channels through the same L3 polisher
 * and L4 commit machinery everything else uses (undo transaction included).
 *
 * <pre>
 * { "op": "set_track",
 *   "target": { "replay": "Replay 0", "track": "myaddon:wobble" },
 *   "tick_range": [6, 22],
 *   "intent": [ { "kind": "ease_out", "strength": 0.7 } ] }
 * </pre>
 */
public class AiActionDispatcher
{
    /**
     * Dispatch one operation. Returns null when the op kind is unknown - the
     * caller surfaces that to the model as a failed tool call rather than
     * guessing.
     */
    public static FrameDiff dispatch(MapType op, FormProperties properties, ValueGroup undoContext, UndoManager<ValueGroup> undoManager)
    {
        if (op == null || !op.getString("op").equals("set_track"))
        {
            return null;
        }

        MapType target = op.get("target").isMap() ? op.get("target").asMap() : null;
        String trackKey = target == null ? "" : target.getString("track");

        TrackId id = TrackId.parse(trackKey);
        KeyframeChannel channel = id == null ? null : properties.getOrCreate(null, id);

        if (channel == null)
        {
            return null;
        }

        float from = Float.NEGATIVE_INFINITY;
        float to = Float.POSITIVE_INFINITY;

        if (op.has("tick_range"))
        {
            BaseType range = op.get("tick_range");

            if (BaseType.isList(range) && range.asList().size() == 2)
            {
                from = range.asList().get(0).isNumeric() ? range.asList().get(0).asNumeric().floatValue() : Float.NEGATIVE_INFINITY;
                to = range.asList().get(1).isNumeric() ? range.asList().get(1).asNumeric().floatValue() : Float.POSITIVE_INFINITY;
            }
        }

        List<PolishOp> ops = new ArrayList<>();

        if (op.has("intent"))
        {
            BaseType intents = op.get("intent");

            if (BaseType.isList(intents))
            {
                for (int i = 0; i < intents.asList().size(); i++)
                {
                    MapType intent = intents.asList().get(i).isMap() ? intents.asList().get(i).asMap() : null;

                    if (intent == null)
                    {
                        continue;
                    }

                    PolishKind kind = PolishKind.fromLabel(intent.getString("kind"));

                    if (kind == null)
                    {
                        return null;
                    }

                    float strength = intent.has("strength") && intent.get("strength").isNumeric()
                        ? intent.get("strength").asNumeric().floatValue()
                        : -1F;

                    ops.add(new PolishOp(kind, strength, from, to));
                }
            }
        }

        if (ops.isEmpty())
        {
            return new FrameDiff();
        }

        FrameCommitter.ChannelWrite write = EditPatchBuilder.build(trackKey, channel, ops);

        return FrameCommitter.commit(undoContext, undoManager, List.of(write));
    }
}
