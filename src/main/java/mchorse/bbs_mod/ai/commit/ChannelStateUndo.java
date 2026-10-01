package mchorse.bbs_mod.ai.commit;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.undo.IUndo;

/**
 * One AI-committed channel's before/after state, as a serialized snapshot.
 * The same mechanism {@code ValueChangeUndo} uses (DataPath + serialized
 * before/after, resolved against the film's root value group), but for a whole
 * {@code KeyframeChannel}: an AI operation rewrites many keys of a channel at
 * once, and restoring them key-by-key would make Ctrl+Z churn through dozens
 * of steps.
 *
 * <p>{@code getChannel()} hands the resolved channel back once applied, so the
 * film editor's change events can name the affected values.</p>
 */
public class ChannelStateUndo implements IUndo<ValueGroup>
{
    private final DataPath name;
    private final BaseType oldValue;
    private final BaseType newValue;

    private BaseValue appliedValue;
    private boolean invalid;

    public ChannelStateUndo(DataPath name, BaseType oldValue, BaseType newValue)
    {
        this.name = name;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public DataPath getName()
    {
        return this.name;
    }

    /** The channel this entry last applied to (undo manager callback reads this). */
    public BaseValue getChannel()
    {
        return this.appliedValue;
    }

    private BaseValue resolve(ValueGroup context)
    {
        if (this.invalid)
        {
            return null;
        }

        BaseValue value = context.findRecursively(this.name);

        if (value == null || !value.getPath().equals(this.name))
        {
            this.invalid = true;

            return null;
        }

        return this.appliedValue = value;
    }

    @Override
    public IUndo<ValueGroup> noMerging()
    {
        /* AI commits are atomic transactions - never fold into adjacent drags */
        return this;
    }

    @Override
    public boolean isMergeable(IUndo<ValueGroup> undo)
    {
        return false;
    }

    @Override
    public void merge(IUndo<ValueGroup> undo)
    {}

    @Override
    public void undo(ValueGroup context)
    {
        BaseValue value = this.resolve(context);

        if (value != null)
        {
            value.fromData(this.oldValue);
        }
    }

    @Override
    public void redo(ValueGroup context)
    {
        BaseValue value = this.resolve(context);

        if (value != null)
        {
            value.fromData(this.newValue);
        }
    }

    /** Capture a channel's full serialized state. */
    public static MapType capture(BaseValue channel)
    {
        BaseType data = channel.toData();

        return data instanceof MapType ? (MapType) data : new MapType();
    }
}
