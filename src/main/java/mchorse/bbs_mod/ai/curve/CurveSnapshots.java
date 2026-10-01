package mchorse.bbs_mod.ai.curve;

import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Before/after captures of a key list, for A/B comparison and rollback
 * preview. Restores by index - the snapshot records everything a keyframe
 * serializes (tick, value, interpolation with its four easing arguments,
 * bezier handles, forced duration, motion shift), so a restore is bitwise.
 */
public class CurveSnapshots
{
    public static class Snapshot
    {
        public final List<Entry> entries = new ArrayList<>();
    }

    public static class Entry
    {
        public float tick;
        public float value;
        public IInterp interp;
        public double v1;
        public double v2;
        public double v3;
        public double v4;
        public float lx;
        public float ly;
        public float rx;
        public float ry;
        public float duration;
        public float motionShift;
    }

    public static Snapshot capture(List<Keyframe<Float>> keys)
    {
        Snapshot snapshot = new Snapshot();

        for (Keyframe<Float> key : keys)
        {
            Entry entry = new Entry();

            entry.tick = key.getTick();
            entry.value = key.getValue();
            entry.interp = key.getInterpolation().getInterp();
            entry.v1 = key.getInterpolation().getV1();
            entry.v2 = key.getInterpolation().getV2();
            entry.v3 = key.getInterpolation().getV3();
            entry.v4 = key.getInterpolation().getV4();
            entry.lx = key.lx;
            entry.ly = key.ly;
            entry.rx = key.rx;
            entry.ry = key.ry;
            entry.duration = key.getDuration();
            entry.motionShift = key.getMotionShift();
            snapshot.entries.add(entry);
        }

        return snapshot;
    }

    /**
     * Write a snapshot back onto keys captured from the same list (matched by
     * index). Returns false and changes nothing when the list has changed
     * size since the capture - the caller decides whether to re-capture.
     */
    public static boolean restore(Snapshot snapshot, List<Keyframe<Float>> keys)
    {
        if (snapshot.entries.size() != keys.size())
        {
            return false;
        }

        for (int i = 0; i < keys.size(); i++)
        {
            Keyframe<Float> key = keys.get(i);
            Entry entry = snapshot.entries.get(i);

            key.setTick(entry.tick);
            key.setValue(entry.value);
            key.getInterpolation().setInterp(entry.interp);
            key.getInterpolation().setV1(entry.v1);
            key.getInterpolation().setV2(entry.v2);
            key.getInterpolation().setV3(entry.v3);
            key.getInterpolation().setV4(entry.v4);
            key.lx = entry.lx;
            key.ly = entry.ly;
            key.rx = entry.rx;
            key.ry = entry.ry;
            key.setDuration(entry.duration);
            key.setMotionShift(entry.motionShift);
        }

        return true;
    }

    /** Field-by-field comparison of a key list against a snapshot (A/B diff). */
    public static boolean matches(Snapshot snapshot, List<Keyframe<Float>> keys)
    {
        if (snapshot.entries.size() != keys.size())
        {
            return false;
        }

        for (int i = 0; i < keys.size(); i++)
        {
            Keyframe<Float> key = keys.get(i);
            Entry entry = snapshot.entries.get(i);

            if (key.getTick() != entry.tick
                || key.getValue() != entry.value
                || key.getInterpolation().getInterp() != entry.interp
                || key.getInterpolation().getV1() != entry.v1
                || key.getInterpolation().getV2() != entry.v2
                || key.getInterpolation().getV3() != entry.v3
                || key.getInterpolation().getV4() != entry.v4
                || key.lx != entry.lx
                || key.ly != entry.ly
                || key.rx != entry.rx
                || key.ry != entry.ry
                || key.getDuration() != entry.duration
                || key.getMotionShift() != entry.motionShift)
            {
                return false;
            }
        }

        return true;
    }
}
