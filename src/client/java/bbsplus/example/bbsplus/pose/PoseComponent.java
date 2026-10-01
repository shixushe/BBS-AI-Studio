package bbsplus.example.bbsplus.pose;

import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3f;

/**
 * Maps a flat axis index onto a single scalar component of a {@link Transform}
 * (translate.x … scale.z), covering all 9 animated degrees of freedom.
 *
 * <p>BBS-FS stores both {@code transform} and {@code pose_transform} bone tracks
 * as a single {@code KeyframeChannel<Transform>}: every keyframe holds the whole
 * transform, not independent per-component curves. We expose each component as
 * its own axis on the multi-handle keyframe (see {@code Keyframe.getRx(axis)}),
 * and read / write the corresponding field through this enum.</p>
 *
 * <p>BBS-FS 2.4 had a second rotation vector ({@code rotate2}); 2.5 replaced it
 * with a single rotation — euler {@code rotate} or quaternion {@code quat},
 * selected by {@code rotationMode} — and folds old {@code r2} data into the
 * euler angles on load. Rotation is therefore a single group of three channels.</p>
 *
 * <p>{@code PoseTransform extends Transform}, so passing a {@code PoseTransform}
 * anywhere a {@code Transform} is expected works correctly.</p>
 */
public enum PoseComponent
{
    TRANSLATE_X("tx", Group.TRANSLATE, 0, Colors.RED),
    TRANSLATE_Y("ty", Group.TRANSLATE, 1, Colors.GREEN),
    TRANSLATE_Z("tz", Group.TRANSLATE, 2, Colors.BLUE),

    ROTATE_X("rx", Group.ROTATE, 0, Colors.RED),
    ROTATE_Y("ry", Group.ROTATE, 1, Colors.GREEN),
    ROTATE_Z("rz", Group.ROTATE, 2, Colors.BLUE),

    SCALE_X("sx", Group.SCALE, 0, Colors.RED),
    SCALE_Y("sy", Group.SCALE, 1, Colors.GREEN),
    SCALE_Z("sz", Group.SCALE, 2, Colors.BLUE);

    /** Component group, selecting which vector of the transform is addressed. */
    public enum Group
    {
        TRANSLATE,
        ROTATE,
        SCALE
    }

    public static final PoseComponent[] VALUES = values();

    public final String id;
    public final Group group;
    /** Index inside the group's vector (0 = x, 1 = y, 2 = z). */
    public final int component;
    /** Base colour used to render this component's curve. */
    public final int color;

    PoseComponent(String id, Group group, int component, int color)
    {
        this.id = id;
        this.group = group;
        this.component = component;
        this.color = color;
    }

    /** Flat axis index used to address multi-handles on a keyframe. */
    public int axis()
    {
        return this.ordinal();
    }

    private Vector3f vectorOf(Transform transform)
    {
        switch (this.group)
        {
            case TRANSLATE: return transform.translate;
            case ROTATE:    return transform.rotate;
            case SCALE:     return transform.scale;
            default:        return transform.translate;
        }
    }

    /** Read this component's scalar value out of the given transform. */
    public float get(Transform transform)
    {
        Vector3f v = this.vectorOf(transform);

        switch (this.component)
        {
            case 0:  return v.x;
            case 1:  return v.y;
            default: return v.z;
        }
    }

    /** Write this component's scalar value into the given transform. */
    public void set(Transform transform, float value)
    {
        Vector3f v = this.vectorOf(transform);

        switch (this.component)
        {
            case 0:  v.x = value; break;
            case 1:  v.y = value; break;
            default: v.z = value; break;
        }
    }
}
