package bbsplus.example.bbsplus.client;

import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class UndoCursorKeeper
{
    private static final Set<IUndo<?>> PRESERVE_CURSOR = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> ACTIVE_CURSOR = new ThreadLocal<>();

    private UndoCursorKeeper() {}

    public static void mark(IUndo<?> undo)
    {
        if (undo != null)
        {
            PRESERVE_CURSOR.add(undo);

            if (undo instanceof CompoundUndo<?> compoundUndo)
            {
                for (IUndo<?> child : compoundUndo.getUndos())
                {
                    mark(child);
                }
            }
        }
    }

    public static boolean shouldPreserve(IUndo<?> undo)
    {
        return undo != null && PRESERVE_CURSOR.contains(undo);
    }

    public static void begin(int cursor)
    {
        ACTIVE_CURSOR.set(cursor);
    }

    public static void end()
    {
        ACTIVE_CURSOR.remove();
    }

    public static Integer getActiveCursor()
    {
        return ACTIVE_CURSOR.get();
    }
}
