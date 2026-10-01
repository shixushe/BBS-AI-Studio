package bbsplus.example.bbsplus.client.pose;

import bbsplus.example.bbsplus.pose.PoseComponent;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-bone-track Blender-style channel state: which pose components are shown
 * as curves at all, and which are hidden or locked.
 *
 * <p>Stage 1 keeps this in memory and enables every component. Stage 4 adds
 * the hide / lock UI and persistence keyed by form + bone.</p>
 */
public class PoseChannelState
{
    /** In-memory cache keyed by the sheet's track id. */
    private static final Map<String, PoseChannelState> CACHE = new HashMap<>();

    private final Set<PoseComponent> enabled = EnumSet.allOf(PoseComponent.class);
    private final Set<PoseComponent> hidden = EnumSet.noneOf(PoseComponent.class);
    private final Set<PoseComponent> locked = EnumSet.noneOf(PoseComponent.class);

    /**
     * Set once the user manually changes visibility (eye toggle / solo). After
     * that we stop auto-hiding so the manually chosen view survives editor
     * rebuilds (e.g. on undo).
     */
    private boolean userOverridden = false;

    /** Remembered viewport (X = time, Y = value). NaN means "none saved yet". */
    private double viewXMin = Double.NaN, viewXMax = Double.NaN;
    private double viewYMin = Double.NaN, viewYMax = Double.NaN;

    public static PoseChannelState get(UIKeyframeSheet sheet)
    {
        return CACHE.computeIfAbsent(sheet.id, id -> new PoseChannelState());
    }

    /** Whether a viewport has been remembered for this track. */
    public boolean hasViewport()
    {
        return !Double.isNaN(this.viewXMin) && !Double.isNaN(this.viewXMax)
            && !Double.isNaN(this.viewYMin) && !Double.isNaN(this.viewYMax)
            && this.viewXMin < this.viewXMax && this.viewYMin < this.viewYMax;
    }

    public void saveViewport(double xMin, double xMax, double yMin, double yMax)
    {
        this.viewXMin = xMin;
        this.viewXMax = xMax;
        this.viewYMin = yMin;
        this.viewYMax = yMax;
    }

    public double getViewXMin() { return this.viewXMin; }
    public double getViewXMax() { return this.viewXMax; }
    public double getViewYMin() { return this.viewYMin; }
    public double getViewYMax() { return this.viewYMax; }

    /** Whether the user has taken manual control of curve visibility. */
    public boolean isUserOverridden()
    {
        return this.userOverridden;
    }

    /** Set visibility from the auto-hide pass (does not count as a manual override). */
    public void setHiddenAuto(PoseComponent component, boolean value)
    {
        this.toggle(this.hidden, component, value);
    }

    public boolean isEnabled(PoseComponent component)
    {
        return this.enabled.contains(component);
    }

    public boolean isHidden(PoseComponent component)
    {
        return this.hidden.contains(component);
    }

    public boolean isLocked(PoseComponent component)
    {
        return this.locked.contains(component);
    }

    public void setHidden(PoseComponent component, boolean value)
    {
        this.userOverridden = true;
        this.toggle(this.hidden, component, value);
    }

    public void setLocked(PoseComponent component, boolean value)
    {
        this.toggle(this.locked, component, value);
    }

    /**
     * Solo a single component: hide every other enabled component and show only
     * this one. If the track is already soloed on this component (everything
     * else hidden), reveal all components again, so the same ctrl+click toggles
     * the solo state on and off.
     */
    public void soloOrReveal(PoseComponent component)
    {
        this.userOverridden = true;

        boolean alreadySoloed = !this.isHidden(component);

        for (PoseComponent other : PoseComponent.VALUES)
        {
            if (this.isEnabled(other) && other != component && !this.isHidden(other))
            {
                alreadySoloed = false;
                break;
            }
        }

        if (alreadySoloed)
        {
            this.hidden.clear();
            return;
        }

        for (PoseComponent other : PoseComponent.VALUES)
        {
            this.toggle(this.hidden, other, other != component);
        }
    }

    private void toggle(Set<PoseComponent> set, PoseComponent component, boolean value)
    {
        if (value)
        {
            set.add(component);
        }
        else
        {
            set.remove(component);
        }
    }
}
