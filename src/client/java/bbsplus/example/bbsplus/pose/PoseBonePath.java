package bbsplus.example.bbsplus.pose;

import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.forms.FormUtils;

/**
 * Bridges string-based channel IDs ({@code "form.path:boneName"}) to the
 * {@link TrackId} record system used by BBS track identity.
 */
public final class PoseBonePath
{
    public final String formPath;
    public final String bone;

    public PoseBonePath(String formPath, String bone)
    {
        this.formPath = formPath;
        this.bone = bone;
    }

    /**
     * Parse a sheet/channel id into a pose-bone path. Returns null if the id
     * doesn't represent a bone track.
     */
    public static PoseBonePath parse(String channelId)
    {
        if (channelId == null || channelId.isEmpty())
        {
            return null;
        }

        try
        {
            TrackId id = TrackId.parse(channelId);

            if (id != null && id.is(TrackKind.BONE))
            {
                return new PoseBonePath(id.formPath(), id.subject());
            }
        }
        catch (Exception ignored)
        {
            // Fall through to legacy string parsing below.
        }

        // Legacy fallback: old "formPath:bone" format.
        int sep = channelId.lastIndexOf(FormUtils.PATH_SEPARATOR);

        if (sep < 0)
        {
            return null;
        }

        return new PoseBonePath(channelId.substring(0, sep), channelId.substring(sep + 1));
    }

    /**
     * Build a bone key string from form path and bone name.
     */
    public static String toPoseBoneKey(String formPath, String bone)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return bone;
        }

        return formPath + FormUtils.PATH_SEPARATOR + bone;
    }
}
