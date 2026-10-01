package mchorse.bbs_mod.ai.commit;

import mchorse.bbs_mod.ai.curve.CurvePolisher;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;

import java.util.List;

/**
 * Turns a channel plus polish intents into a {@link FrameCommitter.ChannelWrite}
 * by running the L3 polisher on a copy of the keys. The SAME builder output
 * feeds both the ghost frame preview and the eventual commit, so what the user
 * confirms is bitwise what lands - preview and commit are one deterministic
 * path.
 */
public class EditPatchBuilder
{
    /**
     * @param trackId  display/reporting address of the channel
     * @param channel  the source channel (read only; never mutated here)
     * @param ops      polish intents to apply
     */
    @SuppressWarnings("unchecked")
    public static FrameCommitter.ChannelWrite build(String trackId, KeyframeChannel channel, List<PolishOp> ops)
    {
        IKeyframeFactory<Float> factory = channel.getFactory();
        List<Keyframe<Float>> keys = (List<Keyframe<Float>>) channel.getKeyframes();
        List<Keyframe<Float>> polished = CurvePolisher.polished(keys, factory, ops);

        FrameCommitter.ChannelWrite write = new FrameCommitter.ChannelWrite(trackId, channel, 0F);

        for (Keyframe<Float> key : polished)
        {
            EditPatch.KeyWrite kw = new EditPatch.KeyWrite();

            kw.tick = key.getTick();
            kw.value = key.getValue();

            IInterp interp = key.getInterpolation().getInterp();

            if (interp != null)
            {
                kw.interpolation = interp.getKey();
            }

            kw.lx = key.lx;
            kw.ly = key.ly;
            kw.rx = key.rx;
            kw.ry = key.ry;
            kw.duration = key.getDuration();
            kw.motionShift = key.getMotionShift();
            write.keys.add(kw);
        }

        return write;
    }
}
