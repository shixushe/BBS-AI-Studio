package bbsplus.example.bbsplus.client.keyframes;

import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AutoVectorHandles
{
    private AutoVectorHandles()
    {}

    public interface ValueReader
    {
        float get(Keyframe keyframe);
    }

    public interface ValueWriter
    {
        void set(Keyframe keyframe, float value);
    }

    public static boolean apply(List<Keyframe> keyframes, List<Integer> selected, int handleAxis, ValueReader reader, ValueWriter writer)
    {
        if (keyframes.size() < 2 || selected.isEmpty())
        {
            return false;
        }

        List<Integer> indices = validSelected(keyframes.size(), selected);

        if (indices.isEmpty())
        {
            return false;
        }

        if (isContinuous(indices))
        {
            applyContinuous(keyframes, indices, handleAxis, reader, writer);
        }
        else
        {
            applyScattered(keyframes, indices, handleAxis, reader);
        }

        return true;
    }

    public static List<Integer> affectedSegmentLeftIndices(List<Keyframe> keyframes, List<Integer> selected, ValueReader reader)
    {
        if (keyframes.size() < 2 || selected.isEmpty())
        {
            return Collections.emptyList();
        }

        List<Integer> indices = validSelected(keyframes.size(), selected);

        if (indices.isEmpty())
        {
            return Collections.emptyList();
        }

        List<Integer> affected = new ArrayList<>();

        if (isContinuous(indices))
        {
            addContinuousAffectedLeftIndices(keyframes, indices, reader, affected);
        }
        else
        {
            addScatteredAffectedLeftIndices(keyframes, indices, affected);
        }

        return affected;
    }

    private static List<Integer> validSelected(int size, List<Integer> selected)
    {
        List<Integer> indices = new ArrayList<>();

        for (Integer index : selected)
        {
            if (index != null && index >= 0 && index < size && !indices.contains(index))
            {
                indices.add(index);
            }
        }

        Collections.sort(indices);

        return indices;
    }

    private static boolean isContinuous(List<Integer> indices)
    {
        for (int i = 1; i < indices.size(); i++)
        {
            if (indices.get(i) != indices.get(i - 1) + 1)
            {
                return false;
            }
        }

        return indices.size() >= 2;
    }

    private static void applyContinuous(List<Keyframe> keyframes, List<Integer> indices, int handleAxis, ValueReader reader, ValueWriter writer)
    {
        int[] range = continuousRangeByMinMax(keyframes, indices, reader);
        int startPosition = range[0];
        int endPosition = range[1];
        Keyframe start = keyframes.get(indices.get(startPosition));
        Keyframe end = keyframes.get(indices.get(endPosition));
        float startTick = start.getTick();
        float endTick = end.getTick();
        float startValue = reader.get(start);
        float endValue = reader.get(end);
        float tickSpan = endTick - startTick;

        if (tickSpan != 0F)
        {
            for (int i = startPosition + 1; i < endPosition; i++)
            {
                Keyframe keyframe = keyframes.get(indices.get(i));
                float a = (keyframe.getTick() - startTick) / tickSpan;

                writer.set(keyframe, startValue + (endValue - startValue) * a);
            }
        }

        for (int i = startPosition; i < endPosition; i++)
        {
            setSegmentStraight(keyframes.get(indices.get(i)), keyframes.get(indices.get(i + 1)), handleAxis, reader);
        }
    }

    private static void addContinuousAffectedLeftIndices(List<Keyframe> keyframes, List<Integer> indices, ValueReader reader, List<Integer> affected)
    {
        int[] range = continuousRangeByMinMax(keyframes, indices, reader);

        for (int i = range[0]; i < range[1]; i++)
        {
            addUnique(affected, indices.get(i));
        }
    }

    private static int[] continuousRangeByMinMax(List<Keyframe> keyframes, List<Integer> indices, ValueReader reader)
    {
        int minIndex = indices.get(0);
        int maxIndex = indices.get(0);
        float minValue = reader.get(keyframes.get(minIndex));
        float maxValue = minValue;

        for (Integer index : indices)
        {
            float value = reader.get(keyframes.get(index));

            if (value < minValue)
            {
                minValue = value;
                minIndex = index;
            }

            if (value > maxValue)
            {
                maxValue = value;
                maxIndex = index;
            }
        }

        if (minIndex == maxIndex)
        {
            minIndex = indices.get(0);
            maxIndex = indices.get(indices.size() - 1);
        }

        int startPosition = indices.indexOf(minIndex);
        int endPosition = indices.indexOf(maxIndex);

        if (startPosition > endPosition)
        {
            int swap = startPosition;

            startPosition = endPosition;
            endPosition = swap;
        }

        return new int[] {startPosition, endPosition};
    }

    private static void applyScattered(List<Keyframe> keyframes, List<Integer> indices, int handleAxis, ValueReader reader)
    {
        for (Integer index : indices)
        {
            Keyframe current = keyframes.get(index);

            if (index > 0)
            {
                setSegmentStraight(keyframes.get(index - 1), current, handleAxis, reader);
            }

            if (index < keyframes.size() - 1)
            {
                setSegmentStraight(current, keyframes.get(index + 1), handleAxis, reader);
            }
        }
    }

    private static void addScatteredAffectedLeftIndices(List<Keyframe> keyframes, List<Integer> indices, List<Integer> affected)
    {
        for (Integer index : indices)
        {
            if (index > 0)
            {
                addUnique(affected, index - 1);
            }

            if (index < keyframes.size() - 1)
            {
                addUnique(affected, index);
            }
        }
    }

    private static void addUnique(List<Integer> indices, int index)
    {
        if (!indices.contains(index))
        {
            indices.add(index);
        }
    }

    private static void setSegmentStraight(Keyframe left, Keyframe right, int handleAxis, ValueReader reader)
    {
        float dt = right.getTick() - left.getTick();

        if (dt == 0F)
        {
            return;
        }

        float slope = (reader.get(right) - reader.get(left)) / dt;
        float offset = dt / 3F;

        left.getInterpolation().setInterp(Interpolations.BEZIER);

        // BBS 2.6: single-value handles.
        left.rx = offset;
        left.ry = slope * offset;
        right.lx = offset;
        right.ly = -(slope * offset);
    }
}
