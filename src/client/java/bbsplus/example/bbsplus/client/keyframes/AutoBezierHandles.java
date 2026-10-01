package bbsplus.example.bbsplus.client.keyframes;

import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.List;

public final class AutoBezierHandles
{
    private AutoBezierHandles()
    {}

    public interface ValueReader
    {
        float get(Keyframe keyframe, int axis);
    }

    public static final class Handles
    {
        public final float lx;
        public final float ly;
        public final float rx;
        public final float ry;

        public Handles(float lx, float ly, float rx, float ry)
        {
            this.lx = lx;
            this.ly = ly;
            this.rx = rx;
            this.ry = ry;
        }
    }

    public static boolean isAuto(Keyframe keyframe)
    {
        return keyframe != null && isAuto(keyframe.getInterpolation().getInterp());
    }

    public static boolean isAuto(IInterp interp)
    {
        return interp == Interpolations.AUTO || interp == Interpolations.AUTO_CLAMPED;
    }

    public static boolean isAutoClamped(Keyframe keyframe)
    {
        return keyframe != null && keyframe.getInterpolation().getInterp() == Interpolations.AUTO_CLAMPED;
    }

    public static boolean isBezier(Keyframe keyframe)
    {
        return keyframe != null && keyframe.getInterpolation().getInterp() == Interpolations.BEZIER;
    }

    public static Handles stored(Keyframe keyframe, int handleAxis)
    {
        return new Handles(keyframe.lx, keyframe.ly, keyframe.rx, keyframe.ry);
    }

    public static Handles auto(List<Keyframe> keyframes, int index, int valueAxis, boolean clamped, ValueReader reader)
    {
        if (keyframes == null || index < 0 || index >= keyframes.size() || keyframes.size() < 2)
        {
            return null;
        }

        Keyframe current = keyframes.get(index);
        Keyframe prev = index > 0 ? keyframes.get(index - 1) : null;
        Keyframe next = index < keyframes.size() - 1 ? keyframes.get(index + 1) : null;

        if (prev == null && next == null)
        {
            return null;
        }

        double curT = current.getTick();
        double curV = reader.get(current, valueAxis);
        double prevT = prev == null ? curT : prev.getTick();
        double prevV = prev == null ? curV : reader.get(prev, valueAxis);
        double nextT = next == null ? curT : next.getTick();
        double nextV = next == null ? curV : reader.get(next, valueAxis);
        double[] out = new double[4];

        AutoBezier.handles(prevT, prevV, curT, curV, nextT, nextV, prev != null, next != null, clamped, out);

        return new Handles(
            (float) (curT - out[0]),
            (float) (out[1] - curV),
            (float) (out[2] - curT),
            (float) (out[3] - curV)
        );
    }

    public static Handles right(List<Keyframe> keyframes, int index, int valueAxis, int handleAxis, ValueReader reader)
    {
        if (keyframes == null || index < 0 || index >= keyframes.size())
        {
            return null;
        }

        Keyframe keyframe = keyframes.get(index);

        if (isBezier(keyframe))
        {
            return stored(keyframe, handleAxis);
        }

        if (isAuto(keyframe))
        {
            return auto(keyframes, index, valueAxis, isAutoClamped(keyframe), reader);
        }

        return null;
    }

    public static Handles left(List<Keyframe> keyframes, int index, int valueAxis, int handleAxis, ValueReader reader)
    {
        if (keyframes == null || index <= 0 || index >= keyframes.size())
        {
            return null;
        }

        Keyframe current = keyframes.get(index);
        Keyframe prev = keyframes.get(index - 1);

        if (isBezier(prev))
        {
            return stored(current, handleAxis);
        }

        if (isAuto(prev))
        {
            return auto(keyframes, index, valueAxis, isAutoClamped(prev), reader);
        }

        return null;
    }

    public static boolean seedOutgoingAuto(List<Keyframe> keyframes, int index, int axes, ValueReader reader)
    {
        if (keyframes == null || index < 0 || index >= keyframes.size())
        {
            return false;
        }

        Keyframe current = keyframes.get(index);

        if (!isAuto(current))
        {
            return false;
        }

        boolean clamped = isAutoClamped(current);

        // Compute auto-handles for the primary axis only (axis 0) and write
        // them to the shared lx/ly/rx/ry fields.
        Handles currentHandles = auto(keyframes, index, 0, clamped, reader);

        if (currentHandles != null)
        {
            current.rx = currentHandles.rx;
            current.ry = currentHandles.ry;
        }

        if (index < keyframes.size() - 1)
        {
            Keyframe next = keyframes.get(index + 1);
            Handles nextHandles = auto(keyframes, index + 1, 0, clamped, reader);

            if (nextHandles != null)
            {
                next.lx = nextHandles.lx;
                next.ly = nextHandles.ly;
            }
        }

        current.getInterpolation().setInterp(Interpolations.BEZIER);

        return true;
    }

    public static boolean seedIncomingAuto(List<Keyframe> keyframes, int index, int axes, ValueReader reader)
    {
        if (keyframes == null || index <= 0 || index >= keyframes.size())
        {
            return false;
        }

        Keyframe prev = keyframes.get(index - 1);

        if (!isAuto(prev))
        {
            return false;
        }

        boolean clamped = isAutoClamped(prev);
        Keyframe current = keyframes.get(index);

        // Compute auto-handles for the primary axis only (axis 0) and write
        // them to the shared lx/ly/rx/ry fields.
        Handles prevHandles = auto(keyframes, index - 1, 0, clamped, reader);

        if (prevHandles != null)
        {
            prev.rx = prevHandles.rx;
            prev.ry = prevHandles.ry;
        }

        Handles currentHandles = auto(keyframes, index, 0, clamped, reader);

        if (currentHandles != null)
        {
            current.lx = currentHandles.lx;
            current.ly = currentHandles.ly;
        }

        prev.getInterpolation().setInterp(Interpolations.BEZIER);

        return true;
    }
}
